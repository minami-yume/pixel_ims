package com.takaisaisei.pixelims.boot

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.takaisaisei.pixelims.ApplyMode
import com.takaisaisei.pixelims.system.ShizukuShell
import com.takaisaisei.pixelims.data.SettingsRepository
import com.takaisaisei.pixelims.domain.BrokerContract

class ReapplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext)
        if (settings.bootSlots().isEmpty()) return Result.success()

        // On Android < 14 apply is detached: `am instrument` force-stops this very worker after
        // dispatch, which WorkManager treats as a failure and reschedules. Skip if we already fired
        // during this boot to avoid a loop.
        val detached = ApplyMode.detached
        if (detached && settings.bootApplyAlreadyFired()) {
            Log.d(TAG, "Boot re-apply already dispatched this boot; skipping")
            return Result.success()
        }

        val shell = ShizukuShell(applicationContext)
        if (!shell.isAuthorized()) {
            Log.d(TAG, "Shizuku is unavailable or permission was not granted; will retry")
            return Result.retry()
        }

        if (detached) {
            // Mark before firing: the force-stop may kill us before we can return.
            settings.markBootApplyFired()
            shell.runApply(BrokerContract.SLOT_ALL_BOOT, clear = false, notify = true)
            return Result.success()
        }
        return shell.runApply(BrokerContract.SLOT_ALL_BOOT, clear = false, notify = true).fold(
            onSuccess = { Result.success() },
            onFailure = {
                Log.e(TAG, "Boot re-apply failed", it)
                Result.retry()
            },
        )
    }

    companion object {
        const val WORK_NAME = "reapply_on_boot"
        private const val TAG = "ReapplyWorker"
    }
}
