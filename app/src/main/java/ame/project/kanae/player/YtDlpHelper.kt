package ame.project.kanae.player

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/**
 * YtDlpHelper v6
 *
 * Search  → YouTube Innertube API (direct OkHttp, reliable)
 * Stream  → NewPipe Extractor StreamInfo.getInfo() (no binary)
 * PoToken → WebView based generator for integrity bypass
 */
class YtDlpHelper(private val context: Context) {

    private val poTokenGenerator = PoTokenGenerator(context)

    companion object {
        private const val TAG = "YtDlpHelper"

        // YouTube Innertube API — public key, tidak butuh login
        private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val INNERTUBE_SEARCH =
            "https://www.youtube.com/youtubei/v1/search?key=$INNERTUBE_KEY"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Safari/537.36"

        @Volatile private var newPipeReady = false

        fun init() {
            if (newPipeReady) return
            synchronized(this) {
                if (newPipeReady) return
                try {
                    val downloader = NewPipeDownloader.getInstance()
                    NewPipe.init(downloader)

                    // PoTokenProvider fix untuk "Could not get visitorData"
                    YoutubeStreamExtractor.setPoTokenProvider(object : PoTokenProvider {
                        private fun getResult(): PoTokenResult {
                            val vd = downloader.getVisitorData() ?: ""
                            val po = downloader.getPoToken() ?: ""
                            return PoTokenResult(vd, po, null)
                        }
                        override fun getWebClientPoToken(videoId: String?): PoTokenResult = getResult()
                        override fun getAndroidClientPoToken(videoId: String?): PoTokenResult = getResult()
                        override fun getIosClientPoToken(videoId: String?): PoTokenResult = getResult()
                        override fun getWebEmbedClientPoToken(videoId: String?): PoTokenResult = getResult()
                    })

                    newPipeReady = true
                    Log.i(TAG, "NewPipe Extractor ready ✓")
                } catch (e: Exception) {
                    Log.e(TAG, "NewPipe init error", e)
                }
            }
        }
    }

    val isInstalled: Boolean = true

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun ensureInstalled(
        onProgress: (Int) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): Boolean {
        init()
        onLog("NewPipe Extractor ready (no binary needed)")
        onProgress(100)
        return true
    }

    // ── Extract audio stream URL ──────────────────────────────────────────────

    /**
     * Input:
     *   • https://youtube.com/watch?v=xxx  → langsung extract
     *   • https://youtu.be/xxx             → langsung extract
     *   • ytsearch1:query                  → search dulu, lalu extract
     *   • teks bebas                       → dianggap search query
     */
    suspend fun extractAudioUrl(input: String): Result<String> =
        withContext(Dispatchers.IO) {
            init()
            ensureVisitorData()
            try {
                val url = resolveToUrl(input)
                    ?: return@withContext Result.failure(
                        RuntimeException("Tidak ada hasil untuk: \"$input\"")
                    )

                // Try to generate PoToken if we have a video ID
                extractVideoId(url)?.let { videoId ->
                    val currentVd = NewPipeDownloader.getInstance().getVisitorData() ?: ""
                    if (currentVd.isNotBlank()) {
                        val poRes = poTokenGenerator.generate(videoId, currentVd)
                        if (poRes != null) {
                            NewPipeDownloader.getInstance().setPoToken(poRes.poToken)
                        }
                    }
                }

                Log.d(TAG, "Extracting stream: $url")
                val info = StreamInfo.getInfo(ServiceList.YouTube, url)
                Log.d(TAG, "Extraction done. Audio: ${info.audioStreams.size}, Video: ${info.videoStreams.size}, Muxed: ${info.videoOnlyStreams.size}")

                val best = pickBestAudio(info)
                    ?: return@withContext Result.failure(
                        RuntimeException("Tidak ada audio stream di: $url")
                    )

                Log.d(TAG, "Stream OK: ${best.take(80)}…")
                Result.success(best)
            } catch (e: Exception) {
                Log.e(TAG, "extractAudioUrl error: ${e.javaClass.simpleName}: ${e.message}")
                Result.failure(e)
            }
        }

    // ── Fetch metadata ────────────────────────────────────────────────────────

