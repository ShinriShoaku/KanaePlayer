/*
 * KanaePlayer -
 * Copyright (C) 2026 KanaePlayer Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed WITHOUT ANY WARRANTY; see the
 * GNU General Public License for more details: <https://www.gnu.org/licenses/>.
 */

package ame.project.kanae.player

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * YtDlpHelper v9
 *
 * Perbaikan dari v8:
 *  - StreamInfo cache: fetchMetadata + extractAudioUrl tidak double-hit YouTube
 *    untuk URL yang sama (cache TTL 5 menit)
 *  - PoToken generation menggunakan PoTokenGenerator v9 (endpoint jnn-pa yang benar)
 *  - Cooldown PoToken diperpanjang ke 60 detik (dari 10 detik) karena generation
 *    sekarang lebih lama (2x WebView + 2x network)
 *  - Log lebih verbose untuk debug PoToken status
 */
class YtDlpHelper_bk(private val context: Context) {

    private val poTokenGenerator = PoTokenGenerator(context)
    private val poTokenMutex = Mutex()

    // Timestamp terakhir kali generate PoToken dicoba (sukses maupun gagal)
    @Volatile private var lastPoTokenAttemptAt: Long = 0L
    private val POT_RETRY_COOLDOWN_MS = 60_000L // 60 detik — generation lebih lama sekarang

    // ── StreamInfo cache ──────────────────────────────────────────────────────
    // Mencegah double StreamInfo.getInfo() saat fetchMetadata + extractAudioUrl
    // dipanggil berurutan untuk URL yang sama
    private data class CachedStreamInfo(val url: String, val info: StreamInfo, val cachedAt: Long)
    @Volatile private var streamInfoCache: CachedStreamInfo? = null
    private val STREAM_INFO_CACHE_TTL_MS = 5 * 60 * 1000L // 5 menit
    private val streamInfoMutex = Mutex()

