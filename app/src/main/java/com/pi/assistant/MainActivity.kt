package com.pi.assistant

import android.app.ActivityManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pi.assistant.data.prefs.SettingsStore
import com.pi.assistant.ui.AppNavHost
import com.pi.assistant.ui.theme.PiTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val snapshot by settings.state.collectAsStateWithLifecycle()
            // 最近任务的可见性是任务级属性；设置在同一个 Activity 的 Compose
            // 页面里改的，不走 onResume —— 直接对开关做 key，变了立即应用
            LaunchedEffect(snapshot.hideRecents) {
                applyHideRecents(snapshot.hideRecents)
            }
            PiTheme(mode = snapshot.themeMode) {
                AppNavHost()
            }
        }
    }

    /**
     * 隐藏/恢复最近任务卡片。setExcludeFromRecents 只对还活着的任务有效，
     * Activity 存续期间任务就在；个别 ROM 对前台任务不即时收卡，重开 App 后彻底。
     */
    private fun applyHideRecents(hide: Boolean) {
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return
        runCatching {
            am.appTasks.firstOrNull { it.taskInfo.baseActivity?.packageName == packageName }
                ?.setExcludeFromRecents(hide)
        }.onFailure { Log.w(TAG, "设置最近任务可见性失败", it) }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
