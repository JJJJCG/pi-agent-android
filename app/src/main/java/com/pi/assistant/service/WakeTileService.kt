package com.pi.assistant.service

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.pi.assistant.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 下拉快捷设置里的「后台监听」磁贴。
 *
 * 和设置页那个开关是同一件事的两个入口 —— 逻辑都走 [WakeControl]，
 * 所以这里不需要（也不该）自己判断权限、落盘、启停服务。
 *
 * 三个必须踩准的系统约束：
 *   · `onStartListening()` / `onTileAdded()` 时磁贴 UI 才创建出来，
 *     状态必须在这两个回调里 [refresh] —— 用户从磁贴面板划开时看到的是
 *     上次留下的旧状态，不刷新就会显示错的开关。
 *   · **开监听在 34+ 上不能直接 startForegroundService**。targetSdk 34 起，
 *     磁贴点击不算「可见界面」，起前台服务会抛
 *     `ForegroundServiceStartNotAllowedException`（35 上才修）。所以缺权限 /
 *     需要用户确认时，这里改成拉起 [WakeToggleActivity]（App 随即变可见，
 *     服务就能正常起）。这也是为什么磁贴不自己申请权限。
 *   · 从磁贴起 Activity 要用 `startActivityAndCollapse(PendingIntent)`：
 *     老的那个 `startActivityAndCollapse(Intent)` 在 34 上已废弃且会抛异常。
 *     锁屏时面板不该被收起，用 `unlockAndRun` 先解锁再走同一条路。
 *
 * 全程 try/catch：TileService 里抛异常会被系统记成服务崩溃，磁贴直接变灰。
 */
@AndroidEntryPoint
class WakeTileService : TileService() {

    @Inject lateinit var wakeControl: WakeControl

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    /** 点一下 = 切换。这是磁贴唯一的行为，不做长按菜单。 */
    override fun onClick() {
        super.onClick()
        val result = runCatching { wakeControl.toggle() }
            .onFailure { Log.w(TAG, "磁贴切换失败", it) }
            .getOrNull()
            ?: return

        if (result is WakeControl.Result.Failed) {
            Log.i(TAG, "磁贴切换被拒：${result.message}")
            if (needsMicPermission()) {
                // 磁贴自己申请不了权限，请用户去中转页授权（顺带把监听开上）
                openToggleActivityOnUnlock()
            } else {
                // 唤醒引擎坏了之类，说一声就行
                toast(result.message)
            }
        }
        refresh()
    }

    private fun needsMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * 拉起中转页。
     *
     * 锁屏时先 `unlockAndRun`：磁贴不该在锁屏下把面板收起来。
     * 解锁后走 [openToggleActivity]。
     */
    private fun openToggleActivityOnUnlock() {
        if (!isLocked) {
            openToggleActivity()
            return
        }
        runCatching { unlockAndRun { openToggleActivity() } }
            .onFailure {
                Log.w(TAG, "解锁流程失败", it)
                toast("请解锁后重试")
            }
    }

    /**
     * 真正把中转页拉起来。显式带上 `desired_wake=true` ——
     * 授权成功或本来就有权限时，中转页会直接把监听开上。
     */
    private fun openToggleActivity() {
        runCatching {
            val intent = Intent(this, WakeToggleActivity::class.java)
                .putExtra(WakeToggleActivity.EXTRA_DESIRED_STATE, true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // 34+：必须用 PendingIntent 版本，Intent 版本已废弃且会抛
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        REQ_TOGGLE,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
            } else {
                // 33-：从 TileService 起 Activity 没有宿主任务栈，必须 NEW_TASK
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.onFailure {
            Log.w(TAG, "拉起中转页失败", it)
            toast("请打开 App 后再试")
        }
    }

    /** 把当前真实状态推给磁贴。每次都要推 —— 状态可能是在设置页改的。 */
    private fun refresh() {
        val tile = qsTile ?: return
        val on = wakeControl.isOn
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_wake_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_wake)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) "监听中" else "已关闭"
        }
        runCatching { tile.updateTile() }
            .onFailure { Log.w(TAG, "磁贴刷新失败", it) }
    }

    private fun toast(text: String) {
        runCatching {
            android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "WakeTileService"
        private const val REQ_TOGGLE = 8001
    }
}
