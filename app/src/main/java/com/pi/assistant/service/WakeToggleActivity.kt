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
 * 静态快捷方式的中转页。
 *
 * Android 的静态快捷方式只能指向 Activity，不能直达 Service；而「切换后台监听」
 * 这个动作本质上是 Service 的活。所以这里当个一次性壳：拿到 intent 就干活，
 * 干完立即 finish()，界面上一闪而过。
 *
 * 三种入口语义（由 intent 决定）：
 *   · 带 `EXTRA_DESIRED_STATE` → 明确要开或要关（磁贴、未来的通知按钮会用）
 *   · 不带 → 按当前状态取反（快捷方式用这条，一个入口管开管关）
 *
 * 权限补救：开启监听需要录音权限，没有就不能硬开。这时不弹 toast 就算了，
 * 而是直接发起授权请求 —— 用户刚长按图标点过「打开监听」，此刻弹系统授权框
 * 心智上最顺，比让他自己去设置页翻权限友好得多。授权回来再补一次开启。
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

        val desired = readDesiredState(intent)
        val needEnable = desired ?: !wakeControl.isOn

        if (needEnable && !hasMicPermission()) {
            pendingEnabled = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        report(wakeControl.set(needEnable))
        finish()
    }

    /**
     * 从 intent 读「这次是要开还是要关」。没带就返回 null，交给调用方取反。
     *
     * 特别注意 mutability 问题：快捷方式从图标长按菜单点进来的 intent，
     * 可能被系统改写过；这里只读不写，不做任何假设。
     */
    private fun readDesiredState(intent: Intent?): Boolean? {
        if (intent == null) return null
        if (!intent.hasExtra(EXTRA_DESIRED_STATE)) return null
        return intent.getBooleanExtra(EXTRA_DESIRED_STATE, false)
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
         * 明确指定要开还是要关。不传 = 切换。
         *
         * 从外部（比如自动化工具、通知按钮）拉起时用得上：
         * ```
         * am start -n com.pi.assistant/.service.WakeToggleActivity \
         *   --ez desired_wake true
         * ```
         */
        const val EXTRA_DESIRED_STATE = "desired_wake"
    }
}
