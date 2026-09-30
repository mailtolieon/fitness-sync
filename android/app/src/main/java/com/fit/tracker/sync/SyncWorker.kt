package com.fit.tracker.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fit.tracker.data.SyncPreferences
import com.fit.tracker.health.HealthConnectManager
import com.fit.tracker.network.SheetsSyncClient
import com.fit.tracker.network.SyncResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * SyncWorker: Runs in the background using Android WorkManager.
 * Reads aggregated steps from Health Connect and transmits them to Google Sheets.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = SyncPreferences(applicationContext)
        val webAppUrl = prefs.webAppUrl
        val secretToken = prefs.secretToken

        if (webAppUrl.isBlank()) {
            prefs.lastSyncStatus = "Error: Web App URL is empty"
            return Result.failure()
        }

        val healthConnectManager = HealthConnectManager(applicationContext)

        // 1. Check if device supports Health Connect at all
        if (!healthConnectManager.isHealthConnectAvailable()) {
            prefs.lastSyncStatus = "Error: Health Connect is not available on this device"
            return Result.failure()
        }

        // 2. Check if background read feature is supported by OS/Hardware
        // If not supported, fail fast instead of entering an infinite retry loop that drains battery!
        if (!healthConnectManager.isBackgroundReadSupported()) {
            prefs.lastSyncStatus = "Background read unsupported on this device. Use manual sync in app."
            return Result.failure()
        }

        // 3. Verify user has granted permissions
        if (!healthConnectManager.hasPermissions()) {
            prefs.lastSyncStatus = "Error: READ_STEPS permission not granted"
            return Result.retry()
        }
        if (!healthConnectManager.hasBackgroundPermission()) {
            prefs.lastSyncStatus = "Error: READ_HEALTH_DATA_IN_BACKGROUND permission not granted"
            return Result.retry()
        }

        val sheetsClient = SheetsSyncClient()
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        // 4. Read & Sync Yesterday's final count (Failure must NOT be swallowed!)
        val yesterdayResult = healthConnectManager.readDailySteps(yesterday)
        if (yesterdayResult.isFailure) {
            val ex = yesterdayResult.exceptionOrNull()
            prefs.lastSyncStatus = "Error reading yesterday's steps: ${ex?.message}. Retrying..."
            return Result.retry()
        }
        val yesterdaySteps = yesterdayResult.getOrThrow()
        val yesterdaySyncResult = sheetsClient.syncSteps(webAppUrl, secretToken, yesterday, yesterdaySteps)
        if (yesterdaySyncResult is SyncResult.Error) {
            prefs.lastSyncStatus = "Error syncing yesterday's row: ${yesterdaySyncResult.errorMessage}. Retrying..."
            return Result.retry()
        }

        // 5. Read & Sync Today's accumulated count
        val todayResult = healthConnectManager.readDailySteps(today)
        if (todayResult.isFailure) {
            val ex = todayResult.exceptionOrNull()
            prefs.lastSyncStatus = "Error reading today's steps: ${ex?.message}. Retrying..."
            return Result.retry()
        }
        val todaySteps = todayResult.getOrThrow()
        val todaySyncResult = sheetsClient.syncSteps(webAppUrl, secretToken, today, todaySteps)
        if (todaySyncResult is SyncResult.Error) {
            prefs.lastSyncStatus = "Error syncing today's row: ${todaySyncResult.errorMessage}. Retrying..."
            return Result.retry()
        }

        // 6. Both Yesterday and Today successfully read & uploaded!
        val currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        prefs.lastSyncTime = currentTime
        prefs.lastSyncSteps = todaySteps

        val successAction = (todaySyncResult as SyncResult.Success).action
        val successRow = todaySyncResult.row
        prefs.lastSyncStatus = "Success: Yesterday locked, today $successAction (Row $successRow)"
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_TAG = "DailyHealthConnectSyncWork"

        /**
         * Schedules periodic background sync.
         * Runs automatically every 3 hours whenever the device is connected to the network.
         */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            // 3 hour interval (minimum allowed by Android WorkManager is 15 minutes)
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_TAG,
                ExistingPeriodicWorkPolicy.UPDATE,
                syncRequest
            )
        }

        /**
         * Cancels periodic background sync
         */
        fun cancelPeriodicSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_TAG)
        }
    }
}