    companion object {
        private const val TAG = "YtDlpHelper"

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

                    // PoTokenProvider — platform token rules (yt-dlp wiki, Maret 2026):
                    //
                    //  Token yang kita generate via BotGuard (WebView) = WEB platform token.
                    //  PENTING: PoToken bersifat platform-specific — token dari BotGuard/WebView
                    //           HANYA valid untuk WEB client. Inject ke ANDROID akan ditolak YouTube
                    //           di level GVS (googlevideo.com) karena cross-platform mismatch,
                    //           meskipun player response tetap Status=OK.
                    //
                    //  Untuk ANDROID yang benar butuh DroidGuard — tidak bisa dijalankan dari WebView.
                    //  Solusi saat ini: biarkan ANDROID tanpa PoToken (masih bisa stream banyak video).
                    //
                    //  PoTokenResult(visitorData, streamingDataPoToken, playerRequestsPoToken)
                    //
                    //  Client    │ streamingDataPoToken │ playerRequestsPoToken │ Alasan
                    //  ──────────┼─────────────────────┼───────────────────────┼──────────────────────────
                    //  WEB       │ null                 │ BotGuard token ✓      │ metadata only, WEB token valid
                    //  ANDROID   │ null                 │ null                  │ butuh DroidGuard, bukan BotGuard
                    //  iOS       │ null                 │ null                  │ butuh iOSGuard
                    //  WebEmbed  │ null                 │ null                  │ tidak butuh token
                    YoutubeStreamExtractor.setPoTokenProvider(object : PoTokenProvider {

                        // WEB: hanya metadata di NPE dev branch.
                        // BotGuard token valid untuk WEB → inject sebagai playerRequestsPoToken.
                        override fun getWebClientPoToken(videoId: String?): PoTokenResult {
                            val vd = downloader.getVisitorData() ?: ""
                            val po = if (downloader.isPoTokenValid()) downloader.getPoToken() else null
                            if (po != null) {
                                Log.d(TAG, "PoTokenProvider WEB: playerRequestsPoToken=${po.take(15)}... (BotGuard ✓)")
                            } else {
                                Log.d(TAG, "PoTokenProvider WEB: no valid BotGuard token")
                            }
                            // streamingDataPoToken=null (WEB tidak fetch streamingData di NPE dev)
                            // playerRequestsPoToken=po  (BotGuard token valid untuk WEB player request)
                            return PoTokenResult(vd, "", po)
                        }

                        // ANDROID: PRIMARY client untuk streamingData di NPE dev branch.
                        // TIDAK inject PoToken — BotGuard token tidak valid untuk ANDROID GVS.
                        // DroidGuard tidak tersedia dari WebView → biarkan tanpa token.
                        // Token salah platform lebih buruk daripada tidak ada token sama sekali.
                        override fun getAndroidClientPoToken(videoId: String?): PoTokenResult {
                            val vd = downloader.getVisitorData() ?: ""
                            Log.d(TAG, "PoTokenProvider ANDROID: no token (DroidGuard not available)")
                            return PoTokenResult(vd, "", null)
                        }

                        // iOS: butuh iOSGuard → tidak inject
                        override fun getIosClientPoToken(videoId: String?): PoTokenResult {
                            val vd = downloader.getVisitorData() ?: ""
                            return PoTokenResult(vd, "\\", null)
                        }

                        // WebEmbed: tidak butuh token
                        override fun getWebEmbedClientPoToken(videoId: String?): PoTokenResult {
                            val vd = downloader.getVisitorData() ?: ""
                            return PoTokenResult(vd, "", null)
                        }
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

    suspend fun extractAudioUrl(input: String): Result<String> =
        withContext(Dispatchers.IO) {
            init()
            ensureVisitorData()
            try {
                val url = resolveToUrl(input)
                    ?: return@withContext Result.failure(
                        RuntimeException("Tidak ada hasil untuk: \"$input\"")
                    )

                // Coba refresh PoToken kalau perlu — cooldown mencegah double-generate
                extractVideoId(url)?.let { videoId ->
                    ensurePoToken(videoId)
                }

                Log.d(TAG, "Extracting stream: $url")
                val info = getStreamInfo(url)
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

                // ensurePoToken di sini — kalau extractAudioUrl dipanggil sesudahnya,
                // cooldown mencegah generate ulang yang sia-sia
                extractVideoId(url)?.let { videoId ->
                    ensurePoToken(videoId)
                }

                // Pakai cached StreamInfo kalau tersedia
                val info = getStreamInfo(url)
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

    // ── StreamInfo cache ──────────────────────────────────────────────────────

    /**
     * Ambil StreamInfo dengan cache 5 menit.
     * Mencegah double-hit ke YouTube saat fetchMetadata + extractAudioUrl
     * dipanggil berurutan untuk URL yang sama.
     */
    private suspend fun getStreamInfo(url: String): StreamInfo {
        return streamInfoMutex.withLock {
            val cached = streamInfoCache
            val now = System.currentTimeMillis()

            if (cached != null
                && cached.url == url
                && (now - cached.cachedAt) < STREAM_INFO_CACHE_TTL_MS
            ) {
                Log.d(TAG, "StreamInfo cache HIT untuk: $url")
                return@withLock cached.info
            }

            Log.d(TAG, "StreamInfo cache MISS — fetching dari NewPipe: $url")
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            streamInfoCache = CachedStreamInfo(url, info, now)
            info
        }
    }

    /**
     * Invalidate cache secara manual (opsional, misal saat poToken baru di-set).
     */
    fun invalidateStreamInfoCache() {
        streamInfoCache = null
        Log.d(TAG, "StreamInfo cache invalidated")
    }

    // ── PoToken refresh dengan Mutex + Cooldown ───────────────────────────────

    /**
     * Generate BotGuard PoToken (WEB platform) dengan tiga guard:
     *
     *  1. Fast path: token masih valid → langsung return
     *  2. Cooldown: attempt terakhir baru saja dilakukan (< 60 detik) → skip
     *  3. Mutex: kalau dua coroutine masuk bersamaan, hanya satu yang generate
     *
     * Token yang dihasilkan di-inject ke WEB client sebagai playerRequestsPoToken.
     * TIDAK di-inject ke ANDROID — BotGuard token tidak valid untuk ANDROID GVS
     * (butuh DroidGuard yang tidak bisa dijalankan dari WebView).
     *
     * Setelah PoToken berhasil di-generate, invalidate StreamInfo cache agar
     * request berikutnya pakai token yang baru.
     */
    private suspend fun ensurePoToken(videoId: String) {
        val downloader = NewPipeDownloader.getInstance()

        // Guard 1: token masih valid
        if (downloader.isPoTokenValid()) {
            Log.d(TAG, "PoToken masih valid ✓ skip regenerate")
            return
        }

        // Guard 2: cooldown — jangan retry terlalu cepat
        val timeSinceLast = System.currentTimeMillis() - lastPoTokenAttemptAt
        if (timeSinceLast < POT_RETRY_COOLDOWN_MS) {
            Log.d(TAG, "PoToken cooldown aktif (${timeSinceLast / 1000}s ago) — skip generate")
            return
        }

        // Guard 3: mutex — hanya satu coroutine yang generate
        poTokenMutex.withLock {
            // Re-check setelah masuk mutex
            if (downloader.isPoTokenValid()) {
                Log.d(TAG, "PoToken sudah di-refresh oleh coroutine lain ✓")
                return@withLock
            }
            if ((System.currentTimeMillis() - lastPoTokenAttemptAt) < POT_RETRY_COOLDOWN_MS) {
                Log.d(TAG, "PoToken cooldown (inside mutex) — skip")
                return@withLock
            }

            val vd = downloader.getVisitorData() ?: ""
            if (vd.isBlank()) {
                Log.w(TAG, "ensurePoToken: visitorData kosong, skip")
                return@withLock
            }

            // Catat waktu attempt SEBELUM generate — agar cooldown langsung berlaku
            lastPoTokenAttemptAt = System.currentTimeMillis()
            Log.d(TAG, "PoToken expired/missing → generating untuk videoId=$videoId")

            val poRes = poTokenGenerator.generate(videoId, vd)
            if (poRes != null) {
                downloader.setPoToken(poRes.poToken)
                // Invalidate cache agar stream request berikutnya pakai token baru
                invalidateStreamInfoCache()
                Log.i(TAG, "PoToken refreshed ✓ (${poRes.poToken.take(20)}...)")
            } else {
                Log.w(TAG, "PoToken generation gagal — cooldown ${POT_RETRY_COOLDOWN_MS / 1000}s aktif")
            }
        }
    }

    // ── YouTube Innertube Search ──────────────────────────────────────────────

    suspend fun searchFirstResult(query: String): VideoMeta? =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Innertube search: \"$query\"")
            try {
                val visitorData = NewPipeDownloader.getInstance().getVisitorData()
                val bodyJson = buildInnertubeBody(query, visitorData)

                val reqBuilder = Request.Builder()
                    .url(INNERTUBE_SEARCH)
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")

                if (!visitorData.isNullOrBlank()) {
                    reqBuilder.header("X-Goog-Visitor-Id", visitorData)
                }

                val response = http.newCall(reqBuilder.build()).execute()
                val body = response.body?.string()
                    ?: run { Log.e(TAG, "Empty response from Innertube"); return@withContext null }

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

    // ── visitorData management ────────────────────────────────────────────────

    private suspend fun ensureVisitorData() {
        val downloader = NewPipeDownloader.getInstance()
        if (downloader.isVisitorDataValid()) {
            Log.d(TAG, "visitorData masih valid ✓")
            return
        }

        Log.d(TAG, "visitorData kosong/expired → fetch dari YouTube...")
        try {
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?key=$INNERTUBE_KEY")
                .post(buildInnertubeBody("music", null)
                    .toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: return
            extractVisitorData(body)

            if (downloader.isVisitorDataValid()) {
                Log.d(TAG, "visitorData berhasil di-bootstrap ✓")
            } else {
                Log.w(TAG, "Bootstrap visitorData gagal — akan dicoba lagi di request berikutnya")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureVisitorData fetch error: ${e.message}")
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
                val downloader = NewPipeDownloader.getInstance()
                if (!downloader.isVisitorDataValid()) {
                    downloader.setVisitorData(newVisitorData)
                    Log.d(TAG, "visitorData di-refresh dari response: ${newVisitorData.take(20)}...")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract visitorData: ${e.message}")
        }
    }

    // ── Innertube request builder ─────────────────────────────────────────────

    private fun buildInnertubeBody(query: String, visitorData: String?): String {
        val escaped = query
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

        val vdField = if (!visitorData.isNullOrBlank())
            ""","visitorData": "$visitorData""""
        else ""

        return """
            {
              "query": "$escaped",
              "context": {
                "client": {
                  "clientName": "WEB",
                  "clientVersion": "2.20241210.01.00",
                  "hl": "en",
                  "gl": "US"$vdField
                }
              },
              "params": "EgIQAQ%3D%3D"
            }
        """.trimIndent()
    }

    // ── Innertube response parser ─────────────────────────────────────────────

    private fun parseInnertubeFirstVideo(json: String): VideoMeta? {
        return try {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
            reader.isLenient = true
            val root = JsonParser.parseReader(reader).asJsonObject

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
                        title     = title,
                        duration  = duration,
                        thumbnail = thumbUrl,
                        channel   = channel,
                        videoUrl  = "https://www.youtube.com/watch?v=$videoId"
                    )
                }
            }
            fallbackVideoIdExtract(json)
        } catch (e: Exception) {
            Log.e(TAG, "parseInnertubeFirstVideo error: ${e.message}")
            fallbackVideoIdExtract(json)
        }
    }

    private fun fallbackVideoIdExtract(json: String): VideoMeta? {
        val match = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(json)
            ?: return null
        val videoId = match.groupValues[1]
        Log.d(TAG, "Fallback videoId: $videoId")
        return VideoMeta(
            title     = "Video",
            duration  = 0,
            thumbnail = null,
            channel   = null,
            videoUrl  = "https://www.youtube.com/watch?v=$videoId"
        )
    }

    // ── Resolve input → YouTube URL ───────────────────────────────────────────

    private suspend fun resolveToUrl(input: String): String? {
        val t = input.trim()
        return when {
            t.contains("youtube.com/watch") ||
            t.contains("youtu.be/") ||
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
        val audioOnly = info.audioStreams.filter { it.content.startsWith("http") }

        val opus = audioOnly.filter {
            it.format?.name?.contains("opus", true) == true ||
                    it.content.contains("mime=audio%2Fwebm")
        }

        val bestAudioOnly = (if (opus.isNotEmpty()) opus.maxByOrNull { it.averageBitrate }
        else audioOnly.maxByOrNull { it.averageBitrate })?.content

        if (bestAudioOnly != null) return bestAudioOnly

        Log.d(TAG, "No audio-only streams, falling back to muxed streams")
        return info.videoStreams
            .filter { it.content.startsWith("http") }
            .minByOrNull { it.resolution ?: "360p" }
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
                2    -> parts[0] * 60 + parts[1]
                3    -> parts[0] * 3600 + parts[1] * 60 + parts[2]
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
