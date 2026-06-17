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
 * NewPipeDownloader v9
 *
 * Perbaikan dari v8:
 *  - HAPUS inject X-Goog-Po-Token header untuk WEB client.
 *    Alasan: di NPE dev branch, WEB client hanya dipakai untuk metadata/thumbnail
 *    via fetchWebClientMetadataAndSetThumbnails() — tidak fetch streamingData.
 *    PoToken untuk WEB sekarang di-inject via PoTokenProvider.getWebClientPoToken()
 *    sebagai playerRequestsPoToken (param ke-2), bukan via header manual.
 *
 *  - Log WEB player Status=UNKNOWN sekarang bukan error — itu normal karena
 *    response WEB metadata-only memang tidak punya streamingData/playabilityStatus.
 *    Log level diubah dari WARNING ke DEBUG untuk WEB client.
 *
 *  - streamingData missing warning hanya berlaku untuk ANDROID/iOS client,
 *    bukan WEB.
 */
class NewPipeDownloader private constructor(private val client: OkHttpClient) : Downloader() {

    companion object {
        private const val TAG = "NewPipeDownloader"

        private const val VISITOR_ID_PATH = "youtubei/v1/visitor_id"
        private const val PLAYER_PATH = "youtubei/v1/player"

        // TTL constants
        private const val VD_TTL_MS  = 6 * 60 * 60 * 1000L  // 6 jam
        private const val POT_TTL_MS = 1 * 60 * 60 * 1000L  // 1 jam (konservatif)

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

    // ── State dengan timestamp ────────────────────────────────────────────────

    @Volatile private var visitorData: String? = null
    @Volatile private var visitorDataSetAt: Long = 0L

    @Volatile private var poToken: String? = null
    @Volatile private var poTokenSetAt: Long = 0L

    // ── VisitorData ───────────────────────────────────────────────────────────

    fun setVisitorData(data: String?) {
        visitorData = data
        visitorDataSetAt = if (data.isNullOrBlank()) 0L else System.currentTimeMillis()
        Log.d(TAG, "visitorData cached: ${data?.take(25)}...")
    }

    fun getVisitorData(): String? = visitorData

    fun isVisitorDataValid(): Boolean {
        if (visitorData.isNullOrBlank()) return false
        val age = System.currentTimeMillis() - visitorDataSetAt
        val valid = age < VD_TTL_MS
        if (!valid) Log.w(TAG, "visitorData EXPIRED (age=${age / 60000}min, TTL=${VD_TTL_MS / 60000}min)")
        return valid
    }

    // ── PoToken ───────────────────────────────────────────────────────────────

    fun setPoToken(token: String?) {
        poToken = token
        poTokenSetAt = if (token.isNullOrBlank()) 0L else System.currentTimeMillis()
        if (!token.isNullOrBlank()) {
            Log.d(TAG, "poToken cached: ${token.take(25)}...")
        }
    }

    fun getPoToken(): String? = poToken

    fun isPoTokenValid(): Boolean {
        if (poToken.isNullOrBlank()) return false
        val age = System.currentTimeMillis() - poTokenSetAt
        val valid = age < POT_TTL_MS
        if (!valid) Log.w(TAG, "poToken EXPIRED (age=${age / 60000}min, TTL=${POT_TTL_MS / 60000}min)")
        return valid
    }

    // ── Execute ───────────────────────────────────────────────────────────────

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
        val finalBody = dataToSend?.toRequestBody(contentType.toMediaType())

        // ── Detect client name untuk logging ────────────────────────────────
        var currentClientName = "UNKNOWN"
        if (url.contains(PLAYER_PATH) && httpMethod == "POST" && dataToSend != null) {
            val bodyStr = dataToSend.toString(Charsets.UTF_8)
            currentClientName = Regex("""\"clientName\"\s*:\s*\"([^\"]+)\"""")
                .find(bodyStr)?.groupValues?.get(1) ?: "UNKNOWN"
            Log.d(TAG, "Player request for client: $currentClientName")
        }

        val builder = OkRequest.Builder()
            .url(url)
            .method(httpMethod, finalBody)

        headers.forEach { (name, values) ->
            values.forEach { v -> builder.addHeader(name, v) }
        }

        // Apply Chrome UA hanya kalau extractor tidak menyediakan
        val hasUA = headers.keys.any { it.equals("User-Agent", true) }
        if (!hasUA) {
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
        }

        visitorData?.let { builder.header("X-Goog-Visitor-Id", it) }

        // CATATAN: X-Goog-Po-Token TIDAK diinject manual di sini.
        // PoToken dihandle oleh NPE via PoTokenProvider (di YtDlpHelper.initNpe()):
        //   - ANDROID client → streamingDataPoToken (param ke-3)
        //   - WEB client     → playerRequestsPoToken (param ke-2), metadata only
        // Manual inject via header sudah tidak diperlukan dan bisa konflik.

        val resp = client.newCall(builder.build()).execute()
        if (resp.code == 429) throw ReCaptchaException("reCaptcha Required", url)

        val responseBody = resp.body?.string() ?: ""

        // ── Parse playability status untuk logging ──────────────────────────
        if (url.contains(PLAYER_PATH)) {
            val json = try { JSONObject(responseBody) } catch (e: Exception) { null }
            val status = json?.optJSONObject("playabilityStatus")
                ?: json?.optJSONObject("playerResponse")?.optJSONObject("playabilityStatus")
            val statusCode = status?.optString("status")
                ?: (if (json?.has("streamingData") == true) "OK" else "UNKNOWN")
            val videoId = json?.optJSONObject("videoDetails")?.optString("videoId") ?: "unknown"

            val isWebClient = currentClientName == "WEB"
                    || currentClientName == "WEB_REMIX"
                    || currentClientName == "WEB_EMBEDDED_PLAYER"

            Log.d(TAG, "Player Response for $videoId: Status=$statusCode, Client=$currentClientName")

            if (statusCode == "UNKNOWN") {
                if (isWebClient) {
                    // WEB client di NPE dev branch hanya fetch metadata (thumbnail, title, dll),
                    // bukan streamingData → Status=UNKNOWN is BY DESIGN, bukan error.
                    Log.d(TAG, "WEB metadata-only response (normal) — root keys: ${json?.keys()?.asSequence()?.toList()}")
                } else {
                    // Non-WEB client UNKNOWN = masalah nyata
                    val keys = mutableListOf<String>()
                    json?.keys()?.forEach { keys.add(it) }
                    Log.w(TAG, "Non-WEB client UNKNOWN response, root keys: $keys")
                }
            }

            if (statusCode != "OK" && statusCode != "UNKNOWN") {
                val reason = status?.optString("reason") ?: "No reason"
                Log.w(TAG, "YouTube playability error: $statusCode ($reason)")
                Log.d(TAG, "Player response preview: ${responseBody.take(1000)}")
            }

            // streamingData missing warning hanya relevan untuk ANDROID/iOS (primary clients)
            if (!isWebClient && json != null && !json.has("streamingData") && statusCode == "OK") {
                Log.w(TAG, "[$currentClientName] Status=OK tapi streamingData MISSING → PoToken mungkin expired")
            }
        }

        // ── Auto-extract visitorData dari response (hanya kalau belum ada) ─
        if (!isVisitorDataValid() && url.contains("youtube")) {
            Regex(""""visitorData"\s*:\s*"([^"]{10,})"""")
                .find(responseBody)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Log.d(TAG, "Auto-extracted fresh visitorData from response")
                    setVisitorData(it)
                }
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
