package com.fit.tracker.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * SyncResult: Encapsulates network response details
 */
sealed class SyncResult {
    data class Success(val message: String, val action: String, val row: Int) : SyncResult()
    data class Error(val errorMessage: String, val statusCode: Int = 0) : SyncResult()
}

/**
 * SheetsSyncClient: Posts aggregated step records to your Google Apps Script Web App
 */
class SheetsSyncClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Sends step counts for a given date to Google Apps Script Web App
     */
    suspend fun syncSteps(
        webAppUrl: String,
        apiSecretToken: String,
        date: LocalDate,
        steps: Long
    ): SyncResult = withContext(Dispatchers.IO) {
        if (webAppUrl.isBlank()) {
            return@withContext SyncResult.Error("Apps Script Web App URL is not configured.")
        }

        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE) // Format: YYYY-MM-DD

        val jsonPayload = JSONObject().apply {
            put("token", apiSecretToken.trim())
            put("date", dateStr)
            put("steps", steps)
        }

        val requestBody = jsonPayload.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(webAppUrl.trim())
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(bodyString)
                        val status = jsonResponse.optString("status", "")
                        if (status == "success") {
                            val action = jsonResponse.optString("action", "updated")
                            val row = jsonResponse.optInt("row", 0)
                            val msg = jsonResponse.optString("message", "Synced successfully")
                            return@withContext SyncResult.Success(msg, action, row)
                        } else {
                            val errorMsg = jsonResponse.optString("message", "Unknown script error")
                            return@withContext SyncResult.Error(errorMsg, response.code)
                        }
                    } catch (e: Exception) {
                        return@withContext SyncResult.Success(
                            message = "Synced (Raw response: $bodyString)",
                            action = "synced",
                            row = 0
                        )
                    }
                } else {
                    return@withContext SyncResult.Error(
                        errorMessage = "HTTP Error ${response.code}: $bodyString",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext SyncResult.Error("Network Connection Failed: ${e.localizedMessage ?: e.message}")
        }
    }
}
