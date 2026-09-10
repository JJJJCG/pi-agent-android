package com.pi.assistant.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pi.assistant.BuildConfig
import com.pi.assistant.ui.chat.ChatScreen
import com.pi.assistant.ui.debug.DebugScreen
import com.pi.assistant.ui.settings.SettingsScreen

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val DEBUG = "debug"
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.CHAT) {
        composable(Routes.CHAT) {
            ChatScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenDebug = { navController.navigate(Routes.DEBUG) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        // 调试页只在 debug 构建里注册；release 包里这项导航根本不存在，
        // 配合 R8 后 DebugScreen / DebugViewModel 整段代码都会被移除。
        if (BuildConfig.DEBUG) {
            composable(Routes.DEBUG) {
                DebugScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
