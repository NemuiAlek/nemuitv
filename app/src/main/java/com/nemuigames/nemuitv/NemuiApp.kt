package com.nemuigames.nemuitv

import android.app.Application
import com.nemuigames.nemuitv.core.NemuiCore

/** Application: owns the one [NemuiCore] shared by the Home activity and the Home-button overlay. */
class NemuiApp : Application() {
    lateinit var core: NemuiCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = NemuiCore(this)
    }
}
