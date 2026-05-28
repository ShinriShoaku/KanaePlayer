package ame.project.kanae.player

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NewPipeDownloader v7
 *
 * Strategi:
 *  1. visitor_id → inject cached visitorData.
 *  2. ANDROID player → return HTTP 400, biar NewPipe Extractor skip ANDROID
 *     dan fallback ke WEB client secara internal.
 *  3. WEB player → inject X-Goog-Po-Token jika tersedia.
 *  4. Content-Type application/json untuk youtubei/v1.
 *  5. Logging playabilityStatus untuk diagnosis.
 */
class NewPipeDownloader private constructor(private val client: OkHttpClient) : Downloader() {

    companion object {
        private const val TAG = "NewPipeDownloader"

        private const val VISITOR_ID_PATH = "youtubei/v1/visitor_id"
        private const val PLAYER_PATH = "youtubei/v1/player"

        private val ANDROID_CLIENT_NAMES = listOf(
            "\"ANDROID\"",
            "\"ANDROID_TESTSUITE\"",
            "\"ANDROID_MUSIC\"",
            "\"ANDROID_CREATOR\"",
            "\"ANDROID_VR\""
        )

        @Volatile private var instance: NewPipeDownloader? = null

        fun getInstance(): NewPipeDownloader = instance ?: synchronized(this) {
            instance ?: NewPipeDownloader(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()
            ).also { instance = it }
        }
    }

    @Volatile private var visitorData: String? = null
    @Volatile private var poToken: String? = null

    fun setVisitorData(data: String?) {
        visitorData = data
        Log.d(TAG, "visitorData cached: ${data?.take(25)}...")
    }

    fun getVisitorData(): String? = visitorData

    fun setPoToken(token: String?) {
        poToken = token
        if (!token.isNullOrBlank()) {
            Log.d(TAG, "poToken cached: ${token.take(25)}...")
        }
    }

    fun getPoToken(): String? = poToken

    @Throws(ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        // ── Intercept 1: visitor_id ─────────────────────────────────────────
        if (url.contains(VISITOR_ID_PATH) && !visitorData.isNullOrBlank()) {
            val esc = visitorData!!.replace("\\", "\\\\").replace("\"", "\\\"")
            Log.d(TAG, "Intercept visitor_id → injecting cached visitorData")
            return Response(
                200, "OK", emptyMap(),
                """{"responseContext":{"visitorData":"$esc","serviceTrackingParams":[]},"visitorData":"$esc"}""",
                url
            )
        }

        // ── Content-Type Detection ──────────────────────────────────────────
        val isInnertube = url.contains("youtubei/v1")
        val contentType = if (isInnertube) "application/json" else "application/x-www-form-urlencoded"

        var finalBody = dataToSend?.toRequestBody(contentType.toMediaType())

        // ── Intercept 2: ANDROID player ─────────────────────────────────────
        var currentClientName = "UNKNOWN"
        if (url.contains(PLAYER_PATH) && httpMethod == "POST" && dataToSend != null) {
            val bodyStr = dataToSend.toString(Charsets.UTF_8)
            currentClientName = Regex("""\"clientName\"\s*:\s*\"([^\"]+)\"""").find(bodyStr)?.groupValues?.get(1) ?: "UNKNOWN"
            Log.d(TAG, "Player request for client: $currentClientName")
        }

        val builder = OkRequest.Builder()
            .url(url)
            .method(httpMethod, finalBody)

        headers.forEach { (name, values) ->
            values.forEach { v -> builder.addHeader(name, v) }
        }

        // Apply Chrome UA only if no User-Agent is provided by the extractor
        val hasUA = headers.keys.any { it.equals("User-Agent", true) }
        if (!hasUA) {
            builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        }

        visitorData?.let { builder.header("X-Goog-Visitor-Id", it) }

        // Injeksi Po-Token hanya jika request adalah untuk client WEB
        poToken?.let { token ->
            if (url.contains(PLAYER_PATH)) {
                val bodyStr = dataToSend?.toString(Charsets.UTF_8) ?: ""
                // Pastikan kita hanya menyuntikkan token WEB ke client yang sesuai
                if (bodyStr.contains("\"WEB\"") || bodyStr.contains("\"WEB_REMIX\"") || bodyStr.contains("\"WEB_EMBEDDED\"")) {
                    builder.header("X-Goog-Po-Token", token)
                    Log.d(TAG, "Injecting X-Goog-Po-Token for WEB player")
                }
            }
        }

        val resp = client.newCall(builder.build()).execute()
        if (resp.code == 429) throw ReCaptchaException("reCaptcha Required", url)

        val responseBody = resp.body?.string() ?: ""

        // ── Parse playability error untuk logging ───────────────────────────
        if (url.contains(PLAYER_PATH)) {
            val json = try { JSONObject(responseBody) } catch (e: Exception) { null }
            val status = json?.optJSONObject("playabilityStatus") ?: json?.optJSONObject("playerResponse")?.optJSONObject("playabilityStatus")
            val statusCode = status?.optString("status") ?: (if (json?.has("streamingData") == true) "OK" else "UNKNOWN")
            val videoId = json?.optJSONObject("videoDetails")?.optString("videoId") ?: "unknown"

            Log.d(TAG, "Player Response for $videoId: Status=$statusCode, Client=$currentClientName")

            if (statusCode == "UNKNOWN") {
                val keys = mutableListOf<String>()
                json?.keys()?.forEach { keys.add(it) }
                Log.d(TAG, "Unknown response root keys: $keys")
            }

            if (statusCode != "OK" && statusCode != "UNKNOWN") {
                val reason = status?.optString("reason") ?: "No reason"
                Log.w(TAG, "YouTube playability error: $statusCode ($reason)")
                Log.d(TAG, "Player response preview: ${responseBody.take(1000)}")
            } else if (json != null && !json.has("streamingData") && statusCode == "OK") {
                Log.w(TAG, "YouTube Status OK but streamingData is MISSING (PoToken required)")
            }
        }

        // ── Auto-extract visitorData ───────────────────────────────────────
        if (visitorData.isNullOrBlank() && url.contains("youtube")) {
            Regex(""""visitorData"\s*:\s*"([^"]{10,})"""")
                .find(responseBody)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { setVisitorData(it) }
        }

        return Response(
            resp.code, resp.message,
            resp.headers.toMultimap(),
            responseBody,
            resp.request.url.toString()
        )
    }

    private fun parsePlayabilityError(body: String): String? {
        return try {
            val json = JSONObject(body)
            val status = json.optJSONObject("playabilityStatus")
            val statusCode = status?.optString("status")
            val reason = status?.optString("reason")
            val subError = status?.optJSONObject("errorScreen")
                ?.optJSONObject("playerErrorMessageRenderer")
                ?.optString("reason", "")

            when {
                statusCode == null || statusCode == "OK" -> null
                !reason.isNullOrBlank() -> "$statusCode: $reason"
                !subError.isNullOrBlank() -> "$statusCode: $subError"
                else -> statusCode
            }
        } catch (e: Exception) {
            null
        }
    }
}