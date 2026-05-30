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

        const val ACTION_PLAY_PAUSE   = "ame.project.ytplayer.PLAY_PAUSE"
        const val ACTION_SKIP         = "ame.project.ytplayer.SKIP"
        const val ACTION_STOP         = "ame.project.ytplayer.STOP"
        const val ACTION_SHOW_OVERLAY = "ame.project.ytplayer.SHOW_OVERLAY"
        const val ACTION_CANVAS_MODE  = "ame.project.ytplayer.CANVAS_MODE"

        const val BROADCAST_STATE = "ame.project.ytplayer.STATE_UPDATE"
        const val BROADCAST_CHAT  = "ame.project.ytplayer.CHAT_UPDATE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var audioPlayer: AudioPlayer
    private lateinit var ytDlp: YtDlpHelper
    private lateinit var tiktokManager: TikTokLiveManager
    private lateinit var overlayManager: OverlayManager
    private lateinit var queueOverlayManager: QueueOverlayManager

    private val queue       = ArrayDeque<Song>()
    private var currentSong: Song?  = null
    private var isPlaying   = false
    private var isPaused    = false
    private var positionMs  = 0L
    private var durationMs  = 0L
    private var shuffleMode = false
    private var tiktokConnected = false

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
        startForeground(NOTIF_ID, buildNotification("Starting…"))

        loadPrefs()

        audioPlayer = AudioPlayer(this, serviceScope).also { p ->
            p.onComplete = ::onSongComplete
            p.onError    = { err -> Log.e(TAG, "Player error: $err"); playNext() }
            p.onProgress = { pos, dur ->
                positionMs = pos; durationMs = dur
                overlayManager.updateSong(currentSong, pos, dur)
            }
            p.init()
        }

        YtDlpHelper.init()
        ytDlp = YtDlpHelper(this)

        overlayManager = OverlayManager(
            context     = this,
            scope       = serviceScope,
            onPlayPause = ::togglePlayPause,
            onSkip      = ::playNext,
            onClose     = { }
        )

        queueOverlayManager = QueueOverlayManager(
            context  = this,
            onPlay   = { pos -> queue.elementAtOrNull(pos)?.let { playSong(it) } },
            onRemove = { pos -> removeFromQueue(pos) }
        )

        tiktokManager = buildTikTokManager()

        // ── BUG FIX: Do NOT auto-connect on service start.
        //    The user must click "Save & Connect" explicitly.
        //    (Previously: if (tiktokUsername.isNotBlank() && apiKey.isNotBlank()) tiktokManager.connect())
        Log.d(TAG, "Service ready. ytdlp=${ytDlp.isInstalled}. Auto-connect disabled.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE   -> togglePlayPause()
            ACTION_SKIP         -> playNext()
            ACTION_STOP         -> stopPlayer()
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_CANVAS_MODE  -> {
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
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Settings ──────────────────────────────────────────────────────
    private fun loadPrefs() {
        apiKey         = prefs.getString("euler_api_key", "")     ?: ""
        tiktokUsername = prefs.getString("tiktok_username", "")   ?: ""
        shuffleMode    = prefs.getBoolean("shuffle_mode", false)
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
        prefs.edit()
            .putString("euler_api_key", this.apiKey)
            .putString("tiktok_username", this.tiktokUsername)
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
                youtubeUrl  = youtubeUrl,
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
            isPlaying   = true
            isPaused    = false
            updateNotification(song.title)
            overlayManager.updateSong(song, 0, song.duration * 1000L)
            overlayManager.setPlayingState(true)
            broadcastState()

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

        if (queue.isEmpty()) {
            updateNotification("Queue empty")
            overlayManager.updateSong(null, 0, 0)
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

        when (chat.commandType) {
            TikTokChat.CommandType.REQUEST -> {
                val arg = chat.commandArg ?: return
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
        if (!overlayManager.isShowing)     showOverlay()
        if (!queueOverlayManager.isShowing) queueOverlayManager.show(getQueue())

        overlayManager.setCanvasMode(locked = true, x = px, y = py)
        queueOverlayManager.setCanvasMode(locked = true, x = qx, y = qy)

        broadcastState()
    }

    /** Unlock overlays – they become draggable again. */
    fun disableCanvasMode() {
        canvasModeEnabled = false
        overlayManager.setCanvasMode(locked = false)
        queueOverlayManager.setCanvasMode(locked = false)
        broadcastState()
    }

    fun getCanvasState(): Map<String, Int> = mapOf(
        "canvas_px" to canvasPlayerX,
        "canvas_py" to canvasPlayerY,
        "canvas_qx" to canvasQueueX,
        "canvas_qy" to canvasQueueY
    )

    // ── Overlay passthrough ───────────────────────────────────────────
    fun showOverlay()  {
        overlayManager.show()
        syncOverlayAll()
    }
    fun hideOverlay()  { overlayManager.hide() }
    val overlayVisible get() = overlayManager.isShowing

    private fun syncOverlayAll() {
        overlayManager.updateSong(currentSong, positionMs, durationMs)
        overlayManager.updateQueueCount(queue.size)
        overlayManager.setLiveStatus(tiktokConnected)
        overlayManager.setPlayingState(isPlaying && !isPaused)
    }

    fun toggleQueueOverlay() {
        if (queueOverlayManager.isShowing) queueOverlayManager.hide()
        else queueOverlayManager.show(getQueue())
    }

    // ── State map (for MainActivity syncUi) ───────────────────────────
    fun getStateMap(): Map<String, Any?> = mapOf(
        "current_song"     to currentSong?.let { gson.toJson(it) },
        "is_playing"       to isPlaying,
        "is_paused"        to isPaused,
        "queue_count"      to queue.size,
        "position_ms"      to positionMs,
        "duration_ms"      to durationMs,
        "shuffle_mode"     to shuffleMode,
        "tiktok_connected" to tiktokConnected,
        "ytdlp_installed"  to ytDlp.isInstalled,
        "canvas_mode"      to canvasModeEnabled
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
