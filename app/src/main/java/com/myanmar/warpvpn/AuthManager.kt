package com.myanmar.warpvpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AuthManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
    
    private val workerApiUrl = "https://your-worker-name.subdomain.workers.dev/api/check-license"

    suspend fun checkLicenseServer(hwid: String, inputSerialKey: String? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val keyToCheck = inputSerialKey ?: prefs.getString("SAVED_SERIAL_KEY", "") ?: ""

            val jsonBody = JSONObject().apply {
                put("hwid", hwid)
                if (keyToCheck.isNotEmpty()) {
                    put("serial_key", keyToCheck)
                }
            }

            val request = Request.Builder()
                .url(workerApiUrl)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseData = response.body?.string() ?: return@withContext Pair(false, "Server Response Empty")

            val jsonResult = JSONObject(responseData)
            val success = jsonResult.optBoolean("success", false)
            val message = jsonResult.optString("message", "License Verification Failed")

            if (success) {
                val serialKey = jsonResult.optString("serial_key")
                val expireDate = jsonResult.optLong("expire_date")

                prefs.edit()
                    .putString("SAVED_SERIAL_KEY", serialKey)
                    .putLong("SAVED_EXPIRE_DATE", expireDate)
                    .putBoolean("IS_ACTIVATED", true)
                    .apply()
            } else {
                prefs.edit().putBoolean("IS_ACTIVATED", false).apply()
            }

            return@withContext Pair(success, message)
        } catch (e: Exception) {
            return@withContext Pair(false, "Network Error: ${e.localizedMessage}")
        }
    }

    fun isLocalLicenseValid(): Boolean {
        val isActivated = prefs.getBoolean("IS_ACTIVATED", false)
        val expireDate = prefs.getLong("SAVED_EXPIRE_DATE", 0L)
        return isActivated && System.currentTimeMillis() < expireDate
    }

    fun getSavedSerialKey(): String = prefs.getString("SAVED_SERIAL_KEY", "") ?: ""
}
