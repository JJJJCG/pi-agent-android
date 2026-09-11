package com.pi.assistant.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 快捷方式的执行页（开 / 关后台监听）。
 *
 * Android 的静态快捷方式只能指向 Activity，不能直达 Service；而「开关后台监听」
 * 本质上是 Service 的活。所以这里当个一次性壳：拿到 intent 就干活，干完立即
 * finish()，界面上一闪而过（主题是 Theme.PiAgent.Transparent）。
 *
 * 方向怎么定 —— 三种入口，优先级从高到低：
 *   1. action = SET_WAKE_ON / SET_WAKE_OFF ← 桌面快捷方式的正常路径
 *   2. extra  EXTRA_DESIRED_STATE           ← 磁贴、外部自动化工具（am start）用
 *   3. 都没有 → 按当前状态取反               ← 兜底，别让 intent 缺字段就白点
 *
 * 权限补救：开监听需要录音权限。没有时不弹 toast 就算了，而是直接发起授权请求
 * —— 用户刚点过「打开监听」，此刻弹系统授权框心智上最顺。授权回来再补一次开启。
 */
@AndroidEntryPoint
class WakeToggleActivity : ComponentActivity() {

    @Inject lateinit var wakeControl: WakeControl

    /** 授权结束后要不要继续开。null = 这次不是权限触发的。 */
    private var pendingEnabled: Boolean? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingEnabled == true) {
            // 用户点了「允许」，把刚才没开成的补上
            report(wakeControl.set(true))
        } else if (!granted) {
            toast("没有录音权限，唤醒开不了")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明主题 + 不做任何 setContent：这个 Activity 只借个生命周期收发结果。
        // 不调 setContentView 是刻意的 —— 少了布局测量，闪一下就走。

        val enabled = resolveDesiredState(intent)

        if (enabled && !hasMicPermission()) {
            pendingEnabled = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        report(wakeControl.set(enabled))
        finish()
    }

    /**
     * 判断这次要开还是要关。
     *
     * action 优先：快捷方式的 intent 是系统按 shortcuts.xml 拼的，只能带 action，
     * 带不了 extras —— 所以「开 / 关」必须靠 action 区分，这是主路径。
     */
    private fun resolveDesiredState(intent: Intent?): Boolean {
        when (intent?.action) {
            ACTION_ON -> return true
            ACTION_OFF -> return false
        }
        if (intent?.hasExtra(EXTRA_DESIRED_STATE) == true) {
            return intent.getBooleanExtra(EXTRA_DESIRED_STATE, false)
        }
        // 兜底：啥都没说，当作切换
        return !wakeControl.isOn
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun report(result: WakeControl.Result) {
        when (result) {
            is WakeControl.Result.Ok -> toast(result.message)
            is WakeControl.Result.Failed -> toast(result.message)
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * 明确指定要开还是要关。不传 action、也不传这个 extra 时按当前状态取反。
         *
         * 从外部（自动化工具、通知按钮）拉起时用得上：
         * ```
         * am start -n com.pi.assistant/.service.WakeToggleActivity \
         *   --ez desired_wake true
         * ```
         */
        const val EXTRA_DESIRED_STATE = "desired_wake"

        /** 桌面快捷方式「打开监听」发出的 action。 */
        const val ACTION_ON = "com.pi.assistant.action.SET_WAKE_ON"

        /** 桌面快捷方式「关闭监听」发出的 action。 */
        const val ACTION_OFF = "com.pi.assistant.action.SET_WAKE_OFF"
    }
}
