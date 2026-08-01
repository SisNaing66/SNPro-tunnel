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
            val responseData = response.body?.string() ?: return@withContext Pair(false, "Empty Server Response")

            val jsonResult = JSONObject(responseData)
            val success = jsonResult.optBoolean("success", false)
            val message = jsonResult.optString("message", "Verification Failed")

            if (success) {
                val serialKey = jsonResult.optString("serial_key", keyToCheck)
                val expireDate = jsonResult.optLong("expire_date", 0L)

                prefs.edit()
                    .putString("SAVED_SERIAL_KEY", serialKey)
                    .putLong("SAVED_EXPIRE_DATE", expireDate)
                    .putBoolean("IS_ACTIVATED", true)
                    .apply()
            } else {
                if (jsonResult.optBoolean("is_expired", false) || inputSerialKey != null) {
                    prefs.edit()
                        .putBoolean("IS_ACTIVATED", false)
                        .putLong("SAVED_EXPIRE_DATE", 0L)
                        .apply()
                }
            }

            return@withContext Pair(success, message)
        } catch (e: Exception) {
            val isLocalValid = isLocalLicenseValid()
            if (isLocalValid) {
                return@withContext Pair(true, "Offline License Active")
            }
            return@withContext Pair(false, "Network Error: ${e.localizedMessage}")
        }
    }

    fun isLocalLicenseValid(): Boolean {
        val isActivated = prefs.getBoolean("IS_ACTIVATED", false)
        val expireDate = prefs.getLong("SAVED_EXPIRE_DATE", 0L)
        return isActivated && System.currentTimeMillis() < expireDate
    }

    fun getSavedSerialKey(): String {
        return prefs.getString("SAVED_SERIAL_KEY", "NOT_ACTIVATED") ?: "NOT_ACTIVATED"
    }

    fun getSavedExpireDate(): Long {
        return prefs.getLong("SAVED_EXPIRE_DATE", 0L)
    }

    fun clearLicenseData() {
        prefs.edit()
            .remove("SAVED_SERIAL_KEY")
            .remove("SAVED_EXPIRE_DATE")
            .putBoolean("IS_ACTIVATED", false)
            .apply()
    }
}
