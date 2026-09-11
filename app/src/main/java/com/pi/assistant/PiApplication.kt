package com.pi.assistant

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.pi.assistant.system.IdleReaper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PiApplication : Application() {

    @Inject lateinit var idleReaper: IdleReaper

    override fun onCreate() {
        super.onCreate()
        // 进后台的信号源：IdleReaper 据此在空闲时收工（A6）
        ProcessLifecycleOwner.get().lifecycle.addObserver(idleReaper)
    }
}
