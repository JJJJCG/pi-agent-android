package com.pi.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pi.assistant.MainActivity
import com.pi.assistant.R
import com.pi.assistant.audio.KwsEngine
import com.pi.assistant.data.prefs.PiSettings
import com.pi.assistant.data.prefs.SettingsStore
import com.pi.assistant.voice.VoiceBus
import com.pi.assistant.voice.VoiceSession
import com.pi.assistant.voice.VoiceStage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/**
 * 后台常驻的唤醒服务。
 *
 * 设计上刻意保守：
 *   · 只由用户手动开（Android 12+ 也不允许后台自启，别试）
 *   · 常驻通知里必须能一键停
 *   · 首开时明确告知麦克风将常驻采集（在设置页做）
 *   · 默认受条件限制（充电 / 家里 Wi-Fi / 时间段）可以按需收紧
 *
 * 时序上最关键的一点：KWS 监听循环和「一轮对话」是**串行**的。
 * 命中唤醒词后先让 listen() 返回（麦克风随之释放），再去录音，
 * 否则两个 AudioRecord 会互相抢设备。
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var kwsEngine: KwsEngine
    @Inject lateinit var voiceSession: VoiceSession
    @Inject lateinit var bus: VoiceBus

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var loopJob: Job? = null

    /** 同一轮内忽略重复唤醒，防止一句话被识别成两次。 */
    private var lastWakeAt = 0L

    /** 连续出错次数，驱动指数退避（见主循环的 Error 分支）。 */
    private var errorStreak = 0

    /** 当前是否处于前台优先级（决定 leaveForeground 要不要动手）。 */
    private var inForeground = false

    // 条件判断的缓存（见 conditionsSatisfied 的注释）
    private var cachedConditions = false
    private var cachedConditionsAt = 0L

    /** 上一次推给通知栏的文案，用来去重。 */
    private var lastNotifyText: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settings.updateWakeEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }

        // 必须在 5 秒内进入前台，否则直接 ANR/被杀
        enterForeground()

        kwsEngine.unavailableReason()?.let { reason ->
            bus.setError(reason)
            notify("唤醒不可用：$reason")
            stopSelf()
            return START_NOT_STICKY
        }

        if (loopJob?.isActive != true) startLoop()

        // 被杀后尽量重建；真正的重启仍受厂商省电策略影响
        return START_STICKY
    }

    override fun onDestroy() {
        bus.setServiceRunning(false)
        runCatching { kwsEngine.release() }   // 服务没了，模型跟着走（A1）
        scope.cancel()
        loopJob = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------ 主循环

    private fun startLoop() {
        loopJob = scope.launch {
            bus.setServiceRunning(true)
            Log.d(TAG, "唤醒服务启动")

            while (isActive) {
                if (!conditionsSatisfied()) {
                    // 条件不满足：主动降级出前台，让进程能被当成普通后台回收（A4）
                    leaveForeground()
                    bus.setHeadline(conditionHint())
                    bus.setStage(VoiceStage.IDLE)
                    notify("条件不满足，暂不监听")
                    delay(CONDITION_RECHECK_MS)
                    continue
                }
                enterForeground()

                bus.setStage(VoiceStage.WAITING_WAKE)
                bus.setHeadline(null)
                notify("在听唤醒词")

                when (val outcome = listenUntilHit()) {
                    is ListenResult.Hit -> {
                        errorStreak = 0
                        Log.i(TAG, "命中唤醒词：${outcome.keyword}")
                        bus.onWake(outcome.keyword)
                        notify("正在处理…")
                        // 此时 listen() 已返回，麦克风已释放，可以安全录音
                        voiceSession.runWakeTurn()
                        notify("在听唤醒词")
                    }

                    ListenResult.Error -> {
                        if (!isActive) break
                        // 指数退避：麦克风被占用时不再每 3 秒炸一次（A2）
                        val backoff = (ERROR_BACKOFF_MS shl errorStreak).coerceAtMost(MAX_BACKOFF_MS)
                        errorStreak++
                        Log.w(TAG, "监听出错，连续第 $errorStreak 次，退避 ${backoff}ms")
                        delay(backoff)
                    }

                    // 条件变了或被取消，不算错
                    ListenResult.Stopped -> errorStreak = 0
                }
            }
        }
    }

    /** 监听一趟的三种结局。null 语义被拆开：出错和「条件变了」要区别对待。 */
    private sealed interface ListenResult {
        data class Hit(val keyword: String) : ListenResult
        data object Error : ListenResult
        data object Stopped : ListenResult
    }

    /**
     * 跑一趟监听。命中返回 Hit，出错返回 Error，条件变化/被取消返回 Stopped。
     */
    private suspend fun listenUntilHit(): ListenResult {
        var hit: String? = null
        var errored = false

        kwsEngine.listen(
            // C1：不再快照 coroutineContext.isActive —— 那在函数入口求值一次、
            // 恒为 true，纯误导。真正响应取消的是 KwsEngine 循环里的 isActive。
            shouldContinue = { hit == null && conditionsSatisfied() },
            onKeyword = { keyword ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastWakeAt >= WAKE_LOCKOUT_MS) {
                    lastWakeAt = now
                    hit = keyword
                }
            },
            onError = { message ->
                errored = true
                bus.setError(message)
                notify(message)
            },
        )

        val keyword = hit
        return when {
            keyword != null -> ListenResult.Hit(keyword)
            errored -> ListenResult.Error
            else -> ListenResult.Stopped
        }
    }

    // ------------------------------------------------------------ 前台降级（A4）

    private fun enterForeground() {
        if (inForeground) return
        startForegroundCompat(buildNotification("在听唤醒词"))
        inForeground = true
        lastNotifyText = "在听唤醒词"
    }

    /**
     * 退出前台状态，让进程降为普通后台（可被回收），但服务与循环继续。
     *
     * Android 13+ 用 DETACH：通知留在抽屉里、用户能划掉，服务不受影响。
     * 13 以下只能 REMOVE：通知会整个消失，条件恢复时 enterForeground() 会重新贴一条。
     */
    private fun leaveForeground() {
        if (!inForeground) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }.onFailure { Log.w(TAG, "退出前台失败", it) }
        inForeground = false
    }

    // ------------------------------------------------------------ 生效条件

    /**
     * 生效条件是否满足。
     *
     * 这个判断在 `shouldContinue` 里**每帧**都会被问到（512 样本 ≈ 32ms，
     * 约 31 次/秒）。而底层那几个检查并不便宜：
     *   · isCharging() 是一次 registerReceiver 的 Binder IPC
     *   · isOnWifi() 是 ConnectivityManager 的两次 Binder IPC
     *   · isWithinTimeWindow() 每次 new 一个 Calendar
     * 合起来每秒近百次跨进程调用，而这些状态几秒内根本不会变 —— 纯烧电。
     *
     * 所以缓存结果，每 [CONDITION_CACHE_MS] 才真去查一次。
     * 例外：`wakeEnabled` 是纯内存读，不缓存 —— 用户把开关关掉后要立刻停，
     * 不能等缓存过期。
     */
    private fun conditionsSatisfied(): Boolean {
        if (!settings.current.wakeEnabled) return false

        val now = SystemClock.elapsedRealtime()
        if (now - cachedConditionsAt < CONDITION_CACHE_MS) return cachedConditions

        cachedConditionsAt = now
        cachedConditions = evaluateConditions()
        return cachedConditions
    }

    private fun evaluateConditions(): Boolean {
        val snapshot = settings.current
        if (snapshot.wakeOnlyCharging && !isCharging()) return false
        if (snapshot.wakeOnlyWifi && !isOnWifi()) return false
        return isWithinTimeWindow(snapshot)
    }

    private fun conditionHint(): String = when {
        !settings.current.wakeEnabled -> "唤醒已关闭"
        settings.current.wakeOnlyCharging && !isCharging() -> "仅充电时监听，当前未充电"
        settings.current.wakeOnlyWifi && !isOnWifi() -> "仅家里 Wi-Fi 下监听，当前不在"
        else -> "不在设定的监听时段内"
    }

    private fun isCharging(): Boolean = runCatching {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    private fun isOnWifi(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val network = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    private fun isWithinTimeWindow(snapshot: PiSettings): Boolean {
        val start = snapshot.wakeStartHour
        val end = snapshot.wakeEndHour
        if (start == PiSettings.HOUR_ANY || end == PiSettings.HOUR_ANY) return true

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (start <= end) {
            hour in start until end
        } else {
            // 跨零点，比如 22 点到次日 7 点
            hour >= start || hour < end
        }
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "唤醒监听",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "麦克风常驻采集时显示的常驻通知"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            Log.e(TAG, "进入前台失败", it)
            stopSelf()
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            REQ_STOP,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("pi 助手")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, "停止监听", stop)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notify(text: String) {
        // 内容没变就别重建：buildNotification 要造两个 PendingIntent，
        // notify 本身又是一次到 system_server 的 IPC。主循环里同一个文案
        // 会被反复推（比如每轮结束都回到「在听唤醒词」），去重掉能省不少。
        if (text == lastNotifyText) return
        lastNotifyText = text
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "pi_wake_word"
        private const val NOTIFICATION_ID = 9901
        private const val REQ_OPEN = 1001
        private const val REQ_STOP = 1002

        private const val WAKE_LOCKOUT_MS = 3_000L

        /** 条件本身就是几分钟级的变化，30s 复查一次足够（15s 没必要）。 */
        private const val CONDITION_RECHECK_MS = 30_000L
        private const val ERROR_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 60_000L

        /** 条件判断的缓存时长。充电/Wi-Fi 状态几秒内不会变，5 秒足够及时。 */
        private const val CONDITION_CACHE_MS = 5_000L

        const val ACTION_STOP = "com.pi.assistant.action.STOP_WAKE"

        /** 由界面在用户点开关时调用（这是唯一合规的启动来源）。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
