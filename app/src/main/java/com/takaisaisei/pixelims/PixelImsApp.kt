package com.takaisaisei.pixelims

import android.app.Application
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Process-wide, low-level initialization that must run for *every* entry point, not just the UI.
 * The Shizuku provider delivers the privileged service binder to UI and boot worker entry points.
 */
class PixelImsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initHiddenApiBypass()
    }

    private fun initHiddenApiBypass() {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
            .onFailure { Log.e(TAG, "Failed to apply HiddenApiBypass exemptions", it) }
    }

    private companion object {
        const val TAG = "PixelIMS"
    }
}
