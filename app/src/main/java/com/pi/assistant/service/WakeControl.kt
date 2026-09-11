package com.pi.assistant.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.pi.assistant.audio.KwsEngine
import com.pi.assistant.data.prefs.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台监听的**唯一开关出口**。
 *
 * 以前这段逻辑长在 `SettingsViewModel.setWakeEnabled()` 里，只有设置页能用。
 * 后来多了静态快捷方式（长按图标）和快捷设置磁贴，三处都要「校验 → 落盘 →
 * 启停服务」这一套，复制三遍迟早会走偏（比如某处忘了先 saveAll）。
 * 所以抽到这里，谁想开关都只能走它 —— 服务启动来源始终唯一且可审计。
 *
 * 刻意不做的事：
 *   · 不自启动。开启的唯一来源永远是用户的显式点击。
 *   · 不申请权限。少了录音权限就返回失败，由调用方决定怎么补救
 *     （界面弹设置页、快捷方式弹个 toast、磁贴直接把用户带到授权页）。
 */
@Singleton
class WakeControl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val kwsEngine: KwsEngine,
) {

    /** 开/关的结果。文案由调用方决定展示方式，所以这里只说「成没成、为什么」。 */
    sealed interface Result {
        data class Ok(val message: String) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * 开关的三种真实状态。
     *
     * 「配置说的」和「实际在跑的」可能不是一回事：昨天开了监听，系统今天把
     * 进程杀了 —— 配置里还是 true，但没人在听。设置页和主界面顶栏都要按
     * 这个三态说实话，否则用户以为开着，喊半天没反应。
     */
    enum class State {
        /** 配置就是关的。 */
        OFF,

        /** 配置开着，服务也确实在跑。 */
        RUNNING,

        /** 配置开着，但服务没在跑 —— 多半被系统杀了，需要用户手动拉起来。 */
        NOT_RUNNING,
    }

    val isOn: Boolean get() = settings.current.wakeEnabled

    /**
     * 端侧 KWS 不可用的原因（缺 so / 缺模型），null 表示可用。
     *
     * 暴露给界面做提前提示：不可用时开关该变灰并说明原因，
     * 而不是等用户点了才弹一句「唤醒不可用」。
     */
    val unavailableReason: String? get() = kwsEngine.unavailableReason()

    /**
     * 切换。`on` 为 null 时按当前配置取反 —— 快捷方式和磁贴不需要先看状态，
     * 直接调这一个入口就行。
     */
    fun toggle(on: Boolean? = null): Result = set(on ?: !isOn)

    /**
     * 显式设定开关状态。
     *
     * @param skippedConfigSave 由设置页调用时传 true —— 它自己会先 `saveAll()`
     *   把草稿里改过的 KWS 阈值 / 生效条件一起落盘，这里再落一次没必要，
     *   反而可能用还没保存的旧值覆盖掉。
     */
    fun set(enabled: Boolean, skippedConfigSave: Boolean = false): Result {
        if (!enabled) {
            settings.updateWakeEnabled(false)
            WakeWordService.stop(context)
            return Result.Ok("已关闭后台监听")
        }

        kwsEngine.unavailableReason()?.let {
            return Result.Failed("唤醒不可用：$it")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.Failed("没有录音权限，唤醒开不了")
        }

        // 唤醒参数（阈值、条件、时段）可能刚在设置页改过：一并落盘后服务才会读到新值。
        if (!skippedConfigSave) settings.save(settings.current)
        settings.updateWakeEnabled(true)
        WakeWordService.start(context)

        val saved = settings.current
        // 指定 Wi-Fi 但读不到名字，是这里最容易踩的坑：开关是开的，条件却永不满足。
        // 提前说破，免得用户对着一个「开着但没用」的开关发呆。
        val message = if (saved.wakeOnlyWifi && saved.wakeWifiSsid.isNotBlank() &&
            !hasWifiNamePermission()
        ) {
            "已打开监听，但读不到 Wi-Fi 名（缺权限），指定 Wi-Fi 条件不会满足"
        } else {
            "已打开后台监听，麦克风将常驻采集"
        }
        return Result.Ok(message)
    }

    /** 读 Wi-Fi 名要的运行时权限，双轨（与 Manifest 注释一致）。 */
    fun hasWifiNamePermission(): Boolean = runCatching {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}
