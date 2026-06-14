package ame.project.kanae.player

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
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
 * YtDlpHelper v10
 *
 * Arsitektur dual-library:
 *
 *  PRIMARY  → NewPipe Extractor (NPE)
 *             Fast, no binary, in-process Kotlin.
 *             Kekurangan: WEB client tidak bisa stream, PoToken hanya BotGuard (WEB only).
 *
 *  FALLBACK → youtubedl-android (yt-dlp)
 *             Dipakai otomatis kalau NPE gagal (ExtractionException, 403, empty streams, dll).
 *             yt-dlp handle sendiri semua client selection + PoToken logic.
 *             Butuh inisialisasi pertama (download binary ~20MB) via ensureInstalled().
 *
 *  Flow extractAudioUrl():
 *    1. Coba NPE → kalau dapat audio stream URL → return
 *    2. NPE gagal / stream kosong → log warning → coba yt-dlp fallback
 *    3. yt-dlp fallback → return URL atau throw
 *
 *  Flow fetchMetadata():
 *    1. Coba NPE → kalau dapat info → return VideoMeta
 *    2. NPE gagal → coba yt-dlp getInfo() sebagai fallback
 *
 *  Setup di build.gradle:
 *    repositories { maven { url "https://jitpack.io" } }
 *    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.2")
 *    implementation("com.github.yausername.youtubedl-android:library:latest.release")
 */
class YtDlpHelper(private val context: Context) {

    private val poTokenGenerator = PoTokenGenerator(context)
    private val poTokenMutex = Mutex()

    @Volatile private var lastPoTokenAttemptAt: Long = 0L
    private val POT_RETRY_COOLDOWN_MS = 60_000L

    // ── StreamInfo cache ──────────────────────────────────────────────────────

    private data class CachedStreamInfo(val url: String, val info: StreamInfo, val cachedAt: Long)
    @Volatile private var streamInfoCache: CachedStreamInfo? = null
    private val STREAM_INFO_CACHE_TTL_MS = 5 * 60 * 1000L
    private val streamInfoMutex = Mutex()

    // ── yt-dlp init state ─────────────────────────────────────────────────────

    @Volatile private var ytdlpReady = false
    private val ytdlpInitMutex = Mutex()

    companion object {
        private const val TAG = "YtDlpHelper"

        private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val INNERTUBE_SEARCH =
            "https://www.youtube.com/youtubei/v1/search?key=$INNERTUBE_KEY"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Safari/537.36"

        @Volatile private var npeReady = false

        fun initNpe() {
            if (npeReady) return
            synchronized(this) {
                if (npeReady) return
                try {
                    val downloader = NewPipeDownloader.getInstance()
                    NewPipe.init(downloader)

                    // PoTokenProvider — platform-specific rules:
                    //
                    //  BotGuard (WebView) token = WEB platform ONLY.
                    //  ANDROID butuh DroidGuard — tidak tersedia dari WebView.
                    //  Cross-platform injection menyebabkan GVS 403 saat playback.
                    //
                    //  PoTokenResult(visitorData, playerRequestsPoToken, streamingDataPoToken)
                    //
                    //  WEB      → (vd, botguardToken, null)  metadata + player request
                    //  ANDROID  → (vd, "", null)             DroidGuard tidak tersedia
                    //  iOS      → (vd, "", null)             iOSGuard tidak tersedia
                    //  WebEmbed → (vd, "", null)             tidak perlu token
                    YoutubeStreamExtractor.setPoTokenProvider(object : PoTokenProvider {
                        override fun getWebClientPoToken(videoId: String?): PoTokenResult {
                            val vd = downloader.getVisitorData() ?: ""
                            // Ambil token meskipun isPoTokenValid() false untuk melihat apakah ada token lama
                            val po = downloader.getPoToken()
                            val isValid = downloader.isPoTokenValid()
                            
                            Log.d(TAG, "PoTokenProvider WEB: videoId=$videoId, valid=$isValid, po=${po?.take(15)}..., vd=${vd.take(15)}...")
                            
                            // Inject ke kedua slot (Player & StreamingData) untuk memaksimalkan peluang
                            return PoTokenResult(vd, po ?: "", po ?: "")
                        }
                        override fun getAndroidClientPoToken(videoId: String?): PoTokenResult {
                            val vd = downloader.getVisitorData() ?: ""
                            Log.d(TAG, "PoTokenProvider ANDROID: videoId=$videoId, vd=${vd.take(15)}...")
                            return PoTokenResult(vd, "", null)
                        }
                        override fun getIosClientPoToken(videoId: String?): PoTokenResult {
                            val vd = downloader.getVisitorData() ?: ""
                            return PoTokenResult(vd, "", null)
                        }
                        override fun getWebEmbedClientPoToken(videoId: String?): PoTokenResult {
                            val vd = downloader.getVisitorData() ?: ""
                            return PoTokenResult(vd, "", null)
                        }
                    })

                    npeReady = true
                    Log.i(TAG, "NewPipe Extractor ready ✓")
                } catch (e: Exception) {
                    Log.e(TAG, "NewPipe init error", e)
                }
            }
        }
    }

