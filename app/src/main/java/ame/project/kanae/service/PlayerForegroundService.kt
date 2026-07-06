package ame.project.kanae.service

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import ame.project.kanae.MainActivity
import ame.project.kanae.model.Song
import ame.project.kanae.model.TikTokChat
import ame.project.kanae.model.CustomTheme
import ame.project.kanae.overlay.ChatOverlayManager
import ame.project.kanae.overlay.CustomOverlayManager
import ame.project.kanae.overlay.LyricsOverlayManager
import ame.project.kanae.overlay.OverlayManager
import ame.project.kanae.overlay.QueueOverlayManager
import ame.project.kanae.overlay.TikTokNotificationOverlayManager
import ame.project.kanae.overlay.TikTokJoinOverlayManager
import ame.project.kanae.overlay.TikTokLikeOverlayManager
import ame.project.kanae.overlay.TikTokFollowOverlayManager
import ame.project.kanae.player.AudioPlayer
import ame.project.kanae.player.YtDlpHelper
import ame.project.kanae.tiktok.TikTokLiveManager
import android.view.WindowManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*

class PlayerForegroundService : Service() {

    companion object {
        private const val TAG              = "PlayerService"
        private const val NOTIF_CHANNEL_ID = "yt_player_channel"
        private const val NOTIF_ID         = 1001
        private const val MAX_QUEUE        = 50
        private const val PREFS_NAME       = "ytplayer_prefs"

        const val ACTION_PLAY_PAUSE    = "ame.project.ytplayer.PLAY_PAUSE"
        const val ACTION_SKIP          = "ame.project.ytplayer.SKIP"
        const val ACTION_STOP          = "ame.project.ytplayer.STOP"
        const val ACTION_SHOW_OVERLAY  = "ame.project.ytplayer.SHOW_OVERLAY"
        const val ACTION_CANVAS_MODE   = "ame.project.ytplayer.CANVAS_MODE"
        const val ACTION_LYRICS_TOGGLE = "ame.project.ytplayer.LYRICS_TOGGLE"

        const val BROADCAST_STATE = "ame.project.ytplayer.STATE_UPDATE"
        const val BROADCAST_CHAT  = "ame.project.ytplayer.CHAT_UPDATE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var audioPlayer: AudioPlayer
    private lateinit var ytDlp: YtDlpHelper
    private lateinit var tiktokManager: TikTokLiveManager
    private lateinit var overlayManager: OverlayManager
    private lateinit var queueOverlayManager: QueueOverlayManager
    private lateinit var lyricsOverlayManager: LyricsOverlayManager
    private lateinit var chatOverlayManager: ChatOverlayManager
    private lateinit var notifOverlayManager: TikTokNotificationOverlayManager
    private lateinit var joinOverlayManager: TikTokJoinOverlayManager
    private lateinit var likeOverlayManager: TikTokLikeOverlayManager
    private lateinit var followOverlayManager: TikTokFollowOverlayManager
    private lateinit var customOverlayManager: CustomOverlayManager

    private val queue       = ArrayDeque<Song>()
    private var currentSong: Song?  = null
    private var isPlaying   = false
    private var isPaused    = false
    private var positionMs  = 0L
    private var durationMs  = 0L
    private var shuffleMode = false
    private var musicVolume = 1.0f
    private var notifVolume = 1.0f
    private var tiktokConnected = false
    private var tiktokConnecting = false
    private var tiktokConnectTime = 0L
    private var requestLimit  = 3
    private var useTiktokGiftIcon = true
    private var joinEnabled = true
    private var likeEnabled = true
    private var followEnabled = true
    private var notifEnabled = true

    private var tts: TextToSpeech? = null
    private var ttsEnabled: Boolean = false
    private var ttsVolume: Float = 1.0f
    private var ttsMaxLength: Int = 100
    private var isTtsSpeaking: Boolean = false
    private var ttsMediaPlayer: MediaPlayer? = null
    private var ttsLoudnessEnhancer: LoudnessEnhancer? = null
    private val ttsFile by lazy { File(cacheDir, "tts_cache.wav") }

    private var apiKey        = ""
    private var tiktokUsername = ""
    private var commandConfig  = TikTokLiveManager.CommandConfig()

    // ── Canvas mode state ─────────────────────────────────────────────
    private var canvasModeEnabled = false
    private var canvasPlayerX     = 16
    private var canvasPlayerY     = 100
    private var canvasQueueX      = 16
    private var canvasQueueY      = 440

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val gson  = Gson()

    fun getCustomOverlayManager() = customOverlayManager

    inner class LocalBinder : Binder() {
        fun getService(): PlayerForegroundService = this@PlayerForegroundService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ─────────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        createNotificationChannel()
        //startForeground(NOTIF_ID, buildNotification("Starting…"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("Starting…"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Starting…"))
        }


        loadPrefs()

        audioPlayer = AudioPlayer(this, serviceScope).also { p ->
            p.onComplete = ::onSongComplete
            p.onError    = { err -> Log.e(TAG, "Player error: $err"); playNext() }
            p.onProgress = { pos, dur ->
                positionMs = pos
                if (dur > 0) durationMs = dur
                
                overlayManager.updateSong(currentSong, positionMs, durationMs)
                // Sync lyrics cue to current playback position
                if (lyricsOverlayManager.isShowing) {
                    lyricsOverlayManager.updatePosition(positionMs)
                }
                broadcastState()
            }
            p.init()
            p.setVolume(musicVolume)
        }

        YtDlpHelper.initNpe()
        ytDlp = YtDlpHelper(this)
        serviceScope.launch {
            ytDlp.ensureInstalled(
                onProgress = { p -> Log.d(TAG, "yt-dlp install progress: $p%") },
                onLog = { msg -> Log.d(TAG, "yt-dlp install: $msg") }
            )
            broadcastState()
        }

        overlayManager = OverlayManager(
            context     = this,
            scope       = serviceScope,
            onPlayPause = ::togglePlayPause,
            onSkip      = ::playNext,
            onClose     = { broadcastState() }
        ).apply {
            updateStyle(prefs.getInt("canvas_player_layout", ame.project.kanae.R.layout.overlay_layout))
            
            // Restore last position/scale
            val x = prefs.getInt("player_x", 16)
            val y = prefs.getInt("player_y", 100)
            val scale = prefs.getFloat("player_scale", 1f)
            applyConfig(x, y, scale)

            onPositionChanged = { nx, ny, ns ->
                prefs.edit().putInt("player_x", nx).putInt("player_y", ny).putFloat("player_scale", ns).apply()
            }
        }

        queueOverlayManager = QueueOverlayManager(
            context  = this,
            onPlay   = { pos -> queue.elementAtOrNull(pos)?.let { playSong(it) } },
            onRemove = { pos -> removeFromQueue(pos) },
            onClose  = { broadcastState() }
        ).apply {
            val container = prefs.getInt("canvas_queue_layout", ame.project.kanae.R.layout.overlay_queue_layout)
            val item = prefs.getInt("canvas_queue_item_layout", ame.project.kanae.R.layout.item_queue)
            updateStyle(container, item)

            // Restore last position/scale
            val x = prefs.getInt("queue_x", 16)
            val y = prefs.getInt("queue_y", 420)
            val scale = prefs.getFloat("queue_scale", 1f)
            val width = prefs.getInt("queue_width", 300)
            applyConfig(x, y, scale, width)

            onPositionChanged = { nx, ny, ns ->
                prefs.edit().putInt("queue_x", nx).putInt("queue_y", ny).putFloat("queue_scale", ns).apply()
            }
        }

        lyricsOverlayManager = LyricsOverlayManager(
            context       = this,
            scope         = serviceScope,
            preferredLang = loadPreferredLyricsLang(),
            onClose       = { broadcastState() }
        ).apply {
            updateStyle(prefs.getInt("canvas_lyrics_layout", ame.project.kanae.R.layout.overlay_lyrics_layout))

            // Restore last position/scale
            val x = prefs.getInt("lyrics_x", 16)
            val y = prefs.getInt("lyrics_y", 750)
            val scale = prefs.getFloat("lyrics_scale", 1f)
            applyConfig(x, y, scale)

            onPositionChanged = { nx, ny, ns ->
                prefs.edit().putInt("lyrics_x", nx).putInt("lyrics_y", ny).putFloat("lyrics_scale", ns).apply()
            }
        }

        chatOverlayManager = ChatOverlayManager(
            context = this,
            scope   = serviceScope,
            maxLines = prefs.getInt("chat_max_lines", 5),
            onClose = { broadcastState() }
        ).apply {
            setTransparent(prefs.getBoolean("chat_transparent", true))
            setStickerAnimationEnabled(prefs.getBoolean("chat_sticker_animation", true))
            setOverlayWidth(prefs.getInt("chat_width", 300))
            setDisplayDuration(prefs.getInt("chat_duration", 6))
            updateStyle(
                prefs.getInt("canvas_chat_layout", ame.project.kanae.R.layout.item_chat_bubble),
                prefs.getInt("canvas_chat_bg", ame.project.kanae.R.drawable.bg_chat_bubble)
            )

            // Restore last position/scale
            val x = prefs.getInt("chat_x", 16)
            val y = prefs.getInt("chat_y", 500)
            val scale = prefs.getFloat("chat_scale", 1f)
            val width = prefs.getInt("chat_width", 150)
            applyConfig(x, y, scale, width)

            onPositionChanged = { nx, ny, ns ->
                prefs.edit().putInt("chat_x", nx).putInt("chat_y", ny).putFloat("chat_scale", ns).apply()
            }
        }

        notifOverlayManager = TikTokNotificationOverlayManager(this).apply {
            setConfig(
                prefs.getString("notif_share_img", null),
                prefs.getString("notif_gift_img", null),
                prefs.getString("notif_share_aud", null),
                prefs.getString("notif_gift_aud", null),
                prefs.getInt("notif_duration", 5)
            )
            setUseTiktokGiftIcon(prefs.getBoolean("use_tiktok_gift_icon", true))
            setVolume(notifVolume)
            setVisualPunchEnabled(prefs.getBoolean("notif_visual_punch", false))

            // Restore last position/scale
            val x = prefs.getInt("notif_x", 100)
            val y = prefs.getInt("notif_y", 100)
            val scale = prefs.getFloat("notif_scale", 1.0f)
            applyConfig(x, y, scale)

            onPositionChanged = { nx, ny, ns ->
                prefs.edit().putInt("notif_x", nx).putInt("notif_y", ny).putFloat("notif_scale", ns).apply()
            }
        }

        joinOverlayManager = TikTokJoinOverlayManager(this).apply {
            updateStyle(prefs.getInt("canvas_join_layout", ame.project.kanae.R.layout.overlay_tiktok_join))
            setDuration(prefs.getInt("join_duration", 4))
            
            // Restore last position
            val x = prefs.getInt("join_x", 100)
            val y = prefs.getInt("join_y", 200)
            val scale = prefs.getFloat("join_scale", 1f)
            applyConfig(x, y, scale)

            onPositionChanged = { nx, ny, ns ->
                prefs.edit().putInt("join_x", nx).putInt("join_y", ny).putFloat("join_scale", ns).apply()
            }
        }
        likeOverlayManager = TikTokLikeOverlayManager(this).apply {
            updateStyle(prefs.getInt("canvas_like_layout", ame.project.kanae.R.layout.overlay_tiktok_like))
            setAnimationEnabled(prefs.getBoolean("like_animation_enabled", true))
            setDuration(prefs.getInt("like_duration", 4))
            
            // Restore last position
            val x = prefs.getInt("like_x", 50)
            val y = prefs.getInt("like_y", 500)
            val scale = prefs.getFloat("like_scale", 1f)
            applyConfig(x, y, scale)

            onPositionChanged = { nx, ny, ns ->
                prefs.edit().putInt("like_x", nx).putInt("like_y", ny).putFloat("like_scale", ns).apply()
            }
        }
        followOverlayManager = TikTokFollowOverlayManager(this).apply {
            updateStyle(prefs.getInt("canvas_follow_layout", ame.project.kanae.R.layout.overlay_tiktok_follow))
            setDuration(prefs.getInt("follow_duration", 4))
            setVisualPunchEnabled(prefs.getBoolean("follow_visual_punch", false))

            // Restore last position
            val x = prefs.getInt("follow_x", 100)
            val y = prefs.getInt("follow_y", 300)
            val scale = prefs.getFloat("follow_scale", 1f)
            applyConfig(x, y, scale)

            onPositionChanged = { nx, ny, ns ->
                prefs.edit().putInt("follow_x", nx).putInt("follow_y", ny).putFloat("follow_scale", ns).apply()
            }
        }

        customOverlayManager = CustomOverlayManager(this, serviceScope)

        updateCustomThemes()

        // Apply saved configurations to overlays
        overlayManager.setVisualPunchEnabled(prefs.getBoolean("player_visual_punch", false))
        queueOverlayManager.setVisualPunchEnabled(prefs.getBoolean("queue_visual_punch", false))
        lyricsOverlayManager.setVisualPunchEnabled(prefs.getBoolean("lyrics_visual_punch", false))
        chatOverlayManager.setVisualPunchEnabled(prefs.getBoolean("chat_visual_punch", false))

        val qAutoHide = prefs.getBoolean("queue_auto_hide", false)
        val qDuration = prefs.getInt("queue_duration", 10)
        queueOverlayManager.setAutoHide(qAutoHide, qDuration)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.forLanguageTag("id-ID"))
            }
        }

        tiktokManager = buildTikTokManager()

        // ── BUG FIX: Do NOT auto-connect on service start.
        //    The user must click "Save & Connect" explicitly.
        //    (Previously: if (tiktokUsername.isNotBlank() && apiKey.isNotBlank()) tiktokManager.connect())
        Log.d(TAG, "Service ready. ytdlp=${ytDlp.isInstalled}. Auto-connect disabled.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE    -> togglePlayPause()
            ACTION_SKIP          -> playNext()
            ACTION_STOP          -> stopPlayer()
            ACTION_SHOW_OVERLAY  -> showOverlay()
            ACTION_LYRICS_TOGGLE -> toggleLyricsOverlay()
            ACTION_CANVAS_MODE   -> {
                val enable = intent.getBooleanExtra("enabled", false)
                val px = intent.getIntExtra("player_x", canvasPlayerX)
                val py = intent.getIntExtra("player_y", canvasPlayerY)
                val qx = intent.getIntExtra("queue_x",  canvasQueueX)
                val qy = intent.getIntExtra("queue_y",  canvasQueueY)
                if (enable) enableCanvasMode(px, py, qx, qy)
                else        disableCanvasMode()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        tts?.stop()
        tts?.shutdown()
        cleanupPlayer() // Pastikan MediaPlayer & Enhancer TTS dilepaskan
        
        tiktokManager.release() // Gunakan release() bukan hanya disconnect()
        audioPlayer.release()
        
        overlayManager.hide()
        queueOverlayManager.hide()
        lyricsOverlayManager.hide()
        chatOverlayManager.hide()
        
        // Perbaikan: Pastikan overlay notif, join, dan like juga di-hide
        notifOverlayManager.hide()
        joinOverlayManager.hide()
        likeOverlayManager.hide()
        followOverlayManager.hide()

        customOverlayManager.hideAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Settings ──────────────────────────────────────────────────────
    private fun loadPrefs() {
        apiKey         = prefs.getString("euler_api_key", "")     ?: ""
        tiktokUsername = prefs.getString("tiktok_username", "")   ?: ""
        shuffleMode    = prefs.getBoolean("shuffle_mode", false)
        requestLimit   = prefs.getInt("request_limit", 3)
        musicVolume    = prefs.getFloat("music_volume", 1.0f)
        notifVolume    = prefs.getFloat("notif_volume", 1.0f)
        useTiktokGiftIcon = prefs.getBoolean("use_tiktok_gift_icon", true)
        joinEnabled    = prefs.getBoolean("join_enabled", true)
        likeEnabled    = prefs.getBoolean("like_enabled", true)
        followEnabled  = prefs.getBoolean("follow_enabled", true)
        notifEnabled   = prefs.getBoolean("notif_enabled", true)

        ttsEnabled = prefs.getBoolean("chat_tts_enabled", false)
        ttsVolume = prefs.getFloat("chat_tts_volume", 1.0f)
        ttsMaxLength = prefs.getInt("chat_tts_max_length", 100)

        canvasPlayerX  = prefs.getInt("canvas_px", 16)
        canvasPlayerY  = prefs.getInt("canvas_py", 100)
        canvasQueueX   = prefs.getInt("canvas_qx", 16)
        canvasQueueY   = prefs.getInt("canvas_qy", 440)

        commandConfig = TikTokLiveManager.CommandConfig(
            enabled            = prefs.getBoolean("commands_enabled", true),
            requestPrefixes    = splitPref("cmd_request",    "#req,#request,#lagu,#song"),
            skipPrefixes       = splitPref("cmd_skip",       "#skip,#next,#lewat"),
            stopPrefixes       = splitPref("cmd_stop",       "#stop"),
            queuePrefixes      = splitPref("cmd_queue",      "#queue,#antrian,#q"),
            clearMusicPrefixes = splitPref("cmd_clear_music","#cm,#hapus")
        )
    }

    private fun splitPref(key: String, default: String): List<String> =
        (prefs.getString(key, default) ?: default)
            .split(",").map { it.trim() }.filter { it.isNotBlank() }

    fun saveSettings(
        apiKey: String,
        username: String,
        limit: Int = 3,
        cmdConfig: TikTokLiveManager.CommandConfig? = null
    ) {
        if (apiKey.isBlank() && username.isBlank()) {
            // Disconnect request
            tiktokManager.disconnect()
            tiktokConnected = false
            tiktokConnecting = false
            overlayManager.setLiveStatus(false)
            broadcastState()
            return
        }

        this.apiKey        = apiKey
        this.tiktokUsername = username.removePrefix("@")
        this.requestLimit   = limit
        prefs.edit()
            .putString("euler_api_key", this.apiKey)
            .putString("tiktok_username", this.tiktokUsername)
            .putInt("request_limit", this.requestLimit)
            .apply()

        cmdConfig?.let { cfg ->
            this.commandConfig = cfg
            prefs.edit()
                .putBoolean("commands_enabled", cfg.enabled)
                .putString("cmd_request",     cfg.requestPrefixes.joinToString(","))
                .putString("cmd_skip",         cfg.skipPrefixes.joinToString(","))
                .putString("cmd_stop",         cfg.stopPrefixes.joinToString(","))
                .putString("cmd_queue",        cfg.queuePrefixes.joinToString(","))
                .putString("cmd_clear_music",  cfg.clearMusicPrefixes.joinToString(","))
                .apply()
        }

        tiktokManager.release()
        tiktokManager = buildTikTokManager()
        if (this.tiktokUsername.isNotBlank() && this.apiKey.isNotBlank()) {
            tiktokConnecting = true
            tiktokConnectTime = System.currentTimeMillis() + 3600000 // Future time to ignore everything until connected
            broadcastState()
            tiktokManager.connect()
        }
    }

    // ── Queue operations ──────────────────────────────────────────────
    fun addToQueue(youtubeUrl: String, requestedBy: String? = null): Boolean {
        if (queue.size >= MAX_QUEUE) return false
        serviceScope.launch {
            val meta = ytDlp.fetchMetadata(youtubeUrl)
            if (meta == null) {
                Log.w(TAG, "Gagal mendapatkan metadata untuk: $youtubeUrl")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerForegroundService, "Music tidak ditemukan", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val song = Song(
                title       = meta.title,
                youtubeUrl  = meta.videoUrl,
                thumbnail   = meta.thumbnail,
                duration    = meta.duration,
                channel     = meta.channel,
                requestedBy = requestedBy
            )
            queue.addLast(song)
            if (!isPlaying && currentSong == null) {
                playNext()
            } else {
                broadcastState()
                if (queueOverlayManager.isShowing) {
                    queueOverlayManager.updateQueue(getQueue())
                }
                overlayManager.updateQueueCount(queue.size)
            }
        }
        return true
    }

    fun removeFromQueue(index: Int) {
        if (index in queue.indices) {
            queue.removeAt(index)
            broadcastState()
            syncQueueOverlay()
        }
    }

    /**
     * Remove song at [position] (1-indexed, as used in the #cm command).
     * Returns true if removal was successful.
     */
    fun clearMusicAt(position: Int): Boolean {
        val idx = position - 1          // convert to 0-indexed
        if (idx !in queue.indices) return false
        queue.removeAt(idx)
        broadcastState()
        syncQueueOverlay()
        return true
    }

    /**
     * Remove the first song whose title contains [query] (case-insensitive).
     * Returns the removed song title, or null if nothing matched.
     */
    fun clearMusicByTitle(query: String): String? {
        val lower = query.lowercase().trim()
        val idx   = queue.indexOfFirst { it.title.lowercase().contains(lower) }
        if (idx == -1) return null
        val removed = queue.removeAt(idx)
        broadcastState()
        syncQueueOverlay()
        return removed.title
    }

    fun clearQueue() {
        queue.clear()
        broadcastState()
        syncQueueOverlay()
    }

    fun moveInQueue(from: Int, to: Int) {
        if (from in queue.indices && to in queue.indices) {
            val song = queue.removeAt(from)
            queue.add(to, song)
            broadcastState()
            syncQueueOverlay()
        }
    }

    fun getQueue(): List<Song> = queue.toList()

    fun toggleShuffle(): Boolean {
        shuffleMode = !shuffleMode
        prefs.edit().putBoolean("shuffle_mode", shuffleMode).apply()
        return shuffleMode
    }

    // ── Playback ──────────────────────────────────────────────────────
    fun playSong(song: Song) {
        serviceScope.launch {
            currentSong = song
            positionMs  = 0L
            durationMs  = song.duration * 1000L
            isPlaying   = true
            isPaused    = false
            updateNotification(song.title)
            overlayManager.updateSong(song, 0, song.duration * 1000L)
            overlayManager.setPlayingState(true)
            broadcastState()

            // Load lyrics for new song (non-blocking, runs in background)
            if (lyricsOverlayManager.isShowing) {
                lyricsOverlayManager.loadForSong(song)
            }

            ytDlp.extractAudioUrl(song.youtubeUrl)
                .onSuccess  { url -> audioPlayer.play(url) }
                .onFailure  { err ->
                    Log.e(TAG, "extractAudioUrl failed: $err")
                    updateNotification("Error: ${err.message}")
                    playNext()
                }
        }
    }

    fun togglePlayPause() {
        when {
            isPlaying && !isPaused -> {
                audioPlayer.pause(); isPaused = true
                overlayManager.setPlayingState(false)
                updateNotification("Paused: ${currentSong?.title}")
            }
            isPaused -> {
                audioPlayer.resume(); isPaused = false
                overlayManager.setPlayingState(true)
                updateNotification(currentSong?.title ?: "Playing")
            }
            queue.isNotEmpty() -> playNext()
        }
        broadcastState()
    }

    fun playNext() {
        audioPlayer.stop()
        isPlaying = false; isPaused = false; currentSong = null
        positionMs = 0L; durationMs = 0L

        if (queue.isEmpty()) {
            updateNotification("Queue empty")
            broadcastState(); syncQueueOverlay()
            return
        }
        val next = if (shuffleMode) queue.removeAt(queue.indices.random())
                   else queue.removeFirst()
        playSong(next)
        syncQueueOverlay()
    }

    fun stopPlayer() {
        audioPlayer.stop()
        currentSong = null; isPlaying = false; isPaused = false
        positionMs = 0L; durationMs = 0L
        overlayManager.setPlayingState(false)
        overlayManager.updateSong(null, 0, 0)
        updateNotification("Stopped")
        broadcastState()
    }

    private fun onSongComplete() {
        Log.d(TAG, "Song complete → playNext")
        playNext()
    }

    // ── TikTok chat handler ───────────────────────────────────────────
    private fun handleTikTokChat(chat: TikTokChat) {
        Log.d(TAG, "[TikTok] @${chat.uniqueId}: ${chat.comment}")
        broadcastChat(chat)

        if (chatOverlayManager.isShowing) {
            chatOverlayManager.addChat(chat.nickname, chat.comment, emotes = chat.emotes)
        }

        if (System.currentTimeMillis() - tiktokConnectTime < 5000) {
            Log.d(TAG, "[TikTok] Ignoring command from @${chat.uniqueId} (initial 5s delay)")
            return
        }

        speak(chat.comment)

        val isAdmin = isAdmin(chat.uniqueId)

        when (chat.commandType) {
            TikTokChat.CommandType.REQUEST -> {
                val arg = chat.commandArg ?: return

                // NEW: Limit song requests (Customizable per user)
                if (!isAdmin) {
                    val userReq = "@${chat.uniqueId}"
                    val count   = queue.count { it.requestedBy == userReq }
                    if (count >= requestLimit) {
                        Log.d(TAG, "[TikTok] Request denied: $userReq already has $count songs in queue (limit $requestLimit)")
                        return
                    }
                }

                val url = if (arg.contains("youtube.com") || arg.contains("youtu.be")) arg
                          else "ytsearch1:$arg"
                addToQueue(url, requestedBy = "@${chat.uniqueId}")
            }
            TikTokChat.CommandType.SKIP        -> if (isAdmin) playNext()
            TikTokChat.CommandType.STOP        -> if (isAdmin) stopPlayer()
            TikTokChat.CommandType.QUEUE       -> {
                if (isAdmin) broadcastState()
                
                // Show queue overlay if requested via command (even if autohide is on)
                serviceScope.launch(Dispatchers.Main) {
                    if (!queueOverlayManager.isShowing) {
                        val x = prefs.getInt("queue_x", 16)
                        val y = prefs.getInt("queue_y", 420)
                        val scale = prefs.getFloat("queue_scale", 1f)
                        val width = prefs.getInt("queue_width", 300)
                        queueOverlayManager.show(queue.toList())
                        queueOverlayManager.applyConfig(x, y, scale, width)
                    } else {
                        queueOverlayManager.updateQueue(queue.toList())
                        queueOverlayManager.resetHideTimer()
                    }
                }
            }
            TikTokChat.CommandType.CLEAR_MUSIC -> {
                if (!isAdmin) return
                val arg = chat.commandArg?.trim() ?: return

                // Coba parse sebagai angka (posisi) dulu
                val pos = arg.toIntOrNull()
                if (pos != null) {
                    // #cm 2 → hapus posisi ke-2
                    val ok = clearMusicAt(pos)
                    Log.d(TAG, "#cm posisi $pos → removed=$ok")
                } else {
                    // #cm judul lagu → cari berdasarkan judul (partial match)
                    val removed = clearMusicByTitle(arg)
                    Log.d(TAG, "#cm judul \"$arg\" → removed=${removed ?: "not found"}")
                }
            }
            TikTokChat.CommandType.COMMAND_TOGGLE -> {
                if (!isAdmin) return
                val arg = chat.commandArg?.lowercase()?.trim()
                val newState = when (arg) {
                    "on", "enable", "1" -> true
                    "off", "disable", "0" -> false
                    else -> !commandConfig.enabled // Toggle if no specific arg
                }
                
                commandConfig = commandConfig.copy(enabled = newState)
                tiktokManager.setCommandConfig(commandConfig)
                
                // Simpan ke SharedPreferences
                prefs.edit().putBoolean("commands_enabled", newState).apply()
                
                broadcastSystemChat("Commands ${if (newState) "ENABLED" else "DISABLED"} via @${chat.uniqueId}")
                broadcastState()
            }
            TikTokChat.CommandType.NONE -> { }
        }
    }

    private fun isAdmin(uniqueId: String): Boolean {
        val cleanOwner = tiktokUsername.trim().removePrefix("@")
        if (uniqueId.equals(cleanOwner, ignoreCase = true)) return true
        if (uniqueId.equals("admin", ignoreCase = true)) return true

        val authorized = prefs.getString("authorized_users", "") ?: ""
        return authorized.split(",").any { it.trim().equals(uniqueId, ignoreCase = true) }
    }

    // ── Canvas mode ───────────────────────────────────────────────────
    /**
     * Lock both overlays at saved positions so they cannot be moved/scaled.
     * This is activated from [CanvasActivity] when user taps "Lock".
     */
    fun enableCanvasMode(px: Int, py: Int, qx: Int, qy: Int) {
        canvasModeEnabled = true
        canvasPlayerX = px; canvasPlayerY = py
        canvasQueueX  = qx; canvasQueueY  = qy

        // Persist positions
        prefs.edit()
            .putInt("canvas_px", px).putInt("canvas_py", py)
            .putInt("canvas_qx", qx).putInt("canvas_qy", qy)
            .apply()

        // Show overlays (if not already) then lock them
        if (!overlayManager.isShowing)      overlayManager.show()
        if (!queueOverlayManager.isShowing) queueOverlayManager.show(getQueue())

        overlayManager.setCanvasMode(locked = true, x = px, y = py)
        queueOverlayManager.setCanvasMode(locked = true, x = qx, y = qy)
        if (lyricsOverlayManager.isShowing)
            lyricsOverlayManager.setCanvasMode(locked = true, x = qx, y = qy + 440)
        if (chatOverlayManager.isShowing)
            chatOverlayManager.setCanvasMode(locked = true, x = qx, y = qy + 800)
        if (joinOverlayManager.isShowing) {
            val jx = prefs.getInt("join_x", 100)
            val jy = prefs.getInt("join_y", 200)
            joinOverlayManager.setCanvasMode(locked = true, x = jx, y = jy)
        }
        if (likeOverlayManager.isShowing) {
            val lx = prefs.getInt("like_x", 50)
            val ly = prefs.getInt("like_y", 100)
            likeOverlayManager.setCanvasMode(locked = true, x = lx, y = ly)
        }
        if (followOverlayManager.isShowing) {
            val fx = prefs.getInt("follow_x", 100)
            val fy = prefs.getInt("follow_y", 300)
            followOverlayManager.setCanvasMode(locked = true, x = fx, y = fy)
        }

        broadcastState()
    }

    /** Unlock overlays – they become draggable again. */
    fun disableCanvasMode() {
        canvasModeEnabled = false
        overlayManager.setCanvasMode(locked = false)
        queueOverlayManager.setCanvasMode(locked = false)
        if (lyricsOverlayManager.isShowing)
            lyricsOverlayManager.setCanvasMode(locked = false)
        if (chatOverlayManager.isShowing)
            chatOverlayManager.setCanvasMode(locked = false)
        if (joinOverlayManager.isShowing)
            joinOverlayManager.setCanvasMode(locked = false)
        if (likeOverlayManager.isShowing)
            likeOverlayManager.setCanvasMode(locked = false)
        if (followOverlayManager.isShowing)
            followOverlayManager.setCanvasMode(locked = false)
        broadcastState()
    }

    private fun loadPreferredLyricsLang(): String =
        prefs.getString("lyrics_lang", "id") ?: "id"

    fun getCanvasState(): Map<String, Int> = mapOf(
        "canvas_px" to canvasPlayerX,
        "canvas_py" to canvasPlayerY,
        "canvas_qx" to canvasQueueX,
        "canvas_qy" to canvasQueueY
    )

    // ── Overlay passthrough ───────────────────────────────────────────
    fun showOverlay() {
        val x = prefs.getInt("player_x", 16)
        val y = prefs.getInt("player_y", 100)
        val scale = prefs.getFloat("player_scale", 1f)
        overlayManager.show(x, y)
        overlayManager.applyConfig(x, y, scale)
        broadcastState()
    }
    fun hideOverlay()  { overlayManager.hide() }
    val overlayVisible get() = overlayManager.isShowing

    fun toggleQueueOverlay() {
        if (queueOverlayManager.isShowing) {
            queueOverlayManager.hide()
        } else {
            showQueueOverlay()
        }
    }

    fun showQueueOverlay() {
        val x = prefs.getInt("queue_x", 16)
        val y = prefs.getInt("queue_y", 420)
        val scale = prefs.getFloat("queue_scale", 1f)
        val width = prefs.getInt("queue_width", 300)
        queueOverlayManager.show(queue.toList())
        queueOverlayManager.applyConfig(x, y, scale, width)
        broadcastState()
    }

    val queueOverlayVisible get() = queueOverlayManager.isShowing

    fun updateQueueAutoHide(enabled: Boolean) {
        prefs.edit().putBoolean("queue_auto_hide", enabled).apply()
        val duration = prefs.getInt("queue_duration", 10)
        queueOverlayManager.setAutoHide(enabled, duration)
    }

    fun updateQueueDuration(seconds: Int) {
        prefs.edit().putInt("queue_duration", seconds).apply()
        val enabled = prefs.getBoolean("queue_auto_hide", false)
        queueOverlayManager.setAutoHide(enabled, seconds)
    }

    // ── Lyrics overlay ────────────────────────────────────────────────
    fun toggleLyricsOverlay() {
        if (lyricsOverlayManager.isShowing) {
            lyricsOverlayManager.hide()
        } else {
            lyricsOverlayManager.show()
            // If a song is already playing, immediately start fetching lyrics
            currentSong?.let { lyricsOverlayManager.loadForSong(it) }
        }
        broadcastState()
    }

    val lyricsOverlayVisible get() = lyricsOverlayManager.isShowing

    // ── Chat overlay ──────────────────────────────────────────────────
    fun toggleChatOverlay() {
        if (chatOverlayManager.isShowing) {
            chatOverlayManager.hide()
        } else {
            val x = prefs.getInt("chat_x", 16)
            val y = prefs.getInt("chat_y", 500)
            val scale = prefs.getFloat("chat_scale", 1f)
            val width = prefs.getInt("chat_width", 150)
            chatOverlayManager.show(x, y, scale, width)
        }
        broadcastState()
    }

    fun updateChatMaxLines(lines: Int) {
        prefs.edit().putInt("chat_max_lines", lines).apply()
        chatOverlayManager.setMaxLines(lines)
    }

    fun updateChatTransparency(transparent: Boolean) {
        prefs.edit().putBoolean("chat_transparent", transparent).apply()
        chatOverlayManager.setTransparent(transparent)
    }

    fun updateChatWidth(widthDp: Int) {
        prefs.edit().putInt("chat_width", widthDp).apply()
        chatOverlayManager.setOverlayWidth(widthDp)
    }

    fun updateChatDuration(seconds: Int) {
        prefs.edit().putInt("chat_duration", seconds).apply()
        chatOverlayManager.setDisplayDuration(seconds)
    }

    fun updateChatStyle(layoutId: Int, bgId: Int) {
        prefs.edit()
            .putInt("canvas_chat_layout", layoutId)
            .putInt("canvas_chat_bg", bgId)
            .apply()
        chatOverlayManager.updateStyle(layoutId, bgId)
    }

    fun updateJoinStyle(layoutId: Int) {
        prefs.edit()
            .putInt("canvas_join_layout", layoutId)
            .apply()
        joinOverlayManager.updateStyle(layoutId)
    }

    fun updateLikeStyle(layoutId: Int) {
        prefs.edit()
            .putInt("canvas_like_layout", layoutId)
            .apply()
        likeOverlayManager.updateStyle(layoutId)
    }

    fun updateFollowStyle(layoutId: Int) {
        prefs.edit()
            .putInt("canvas_follow_layout", layoutId)
            .apply()
        followOverlayManager.updateStyle(layoutId)
    }

    fun updatePlayerStyle(layoutId: Int) {
        prefs.edit()
            .putInt("canvas_player_layout", layoutId)
            .apply()
        overlayManager.updateStyle(layoutId)
    }

    fun updateLyricsStyle(layoutId: Int) {
        prefs.edit()
            .putInt("canvas_lyrics_layout", layoutId)
            .apply()
        lyricsOverlayManager.updateStyle(layoutId)
    }

    fun updateQueueStyle(containerId: Int, itemId: Int) {
        prefs.edit()
            .putInt("canvas_queue_layout", containerId)
            .putInt("canvas_queue_item_layout", itemId)
            .apply()
        queueOverlayManager.updateStyle(containerId, itemId)
    }

    private fun loadTheme(category: String): CustomTheme {
        val baseKey = "canvas_${category}_custom"
        return CustomTheme(
            bgPrimary = if (prefs.contains("${baseKey}_bg")) prefs.getInt("${baseKey}_bg", 0) else null,
            bgSecondary = if (prefs.contains("${baseKey}_bg_sec")) prefs.getInt("${baseKey}_bg_sec", 0) else null,
            textPrimary = if (prefs.contains("${baseKey}_text")) prefs.getInt("${baseKey}_text", 0) else null,
            textSecondary = if (prefs.contains("${baseKey}_text_sec")) prefs.getInt("${baseKey}_text_sec", 0) else null,
            alpha = prefs.getInt("${baseKey}_alpha", 255)
        )
    }

    fun updateCustomThemes() {
        overlayManager.applyTheme(loadTheme("player"))
        queueOverlayManager.applyTheme(loadTheme("queue"))
        lyricsOverlayManager.applyTheme(loadTheme("lyrics"))
        chatOverlayManager.applyTheme(loadTheme("chat"))
        notifOverlayManager.applyTheme(loadTheme("notif"))
        joinOverlayManager.applyTheme(loadTheme("join"))
        likeOverlayManager.applyTheme(loadTheme("like"))
        followOverlayManager.applyTheme(loadTheme("follow"))
    }

    fun updateTtsEnabled(enabled: Boolean) {
        ttsEnabled = enabled
        prefs.edit().putBoolean("chat_tts_enabled", enabled).apply()
    }

    fun updateTtsVolume(volume: Float) {
        Log.d(TAG, "updateTtsVolume: $volume")
        ttsVolume = volume
        prefs.edit().putFloat("chat_tts_volume", volume).apply()
    }

    fun updateTtsMaxLength(length: Int) {
        ttsMaxLength = length
        prefs.edit().putInt("chat_tts_max_length", length).apply()
    }

    fun updateMusicVolume(volume: Float) {
        musicVolume = volume
        if (!isTtsSpeaking) {
            audioPlayer.setVolume(volume)
        } else {
            audioPlayer.setVolume(volume * 0.15f)
        }
        prefs.edit().putFloat("music_volume", volume).apply()
        broadcastState()
    }

    fun updateNotifVolume(volume: Float) {
        notifVolume = volume
        prefs.edit().putFloat("notif_volume", volume).apply()
        notifOverlayManager.setVolume(volume)
        broadcastState()
    }

    fun updateUseTiktokGiftIcon(enabled: Boolean) {
        Log.d(TAG, "Setting UseTiktokGiftIcon changed to: $enabled")
        useTiktokGiftIcon = enabled
        prefs.edit().putBoolean("use_tiktok_gift_icon", enabled).apply()
        notifOverlayManager.setUseTiktokGiftIcon(enabled)
        broadcastState()
    }

    fun updateNotifConfig(shareImg: String?, giftImg: String?, shareAud: String?, giftAud: String?, duration: Int, refreshType: String? = null) {
        notifOverlayManager.setConfig(shareImg, giftImg, shareAud, giftAud, duration)
        if (notifOverlayManager.isShowing || refreshType != null) {
            showNotifDummy(refreshType ?: "gift")
        }
    }

    fun toggleNotifOverlay() {
        notifEnabled = !notifEnabled
        prefs.edit().putBoolean("notif_enabled", notifEnabled).apply()
        if (notifEnabled) {
            showNotifDummy()
        } else {
            notifOverlayManager.hide()
        }
        broadcastState()
    }

    fun showChatDummy() {
        chatOverlayManager.addDummyChat()
    }

    fun hideChatDummy() {
        chatOverlayManager.clearDummyChat()
    }

    fun showNotifDummy(type: String = "gift", persistent: Boolean = false) {
        val x = prefs.getInt("notif_x", 100)
        val y = prefs.getInt("notif_y", 100)
        val scale = prefs.getFloat("notif_scale", 1.0f)
        
        val action = if (type == "gift") "mengirim Gift (Preview)" else "membagikan live (Preview)"
        notifOverlayManager.showNotification("Preview User", action, type, isDummy = true, persistent = persistent)
        notifOverlayManager.applyConfig(x, y, scale)
    }

    fun resetNotifTimer() {
        notifOverlayManager.resetHideTimer()
    }

    fun hideNotif() {
        notifOverlayManager.hide()
    }

    fun updateNotifEnabled(enabled: Boolean) {
        notifEnabled = enabled
        prefs.edit().putBoolean("notif_enabled", enabled).apply()
        broadcastState()
    }

    fun hideJoinOverlay() {
        joinOverlayManager.hide()
    }

    fun hideLikeOverlay() {
        likeOverlayManager.hide()
    }

    fun hideFollowOverlay() {
        followOverlayManager.hide()
    }

    fun updateVisualPunchEnabled(key: String, enabled: Boolean) {
        prefs.edit().putBoolean("${key}_visual_punch", enabled).apply()
        when (key) {
            "player" -> overlayManager.setVisualPunchEnabled(enabled)
            "queue"  -> queueOverlayManager.setVisualPunchEnabled(enabled)
            "lyrics" -> lyricsOverlayManager.setVisualPunchEnabled(enabled)
            "chat"   -> chatOverlayManager.setVisualPunchEnabled(enabled)
            "notif"  -> notifOverlayManager.setVisualPunchEnabled(enabled)
            "follow" -> followOverlayManager.setVisualPunchEnabled(enabled)
        }
    }

    val chatOverlayVisible get() = chatOverlayManager.isShowing
    val notifOverlayVisible get() = notifOverlayManager.isShowing
    val joinOverlayVisible get() = joinOverlayManager.isShowing
    val likeOverlayVisible get() = likeOverlayManager.isShowing
    val followOverlayVisible get() = followOverlayManager.isShowing

    fun toggleJoinOverlay() {
        joinEnabled = !joinEnabled
        prefs.edit().putBoolean("join_enabled", joinEnabled).apply()
        if (joinEnabled) {
            showJoinDummy()
        } else {
            joinOverlayManager.hide()
        }
        broadcastState()
    }

    fun toggleLikeOverlay() {
        likeEnabled = !likeEnabled
        prefs.edit().putBoolean("like_enabled", likeEnabled).apply()
        if (likeEnabled) {
            showLikeDummy()
        } else {
            likeOverlayManager.hide()
        }
        broadcastState()
    }

    fun toggleFollowOverlay() {
        followEnabled = !followEnabled
        prefs.edit().putBoolean("follow_enabled", followEnabled).apply()
        if (followEnabled) {
            showFollowDummy()
        } else {
            followOverlayManager.hide()
        }
        broadcastState()
    }

    fun updateJoinEnabled(enabled: Boolean) {
        joinEnabled = enabled
        prefs.edit().putBoolean("join_enabled", enabled).apply()
        broadcastState()
    }

    fun updateLikeEnabled(enabled: Boolean) {
        likeEnabled = enabled
        prefs.edit().putBoolean("like_enabled", enabled).apply()
        broadcastState()
    }

    fun updateFollowEnabled(enabled: Boolean) {
        followEnabled = enabled
        prefs.edit().putBoolean("follow_enabled", enabled).apply()
        broadcastState()
    }

    fun updateLikeAnimationEnabled(enabled: Boolean) {
        likeOverlayManager.setAnimationEnabled(enabled)
    }

    fun updateJoinDuration(seconds: Int) {
        prefs.edit().putInt("join_duration", seconds).apply()
        joinOverlayManager.setDuration(seconds)
    }

    fun updateLikeDuration(seconds: Int) {
        prefs.edit().putInt("like_duration", seconds).apply()
        likeOverlayManager.setDuration(seconds)
    }

    fun updateFollowDuration(seconds: Int) {
        prefs.edit().putInt("follow_duration", seconds).apply()
        followOverlayManager.setDuration(seconds)
    }

    fun updateStickerAnimationEnabled(enabled: Boolean) {
        chatOverlayManager.setStickerAnimationEnabled(enabled)
    }



    fun showJoinDummy(persistent: Boolean = false) {
        val x = prefs.getInt("join_x", 100)
        val y = prefs.getInt("join_y", 200)
        val scale = prefs.getFloat("join_scale", 1f)
        joinOverlayManager.showJoin("Preview", null, isDummy = true, persistent = persistent)
        joinOverlayManager.applyConfig(x, y, scale)
    }

    fun showLikeDummy(persistent: Boolean = false) {
        val x = prefs.getInt("like_x", 50)
        val y = prefs.getInt("like_y", 100)
        val scale = prefs.getFloat("like_scale", 1f)
        likeOverlayManager.showLike("Preview", 1, null, isDummy = true, persistent = persistent)
        likeOverlayManager.applyConfig(x, y, scale)
    }

    fun showFollowDummy(persistent: Boolean = false) {
        val x = prefs.getInt("follow_x", 100)
        val y = prefs.getInt("follow_y", 300)
        val scale = prefs.getFloat("follow_scale", 1f)
        followOverlayManager.showFollow("Preview", null, isDummy = true, persistent = persistent)
        followOverlayManager.applyConfig(x, y, scale)
    }

    fun applyOverlayConfig(key: String, x: Int, y: Int, scale: Float, width: Int = 0, height: Int = 0) {
        // If x or y is -1, it means "keep current screen position"
        var finalX = x
        var finalY = y

        fun getCurrentPos(manager: Any?): Pair<Int, Int>? {
            if (manager == null) return null
            return try {
                val lpField = manager.javaClass.getDeclaredField("layoutParams")
                lpField.isAccessible = true
                val lp = lpField.get(manager) as? WindowManager.LayoutParams
                if (lp != null) Pair(lp.x, lp.y) else null
            } catch (e: Exception) { null }
        }

        if (finalX == -1 || finalY == -1) {
            val current = when(key) {
                "player" -> getCurrentPos(overlayManager)
                "queue"  -> getCurrentPos(queueOverlayManager)
                "lyrics" -> getCurrentPos(lyricsOverlayManager)
                "chat"   -> getCurrentPos(chatOverlayManager)
                "notif"  -> getCurrentPos(notifOverlayManager)
                "join"   -> getCurrentPos(joinOverlayManager)
                "like"   -> getCurrentPos(likeOverlayManager)
                "follow" -> getCurrentPos(followOverlayManager)
                else -> null
            }
            if (current != null) {
                if (finalX == -1) finalX = current.first
                if (finalY == -1) finalY = current.second
            } else {
                // Overlay not showing, fallback to existing saved pref
                if (finalX == -1) finalX = prefs.getInt("${key}_x", 100)
                if (finalY == -1) finalY = prefs.getInt("${key}_y", 100)
            }
        }

        // Save to prefs for persistence
        prefs.edit()
            .putInt("${key}_x", finalX)
            .putInt("${key}_y", finalY)
            .putFloat("${key}_scale", scale)
            .putInt("${key}_width", width)
            .putInt("${key}_height", height)
            .apply()

        // Apply immediately if overlay is showing
        when (key) {
            "player" -> if (overlayManager.isShowing) overlayManager.applyConfig(finalX, finalY, scale, width, height)
            "queue"  -> if (queueOverlayManager.isShowing) queueOverlayManager.applyConfig(finalX, finalY, scale, width, height)
            "lyrics" -> if (lyricsOverlayManager.isShowing) lyricsOverlayManager.applyConfig(finalX, finalY, scale, width, height)
            "chat"   -> if (chatOverlayManager.isShowing) chatOverlayManager.applyConfig(finalX, finalY, scale, width)
            "notif"  -> if (notifOverlayManager.isShowing) notifOverlayManager.applyConfig(finalX, finalY, scale, width, height)
            "join"   -> if (joinOverlayManager.isShowing) joinOverlayManager.applyConfig(finalX, finalY, scale)
            "like"   -> if (likeOverlayManager.isShowing) likeOverlayManager.applyConfig(finalX, finalY, scale)
            "follow" -> if (followOverlayManager.isShowing) followOverlayManager.applyConfig(finalX, finalY, scale)
        }
    }

    fun getOverlayPosition(key: String): Pair<Int, Int> {
        fun getPos(manager: Any?): Pair<Int, Int> {
            if (manager == null) return Pair(0, 0)
            return try {
                val lpField = manager.javaClass.getDeclaredField("layoutParams")
                lpField.isAccessible = true
                val lp = lpField.get(manager) as? WindowManager.LayoutParams
                if (lp != null) Pair(lp.x, lp.y) else Pair(0, 0)
            } catch (e: Exception) { 
                // Fallback to saved prefs if field access fails or manager is null
                Pair(prefs.getInt("${key}_x", 0), prefs.getInt("${key}_y", 0))
            }
        }
        return when(key) {
            "player" -> getPos(overlayManager)
            "queue"  -> getPos(queueOverlayManager)
            "lyrics" -> getPos(lyricsOverlayManager)
            "chat"   -> getPos(chatOverlayManager)
            "notif"  -> getPos(notifOverlayManager)
            "join"   -> getPos(joinOverlayManager)
            "like"   -> getPos(likeOverlayManager)
            "follow" -> getPos(followOverlayManager)
            else -> Pair(0, 0)
        }
    }

    // ── State map (for MainActivity syncUi) ───────────────────────────
    fun getStateMap(): Map<String, Any?> = mapOf(
        "current_song"      to currentSong?.let { gson.toJson(it) },
        "is_playing"        to isPlaying,
        "is_paused"         to isPaused,
        "queue_count"       to queue.size,
        "position_ms"       to positionMs,
        "duration_ms"       to durationMs,
        "shuffle_mode"      to shuffleMode,
        "tiktok_connected"  to tiktokConnected,
        "tiktok_connecting" to tiktokConnecting,
        "request_limit"     to requestLimit,
        "ytdlp_installed"   to ytDlp.isInstalled,
        "canvas_mode"       to canvasModeEnabled,
        "lyrics_visible"    to lyricsOverlayManager.isShowing,
        "queue_visible"     to queueOverlayManager.isShowing,
        "chat_visible"      to chatOverlayManager.isShowing,
        "notif_visible"     to notifOverlayManager.isShowing,
        "notif_enabled"     to notifEnabled,
        "join_visible"      to joinOverlayManager.isShowing,
        "like_visible"      to likeOverlayManager.isShowing,
        "follow_visible"    to followOverlayManager.isShowing,
        "join_enabled"      to joinEnabled,
        "like_enabled"      to likeEnabled,
        "follow_enabled"    to followEnabled,
        "join_duration"     to prefs.getInt("join_duration", 4),
        "like_duration"     to prefs.getInt("like_duration", 4),
        "follow_duration"   to prefs.getInt("follow_duration", 4),
        "chat_max_lines"    to prefs.getInt("chat_max_lines", 5),
        "chat_width"        to prefs.getInt("chat_width", 300),
        "chat_duration"     to prefs.getInt("chat_duration", 6),
        "chat_tts_enabled"  to ttsEnabled,
        "chat_tts_volume"   to ttsVolume,
        "chat_tts_max_length" to ttsMaxLength,
        "commands_enabled"  to commandConfig.enabled,
        "music_volume"      to musicVolume,
        "notif_volume"      to notifVolume,
        "use_tiktok_gift_icon" to useTiktokGiftIcon
    )

    // ── Notification ──────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL_ID, "YT Player",
            NotificationManager.IMPORTANCE_LOW)
            .apply { description = "YouTube TikTok Player" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val playPausePi = PendingIntent.getService(
            this, 1,
            Intent(this, PlayerForegroundService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE)
        val skipPi = PendingIntent.getService(
            this, 2,
            Intent(this, PlayerForegroundService::class.java).setAction(ACTION_SKIP),
            PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Kanae Player")
            .setContentText(text)
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_play, "Play/Pause", playPausePi)
            .addAction(android.R.drawable.ic_media_next, "Skip", skipPi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    // ── Broadcasts ────────────────────────────────────────────────────
    private fun broadcastState() {
        val intent = Intent(BROADCAST_STATE)
        getStateMap().forEach { (k, v) ->
            when (v) {
                is String  -> intent.putExtra(k, v)
                is Boolean -> intent.putExtra(k, v)
                is Int     -> intent.putExtra(k, v)
                is Long    -> intent.putExtra(k, v)
            }
        }
        sendBroadcast(intent)
    }

    private fun broadcastChat(chat: TikTokChat) {
        sendBroadcast(Intent(BROADCAST_CHAT).apply {
            putExtra("unique_id", chat.uniqueId)
            putExtra("nickname",  chat.nickname)
            putExtra("comment",   chat.comment)
            putExtra("cmd_type",  chat.commandType.name)
        })
    }

    private fun broadcastSystemChat(message: String) {
        sendBroadcast(Intent(BROADCAST_CHAT).apply {
            putExtra("unique_id", "system")
            putExtra("nickname",  "System")
            putExtra("comment",   message)
            putExtra("cmd_type",  "NONE")
        })
    }

    // ── Private helpers ───────────────────────────────────────────────
    private fun syncQueueOverlay() {
        if (queueOverlayManager.isShowing) queueOverlayManager.updateQueue(getQueue())
        overlayManager.updateQueueCount(queue.size)
    }

    private fun speak(text: String) {
        if (!ttsEnabled || tts == null) return
        
        try {
            if (text.length > ttsMaxLength) return

            val trimmed = text.trim()
            if (trimmed.startsWith("@")) return
            
            val isCommand = commandConfig.let { cfg ->
                cfg.requestPrefixes.any { trimmed.startsWith(it, ignoreCase = true) } ||
                cfg.skipPrefixes.any { trimmed.startsWith(it, ignoreCase = true) } ||
                cfg.stopPrefixes.any { trimmed.startsWith(it, ignoreCase = true) } ||
                cfg.queuePrefixes.any { trimmed.startsWith(it, ignoreCase = true) } ||
                cfg.clearMusicPrefixes.any { trimmed.startsWith(it, ignoreCase = true) }
            }
            if (isCommand) return

            val cleanedText = trimmed.replace("@", "")
            if (cleanedText.isBlank()) return

            val params = Bundle()
            val utteranceId = "chat_${System.currentTimeMillis()}"
            
            // Setup listener
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    // Jika fallback ke direct speak, kita tetap butuh ducking
                    if (id == utteranceId && (!ttsFile.exists() || ttsFile.length() == 0L)) {
                        serviceScope.launch(Dispatchers.Main) { applyDucking(true) }
                    }
                }
                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        serviceScope.launch(Dispatchers.Main) {
                            if (ttsFile.exists() && ttsFile.length() > 0) {
                                playTtsFile()
                            } else {
                                // Jika file tidak ada, berarti sudah dibaca via direct speak
                                applyDucking(false)
                            }
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { 
                    serviceScope.launch(Dispatchers.Main) { applyDucking(false) }
                }
            })

            // Coba buat file audio
            val result = tts?.synthesizeToFile(cleanedText, params, ttsFile, utteranceId)
            
            // Fallback: Jika synthesizeToFile gagal, langsung bersuara
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "synthesizeToFile failed, falling back to direct speak")
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsVolume.coerceIn(0f, 1f))
                tts?.speak(cleanedText, TextToSpeech.QUEUE_ADD, params, utteranceId)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak error: ${e.message}")
            applyDucking(false)
        }
    }

    private fun applyDucking(enabled: Boolean) {
        isTtsSpeaking = enabled
        val volume = if (enabled) {
            val duckFactor = when {
                ttsVolume >= 1.5f -> 0.85f
                ttsVolume >= 1.0f -> 0.90f
                else -> 0.95f
            }
            musicVolume * duckFactor
        } else {
            musicVolume
        }
        audioPlayer.setVolume(volume)
    }

    private fun playTtsFile() {
        try {
            if (!ttsFile.exists() || ttsFile.length() == 0L) {
                applyDucking(false)
                return
            }

            ttsMediaPlayer?.stop()
            ttsMediaPlayer?.release()

            applyDucking(true)

            ttsMediaPlayer = MediaPlayer().apply {
                setDataSource(ttsFile.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                
                setVolume(1.0f, 1.0f)
                
                setOnCompletionListener {
                    applyDucking(false)
                    cleanupPlayer()
                }
                setOnErrorListener { _, _, _ ->
                    applyDucking(false)
                    cleanupPlayer()
                    true
                }
                prepare()
                
                if (ttsVolume > 1.0f) {
                    try {
                        ttsLoudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                            val targetGain = ((ttsVolume - 1.0f) * 3000).toInt().coerceIn(0, 4000)
                            setTargetGain(targetGain)
                            enabled = true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "LoudnessEnhancer error: ${e.message}")
                    }
                }
                
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing TTS file: ${e.message}")
            applyDucking(false)
            cleanupPlayer()
        }
    }

    private fun cleanupPlayer() {
        try {
            ttsLoudnessEnhancer?.release()
            ttsLoudnessEnhancer = null
            ttsMediaPlayer?.release()
            ttsMediaPlayer = null
            if (ttsFile.exists()) ttsFile.delete()
        } catch (e: Exception) { /* ignore */ }
    }

    private fun buildTikTokManager(): TikTokLiveManager =
        TikTokLiveManager(apiKey, tiktokUsername, serviceScope).also { t ->
            t.setCommandConfig(commandConfig)
            t.onChat         = ::handleTikTokChat
            t.onLike = { nick, uid, count, profile ->
                if (likeEnabled && System.currentTimeMillis() - tiktokConnectTime >= 5000) {
                    likeOverlayManager.showLike(nick, count, profile)
                }
            }
            t.onJoin = { nick, uid, profile ->
                if (joinEnabled && System.currentTimeMillis() - tiktokConnectTime >= 5000) {
                    joinOverlayManager.showJoin(nick, profile)
                }
            }
            t.onFollow = { nick, uid, profile ->
                if (followEnabled && System.currentTimeMillis() - tiktokConnectTime >= 5000) {
                    followOverlayManager.showFollow(nick, profile)
                }
            }
            t.onGift = { uid, nick, gift, count, iconUrl ->
                Log.d(TAG, "[TikTok] Gift from @$uid ($nick): $gift x$count | Icon: $iconUrl")
                broadcastSystemChat("$nick mengirim $gift x$count")
                if (notifEnabled && System.currentTimeMillis() - tiktokConnectTime >= 5000) {
                    notifOverlayManager.showNotification(nick, "mengirim $gift x$count", "gift", giftIconUrl = iconUrl)
                }
            }
            t.onShare = { _, nick ->
                broadcastSystemChat("$nick membagikan live")
                if (notifEnabled && System.currentTimeMillis() - tiktokConnectTime >= 5000) {
                    notifOverlayManager.showNotification(nick, "membagikan live", "share")
                }
            }
            t.onConnected    = {
                tiktokConnected = true
                tiktokConnecting = false
                tiktokConnectTime = System.currentTimeMillis()
                overlayManager.setLiveStatus(true)
                broadcastSystemChat("Connected to TikTok Live @$tiktokUsername")
                broadcastState()
            }
            t.onDisconnected = {
                tiktokConnected = false
                tiktokConnecting = false
                overlayManager.setLiveStatus(false)
                broadcastSystemChat("Disconnected from TikTok Live")
                broadcastState()
            }
            t.onConnecting = {
                tiktokConnecting = true
                broadcastState()
            }
            t.onError = { 
                Log.w(TAG, "TikTok error: $it")
                // Only set tiktokConnecting to false if it's NOT a retry message
                if (!it.contains("Retrying", ignoreCase = true)) {
                    tiktokConnecting = false
                }
                broadcastSystemChat("Error: $it")
                broadcastState()
            }
        }
}
