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
import ame.project.kanae.player.AudioPlayer
import ame.project.kanae.player.YtDlpHelper
import ame.project.kanae.tiktok.TikTokLiveManager
import kotlinx.coroutines.*

/**
 * PlayerForegroundService
 *
 * Runs as a sticky foreground service so Android never kills it.
 * Owns:
 *   • AudioPlayer  – ExoPlayer wrapper
 *   • YtDlpHelper  – yt-dlp binary runner for URL extraction
 *   • TikTokLiveManager – EulerStream WebSocket/HTTP
 *   • OverlayManager – floating draggable window
 *   • Queue          – in-memory list + SharedPreferences persistence
 *
 * Clients (MainActivity) bind via ServiceConnection and call the
 * public methods on the Binder returned by onBind().
 */
class PlayerForegroundService : Service() {

    companion object {
        private const val TAG = "PlayerService"
        private const val NOTIF_CHANNEL_ID = "yt_player_channel"
        private const val NOTIF_ID = 1001
        private const val MAX_QUEUE = 50
        private const val PREFS_NAME = "ytplayer_prefs"

        // Intent actions
        const val ACTION_PLAY_PAUSE = "ame.project.ytplayer.PLAY_PAUSE"
        const val ACTION_SKIP       = "ame.project.ytplayer.SKIP"
        const val ACTION_STOP       = "ame.project.ytplayer.STOP"
        const val ACTION_SHOW_OVERLAY = "ame.project.ytplayer.SHOW_OVERLAY"

        // Broadcast
        const val BROADCAST_STATE = "ame.project.ytplayer.STATE_UPDATE"
        const val BROADCAST_CHAT  = "ame.project.ytplayer.CHAT_UPDATE"
    }

    // ── Coroutine scope tied to service lifetime ──────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Components ────────────────────────────────────────────────────────────
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var ytDlp: YtDlpHelper
    private lateinit var tiktokManager: TikTokLiveManager
    private lateinit var overlayManager: OverlayManager

    // ── Queue state ───────────────────────────────────────────────────────────
    private val queue = ArrayDeque<Song>()
    private var currentSong: Song? = null
    private var isPlaying = false
    private var isPaused  = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var shuffleMode = false
    private var tiktokConnected = false

    // ── Settings (loaded from SharedPreferences) ──────────────────────────────
    private var apiKey = ""
    private var tiktokUsername = ""

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val gson = Gson()

    // ── Binder ────────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): PlayerForegroundService = this@PlayerForegroundService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))

        loadPrefs()

        // Init audio player
        audioPlayer = AudioPlayer(this, serviceScope).also { p ->
            p.onComplete = ::onSongComplete
            p.onError    = { err -> Log.e(TAG, "Player error: $err"); playNext() }
            p.onProgress = { pos, dur ->
                positionMs = pos
                durationMs = dur
                overlayManager.updateSong(currentSong, pos, dur)
            }
            p.init()
        }

        // Init NewPipe Extractor (pengganti yt-dlp)
        YtDlpHelper.init()
        ytDlp = YtDlpHelper(this)

        // Init overlay
        overlayManager = OverlayManager(
            context   = this,
            onPlayPause = ::togglePlayPause,
            onSkip      = ::playNext,
            onClose     = { /* overlay closed by user, keep service running */ }
        )

        // Init TikTok manager
        tiktokManager = TikTokLiveManager(apiKey, tiktokUsername, serviceScope).also { t ->
            t.onChat         = ::handleTikTokChat
            t.onConnected    = { tiktokConnected = true; overlayManager.setLiveStatus(true);  broadcastState() }
            t.onDisconnected = { tiktokConnected = false; overlayManager.setLiveStatus(false); broadcastState() }
            t.onError        = { Log.w(TAG, "TikTok error: $it") }
        }

        // NewPipe Extractor sudah di-init di YtDlpHelper.init() — tidak perlu download binary

        // Connect TikTok Live
        if (tiktokUsername.isNotBlank() && apiKey.isNotBlank()) {
            tiktokManager.connect()
        }

        Log.d(TAG, "Service ready. ytdlp=${ytDlp.isInstalled}, nativeDir=${applicationInfo.nativeLibraryDir}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE   -> togglePlayPause()
            ACTION_SKIP         -> playNext()
            ACTION_STOP         -> stopPlayer()
            ACTION_SHOW_OVERLAY -> overlayManager.show()
        }
        return START_STICKY  // re-start if killed by system
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        tiktokManager.disconnect()
        audioPlayer.release()
        overlayManager.hide()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Preference helpers ────────────────────────────────────────────────────

    private fun loadPrefs() {
        apiKey          = prefs.getString("euler_api_key", "") ?: ""
        tiktokUsername  = prefs.getString("tiktok_username", "") ?: ""
        shuffleMode     = prefs.getBoolean("shuffle_mode", false)
    }

    fun saveSettings(apiKey: String, username: String) {
        this.apiKey = apiKey
        this.tiktokUsername = username.removePrefix("@")
        prefs.edit()
            .putString("euler_api_key", this.apiKey)
            .putString("tiktok_username", this.tiktokUsername)
            .apply()
        // Reconnect with new creds
        tiktokManager.disconnect()
        tiktokManager = TikTokLiveManager(this.apiKey, this.tiktokUsername, serviceScope).also { t ->
            t.onChat         = ::handleTikTokChat
            t.onConnected    = { tiktokConnected = true; overlayManager.setLiveStatus(true); broadcastState() }
            t.onDisconnected = { tiktokConnected = false; overlayManager.setLiveStatus(false); broadcastState() }
        }
        if (this.tiktokUsername.isNotBlank() && this.apiKey.isNotBlank()) tiktokManager.connect()
    }

