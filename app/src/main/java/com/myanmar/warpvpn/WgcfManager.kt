package com.myanmar.warpvpn

import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
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
        
    private fun generateWarpIpList(): List<String> {
        val ipList = mutableListOf<String>()
        for (i in 1..250) {
            ipList.add("162.159.192.$i")
        }
        for (i in 1..250) {
            ipList.add("162.159.195.$i")
        }
        return ipList.shuffled()
    }
    
    suspend fun findFastestWorkingEndpoint(timeoutMs: Int = 1200): String = withContext(Dispatchers.IO) {
        val allIps = generateWarpIpList()
        var bestIp: String? = null
        
        val chunkedIps = allIps.chunked(50)

        for (chunk in chunkedIps) {
            val results = coroutineScope {
                chunk.map { ip ->
                    async {
                        val latency = testEndpointLatency(ip, timeoutMs)
                        if (latency > 0) Pair(ip, latency) else null
                    }
                }.awaitAll().filterNotNull()
            }
            
            if (results.isNotEmpty()) {
                bestIp = results.minByOrNull { it.second }?.first
                if (bestIp != null) break
            }
        }

        return@withContext bestIp ?: "162.159.195.1"
    }
    
    private fun testEndpointLatency(ip: String, timeoutMs: Int): Long {
        val ports = listOf(500, 2408)
        for (port in ports) {
            try {
                val startTime = System.currentTimeMillis()
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                val latency = System.currentTimeMillis() - startTime
                socket.close()
                return latency
            } catch (e: Exception) {
                continue
            }
        }
        return -1L
    }

    // Main function to register and get WARP config
    suspend fun registerAndGetConfig(
        engineMode: String = "CF_DIRECT",
        maxRetries: Int = 3
    ): String = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        
        val bestEndpoint = findFastestWorkingEndpoint()

        repeat(maxRetries) { attempt ->
            try {
                return@withContext if (engineMode == "CUSTOM_API") {
                    fetchFromCustomApi(bestEndpoint)
                } else {
                    fetchFromCloudflareApiWithFallback(bestEndpoint)
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

    // Fetch config from Cloudflare API with fallback
    private fun fetchFromCloudflareApiWithFallback(bestEndpoint: String): String {
        var lastException: Exception? = null

        for (apiBase in cfApiBases) {
            try {
                return fetchFromCloudflareApi(apiBase, bestEndpoint)
            } catch (e: Exception) {
                lastException = e
            }
        }

        throw lastException ?: Exception("All Cloudflare API endpoints failed")
    }

    // Fetch config from specific Cloudflare API and endpoint
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

    // Fetch config from custom backup API
    private fun fetchFromCustomApi(bestEndpoint: String): String {
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

        return buildRawWireGuardConfig(
            privateKey = clientPrivateKey,
            endpoint = bestEndpoint,
            port = "500",
            address = rawAddress,
            publicKey = serverPublicKey,
            dns = "1.1.1.1, 1.0.0.1"
        )
    }

    // Build RAW WireGuard Config
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

    // Single Endpoint Latency Tester
    suspend fun testEndpoint(endpoint: String, timeout: Int = 2000): Boolean = withContext(Dispatchers.IO) {
        return@withContext testEndpointLatency(endpoint, timeout) > 0
    }

    // Get all available generated endpoints
    fun getAllEndpoints(): List<String> = generateWarpIpList()
}
