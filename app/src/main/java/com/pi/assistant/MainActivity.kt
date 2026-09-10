package com.pi.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
            PiTheme(mode = snapshot.themeMode) {
                AppNavHost()
            }
        }
    }
}