    // ── Queue API (called from Activity or TikTok commands) ──────────────────

    fun addToQueue(youtubeUrl: String, requestedBy: String? = null): Boolean {
        if (queue.size >= MAX_QUEUE) return false
        serviceScope.launch {
            val meta = ytDlp.fetchMetadata(youtubeUrl)
            val song = Song(
                title       = meta?.title ?: youtubeUrl,
                youtubeUrl  = youtubeUrl,
                thumbnail   = meta?.thumbnail,
                duration    = meta?.duration ?: 0,
                channel     = meta?.channel,
                requestedBy = requestedBy
            )
            queue.addLast(song)
            if (!isPlaying && currentSong == null) playNext()
            else broadcastState()
        }
        return true
    }

    fun removeFromQueue(index: Int) {
        if (index in queue.indices) {
            queue.removeAt(index)
            broadcastState()
        }
    }

    fun clearQueue() {
        queue.clear()
        broadcastState()
    }

    fun moveInQueue(from: Int, to: Int) {
        if (from in queue.indices && to in queue.indices) {
            val song = queue.removeAt(from)
            queue.add(to, song)
            broadcastState()
        }
    }

    fun getQueue(): List<Song> = queue.toList()

    fun toggleShuffle(): Boolean {
        shuffleMode = !shuffleMode
        prefs.edit().putBoolean("shuffle_mode", shuffleMode).apply()
        return shuffleMode
    }

    // ── Player controls ───────────────────────────────────────────────────────

    fun playSong(song: Song) {
        serviceScope.launch {
            currentSong = song
            isPlaying   = true
            isPaused    = false
            updateNotification(song.title)
            overlayManager.updateSong(song, 0, song.duration * 1000L)
            overlayManager.setPlayingState(true)
            broadcastState()

            val result = ytDlp.extractAudioUrl(song.youtubeUrl)
            result.onSuccess { url ->
                audioPlayer.play(url)
            }.onFailure { err ->
                Log.e(TAG, "extractAudioUrl failed: $err")
                updateNotification("Error: ${err.message}")
                playNext()
            }
        }
    }

    fun togglePlayPause() {
        when {
            isPlaying && !isPaused -> {
                audioPlayer.pause()
                isPaused = true
                overlayManager.setPlayingState(false)
                updateNotification("Paused: ${currentSong?.title}")
            }
            isPaused -> {
                audioPlayer.resume()
                isPaused = false
                overlayManager.setPlayingState(true)
                updateNotification(currentSong?.title ?: "Playing")
            }
            queue.isNotEmpty() -> playNext()
        }
        broadcastState()
    }

    fun playNext() {
        audioPlayer.stop()
        isPlaying = false
        isPaused  = false
        currentSong = null

        if (queue.isEmpty()) {
            updateNotification("Queue empty")
            broadcastState()
            return
        }
        val next = if (shuffleMode) queue.removeAt((queue.indices).random())
                   else queue.removeFirst()
        playSong(next)
    }

    fun stopPlayer() {
        audioPlayer.stop()
        currentSong = null
        isPlaying   = false
        isPaused    = false
        updateNotification("Stopped")
        broadcastState()
    }

    private fun onSongComplete() {
        Log.d(TAG, "Song complete, playing next")
        playNext()
    }

    // ── TikTok chat handler ───────────────────────────────────────────────────

    private fun handleTikTokChat(chat: TikTokChat) {
        Log.d(TAG, "[TikTok] @${chat.uniqueId}: ${chat.comment}")
        broadcastChat(chat)

        when (chat.commandType) {
            TikTokChat.CommandType.REQUEST -> {
                val arg = chat.commandArg ?: return
                // Accept YouTube URL or search query
                val url = if (arg.contains("youtube.com") || arg.contains("youtu.be")) arg
                          else "ytsearch1:$arg"
                addToQueue(url, requestedBy = "@${chat.uniqueId}")
            }
            TikTokChat.CommandType.SKIP  -> playNext()
            TikTokChat.CommandType.STOP  -> stopPlayer()
            TikTokChat.CommandType.QUEUE -> broadcastState()
            TikTokChat.CommandType.NONE  -> {}
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "YT Player",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "YouTube TikTok Player" }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val playPauseIntent = Intent(this, PlayerForegroundService::class.java)
            .setAction(ACTION_PLAY_PAUSE).let {
                PendingIntent.getService(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
            }
        val skipIntent = Intent(this, PlayerForegroundService::class.java)
            .setAction(ACTION_SKIP).let {
                PendingIntent.getService(this, 2, it, PendingIntent.FLAG_IMMUTABLE)
            }

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("YT TikTok Player")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_play, "Play/Pause", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Skip", skipIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    // ── State broadcast ───────────────────────────────────────────────────────

    fun getStateMap(): Map<String, Any?> = mapOf<String, Any?>(
        "current_song"     to currentSong?.let { gson.toJson(it) },
        "is_playing"       to isPlaying,
        "is_paused"        to isPaused,
        "queue_count"      to queue.size,
        "position_ms"      to positionMs,
        "duration_ms"      to durationMs,
        "shuffle_mode"     to shuffleMode,
        "tiktok_connected" to tiktokConnected,
        "ytdlp_installed"  to ytDlp.isInstalled
    )

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
        val intent = Intent(BROADCAST_CHAT).apply {
            putExtra("unique_id", chat.uniqueId)
            putExtra("nickname",  chat.nickname)
            putExtra("comment",   chat.comment)
            putExtra("cmd_type",  chat.commandType.name)
        }
        sendBroadcast(intent)
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    fun showOverlay()  { overlayManager.show() }
    fun hideOverlay()  { overlayManager.hide() }
    val overlayVisible get() = overlayManager.isShowing
}
