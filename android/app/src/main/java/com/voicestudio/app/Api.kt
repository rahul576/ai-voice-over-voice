package com.voicestudio.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

data class ServerStatus(
    val state: String,
    val device: String?,
    val error: String?,
    val overhead: Double,
    val perChar: Double,
)

data class Voice(
    val id: String,
    val name: String,
    val transcript: String,
    val duration: Double,
    val hasSample: Boolean,
)

class ApiException(message: String) : IOException(message)

/** Talks to the Voice Studio server (app/server.py) running on the user's computer. */
class Api(baseUrl: String) {
    val baseUrl = baseUrl.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        // Cloning and long texts can take a while on a CPU-only machine.
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    suspend fun status(): ServerStatus = withContext(Dispatchers.IO) {
        val json = JSONObject(call(Request.Builder().url("$baseUrl/api/status").build()).string())
        val est = json.optJSONObject("estimate")
        ServerStatus(
            state = json.optString("state"),
            device = if (json.isNull("device")) null else json.optString("device"),
            error = if (json.isNull("error")) null else json.optString("error").ifEmpty { null },
            overhead = est?.optDouble("overhead", 2.0) ?: 2.0,
            perChar = est?.optDouble("per_char", 0.08) ?: 0.08,
        )
    }

    suspend fun voices(): List<Voice> = withContext(Dispatchers.IO) {
        val arr = JSONArray(call(Request.Builder().url("$baseUrl/api/voices").build()).string())
        (0 until arr.length()).map { parseVoice(arr.getJSONObject(it)) }
    }

    suspend fun clone(wav: ByteArray, name: String, transcript: String, denoise: Boolean): Voice = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "sample.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("name", name)
            .addFormDataPart("transcript", transcript)
            .addFormDataPart("denoise", denoise.toString())
            .build()
        val req = Request.Builder().url("$baseUrl/api/clone").post(body).build()
        parseVoice(JSONObject(call(req).string()))
    }

    suspend fun deleteVoice(id: String) = withContext(Dispatchers.IO) {
        call(Request.Builder().url("$baseUrl/api/voices/$id").delete().build()).close()
    }

    /** The sample with background noise removed, for a preview before saving. */
    suspend fun clean(wav: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "sample.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        call(Request.Builder().url("$baseUrl/api/clean").post(body).build()).bytes()
    }

    /** The recorded clip a voice was cloned from. */
    suspend fun sample(id: String): ByteArray = withContext(Dispatchers.IO) {
        call(Request.Builder().url("$baseUrl/api/voices/$id/sample").build()).bytes()
    }

    suspend fun speak(voiceId: String, text: String, language: String, speed: Float): ByteArray =
        withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("voice_id", voiceId).put("text", text)
                .put("language", language).put("speed", speed.toDouble())
            val req = Request.Builder().url("$baseUrl/api/speak")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            call(req).bytes()
        }

    private fun parseVoice(o: JSONObject) = Voice(
        id = o.getString("id"),
        name = o.optString("name"),
        transcript = o.optString("transcript"),
        duration = o.optDouble("duration", 0.0),
        hasSample = o.optBoolean("has_sample", false),
    )

    private fun call(req: Request): okhttp3.ResponseBody {
        val res: Response = client.newCall(req).execute()
        if (!res.isSuccessful) {
            val detail = res.body?.string()?.let { runCatching { JSONObject(it).optString("detail") }.getOrNull() }
            res.close()
            throw ApiException(detail?.ifEmpty { null } ?: "Server error ${res.code}")
        }
        return res.body ?: throw ApiException("Empty response from the server")
    }

    companion object {
        const val DEFAULT_PORT = 7860

        /**
         * Looks for a Voice Studio server on the phone's Wi-Fi subnet (x.y.z.1-254), then on the
         * phone itself (a USB cable with `adb reverse tcp:7860 tcp:7860`).
         */
        suspend fun discover(ports: List<Int> = (DEFAULT_PORT..DEFAULT_PORT + 3).toList()): String? =
            withContext(Dispatchers.IO) {
                val prefixes = NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { it.hostAddress!!.substringBeforeLast('.') }
                    .distinct()
                val gate = Semaphore(64)
                coroutineScope {
                    val candidates = prefixes.flatMap { p -> (1..254).flatMap { h -> ports.map { "$p.$h" to it } } }
                    candidates.map { (host, port) ->
                        async {
                            gate.withPermit {
                                if (!portOpen(host, port)) return@withPermit null
                                val url = "http://$host:$port"
                                runCatching { Api(url).status(); url }.getOrNull()
                            }
                        }
                    }.awaitAll().firstOrNull { it != null }
                } ?: ports.firstNotNullOfOrNull { port ->
                    val url = "http://127.0.0.1:$port"
                    url.takeIf { portOpen("127.0.0.1", port) && runCatching { Api(url).status() }.isSuccess }
                }
            }

        private fun portOpen(host: String, port: Int) = try {
            Socket().use { it.connect(InetSocketAddress(host, port), 400); true }
        } catch (_: IOException) {
            false
        }
    }
}
