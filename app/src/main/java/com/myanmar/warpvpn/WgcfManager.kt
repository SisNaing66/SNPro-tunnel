package com.myanmar.warpvpn

import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

class WgcfManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
        
    private val cfApiBases: List<String>
        get() = listOf(
            NativeUtils.getCfApiBase1(),
            NativeUtils.getCfApiBase2(),
            NativeUtils.getCfApiBase3()
        )
        
    private val customApiUrl: String
        get() = NativeUtils.getCustomApiUrl()
        
    private val warpEndpoints = listOf(
        "162.159.192.3",
        "162.159.192.4",
        "162.159.192.5",
        "162.159.192.6",
        "162.159.192.7",
        "162.159.192.8",
        "162.159.195.1",
        "162.159.195.2",
        "162.159.195.3",
        "162.159.195.4",
        "162.159.195.5",
        "162.159.195.6",
        "162.159.195.7",
        "162.159.195.8",
        "162.159.195.9",
        "162.159.192.150",
        "162.159.192.151",
        "162.159.192.152",
        "162.159.192.153",
        "162.159.192.154",
        "162.159.192.155",
        "162.159.192.156",
        "162.159.192.157",
        "162.159.192.158",
        "162.159.192.159"
    )
    
    suspend fun registerAndGetConfig(
        engineMode: String = "CF_DIRECT",
        maxRetries: Int = 3
    ): String = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                return@withContext if (engineMode == "CUSTOM_API") {
                    fetchFromCustomApi()
                } else {
                    fetchFromCloudflareApiWithFallback()
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    delay(2000L * (attempt + 1))
                }
            }
        }
        
        throw lastException ?: Exception("All retry attempts failed")
    }
    
    private fun fetchFromCloudflareApiWithFallback(): String {
        var lastException: Exception? = null

        for (apiBase in cfApiBases) {
            for (endpoint in warpEndpoints) {
                try {
                    return fetchFromCloudflareApi(apiBase, endpoint)
                } catch (e: Exception) {
                    lastException = e
                }
            }
        }

        throw lastException ?: Exception("All Cloudflare API endpoints failed")
    }
    
    private fun fetchFromCloudflareApi(apiBase: String, endpoint: String): String {
        val keyPair = KeyPair()
        val privateKey = keyPair.privateKey.toBase64()
        val publicKey = keyPair.publicKey.toBase64()

        val installId = UUID.randomUUID().toString()

        val regJson = JSONObject().apply {
            put("key", publicKey)
            put("install_id", installId)
            put("fcm_token", "")
            put("tos", "2024-01-01T00:00:00.000Z")
            put("model", "Android")
            put("type", "Android")
            put("locale", "en_US")
        }

        val regRequest = Request.Builder()
            .url("$apiBase/reg")
            .header("User-Agent", "okhttp/3.12.1")
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("Accept", "application/json")
            .post(regJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(regRequest).execute()
        val responseData = response.body?.string() ?: throw Exception("Empty response from Cloudflare API")

        if (!response.isSuccessful) {
            throw Exception("Cloudflare API error: ${response.code} - ${response.message}")
        }

        val rootJson = JSONObject(responseData)
        val result = if (rootJson.has("result") && !rootJson.isNull("result")) {
            rootJson.getJSONObject("result")
        } else {
            rootJson
        }

        val config = result.getJSONObject("config")
        val peers = config.getJSONArray("peers").getJSONObject(0)
        val serverPublicKey = peers.getString("public_key")

        val interfaceObj = config.getJSONObject("interface")
        val addresses = interfaceObj.getJSONObject("addresses")
        val ipv4 = addresses.getString("v4")
        val ipv6 = addresses.getString("v6")
        
        return buildRawWireGuardConfig(
            privateKey = privateKey,
            endpoint = endpoint,
            port = "500",
            address = "$ipv4/32, $ipv6/128",
            publicKey = serverPublicKey,
            dns = "1.1.1.1, 1.0.0.1"
        )
    }
    
    private fun fetchFromCustomApi(): String {
        val userId = (100000..999999).random().toString()
        val requestUrl = "$customApiUrl?user_id=$userId"

        val request = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", "okhttp/3.12.1")
            .header("Accept", "application/json")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseData = response.body?.string() ?: throw Exception("Empty response from backup API")

        if (!response.isSuccessful) {
            throw Exception("Backup API error: ${response.code} - ${response.message}")
        }

        val json = JSONObject(responseData)
        val success = json.optBoolean("success", false)

        if (!success) {
            val errorMsg = json.optString("error", "Unknown error")
            throw Exception("Backup API failed: $errorMsg")
        }

        val configObj = json.getJSONObject("config")
        val clientPrivateKey = configObj.getString("private_key").trim()
        val rawAddress = configObj.getString("address").trim()
        val serverPublicKey = configObj.getString("public_key").trim()
        val endpoint = findWorkingEndpoint()
        
        return buildRawWireGuardConfig(
            privateKey = clientPrivateKey,
            endpoint = endpoint,
            port = "500",
            address = rawAddress,
            publicKey = serverPublicKey,
            dns = "1.1.1.1, 1.0.0.1"
        )
    }
    
    private fun buildRawWireGuardConfig(
        privateKey: String,
        endpoint: String,
        port: String,
        address: String,
        publicKey: String,
        dns: String
    ): String {
        val formattedAddress = if (address.contains(",") && !address.contains(", ")) {
            address.replace(",", ", ")
        } else {
            address
        }
        
        return """
            [Interface]
            PrivateKey = $privateKey
            Address = $formattedAddress
            DNS = $dns
            MTU = 1280
            
            [Peer]
            PublicKey = $publicKey
            Endpoint = $endpoint:$port
            AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent()
    }
    
    private fun findWorkingEndpoint(): String {
        for (endpoint in warpEndpoints) {
            try {
                val address = InetAddress.getByName(endpoint)
                if (address.isReachable(3000)) {
                    return endpoint
                }
            } catch (e: Exception) {
                continue
            }
        }
        return "162.159.195.1"
    }
    
    suspend fun testEndpoint(endpoint: String, timeout: Int = 3000): Boolean = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName(endpoint)
            return@withContext address.isReachable(timeout)
        } catch (e: Exception) {
            return@withContext false
        }
    }
    
    fun getAllEndpoints(): List<String> = warpEndpoints
}
