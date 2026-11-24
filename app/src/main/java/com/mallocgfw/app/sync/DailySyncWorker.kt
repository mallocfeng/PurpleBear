package com.mallocgfw.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mallocgfw.app.core.AppController
import com.mallocgfw.app.model.AppTextLanguage
import com.mallocgfw.app.xray.AppLogManager
import java.util.concurrent.TimeUnit

class DailySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            AppController.init(applicationContext)
            val state = AppController.current()
            AppTextLanguage.apply(state.settings.language)
            val report = BackgroundSyncManager.syncAll(applicationContext, state)
            val saved = AppController.commitIfUnchanged(base = state, next = report.state)
            if (!saved) {
                AppLogManager.append(applicationContext, "SYNC", "每日自动更新检测到前台状态变化，稍后重试")
                return@runCatching Result.retry()
            }
            if (state.settings.showUpdateNotifications) {
                // Nodes the refresh dropped may have cleared pre-proxies, app pins …
                val cleared = AppController.removalNotice.value
                SyncNotifications.showSyncCompleted(applicationContext, listOfNotNull(report.summary, cleared).joinToString("\n"))
            }
            Result.success()
        }.getOrElse { error ->
            AppLogManager.append(applicationContext, "SYNC", "每日自动更新失败", error)
            Result.retry()
        }
    }
}

object DailySyncScheduler {
    private const val UNIQUE_WORK_NAME = "mallocgfw_daily_sync"

    fun schedule(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<DailySyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
