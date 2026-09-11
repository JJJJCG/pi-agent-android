package com.pi.assistant.system

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.pi.assistant.data.local.ChatDatabase
import com.pi.assistant.data.net.HttpClients
import com.pi.assistant.data.prefs.SettingsStore
import com.pi.assistant.voice.VoiceBus
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 进后台且没在干活时，主动把能释放的都释放（后台占用优化 A6）。
 *
 * 之前代码里没有任何「我进后台了」的信号源：进程会进缓存，但 Room、
 * OkHttp 的连接池、cacheDir 残留都还挂着。这里补上信号源 ——
 * ProcessLifecycleOwner 自带约 700ms 的去抖，旋转屏幕不会误判。
 *
 * 只做「把可释放的都释放，让缓存进程尽量小、且不被任何东西隐式唤醒」；
 * 不做 Process.killProcess —— 自杀进程会破坏 START_STICKY、丢状态，
 * 系统可能立刻重建反而更费电（见优化文档 §7）。
 */
@Singleton
class IdleReaper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val bus: VoiceBus,
    private val clients: HttpClients,
    // Lazy：别为了释放它反而先把 DB 打开
    private val database: Lazy<ChatDatabase>,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** App 进后台。等一会儿再动手 —— 见「Room close 的前提」。 */
    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
        job = scope.launch {
            delay(IDLE_DELAY_MS)
            if (settings.current.wakeEnabled) return@launch      // 唤醒开着，什么都别动
            if (bus.serviceRunning.value) return@launch          // 服务还活着，什么都别动
            release()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        job?.cancel()
    }

    private fun release() {
        clients.release()
        cleanCacheDir()
        // Room close 的前提：ChatViewModel.messages 是 WhileSubscribed(5_000)，
        // IDLE_DELAY_MS = 8s 就是留给退订和旋转的余量。关闭后下次访问会自动重开。
        // 真机若出现 database is closed 的崩，直接删掉这行（几百 KB 不值得冒风险）。
        runCatching { database.get().close() }
            .onFailure { Log.w(TAG, "关闭数据库失败（下次访问会自动重开）", it) }
    }

    /** utt_（识别录音）+ tts_（合成音频）统一收口清掉（A8）。 */
    private fun cleanCacheDir() {
        runCatching {
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("utt_") || it.name.startsWith("tts_") }
                ?.forEach { it.delete() }
        }
    }

    private companion object {
        const val TAG = "IdleReaper"

        /** 比 WhileSubscribed(5_000) 略长，给界面退订和旋转留余量。 */
        const val IDLE_DELAY_MS = 8_000L
    }
}