    suspend fun fetchMetadata(input: String): VideoMeta? =
        withContext(Dispatchers.IO) {
            init()
            ensureVisitorData()
            try {
                val url = resolveToUrl(input) ?: return@withContext null

                // Generate PoToken sebelum extract metadata
                extractVideoId(url)?.let { videoId ->
                    val currentVd = NewPipeDownloader.getInstance().getVisitorData() ?: ""
                    if (currentVd.isNotBlank()) {
                        val poRes = poTokenGenerator.generate(videoId, currentVd)
                        if (poRes != null) {
                            NewPipeDownloader.getInstance().setPoToken(poRes.poToken)
                        }
                    }
                }

                val info = StreamInfo.getInfo(ServiceList.YouTube, url)
                VideoMeta(
                    title     = info.name,
                    duration  = info.duration.toInt(),
                    thumbnail = info.thumbnails.firstOrNull()?.url,
                    channel   = info.uploaderName,
                    videoUrl  = url
                )
            } catch (e: Exception) {
                Log.e(TAG, "fetchMetadata error: ${e.message}")
                null
            }
        }
    // ── YouTube Innertube Search ──────────────────────────────────────────────

    /**
     * Cari video pakai YouTube Innertube API.
     * Jauh lebih reliable daripada NewPipe search handler.
     */
    suspend fun searchFirstResult(query: String): VideoMeta? =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Innertube search: \"$query\"")
            try {
                val bodyJson = buildInnertubeBody(query)
                val req = Request.Builder()
                    .url(INNERTUBE_SEARCH)
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                val response = http.newCall(req).execute()
                val body = response.body?.string()
                    ?: run { Log.e(TAG, "Empty response from Innertube"); return@withContext null }

                // Extract visitorData for NewPipeExtractor
                extractVisitorData(body)

                Log.d(TAG, "Innertube response: ${response.code}, body size: ${body.length}")

                val result = parseInnertubeFirstVideo(body)
                if (result != null) {
                    Log.d(TAG, "Search result: ${result.title} → ${result.videoUrl}")
                } else {
                    Log.w(TAG, "No video found for: \"$query\"")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "searchFirstResult error: ${e.message}")
                null
            }
        }

    // ── visitorData helper ──────────────────────────────────────────────────

    /**
     * Pastikan visitorData sudah ada di cache NewPipeDownloader.
     * Jika kosong (misal baru start app), lakukan search dummy singkat untuk memancingnya.
     */
    private suspend fun ensureVisitorData() {
        val current = NewPipeDownloader.getInstance().getVisitorData()
        if (current.isNullOrBlank()) {
            Log.d(TAG, "visitorData empty — akan di-extract otomatis di request pertama (dummy search dihapus)")
        } else {
            Log.d(TAG, "Reusing cached visitorData")
        }
    }

    private fun extractVisitorData(json: String) {
        try {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
            reader.isLenient = true
            val root = JsonParser.parseReader(reader).asJsonObject
            val newVisitorData = root.getObj("responseContext")
                ?.get("visitorData")?.asString

            if (!newVisitorData.isNullOrBlank()) {
                val existing = NewPipeDownloader.getInstance().getVisitorData()
                if (existing.isNullOrBlank()) {
                    NewPipeDownloader.getInstance().setVisitorData(newVisitorData)
                    Log.d(TAG, "Bootstrapped visitorData: $newVisitorData")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract visitorData: ${e.message}")
        }
    }
    // ── Innertube request builder ─────────────────────────────────────────────

    private fun buildInnertubeBody(query: String): String {
        // Escape query untuk JSON
        val escaped = query
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """
            {
              "query": "$escaped",
              "context": {
                "client": {
                  "clientName": "WEB",
                  "clientVersion": "2.20241210.01.00",
                  "hl": "en",
                  "gl": "US"
                }
              },
              "params": "EgIQAQ%3D%3D"
            }
        """.trimIndent()
    }

    // ── Innertube response parser ─────────────────────────────────────────────

    /**
     * Parse Innertube JSON response, ambil videoRenderer pertama.
     *
     * Struktur:
     * contents
     *   twoColumnSearchResultsRenderer
     *     primaryContents
     *       sectionListRenderer
     *         contents[]
     *           itemSectionRenderer
     *             contents[]
     *               videoRenderer { videoId, title.runs[0].text, lengthText.simpleText }
     */
    private fun parseInnertubeFirstVideo(json: String): VideoMeta? {
        return try {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
            reader.isLenient = true
            val root = JsonParser.parseReader(reader).asJsonObject

            // Navigasi ke sectionList
            val sections = root
                .getObj("contents")
                ?.getObj("twoColumnSearchResultsRenderer")
                ?.getObj("primaryContents")
                ?.getObj("sectionListRenderer")
                ?.getArr("contents")
                ?: return fallbackVideoIdExtract(json)

            for (section in sections) {
                val items = section.asJsonObject
                    .getObj("itemSectionRenderer")
                    ?.getArr("contents")
                    ?: continue

                for (item in items) {
                    val vr = item.asJsonObject.getObj("videoRenderer") ?: continue
                    val videoId = vr["videoId"]?.asString ?: continue
                    val title = vr.getObj("title")
                        ?.getArr("runs")
                        ?.firstOrNull()
                        ?.asJsonObject?.get("text")?.asString
                        ?: continue

                    val durationStr = vr.getObj("lengthText")?.get("simpleText")?.asString
                    val duration = parseDuration(durationStr)

                    val thumbUrl = vr.getObj("thumbnail")
                        ?.getArr("thumbnails")
                        ?.lastOrNull()
                        ?.asJsonObject?.get("url")?.asString

                    val channel = vr.getObj("ownerText")
                        ?.getArr("runs")
                        ?.firstOrNull()
                        ?.asJsonObject?.get("text")?.asString

                    return VideoMeta(
                        title    = title,
                        duration = duration,
                        thumbnail = thumbUrl,
                        channel  = channel,
                        videoUrl = "https://www.youtube.com/watch?v=$videoId"
                    )
                }
            }
            fallbackVideoIdExtract(json)
        } catch (e: Exception) {
            Log.e(TAG, "parseInnertubeFirstVideo error: ${e.message}")
            fallbackVideoIdExtract(json)
        }
    }

    /** Fallback: cari "videoId":"xxx" pertama dengan regex sederhana */
    private fun fallbackVideoIdExtract(json: String): VideoMeta? {
        val match = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(json)
            ?: return null
        val videoId = match.groupValues[1]
        Log.d(TAG, "Fallback videoId: $videoId")
        return VideoMeta(
            title    = "Video",
            duration = 0,
            thumbnail = null,
            channel  = null,
            videoUrl = "https://www.youtube.com/watch?v=$videoId"
        )
    }

    // ── Resolve input → YouTube URL ───────────────────────────────────────────

    private suspend fun resolveToUrl(input: String): String? {
        val t = input.trim()
        return when {
            t.contains("youtube.com/watch") ||
                    t.contains("youtu.be/")         ||
                    t.contains("youtube.com/shorts") -> t

            t.startsWith("ytsearch") -> {
                val q = t.substringAfter(":").trim()
                searchFirstResult(q)?.videoUrl
            }

            else -> searchFirstResult(t)?.videoUrl
        }
    }

    // ── Pick best audio stream ────────────────────────────────────────────────

    private fun pickBestAudio(info: StreamInfo): String? {
        // 1. Try audio-only streams (DASH)
        val audioOnly = info.audioStreams.filter { it.content.startsWith("http") }
        
        // Prefer opus (smaller, better quality)
        val opus = audioOnly.filter {
            it.format?.name?.contains("opus", true) == true ||
                    it.content.contains("mime=audio%2Fwebm")
        }
        
        val bestAudioOnly = (if (opus.isNotEmpty()) opus.maxByOrNull { it.averageBitrate }
        else audioOnly.maxByOrNull { it.averageBitrate })?.content
        
        if (bestAudioOnly != null) return bestAudioOnly
        
        // 2. Fallback to muxed streams (Video + Audio)
        Log.d("YtDlpHelper", "No audio-only streams, falling back to muxed streams")
        return info.videoStreams
            .filter { it.content.startsWith("http") }
            .minByOrNull { it.resolution ?: "360p" } // Prefer lower res for "audio" usage
            ?.content
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "v=([a-zA-Z0-9_-]{11})",
            "be/([a-zA-Z0-9_-]{11})",
            "shorts/([a-zA-Z0-9_-]{11})",
            "embed/([a-zA-Z0-9_-]{11})"
        )
        for (p in patterns) {
            Regex(p).find(url)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    // ── Duration parser ───────────────────────────────────────────────────────

    private fun parseDuration(s: String?): Int {
        if (s == null) return 0
        return try {
            val parts = s.split(":").map { it.toInt() }
            when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }

    // ── Gson helpers ──────────────────────────────────────────────────────────

    private fun com.google.gson.JsonObject.getObj(key: String) =
        this[key]?.takeIf { it.isJsonObject }?.asJsonObject

    private fun com.google.gson.JsonObject.getArr(key: String) =
        this[key]?.takeIf { it.isJsonArray }?.asJsonArray

    // ── Model ─────────────────────────────────────────────────────────────────

    data class VideoMeta(
        val title: String,
        val duration: Int,
        val thumbnail: String?,
        val channel: String?,
        val videoUrl: String = ""
    )
}