    val isInstalled: Boolean
        get() = ytdlpReady

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC: ensureInstalled — init yt-dlp binary (dipanggil satu kali di awal)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Init yt-dlp (fallback library). Harus dipanggil sekali sebelum fallback bisa dipakai.
     * Kalau binary sudah ada (install sebelumnya), langsung return tanpa download.
     * Kalau belum ada, download ~20MB di background.
     *
     * NPE (primary) tidak butuh ini dan langsung siap pakai.
     */
    suspend fun ensureInstalled(
        onProgress: (Int) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        initNpe()
        ytdlpInitMutex.withLock {
            if (ytdlpReady) {
                onLog("yt-dlp sudah siap ✓")
                onProgress(100)
                return@withLock true
            }
            try {
                onLog("Inisialisasi yt-dlp...")
                onProgress(10)
                YoutubeDL.getInstance().init(context.applicationContext)
                onProgress(40)

                // Update ke versi terbaru kalau ada koneksi
                // Ini opsional — kalau tidak ada koneksi, pakai versi yang bundled
                try {
                    onLog("Mengecek update yt-dlp...")
                    val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
                    onLog("yt-dlp update: $status")
                } catch (e: Exception) {
                    Log.w(TAG, "yt-dlp update skip (mungkin offline): ${e.message}")
                    onLog("Update skip, pakai versi tersedia")
                }

                onProgress(100)
                ytdlpReady = true
                onLog("yt-dlp siap sebagai fallback ✓")
                Log.i(TAG, "yt-dlp ready ✓")
                true
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp init error: ${e.message}", e)
                onLog("yt-dlp init gagal: ${e.message}")
                false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC: extractAudioUrl — PRIMARY: NPE, FALLBACK: yt-dlp
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun extractAudioUrl(input: String): Result<String> =
        withContext(Dispatchers.IO) {
            initNpe()
            ensureVisitorData()

            val resolvedUrl = resolveToUrl(input)

            // ── PRIMARY: NPE ──────────────────────────────────────────────────
            if (resolvedUrl != null) {
                val npeResult = tryExtractWithNpe(resolvedUrl)
                if (npeResult.isSuccess) {
                    return@withContext npeResult
                }
                val npeError = npeResult.exceptionOrNull()
                Log.w(TAG, "NPE gagal ekstrak URL (${npeError?.message}) → fallback ke yt-dlp")
            } else {
                Log.w(TAG, "NPE gagal mencari hasil untuk: \"$input\" → mencoba pencarian via yt-dlp")
            }

            // ── FALLBACK: yt-dlp ──────────────────────────────────────────────
            if (!ytdlpReady) {
                Log.w(TAG, "yt-dlp belum diinit — jalankan ensureInstalled() lebih awal")
                return@withContext Result.failure(
                    RuntimeException("NPE gagal dan yt-dlp belum siap")
                )
            }

            // Jika NPE gagal total (pencarian gagal atau ekstraksi gagal), 
            // biarkan yt-dlp mencoba dengan input asli.
            // Gunakan resolvedUrl jika tersedia, atau input asli (tambahkan ytsearch1: jika bukan URL).
            val ytDlpInput = when {
                resolvedUrl != null -> resolvedUrl
                input.contains("youtube.com") || input.contains("youtu.be") -> input
                else -> "ytsearch1:$input"
            }

            Log.d(TAG, "[yt-dlp] Menjalankan fallback akhir dengan input: $ytDlpInput")
            tryExtractWithYtdlp(ytDlpInput)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // DEBUG: Test only
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Memaksa ekstraksi menggunakan yt-dlp (fallback) tanpa mencoba NPE.
     * Gunakan ini hanya untuk debugging/testing integritas binary yt-dlp.
     */
    suspend fun extractAudioUrlWithYtdlpOnly(input: String): Result<String> =
        withContext(Dispatchers.IO) {
            val url = resolveToUrl(input)
                ?: return@withContext Result.failure(RuntimeException("URL tidak valid"))

            if (!ytdlpReady) {
                return@withContext Result.failure(
                    RuntimeException("yt-dlp belum siap. Panggil ensureInstalled() terlebih dahulu.")
                )
            }

            Log.d(TAG, "[DEBUG] Menjalankan ekstraksi yt-dlp murni untuk: $url")
            tryExtractWithYtdlp(url)
        }


    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC: fetchMetadata — PRIMARY: NPE, FALLBACK: yt-dlp
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun fetchMetadata(input: String): VideoMeta? =
        withContext(Dispatchers.IO) {
            initNpe()
            ensureVisitorData()

            val url = resolveToUrl(input) ?: return@withContext null

            // ── PRIMARY: NPE ──────────────────────────────────────────────────
            try {
                extractVideoId(url)?.let { ensurePoToken(it) }
                val info = getStreamInfo(url)
                Log.d(TAG, "fetchMetadata via NPE ✓: ${info.name}")
                return@withContext VideoMeta(
                    title     = info.name,
                    duration  = info.duration.toInt(),
                    thumbnail = info.thumbnails.firstOrNull()?.url,
                    channel   = info.uploaderName,
                    videoUrl  = url
                )
            } catch (e: Exception) {
                Log.w(TAG, "NPE fetchMetadata gagal (${e.message}) → fallback ke yt-dlp")
            }

            // ── FALLBACK: yt-dlp ──────────────────────────────────────────────
            if (!ytdlpReady) return@withContext null
            try {
                val info = YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url))
                Log.d(TAG, "fetchMetadata via yt-dlp ✓: ${info.title}")
                VideoMeta(
                    title     = info.title ?: info.fulltitle ?: "Unknown",
                    duration  = info.duration,
                    thumbnail = info.thumbnail,
                    channel   = info.uploader,
                    videoUrl  = url
                )
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp fetchMetadata gagal: ${e.message}")
                null
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE: NPE extraction
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun tryExtractWithNpe(url: String): Result<String> {
        return try {
            extractVideoId(url)?.let { ensurePoToken(it) }

            Log.d(TAG, "[NPE] Extracting: $url")
            val info = getStreamInfo(url)
            Log.d(TAG, "[NPE] Got streams — Audio: ${info.audioStreams.size}, VideoOnly: ${info.videoOnlyStreams.size}, Muxed: ${info.videoStreams.size}")

            val best = pickBestAudio(info)
            if (best == null) {
                // Stream list kosong = sering tanda PoToken issue atau video restricted
                return Result.failure(
                    RuntimeException("[NPE] No audio streams found untuk: $url")
                )
            }

            Log.d(TAG, "[NPE] Stream OK: ${best.take(80)}…")
            Result.success(best)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE: yt-dlp fallback extraction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ekstrak audio stream URL via yt-dlp.
     *
     * Pakai format selector "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio"
     * untuk prioritaskan opus/webm (kualitas tinggi, hemat bandwidth).
     *
     * --no-playlist: pastikan hanya extract 1 video.
     * --extractor-retries 3: retry kalau network flaky.
     */
    private suspend fun tryExtractWithYtdlp(url: String): Result<String> {
        return try {
            Log.d(TAG, "[yt-dlp] Extracting: $url")

            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--extractor-retries", "3")
                addOption("-f", "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio")
                addOption("--get-url")       // hanya return direct URL
                addOption("--no-warnings")
            }

            val response = YoutubeDL.getInstance().execute(request)
            val streamUrl = response.out.trim().lines().firstOrNull { it.startsWith("http") }

            if (streamUrl.isNullOrBlank()) {
                Log.e(TAG, "[yt-dlp] No URL in response: ${response.out.take(200)}")
                return Result.failure(RuntimeException("[yt-dlp] Stream URL kosong untuk: $url"))
            }

            Log.i(TAG, "[yt-dlp] Stream OK: ${streamUrl.take(80)}…")
            Result.success(streamUrl)
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "[yt-dlp] YoutubeDLException: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "[yt-dlp] Exception: ${e.message}")
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // StreamInfo cache
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun getStreamInfo(url: String): StreamInfo {
        return streamInfoMutex.withLock {
            val cached = streamInfoCache
            val now = System.currentTimeMillis()
            if (cached != null
                && cached.url == url
                && (now - cached.cachedAt) < STREAM_INFO_CACHE_TTL_MS
            ) {
                Log.d(TAG, "[NPE] StreamInfo cache HIT")
                return@withLock cached.info
            }
            Log.d(TAG, "[NPE] StreamInfo cache MISS — fetching")
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            streamInfoCache = CachedStreamInfo(url, info, now)
            info
        }
    }

    fun invalidateStreamInfoCache() {
        streamInfoCache = null
        Log.d(TAG, "StreamInfo cache invalidated")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PoToken generation (BotGuard / WEB only)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun ensurePoToken(videoId: String) {
        val downloader = NewPipeDownloader.getInstance()
        if (downloader.isPoTokenValid()) return

        val timeSinceLast = System.currentTimeMillis() - lastPoTokenAttemptAt
        if (timeSinceLast < POT_RETRY_COOLDOWN_MS) {
            Log.d(TAG, "PoToken cooldown aktif (${timeSinceLast / 1000}s) — skip")
            return
        }

        poTokenMutex.withLock {
            if (downloader.isPoTokenValid()) return@withLock
            if ((System.currentTimeMillis() - lastPoTokenAttemptAt) < POT_RETRY_COOLDOWN_MS) return@withLock

            val vd = downloader.getVisitorData() ?: ""
            if (vd.isBlank()) { Log.w(TAG, "ensurePoToken: visitorData kosong"); return@withLock }

            lastPoTokenAttemptAt = System.currentTimeMillis()
            Log.d(TAG, "Generating BotGuard PoToken untuk videoId=$videoId...")

            val result = poTokenGenerator.generate(videoId, vd)
            if (result != null) {
                Log.i(TAG, "PoToken generated: ${result.poToken.take(20)}...")
                downloader.setPoToken(result.poToken)
                invalidateStreamInfoCache()
                Log.i(TAG, "PoToken refreshed and cache invalidated ✓")
            } else {
                Log.w(TAG, "PoToken generation GAGAL — cooldown ${POT_RETRY_COOLDOWN_MS / 1000}s")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────────────────────────────────

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

                if (!visitorData.isNullOrBlank())
                    reqBuilder.header("X-Goog-Visitor-Id", visitorData)

                val response = http.newCall(reqBuilder.build()).execute()
                val body = response.body?.string()
                    ?: run { Log.e(TAG, "Empty Innertube response"); return@withContext null }

                extractVisitorData(body)
                Log.d(TAG, "Innertube response: ${response.code}, size: ${body.length}")

                val result = parseInnertubeFirstVideo(body)
                if (result != null) Log.d(TAG, "Search: ${result.title} → ${result.videoUrl}")
                else Log.w(TAG, "No result for: \"$query\"")
                result
            } catch (e: Exception) {
                Log.e(TAG, "searchFirstResult error: ${e.message}")
                null
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // visitorData
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun ensureVisitorData() {
        val downloader = NewPipeDownloader.getInstance()
        if (downloader.isVisitorDataValid()) {
            Log.d(TAG, "visitorData masih valid ✓")
            return
        }
        Log.d(TAG, "visitorData expired/kosong → bootstrap...")
        try {
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?key=$INNERTUBE_KEY")
                .post(buildInnertubeBody("music", null).toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val body = http.newCall(req).execute().body?.string() ?: return
            extractVisitorData(body)
            if (downloader.isVisitorDataValid())
                Log.d(TAG, "visitorData bootstrap ✓")
            else
                Log.w(TAG, "Bootstrap visitorData gagal")
        } catch (e: Exception) {
            Log.w(TAG, "ensureVisitorData error: ${e.message}")
        }
    }

    private fun extractVisitorData(json: String) {
        try {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
            reader.isLenient = true
            val root = JsonParser.parseReader(reader).asJsonObject
            val vd = root.getObj("responseContext")?.get("visitorData")?.asString
            if (!vd.isNullOrBlank()) {
                val downloader = NewPipeDownloader.getInstance()
                if (!downloader.isVisitorDataValid()) {
                    downloader.setVisitorData(vd)
                    Log.d(TAG, "visitorData refreshed: ${vd.take(20)}...")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractVisitorData error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildInnertubeBody(query: String, visitorData: String?): String {
        val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val vdField = if (!visitorData.isNullOrBlank()) ""","visitorData":"$visitorData"""" else ""
        return """{"query":"$escaped","context":{"client":{"clientName":"WEB","clientVersion":"2.20241210.01.00","hl":"en","gl":"US"$vdField}},"params":"EgIQAQ%3D%3D"}"""
    }

    private fun parseInnertubeFirstVideo(json: String): VideoMeta? {
        return try {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
            reader.isLenient = true
            val root = JsonParser.parseReader(reader).asJsonObject
            val sections = root.getObj("contents")?.getObj("twoColumnSearchResultsRenderer")
                ?.getObj("primaryContents")?.getObj("sectionListRenderer")?.getArr("contents")
                ?: return fallbackVideoIdExtract(json)

            for (section in sections) {
                val items = section.asJsonObject.getObj("itemSectionRenderer")?.getArr("contents") ?: continue
                for (item in items) {
                    val vr = item.asJsonObject.getObj("videoRenderer") ?: continue
                    val videoId = vr["videoId"]?.asString ?: continue
                    val title = vr.getObj("title")?.getArr("runs")
                        ?.firstOrNull()?.asJsonObject?.get("text")?.asString ?: continue
                    return VideoMeta(
                        title     = title,
                        duration  = parseDuration(vr.getObj("lengthText")?.get("simpleText")?.asString),
                        thumbnail = vr.getObj("thumbnail")?.getArr("thumbnails")?.lastOrNull()?.asJsonObject?.get("url")?.asString,
                        channel   = vr.getObj("ownerText")?.getArr("runs")?.firstOrNull()?.asJsonObject?.get("text")?.asString,
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
        val videoId = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(json)
            ?.groupValues?.get(1) ?: return null
        return VideoMeta("Video", 0, null, null, "https://www.youtube.com/watch?v=$videoId")
    }

    private suspend fun resolveToUrl(input: String): String? {
        val t = input.trim()
        return when {
            t.contains("youtube.com/watch") || t.contains("youtu.be/") || t.contains("youtube.com/shorts") -> t
            t.startsWith("ytsearch:") -> searchFirstResult(t.substringAfter(":").trim())?.videoUrl
            else -> searchFirstResult(t)?.videoUrl
        }
    }

    private fun pickBestAudio(info: StreamInfo): String? {
        val audioOnly = info.audioStreams.filter { it.content.startsWith("http") }
        val opus = audioOnly.filter {
            it.format?.name?.contains("opus", true) == true || it.content.contains("mime=audio%2Fwebm")
        }
        return (if (opus.isNotEmpty()) opus.maxByOrNull { it.averageBitrate }
                else audioOnly.maxByOrNull { it.averageBitrate })?.content
            ?: info.videoStreams.filter { it.content.startsWith("http") }
                .minByOrNull { it.resolution ?: "360p" }?.content
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf("v=([a-zA-Z0-9_-]{11})", "be/([a-zA-Z0-9_-]{11})",
            "shorts/([a-zA-Z0-9_-]{11})", "embed/([a-zA-Z0-9_-]{11})")
        for (p in patterns) Regex(p).find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun parseDuration(s: String?): Int {
        if (s == null) return 0
        return try {
            val p = s.split(":").map { it.toInt() }
            when (p.size) { 2 -> p[0] * 60 + p[1]; 3 -> p[0] * 3600 + p[1] * 60 + p[2]; else -> 0 }
        } catch (_: Exception) { 0 }
    }

    private fun com.google.gson.JsonObject.getObj(key: String) =
        this[key]?.takeIf { it.isJsonObject }?.asJsonObject
    private fun com.google.gson.JsonObject.getArr(key: String) =
        this[key]?.takeIf { it.isJsonArray }?.asJsonArray

    // ─────────────────────────────────────────────────────────────────────────
    // Model
    // ─────────────────────────────────────────────────────────────────────────

    data class VideoMeta(
        val title: String,
        val duration: Int,
        val thumbnail: String?,
        val channel: String?,
        val videoUrl: String = ""
    )
}
