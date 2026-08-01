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

    // Cloudflare Worker API URL
    private val workerApiUrl = "https://your-worker-subdomain.workers.dev/api/verify-license"

    suspend fun verifyLicense(serialKey: String, hwid: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("serial_key", serialKey)
                put("hwid", hwid)
            }

            val request = Request.Builder()
                .url(workerApiUrl)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseData = response.body?.string() ?: return@withContext Pair(false, "Empty Response")

            val jsonResult = JSONObject(responseData)
            val success = jsonResult.optBoolean("success", false)
            val message = jsonResult.optString("message", "Verification Failed")

            if (success) {
                val expireDate = jsonResult.optLong("expire_date", 0)
                
                prefs.edit()
                    .putString("SERIAL_KEY", serialKey)
                    .putLong("EXPIRE_DATE", expireDate)
                    .putBoolean("IS_LOGGED_IN", true)
                    .apply()
            }

            return@withContext Pair(success, message)
        } catch (e: Exception) {
            return@withContext Pair(false, "Network Error: ${e.localizedMessage}")
        }
    }

    fun isUserLoggedIn(): Boolean {
        val isLoggedIn = prefs.getBoolean("IS_LOGGED_IN", false)
        val expireDate = prefs.getLong("EXPIRE_DATE", 0)
        
        if (System.currentTimeMillis() > expireDate) {
            logout()
            return false
        }
        return isLoggedIn
    }

    fun logout() {
        prefs.edit()
            .remove("SERIAL_KEY")
            .remove("EXPIRE_DATE")
            .putBoolean("IS_LOGGED_IN", false)
            .apply()
    }
}
