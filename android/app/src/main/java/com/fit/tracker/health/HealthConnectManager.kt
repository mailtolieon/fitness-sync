package com.fit.tracker.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * HealthConnectManager: Wrapper around Android Health Connect Jetpack SDK.
 * Handles SDK availability checking, background read permission, and aggregated step querying.
 */
class HealthConnectManager(private val context: Context) {

    // Obtain the HealthConnectClient instance
    val healthConnectClient: HealthConnectClient? by lazy {
        if (isHealthConnectAvailable()) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    /**
     * Checks if the device supports reading health data in the background (WorkManager).
     */
    fun isBackgroundReadSupported(): Boolean {
        val client = healthConnectClient ?: return false
        return client.features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    /**
     * Health Connect permissions:
     * 1. READ_STEPS: Read access for StepsRecord
     * 2. READ_HEALTH_DATA_IN_BACKGROUND: Essential for background sync via WorkManager
     */
    val permissions: Set<String>
        get() {
            val perms = mutableSetOf(HealthPermission.getReadPermission(StepsRecord::class))
            if (isBackgroundReadSupported()) {
                perms.add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            }
            return perms
        }

    /**
     * Checks if Health Connect is supported and available on this device.
     * On Android 14+ (API 34+), it is built into the system framework.
     * On Android 9-13, it uses the Google Health Connect Play Store app.
     */
    fun getSdkStatus(): Int {
        return HealthConnectClient.getSdkStatus(context)
    }

    fun isHealthConnectAvailable(): Boolean {
        return getSdkStatus() == HealthConnectClient.SDK_AVAILABLE
    }

    /**
     * Intent to open the Google Play Store to install or update Health Connect (for Android 9-13).
     */
    fun getInstallIntent(): android.content.Intent {
        val uriString = "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
        return android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(uriString)
            setPackage("com.android.vending")
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
        }
    }

    /**
     * Check if user has already granted required Health Connect permissions
     */
    suspend fun hasPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        // Ensure at least READ_STEPS is granted
        val hasReadSteps = granted.contains(HealthPermission.getReadPermission(StepsRecord::class))
        return hasReadSteps
    }

    suspend fun hasBackgroundPermission(): Boolean {
        val client = healthConnectClient ?: return false
        if (!isBackgroundReadSupported()) return true
        val granted = client.permissionController.getGrantedPermissions()
        return granted.contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }

    /**
     * Creates the ActivityResultContract for requesting Health Connect permissions
     */
    fun createPermissionContract(): ActivityResultContract<Set<String>, Set<String>> {
        return PermissionController.createRequestPermissionResultContract()
    }

    /**
     * Reads aggregated daily steps for a specific local calendar date.
     * 
     * CRITICAL FIX:
     * Returns Result<Long> rather than swallowing errors and returning 0L.
     * If an error occurs (permission missing, database error, timeout), we must NOT
     * return 0, because writing 0 to Google Sheets would erase the user's legitimate steps!
     */
    suspend fun readDailySteps(date: LocalDate): Result<Long> {
        val client = healthConnectClient
            ?: return Result.failure(IllegalStateException("Health Connect is not available on this device"))

        val zone = ZoneId.systemDefault()

        // Local day boundaries (00:00:00 to 23:59:59.999 or current time if today)
        val startTime = date.atStartOfDay(zone).toInstant()
        val endTime = if (date == LocalDate.now()) {
            Instant.now()
        } else {
            date.atTime(LocalTime.MAX).atZone(zone).toInstant()
        }

        return try {
            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )
            // Legitimate step count (could be 0 if the user truly rested all day)
            val steps = response[StepsRecord.COUNT_TOTAL] ?: 0L
            Result.success(steps)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
