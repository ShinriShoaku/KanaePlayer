package ame.project.kanae.service

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import ame.project.kanae.MainActivity
import ame.project.kanae.model.Song
import ame.project.kanae.model.TikTokChat
import ame.project.kanae.overlay.ChatOverlayManager
import ame.project.kanae.overlay.LyricsOverlayManager
import ame.project.kanae.overlay.OverlayManager
import ame.project.kanae.overlay.QueueOverlayManager
import ame.project.kanae.player.AudioPlayer
import ame.project.kanae.player.YtDlpHelper
import ame.project.kanae.tiktok.TikTokLiveManager
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

    private val queue       = ArrayDeque<Song>()
    private var currentSong: Song?  = null
    private var isPlaying   = false
    private var isPaused    = false
    private var positionMs  = 0L
    private var durationMs  = 0L
    private var shuffleMode = false
    private var tiktokConnected = false
    private var requestLimit  = 3

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
        )

        queueOverlayManager = QueueOverlayManager(
            context  = this,
            onPlay   = { pos -> queue.elementAtOrNull(pos)?.let { playSong(it) } },
            onRemove = { pos -> removeFromQueue(pos) },
            onClose  = { broadcastState() }
        )

        lyricsOverlayManager = LyricsOverlayManager(
            context       = this,
            scope         = serviceScope,
            preferredLang = loadPreferredLyricsLang(),
            onClose       = { broadcastState() }
        )

        chatOverlayManager = ChatOverlayManager(
            context = this,
            scope   = serviceScope,
            maxLines = prefs.getInt("chat_max_lines", 5),
            onClose = { broadcastState() }
        ).apply {
            setTransparent(prefs.getBoolean("chat_transparent", true))
            setOverlayWidth(prefs.getInt("chat_width", 300))
            setDisplayDuration(prefs.getInt("chat_duration", 6))
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
            ACTION_SHOW_OVERLAY  -> overlayManager.show()
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
        tiktokManager.disconnect()
        audioPlayer.release()
        overlayManager.hide()
        queueOverlayManager.hide()
        lyricsOverlayManager.hide()
        chatOverlayManager.hide()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Settings ──────────────────────────────────────────────────────
    private fun loadPrefs() {
        apiKey         = prefs.getString("euler_api_key", "")     ?: ""
        tiktokUsername = prefs.getString("tiktok_username", "")   ?: ""
        shuffleMode    = prefs.getBoolean("shuffle_mode", false)
        requestLimit   = prefs.getInt("request_limit", 3)
        canvasPlayerX  = prefs.getInt("canvas_px", 16)
        canvasPlayerY  = prefs.getInt("canvas_py", 100)
        canvasQueueX   = prefs.getInt("canvas_qx", 16)
        canvasQueueY   = prefs.getInt("canvas_qy", 440)

        commandConfig = TikTokLiveManager.CommandConfig(
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
                .putString("cmd_request",     cfg.requestPrefixes.joinToString(","))
                .putString("cmd_skip",         cfg.skipPrefixes.joinToString(","))
                .putString("cmd_stop",         cfg.stopPrefixes.joinToString(","))
                .putString("cmd_queue",        cfg.queuePrefixes.joinToString(","))
                .putString("cmd_clear_music",  cfg.clearMusicPrefixes.joinToString(","))
                .apply()
        }

        tiktokManager.disconnect()
        tiktokManager = buildTikTokManager()
        if (this.tiktokUsername.isNotBlank() && this.apiKey.isNotBlank()) {
            tiktokManager.connect()
        }
    }

    // ── Queue operations ──────────────────────────────────────────────
    fun addToQueue(youtubeUrl: String, requestedBy: String? = null, fromChat: Boolean = false): Boolean {
        if (queue.size >= MAX_QUEUE) return false
        serviceScope.launch {
            val meta = ytDlp.fetchMetadata(youtubeUrl)
            val song = Song(
                title       = meta?.title    ?: youtubeUrl,
                youtubeUrl  = meta?.videoUrl ?: youtubeUrl,
                thumbnail   = meta?.thumbnail,
                duration    = meta?.duration ?: 0,
                channel     = meta?.channel,
                requestedBy = requestedBy
            )
            queue.addLast(song)
            if (!isPlaying && currentSong == null) {
                playNext()
            } else {
                broadcastState()
                if (fromChat) {
                    // Auto-show queue overlay when song requested via TikTok chat
                    queueOverlayManager.autoShowIfNeeded(getQueue())
                } else if (queueOverlayManager.isShowing) {
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
            chatOverlayManager.addChat(chat.nickname, chat.comment)
        }

        when (chat.commandType) {
            TikTokChat.CommandType.REQUEST -> {
                val arg = chat.commandArg ?: return

                // NEW: Limit song requests (Customizable per user)
                val cleanOwner = tiktokUsername.trim().removePrefix("@")
                val isOwner    = chat.uniqueId.equals(cleanOwner, ignoreCase = true)
                val isAdmin    = isOwner || chat.uniqueId.equals("admin", ignoreCase = true)

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
                addToQueue(url, requestedBy = "@${chat.uniqueId}", fromChat = true)
            }
            TikTokChat.CommandType.SKIP        -> playNext()
            TikTokChat.CommandType.STOP        -> stopPlayer()
            TikTokChat.CommandType.QUEUE       -> broadcastState()
            TikTokChat.CommandType.CLEAR_MUSIC -> {
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
            TikTokChat.CommandType.NONE -> { }
        }
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
    fun showOverlay()  { overlayManager.show() }
    fun hideOverlay()  { overlayManager.hide() }
    val overlayVisible get() = overlayManager.isShowing

    fun toggleQueueOverlay() {
        if (queueOverlayManager.isShowing) queueOverlayManager.hide()
        else queueOverlayManager.show(getQueue())
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
            chatOverlayManager.show()
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

    val chatOverlayVisible get() = chatOverlayManager.isShowing

    fun applyOverlayConfig(key: String, x: Int, y: Int, scale: Float) {
        // Save to prefs for persistence
        prefs.edit()
            .putInt("${key}_x", x)
            .putInt("${key}_y", y)
            .putFloat("${key}_scale", scale)
            .apply()

        // Apply immediately if overlay is showing
        when (key) {
            "player" -> if (overlayManager.isShowing) overlayManager.applyConfig(x, y, scale)
            "queue"  -> if (queueOverlayManager.isShowing) queueOverlayManager.applyConfig(x, y, scale)
            "lyrics" -> if (lyricsOverlayManager.isShowing) lyricsOverlayManager.applyConfig(x, y, scale)
            "chat"   -> if (chatOverlayManager.isShowing) chatOverlayManager.applyConfig(x, y, scale)
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
        "request_limit"     to requestLimit,
        "ytdlp_installed"   to ytDlp.isInstalled,
        "canvas_mode"       to canvasModeEnabled,
        "lyrics_visible"    to lyricsOverlayManager.isShowing,
        "chat_visible"      to chatOverlayManager.isShowing,
        "chat_max_lines"    to prefs.getInt("chat_max_lines", 5),
        "chat_width"        to prefs.getInt("chat_width", 300),
        "chat_duration"     to prefs.getInt("chat_duration", 6)
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

    // ── Private helpers ───────────────────────────────────────────────
    private fun syncQueueOverlay() {
        if (queueOverlayManager.isShowing) queueOverlayManager.updateQueue(getQueue())
        overlayManager.updateQueueCount(queue.size)
    }

    private fun buildTikTokManager(): TikTokLiveManager =
        TikTokLiveManager(apiKey, tiktokUsername, serviceScope).also { t ->
            t.setCommandConfig(commandConfig)
            t.onChat         = ::handleTikTokChat
            t.onLike = { user, nick, count ->
                if (chatOverlayManager.isShowing) {
                    chatOverlayManager.addChat(nick, "Liked the live! x$count", 0xFFFF4444.toInt())
                }
            }
            t.onGift = { user, nick, gift, count ->
                if (chatOverlayManager.isShowing) {
                    chatOverlayManager.addChat(nick, "Sent $gift x$count", 0xFFFF00FF.toInt())
                }
            }
            t.onConnected    = {
                tiktokConnected = true
                overlayManager.setLiveStatus(true)
                broadcastState()
            }
            t.onDisconnected = {
                tiktokConnected = false
                overlayManager.setLiveStatus(false)
                broadcastState()
            }
            t.onError = { Log.w(TAG, "TikTok error: $it") }
        }
}
