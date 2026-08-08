package com.myanmar.warpvpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AuthManager(private val context: Context) {

    companion object {
        init {
            try {
                System.loadLibrary("warpvpn")
            } catch (e: Throwable) {
                Log.e("AuthManager", "Failed to load native library: ${e.message}")
            }
        }
    }

    private external fun getNativeWorkerApiUrl(): String

    private val workerApiUrl: String
        get() {
            return try {
                getNativeWorkerApiUrl()
            } catch (e: Throwable) {
                Log.e("AuthManager", "Native URL Call Error: ${e.message}")
                "https://invalid-api-url.local/api/check-license"
            }
        }

    // ⏱️ ပြင်ဆင်ချက် - ဆာဗာက အဖြေပြန်လာတာကို သေချာစောင့်နိုင်ဖို့ ၃ စက္ကန့်အစား ၈ စက္ကန့်ကို ပြောင်းထားပါတယ်
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)

    suspend fun checkLicenseServer(hwid: String, inputSerialKey: String? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (workerApiUrl.isEmpty() || !workerApiUrl.startsWith("http")) {
                Log.w("AuthManager", "API URL is not properly configured yet.")
                if (isLocalLicenseValid()) {
                    return@withContext Pair(true, "Offline License Active")
                }
                return@withContext Pair(false, "API URL Not Configured Yet!")
            }

            val keyToCheck = inputSerialKey ?: prefs.getString("SAVED_SERIAL_KEY", "") ?: ""

            val jsonBody = JSONObject().apply {
                put("hwid", hwid)
                if (keyToCheck.isNotEmpty()) {
                    put("serial_key", keyToCheck)
                }
            }

            val request = try {
                Request.Builder()
                    .url(workerApiUrl)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            } catch (e: IllegalArgumentException) {
                Log.e("AuthManager", "Invalid URL Format: ${e.message}")
                if (isLocalLicenseValid()) {
                    return@withContext Pair(true, "Offline License Active")
                }
                return@withContext Pair(false, "Invalid API URL Format!")
            }

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
                // 🛑 ပြင်ဆင်ချက် - Bot ကနေ လိုင်စင်ဖျက်လိုက်လို့ success: false ဖြစ်တာနဲ့ ဖုန်းထဲက လိုင်စင်ကိုပါ အမြစ်ပြတ် ဖျက်ပစ်ပါမယ်
                clearLicenseData()
            }

            return@withContext Pair(success, message)

        } catch (e: Throwable) {
            Log.e("AuthManager", "Exception in checkLicenseServer: ${e.message}", e)

            val isLocalValid = isLocalLicenseValid()
            if (isLocalValid) {
                return@withContext Pair(true, "Offline License Active")
            }
            return@withContext Pair(false, "License Check Error: ${e.localizedMessage ?: "Unknown Error"}")
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

