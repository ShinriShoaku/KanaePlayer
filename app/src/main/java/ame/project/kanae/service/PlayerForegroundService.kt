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

package ame.project.kanae.service

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import ame.project.kanae.MainActivity
import ame.project.kanae.SettingsManager
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
import ame.project.kanae.player.FastPlaybackState
import ame.project.kanae.StyleConfigManager
import ame.project.kanae.StyleThemeConfig
import ame.project.kanae.LayoutMapper
import android.view.WindowManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.session.MediaSession
import ame.project.nlsdk.IKanaeService
import ame.project.nlsdk.IKanaeCallback
import android.content.Context
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*

class PlayerForegroundService : Service() {

    companion object {
        private const val TAG              = "PlayerService"
        private const val NOTIF_CHANNEL_ID = "yt_player_channel"
        private const val NOTIF_ID         = 1001
        private const val MAX_QUEUE        = 50

        private var mediaSession: MediaSession? = null

        const val ACTION_PLAY_PAUSE    = "ame.project.ytplayer.PLAY_PAUSE"
        const val ACTION_SKIP          = "ame.project.ytplayer.SKIP"
        const val ACTION_STOP          = "ame.project.ytplayer.STOP"
        const val ACTION_SHOW_OVERLAY  = "ame.project.ytplayer.SHOW_OVERLAY"
        const val ACTION_CANVAS_MODE   = "ame.project.ytplayer.CANVAS_MODE"
        const val ACTION_LYRICS_TOGGLE = "ame.project.ytplayer.LYRICS_TOGGLE"

        const val BROADCAST_STATE = "ame.project.ytplayer.STATE_UPDATE"
        const val BROADCAST_CHAT  = "ame.project.ytplayer.CHAT_UPDATE"
        const val BROADCAST_SERVICE_READY = "ame.project.kanae.SERVICE_READY"
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
    private var cachedSongJson: String? = null
    private var isPlaying   = false
    private var isPaused    = false
    private var positionMs  = 0L
    private var durationMs  = 0L
    private var tiktokConnected = false
    private var tiktokConnecting = false
    private var tiktokConnectTime = 0L

    private var canvasModeEnabled = false

    private var tts: TextToSpeech? = null
    private var isTtsSpeaking: Boolean = false
    private var ttsMediaPlayer: MediaPlayer? = null
    private var ttsLoudnessEnhancer: LoudnessEnhancer? = null
    private val ttsFile by lazy { File(cacheDir, "tts_cache.wav") }

    private lateinit var settingsManager: SettingsManager
    private lateinit var styleConfigManager: StyleConfigManager
    private val gson  = Gson()

    private val remoteCallbacks = RemoteCallbackList<IKanaeCallback>()

    private val aidlBinder = object : IKanaeService.Stub() {
        override fun registerCallback(callback: IKanaeCallback?) {
            if (callback != null) {
                remoteCallbacks.register(callback)
                // Kirim langsung daftar custom overlay saat ini begitu client (NL Studio) connect,
                // supaya tidak perlu menunggu ada perubahan dulu.
                try {
                    callback.onCustomOverlaysChanged(gson.toJson(customOverlayManager.getConfigs()))
                } catch (_: Exception) {}
            }
        }
        override fun unregisterCallback(callback: IKanaeCallback?) {
            if (callback != null) remoteCallbacks.unregister(callback)
        }
        override fun playPause() { serviceScope.launch { togglePlayPause() } }
        override fun skip() { serviceScope.launch { playNext() } }
        override fun stop() { serviceScope.launch { stopPlayer() } }
        override fun requestMusic(queryOrUrl: String?) {
            serviceScope.launch {
                if (!queryOrUrl.isNullOrBlank()) {
                    val target = if (queryOrUrl.contains("youtube.com") || queryOrUrl.contains("youtu.be")) queryOrUrl
                    else "ytsearch1:$queryOrUrl"
                    addToQueue(target, requestedBy = "NL Studio")
                }
            }
        }

        override fun setVolume(volume: Float) {
            serviceScope.launch { updateMusicVolume(volume) }
        }

        override fun getVolume(): Float = settingsManager.settings.musicVolume

        override fun connectTikTok(username: String?) {
            serviceScope.launch {
                if (!username.isNullOrBlank()) {
                    saveSettings(settingsManager.settings.tiktokApiKey, username)
                }
            }
        }

        override fun disconnectTikTok() {
            serviceScope.launch { saveSettings("", "") }
        }

        override fun isTikTokConnected(): Boolean = tiktokConnected

        override fun getCurrentSongJson(): String? = cachedSongJson
        override fun getQueueJson(): String = runBlocking {
            withContext(Dispatchers.Main) {
                gson.toJson(queue.toList())
            }
        }
        override fun requestQueue() {
            serviceScope.launch { notifyQueueChanged() }
        }
        override fun isPlaying(): Boolean = this@PlayerForegroundService.isPlaying

        // Custom Web Overlay
        override fun getCustomOverlaysJson(): String {
            return gson.toJson(customOverlayManager.getConfigs())
        }

        override fun requestCustomOverlays() {
            serviceScope.launch { notifyCustomOverlaysChanged() }
        }
    }

    fun getCustomOverlayManager() = customOverlayManager

    inner class LocalBinder : Binder() {
        fun getService(): PlayerForegroundService = this@PlayerForegroundService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        if (intent.action == "ame.project.kanae.AIDL_SERVICE") {
            return aidlBinder
        }
        return binder
    }

    // ─────────────────────────────────────────────────────────────────
    private var cpuWakeLock: PowerManager.WakeLock? = null

    /**
     * Naikkan prioritas scheduling thread service ini + pegang PARTIAL_WAKE_LOCK
     * selama service hidup. Ini TIDAK menjamin bebas dari pembatasan CPU khusus
     * vendor (mis. MIUI Game Turbo / ColorOS Game Space), tapi memberi sinyal
     * lebih kuat ke scheduler Android bahwa proses ini butuh CPU, dan mencegah
     * proses/Handler tidur lebih dalam dari yang seharusnya.
     */
    private fun boostServicePriority() {
        val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()

        try {
            // THREAD_PRIORITY_FOREGROUND (-2) lebih tinggi dari nilai default (0),
            // jadi kernel scheduler kasih jatah CPU relatif lebih besar ke thread ini
            // dibanding proses background lain.
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            crashlytics.setCustomKey("thread_priority_boosted", true)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal set thread priority: ${e.message}")
            crashlytics.setCustomKey("thread_priority_boosted", false)
            crashlytics.recordException(e)
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            crashlytics.setCustomKey("battery_opt_ignored", pm.isIgnoringBatteryOptimizations(packageName))

            cpuWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "KanaePlayer:PlaybackWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L /*10 menit*/)
            }
            crashlytics.setCustomKey("wake_lock_acquired", true)

            // Perpanjang tiap 8 menit selama service masih hidup (timeout 10 menit
            // sengaja dipasang sebagai jaring pengaman kalau service ke-kill paksa,
            // supaya wake lock tidak nyangkut nyala selamanya).
            serviceScope.launch {
                while (isActive) {
                    delay(8 * 60 * 1000L)
                    try { cpuWakeLock?.acquire(10 * 60 * 1000L) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal acquire wake lock: ${e.message}")
            crashlytics.setCustomKey("wake_lock_acquired", false)
            crashlytics.recordException(e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        boostServicePriority()

        settingsManager = SettingsManager.getInstance(this)
        styleConfigManager = StyleConfigManager.getInstance(this)

        // Ensure all defaults are loaded and logged
        settingsManager.prepopulateDefaults()
        val chatConfig = settingsManager.getOverlayConfig("chat")
        Log.i(TAG, "Chat Layout Key: ${chatConfig.layoutKey} -> ID: ${LayoutMapper.getLayoutId(chatConfig.layoutKey)}")

        createNotificationChannel()

        val foregroundStarted = startServiceInForeground()
        if (!foregroundStarted) {
            // stopSelf() sudah dipanggil di dalam startServiceInForeground().
            // Jangan lanjut setup AudioPlayer/MediaSession/dll — service ini
            // bakal di-destroy sebentar lagi, buang-buang kerjaan aja.
            return
        }

        audioPlayer = AudioPlayer(this, serviceScope).also { p ->
            p.onComplete = ::onSongComplete
            p.onError    = { err -> Log.e(TAG, "Player error: $err"); playNext() }
            p.onProgress = { pos, dur ->
                positionMs = pos
                if (dur > 0) durationMs = dur

                // Update fast state for Activity access
                FastPlaybackState.positionMs = pos
                if (dur > 0) FastPlaybackState.durationMs = dur

                overlayManager.updateSong(currentSong, positionMs, durationMs)
                // Sync lyrics cue to current playback position
                if (lyricsOverlayManager.isShowing) {
                    lyricsOverlayManager.updatePosition(positionMs)
                }
                broadcastState()
            }
            p.init()
            p.setVolume(settingsManager.settings.musicVolume)

            // MediaSession integration
            p.playerInstance?.let { exo ->
                mediaSession = MediaSession.Builder(this@PlayerForegroundService, exo)
                    .build()
            }
        }

        YtDlpHelper.initNpe()
        ytDlp = YtDlpHelper(this)
        if (!ytDlp.isInstalled) {
            serviceScope.launch {
                ytDlp.ensureInstalled(
                    onProgress = { p -> Log.d(TAG, "yt-dlp install progress: $p%") },
                    onLog = { msg -> Log.d(TAG, "yt-dlp install: $msg") }
                )
                broadcastState()
            }
        }

        overlayManager = OverlayManager(
            context     = this,
            scope       = serviceScope,
            onPlayPause = ::togglePlayPause,
            onSkip      = ::playNext,
            onClose     = { broadcastState() }
        ).apply {
            val config = settingsManager.getOverlayConfig("player")
            val layoutId = LayoutMapper.getLayoutId(config.layoutKey)
            if (layoutId != 0) updateStyle(layoutId)
            applyConfig(config.x, config.y, config.scale, config.width, config.height)

            onPositionChanged = { nx, ny, ns ->
                val cfg = settingsManager.getOverlayConfig("player")
                cfg.x = nx; cfg.y = ny; cfg.scale = ns
                settingsManager.saveSettings()
            }
        }

        queueOverlayManager = QueueOverlayManager(
            context  = this,
            onPlay   = { pos -> queue.elementAtOrNull(pos)?.let { playSong(it) } },
            onRemove = { pos -> removeFromQueue(pos) },
            onClose  = { broadcastState() }
        ).apply {
            val config = settingsManager.getOverlayConfig("queue")
            val containerId = LayoutMapper.getLayoutId(config.layoutKey)
            val itemId = LayoutMapper.getLayoutId(config.itemKey)

            val finalContainer = if (containerId != 0) containerId else ame.project.kanae.R.layout.overlay_queue_layout
            val finalItem = if (itemId != 0) itemId else ame.project.kanae.R.layout.item_queue

            updateStyle(finalContainer, finalItem)
            applyConfig(config.x, config.y, config.scale, config.width)
            setAutoHide(settingsManager.settings.queueAutoHide, settingsManager.settings.queueDuration)

            onPositionChanged = { nx, ny, ns ->
                val cfg = settingsManager.getOverlayConfig("queue")
                cfg.x = nx; cfg.y = ny; cfg.scale = ns
                settingsManager.saveSettings()
            }
        }

        lyricsOverlayManager = LyricsOverlayManager(
            context       = this,
            scope         = serviceScope,
            preferredLang = settingsManager.settings.lyricsLang,
            onClose       = { broadcastState() }
        ).apply {
            val config = settingsManager.getOverlayConfig("lyrics")
            val layoutId = LayoutMapper.getLayoutId(config.layoutKey)
            if (layoutId != 0) updateStyle(layoutId)
            applyConfig(config.x, config.y, config.scale, config.width, config.height)

            onPositionChanged = { nx, ny, ns ->
                val cfg = settingsManager.getOverlayConfig("lyrics")
                cfg.x = nx; cfg.y = ny; cfg.scale = ns
                settingsManager.saveSettings()
            }

            onLyricsChanged = { text ->
                val n = remoteCallbacks.beginBroadcast()
                for (i in 0 until n) {
                    try {
                        remoteCallbacks.getBroadcastItem(i).onLyricsChanged(text)
                    } catch (e: RemoteException) {}
                }
                remoteCallbacks.finishBroadcast()
            }
        }

        chatOverlayManager = ChatOverlayManager(
            context = this,
            scope   = serviceScope,
            maxLines = settingsManager.settings.chatMaxLines,
            onClose = { broadcastState() }
        ).apply {
            val s = settingsManager.settings
            val config = settingsManager.getOverlayConfig("chat")
            setTransparent(s.chatTransparent)
            setStickerAnimationEnabled(s.chatStickerAnimation)
            setOverlayWidth(config.width)
            setDisplayDuration(s.chatDuration)
            setAlwaysShow(s.chatAlwaysShow)
            setHistoryEnabled(s.chatHistoryEnabled)

            val layoutId = LayoutMapper.getLayoutId(config.layoutKey)
            val bgId = LayoutMapper.getDrawableId(config.bgKey)

            val finalLayout = if (layoutId != 0) layoutId else ame.project.kanae.R.layout.item_chat_bubble_boxed
            updateStyle(finalLayout, bgId)

            setTikTokConnected(tiktokConnected)
            applyConfig(config.x, config.y, config.scale, config.width)

            onPositionChanged = { nx, ny, ns ->
                val cfg = settingsManager.getOverlayConfig("chat")
                cfg.x = nx; cfg.y = ny; cfg.scale = ns
                settingsManager.saveSettings()
            }
        }

        notifOverlayManager = TikTokNotificationOverlayManager(this).apply {
            val s = settingsManager.settings
            val config = settingsManager.getOverlayConfig("notif")
            val layoutId = LayoutMapper.getLayoutId(config.layoutKey)
            if (layoutId != 0) updateStyle(layoutId)
            setConfig(
                s.notifShareImg,
                s.notifGiftImg,
                s.notifShareAud,
                s.notifGiftAud,
                s.notifDuration
            )
            setUseTiktokGiftIcon(s.useTiktokGiftIcon)
            setUseCustomGiftSound(s.useCustomGiftSound)
            setVolume(s.notifVolume)
            setVisualPunchEnabled(config.visualPunch)

            applyConfig(config.x, config.y, config.scale)

            onPositionChanged = { nx, ny, ns ->
                val cfg = settingsManager.getOverlayConfig("notif")
                cfg.x = nx; cfg.y = ny; cfg.scale = ns
                settingsManager.saveSettings()
            }
        }

        joinOverlayManager = TikTokJoinOverlayManager(this).apply {
            val config = settingsManager.getOverlayConfig("join")
            val layoutId = LayoutMapper.getLayoutId(config.layoutKey)
            if (layoutId != 0) updateStyle(layoutId)
            setDuration(settingsManager.settings.joinDuration)
            applyConfig(config.x, config.y, config.scale)

            onPositionChanged = { nx, ny, ns ->
                val cfg = settingsManager.getOverlayConfig("join")
                cfg.x = nx; cfg.y = ny; cfg.scale = ns
                settingsManager.saveSettings()
            }
        }
        likeOverlayManager = TikTokLikeOverlayManager(this).apply {
            val config = settingsManager.getOverlayConfig("like")
            val layoutId = LayoutMapper.getLayoutId(config.layoutKey)
            if (layoutId != 0) updateStyle(layoutId)
            setAnimationEnabled(settingsManager.settings.likeAnimationEnabled)
            setDuration(settingsManager.settings.likeDuration)
            applyConfig(config.x, config.y, config.scale)

            onPositionChanged = { nx, ny, ns ->
                val cfg = settingsManager.getOverlayConfig("like")
                cfg.x = nx; cfg.y = ny; cfg.scale = ns
                settingsManager.saveSettings()
            }
        }
        followOverlayManager = TikTokFollowOverlayManager(this).apply {
            val config = settingsManager.getOverlayConfig("follow")
            val layoutId = LayoutMapper.getLayoutId(config.layoutKey)
            if (layoutId != 0) updateStyle(layoutId)
            setDuration(settingsManager.settings.followDuration)
            setVisualPunchEnabled(config.visualPunch)
            applyConfig(config.x, config.y, config.scale)

            onPositionChanged = { nx, ny, ns ->
                val cfg = settingsManager.getOverlayConfig("follow")
                cfg.x = nx; cfg.y = ny; cfg.scale = ns
                settingsManager.saveSettings()
            }
        }

        customOverlayManager = CustomOverlayManager(this, serviceScope)
        customOverlayManager.onConfigsChanged = { notifyCustomOverlaysChanged() }

        updateCustomThemes()

        // Apply saved configurations to overlays
        overlayManager.setVisualPunchEnabled(settingsManager.getOverlayConfig("player").visualPunch)
        queueOverlayManager.setVisualPunchEnabled(settingsManager.getOverlayConfig("queue").visualPunch)
        lyricsOverlayManager.setVisualPunchEnabled(settingsManager.getOverlayConfig("lyrics").visualPunch)
        chatOverlayManager.setVisualPunchEnabled(settingsManager.getOverlayConfig("chat").visualPunch)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.forLanguageTag("id-ID"))
            }
        }

        tiktokManager = buildTikTokManager()

        canvasModeEnabled = settingsManager.settings.canvasModeEnabled
        if (canvasModeEnabled) {
            val s = settingsManager.settings
            enableCanvasMode(s.canvasPlayerX, s.canvasPlayerY, s.canvasQueueX, s.canvasQueueY)
        }

        sendBroadcast(Intent(BROADCAST_SERVICE_READY))
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
                val s = settingsManager.settings
                val enable = intent.getBooleanExtra("enabled", false)
                val px = intent.getIntExtra("player_x", s.canvasPlayerX)
                val py = intent.getIntExtra("player_y", s.canvasPlayerY)
                val qx = intent.getIntExtra("queue_x",  s.canvasQueueX)
                val qy = intent.getIntExtra("queue_y",  s.canvasQueueY)
                if (enable) enableCanvasMode(px, py, qx, qy)
                else        disableCanvasMode()
            }
        }
        // START_NOT_STICKY: JANGAN biarkan sistem auto-restart service ini di
        // background setelah proses di-kill (mis. dibunuh OEM battery optimizer).
        // Restart otomatis semacam itu tidak punya "user-initiated" exemption,
        // jadi startForeground() di onCreate() akan selalu gagal dengan
        // ForegroundServiceStartNotAllowedException di Android 12+. User harus
        // buka app lagi secara manual untuk menyalakan service.
        return START_NOT_STICKY
    }

    // User swipe app dari recents ≠ user mau stop musiknya. Default Android untuk
    // service biasa adalah ikut mati, tapi untuk media player kayak gini biasanya
    // user justru mau lagu TETAP lanjut di background (kayak Spotify/YouTube Music).
    // Override ini eksplisit menyatakan: jangan ikut mati, biarkan FGS tetap hidup.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: app di-swipe dari recents, service tetap lanjut jalan")
        // Tidak ada stopSelf() di sini secara sengaja — kalau kamu MAU service
        // ikut berhenti saat user swipe app, uncomment baris di bawah:
        // stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        try { cpuWakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        cpuWakeLock = null
        mediaSession?.release()
        mediaSession = null
        tts?.stop()
        tts?.shutdown()
        cleanupPlayer()

        tiktokManager.release()
        audioPlayer.release()

        overlayManager.hide()
        queueOverlayManager.hide()
        lyricsOverlayManager.hide()
        chatOverlayManager.hide()

        notifOverlayManager.hide()
        joinOverlayManager.hide()
        likeOverlayManager.hide()
        followOverlayManager.hide()

        customOverlayManager.hideAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun saveSettings(
        apiKey: String,
        username: String,
        limit: Int = 3,
        cmdConfig: TikTokLiveManager.CommandConfig? = null
    ) {
        if (apiKey.isBlank() && username.isBlank()) {
            tiktokManager.disconnect()
            tiktokConnected = false
            chatOverlayManager.setTikTokConnected(false)
            tiktokConnecting = false
            overlayManager.setLiveStatus(false)
            chatOverlayManager.clearChats()
            broadcastState()
            return
        }

        settingsManager.settings.apply {
            tiktokApiKey = apiKey
            tiktokUsername = username.removePrefix("@")
            requestLimit = limit
        }

        cmdConfig?.let { cfg ->
            settingsManager.settings.apply {
                commandsEnabled = cfg.enabled
                cmdRequest = cfg.requestPrefixes.joinToString(",")
                cmdSkip = cfg.skipPrefixes.joinToString(",")
                cmdStop = cfg.stopPrefixes.joinToString(",")
                cmdQueue = cfg.queuePrefixes.joinToString(",")
                cmdClearMusic = cfg.clearMusicPrefixes.joinToString(",")
            }
        }
        settingsManager.saveSettings()

        tiktokManager.release()
        tiktokManager = buildTikTokManager()
        chatOverlayManager.clearChats()
        if (username.isNotBlank() && apiKey.isNotBlank()) {
            tiktokConnecting = true
            tiktokConnectTime = System.currentTimeMillis() + 3600000
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
                notifyQueueChanged()
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
            notifyQueueChanged()
            syncQueueOverlay()
        }
    }

    fun clearMusicAt(position: Int): Boolean {
        val idx = position - 1
        if (idx !in queue.indices) return false
        queue.removeAt(idx)
        broadcastState()
        syncQueueOverlay()
        notifyQueueChanged() // FIX: sama seperti playNext(), broadcast ke NL Studio juga
        return true
    }

    fun clearMusicByTitle(query: String): String? {
        val lower = query.lowercase().trim()
        val idx   = queue.indexOfFirst { it.title.lowercase().contains(lower) }
        if (idx == -1) return null
        val removed = queue.removeAt(idx)
        broadcastState()
        syncQueueOverlay()
        notifyQueueChanged() // FIX: sama seperti playNext(), broadcast ke NL Studio juga
        return removed.title
    }

    fun clearQueue() {
        queue.clear()
        broadcastState()
        notifyQueueChanged()
        syncQueueOverlay()
    }

    fun moveInQueue(from: Int, to: Int) {
        if (from in queue.indices && to in queue.indices) {
            val song = queue.removeAt(from)
            queue.add(to, song)
            broadcastState()
            syncQueueOverlay()
            notifyQueueChanged() // FIX: sama seperti playNext(), broadcast ke NL Studio juga
        }
    }

    fun getQueue(): List<Song> = queue.toList()

    fun toggleShuffle(): Boolean {
        val newState = !settingsManager.settings.shuffleMode
        settingsManager.settings.shuffleMode = newState
        settingsManager.saveSettings()
        return newState
    }

    // ── Playback ──────────────────────────────────────────────────────
    fun playSong(song: Song) {
        serviceScope.launch {
            currentSong = song
            cachedSongJson = gson.toJson(song)

            FastPlaybackState.currentSongJson = cachedSongJson
            FastPlaybackState.isPlaying = true
            FastPlaybackState.isPaused = false
            FastPlaybackState.positionMs = 0L
            FastPlaybackState.durationMs = song.duration * 1000L

            positionMs  = 0L
            durationMs  = song.duration * 1000L
            isPlaying   = true
            isPaused    = false
            updateNotification(song.title)
            overlayManager.updateSong(song, 0, song.duration * 1000L)
            overlayManager.setPlayingState(true)
            broadcastState()
            notifyTrackChanged(song)

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
                FastPlaybackState.isPaused = true
                overlayManager.setPlayingState(false)
                updateNotification("Paused: ${currentSong?.title}")
            }
            isPaused -> {
                audioPlayer.resume(); isPaused = false
                FastPlaybackState.isPaused = false
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
            // FIX: notifyQueueChanged() juga harus dipanggil di sini supaya NL Studio
            // (dan client AIDL lain) tahu antrian sudah kosong, bukan cuma overlay lokal.
            notifyQueueChanged()
            return
        }
        val next = if (settingsManager.settings.shuffleMode) queue.removeAt(queue.indices.random())
        else queue.removeFirst()
        playSong(next)
        syncQueueOverlay()
        // FIX: sebelumnya playNext() hanya memanggil syncQueueOverlay() yang cuma
        // memperbarui overlay LOKAL di Kanae Player. Client AIDL eksternal seperti NL
        // Studio hanya mendengarkan callback onQueueChanged(), yang sebelumnya tidak
        // pernah dikirim di sini. Akibatnya, antrian musik di overlay NL Studio tidak
        // ikut berubah setiap kali lagu di-skip (baik skip manual di app, skip via
        // command TikTok, maupun auto-skip saat lagu selesai - karena semuanya lewat
        // fungsi playNext() ini). Sekarang broadcast-nya ditambahkan supaya NL Studio
        // langsung sinkron setiap kali antrian berubah akibat skip.
        notifyQueueChanged()
    }

    fun stopPlayer() {
        audioPlayer.stop()
        currentSong = null
        cachedSongJson = null
        FastPlaybackState.currentSongJson = null
        FastPlaybackState.isPlaying = false
        FastPlaybackState.isPaused = false
        isPlaying = false; isPaused = false
        positionMs = 0L; durationMs = 0L
        overlayManager.setPlayingState(false)
        overlayManager.updateSong(null, 0, 0)
        updateNotification("Stopped")
        broadcastState()
    }

    private fun onSongComplete() {
        playNext()
    }

    // ── TikTok chat handler ───────────────────────────────────────────
    private fun handleTikTokChat(chat: TikTokChat) {
        Log.d(TAG, "[TikTok] @${chat.uniqueId}: ${chat.comment}")
        broadcastChat(chat)

        if (chatOverlayManager.isShowing) {
            chatOverlayManager.addChat(chat.nickname, chat.comment, emotes = chat.emotes)
        }

        if (System.currentTimeMillis() - tiktokConnectTime < 5000) return

        speak(chat.comment)

        val isAdmin = isAdmin(chat)

        when (chat.commandType) {
            TikTokChat.CommandType.REQUEST -> {
                val arg = chat.commandArg ?: return
                if (!isAdmin) {
                    val userReq = "@${chat.uniqueId}"
                    val count   = queue.count { it.requestedBy == userReq }
                    if (count >= settingsManager.settings.requestLimit) return
                }
                val url = if (arg.contains("youtube.com") || arg.contains("youtu.be")) arg
                else "ytsearch1:$arg"
                addToQueue(url, requestedBy = "@${chat.uniqueId}")
            }
            TikTokChat.CommandType.SKIP        -> if (isAdmin) playNext()
            TikTokChat.CommandType.STOP        -> if (isAdmin) stopPlayer()
            TikTokChat.CommandType.QUEUE       -> {
                if (isAdmin) broadcastState()
                serviceScope.launch(Dispatchers.Main) {
                    if (!queueOverlayManager.isShowing) {
                        val config = settingsManager.getOverlayConfig("queue")
                        queueOverlayManager.show(queue.toList())
                        queueOverlayManager.applyConfig(config.x, config.y, config.scale, config.width)
                    } else {
                        queueOverlayManager.updateQueue(queue.toList())
                        queueOverlayManager.resetHideTimer()
                    }
                }
            }
            TikTokChat.CommandType.CLEAR_MUSIC -> {
                if (!isAdmin) return
                val arg = chat.commandArg?.trim() ?: return
                val pos = arg.toIntOrNull()
                if (pos != null) clearMusicAt(pos)
                else clearMusicByTitle(arg)
            }
            TikTokChat.CommandType.COMMAND_TOGGLE -> {
                if (!isAdmin) return
                val arg = chat.commandArg?.lowercase()?.trim()
                val newState = when (arg) {
                    "on", "enable", "1" -> true
                    "off", "disable", "0" -> false
                    else -> !settingsManager.settings.commandsEnabled
                }

                settingsManager.settings.commandsEnabled = newState
                settingsManager.saveSettings()

                val currentCfg = buildCommandConfig()
                tiktokManager.setCommandConfig(currentCfg)

                broadcastSystemChat("Commands ${if (newState) "ENABLED" else "DISABLED"} via @${chat.uniqueId}")
                broadcastState()
            }
            TikTokChat.CommandType.NONE -> { }
        }
    }

    /**
     * Cek apakah pengirim chat boleh pakai command admin (skip, stop, clear music, dll).
     *
     * - Owner streamer & literal "admin" selalu boleh.
     * - Daftar Authorized Users manual (AdminListActivity) SELALU berlaku sebagai
     *   override, di mode manapun.
     * - Kalau adminAccessMode == "FOLLOWERS": siapa saja yang sudah follow akun TikTok
     *   streamer (chat.isFollower) juga boleh pakai command, tanpa perlu dimasukkan manual.
     * - Kalau adminAccessMode == "AUTHORIZED_USERS" (default): hanya yang ada di daftar
     *   manual di atas yang boleh.
     */
    private fun isAdmin(chat: TikTokChat): Boolean {
        val uniqueId = chat.uniqueId
        val cleanOwner = settingsManager.settings.tiktokUsername.trim().removePrefix("@")
        if (uniqueId.equals(cleanOwner, ignoreCase = true)) return true
        if (uniqueId.equals("admin", ignoreCase = true)) return true

        val authorized = settingsManager.settings.authorizedUsers
        if (authorized.split(",").any { it.trim().equals(uniqueId, ignoreCase = true) }) return true

        return when (settingsManager.settings.adminAccessMode) {
            "FOLLOWERS" -> chat.isFollower
            else -> false
        }
    }

    // ── Canvas mode ───────────────────────────────────────────────────
    fun enableCanvasMode(px: Int, py: Int, qx: Int, qy: Int) {
        canvasModeEnabled = true
        settingsManager.settings.apply {
            canvasModeEnabled = true
            canvasPlayerX = px; canvasPlayerY = py
            canvasQueueX  = qx; canvasQueueY  = qy
        }
        settingsManager.saveSettings()

        if (!overlayManager.isShowing)      overlayManager.show()
        if (!queueOverlayManager.isShowing) queueOverlayManager.show(getQueue())

        overlayManager.setCanvasMode(locked = true, x = px, y = py)
        queueOverlayManager.setCanvasMode(locked = true, x = qx, y = qy)
        if (lyricsOverlayManager.isShowing)
            lyricsOverlayManager.setCanvasMode(locked = true, x = qx, y = qy + 750) // Adjust as needed
        if (chatOverlayManager.isShowing)
            chatOverlayManager.setCanvasMode(locked = true, x = qx, y = qy + 500)

        // ... rest of the lock logic ...
        broadcastState()
    }

    fun disableCanvasMode() {
        settingsManager.settings.canvasModeEnabled = false
        settingsManager.saveSettings()
        overlayManager.setCanvasMode(locked = false)
        queueOverlayManager.setCanvasMode(locked = false)
        if (lyricsOverlayManager.isShowing) lyricsOverlayManager.setCanvasMode(locked = false)
        if (chatOverlayManager.isShowing) chatOverlayManager.setCanvasMode(locked = false)
        if (joinOverlayManager.isShowing) joinOverlayManager.setCanvasMode(locked = false)
        if (likeOverlayManager.isShowing) likeOverlayManager.setCanvasMode(locked = false)
        if (followOverlayManager.isShowing) followOverlayManager.setCanvasMode(locked = false)
        broadcastState()
    }

    fun getCanvasState(): Map<String, Int> = mapOf(
        "canvas_px" to settingsManager.settings.canvasPlayerX,
        "canvas_py" to settingsManager.settings.canvasPlayerY,
        "canvas_qx" to settingsManager.settings.canvasQueueX,
        "canvas_qy" to settingsManager.settings.canvasQueueY
    )

    // ── Overlay passthrough ───────────────────────────────────────────
    fun showOverlay() {
        val config = settingsManager.getOverlayConfig("player")
        overlayManager.show(config.x, config.y)
        overlayManager.applyConfig(config.x, config.y, config.scale)
        broadcastState()
    }
    fun hideOverlay()  { overlayManager.hide() }
    val overlayVisible get() = overlayManager.isShowing

    fun toggleQueueOverlay() {
        if (queueOverlayManager.isShowing) queueOverlayManager.hide()
        else showQueueOverlay()
    }

    fun showQueueOverlay() {
        val config = settingsManager.getOverlayConfig("queue")
        queueOverlayManager.show(queue.toList())
        queueOverlayManager.applyConfig(config.x, config.y, config.scale, config.width)
        broadcastState()
    }

    val queueOverlayVisible get() = queueOverlayManager.isShowing

    fun updateQueueAutoHide(enabled: Boolean) {
        settingsManager.settings.queueAutoHide = enabled
        settingsManager.saveSettings()
        queueOverlayManager.setAutoHide(enabled, settingsManager.settings.queueDuration)
    }

    fun updateQueueDuration(seconds: Int) {
        settingsManager.settings.queueDuration = seconds
        settingsManager.saveSettings()
        queueOverlayManager.setAutoHide(settingsManager.settings.queueAutoHide, seconds)
    }

    // ── Lyrics overlay ────────────────────────────────────────────────
    fun toggleLyricsOverlay() {
        if (lyricsOverlayManager.isShowing) {
            lyricsOverlayManager.hide()
        } else {
            lyricsOverlayManager.show()
            currentSong?.let { lyricsOverlayManager.loadForSong(it) }
        }
        broadcastState()
    }

    val lyricsOverlayVisible get() = lyricsOverlayManager.isShowing

    // ── Chat overlay ──────────────────────────────────────────────────
    fun toggleChatOverlay() {
        if (chatOverlayManager.isShowing) chatOverlayManager.hide()
        else showChatOverlay()
        broadcastState()
    }

    fun showChatOverlay() {
        if (!chatOverlayManager.isShowing) {
            val config = settingsManager.getOverlayConfig("chat")
            chatOverlayManager.show(config.x, config.y, config.scale, config.width)
        }
    }

    fun updateChatMaxLines(lines: Int) {
        settingsManager.settings.chatMaxLines = lines
        chatOverlayManager.setMaxLines(lines)
    }

    fun updateChatTransparency(transparent: Boolean) {
        settingsManager.settings.chatTransparent = transparent
        chatOverlayManager.setTransparent(transparent)
    }

    fun updateChatWidth(widthDp: Int) {
        val config = settingsManager.getOverlayConfig("chat")
        config.width = widthDp
        chatOverlayManager.setOverlayWidth(widthDp)
    }

    fun updateChatDuration(seconds: Int) {
        settingsManager.settings.chatDuration = seconds
        chatOverlayManager.setDisplayDuration(seconds)
    }

    fun updateChatStyle(layoutId: Int, bgId: Int) {
        val config = settingsManager.getOverlayConfig("chat")
        config.layoutKey = LayoutMapper.getLayoutKey(layoutId)
        config.bgKey = LayoutMapper.getDrawableKey(bgId)
        settingsManager.saveSettings()
        chatOverlayManager.updateStyle(layoutId, bgId)
    }

    fun updateChatAlwaysShow(alwaysShow: Boolean) {
        settingsManager.settings.chatAlwaysShow = alwaysShow
        settingsManager.saveSettings()
        chatOverlayManager.setAlwaysShow(alwaysShow)
    }

    fun updateChatHistory(enabled: Boolean) {
        settingsManager.settings.chatHistoryEnabled = enabled
        settingsManager.saveSettings()
        chatOverlayManager.setHistoryEnabled(enabled)
    }

    fun updateJoinStyle(layoutId: Int) {
        val config = settingsManager.getOverlayConfig("join")
        config.layoutKey = LayoutMapper.getLayoutKey(layoutId)
        settingsManager.saveSettings()
        joinOverlayManager.updateStyle(layoutId)
    }

    fun updateLikeStyle(layoutId: Int) {
        val config = settingsManager.getOverlayConfig("like")
        config.layoutKey = LayoutMapper.getLayoutKey(layoutId)
        settingsManager.saveSettings()
        likeOverlayManager.updateStyle(layoutId)
    }

    fun updateFollowStyle(layoutId: Int) {
        val config = settingsManager.getOverlayConfig("follow")
        config.layoutKey = LayoutMapper.getLayoutKey(layoutId)
        settingsManager.saveSettings()
        followOverlayManager.updateStyle(layoutId)
    }

    fun updatePlayerStyle(layoutId: Int) {
        val config = settingsManager.getOverlayConfig("player")
        config.layoutKey = LayoutMapper.getLayoutKey(layoutId)
        settingsManager.saveSettings()
        overlayManager.updateStyle(layoutId)
    }

    fun updateNotifStyle(layoutId: Int) {
        val config = settingsManager.getOverlayConfig("notif")
        config.layoutKey = LayoutMapper.getLayoutKey(layoutId)
        settingsManager.saveSettings()
        notifOverlayManager.updateStyle(layoutId)
    }

    fun updateLyricsStyle(layoutId: Int) {
        val config = settingsManager.getOverlayConfig("lyrics")
        config.layoutKey = LayoutMapper.getLayoutKey(layoutId)
        settingsManager.saveSettings()
        lyricsOverlayManager.updateStyle(layoutId)
    }

    fun updateQueueStyle(containerId: Int, itemId: Int) {
        val config = settingsManager.getOverlayConfig("queue")
        config.layoutKey = LayoutMapper.getLayoutKey(containerId)
        config.itemKey = LayoutMapper.getLayoutKey(itemId)
        settingsManager.saveSettings()
        queueOverlayManager.updateStyle(containerId, itemId)
    }

    private fun loadTheme(category: String): CustomTheme {
        val config = settingsManager.getOverlayConfig(category)
        val styleTheme = styleConfigManager.getStyleTheme(category.uppercase(), config.layoutKey)

        return CustomTheme(
            bgPrimary = styleTheme.bgPrimary,
            bgSecondary = styleTheme.bgSecondary,
            textPrimary = styleTheme.textPrimary,
            textSecondary = styleTheme.textSecondary,
            alpha = styleTheme.alpha
        )
    }

    fun updateCustomThemes() {
        val keys = listOf("player", "queue", "lyrics", "chat", "notif", "join", "like", "follow")
        keys.forEach { key ->
            val theme = loadTheme(key)
            when (key) {
                "player" -> overlayManager.applyTheme(theme)
                "queue" -> queueOverlayManager.applyTheme(theme)
                "lyrics" -> lyricsOverlayManager.applyTheme(theme)
                "chat" -> chatOverlayManager.applyTheme(theme)
                "notif" -> notifOverlayManager.applyTheme(theme)
                "join" -> joinOverlayManager.applyTheme(theme)
                "like" -> likeOverlayManager.applyTheme(theme)
                "follow" -> followOverlayManager.applyTheme(theme)
            }
        }
    }

    fun updateTtsEnabled(enabled: Boolean) {
        settingsManager.settings.chatTtsEnabled = enabled
        settingsManager.saveSettings()
    }

    fun updateTtsVolume(volume: Float) {
        settingsManager.settings.chatTtsVolume = volume
        settingsManager.saveSettings()
    }

    fun updateTtsMaxLength(length: Int) {
        settingsManager.settings.chatTtsMaxLength = length
        settingsManager.saveSettings()
    }

    fun updateMusicVolume(volume: Float) {
        settingsManager.settings.musicVolume = volume
        settingsManager.saveSettings()
        if (!isTtsSpeaking) audioPlayer.setVolume(volume)
        else audioPlayer.setVolume(volume * 0.15f)
        broadcastState()
    }

    fun updateNotifVolume(volume: Float) {
        settingsManager.settings.notifVolume = volume
        settingsManager.saveSettings()
        notifOverlayManager.setVolume(volume)
        broadcastState()
    }

    fun updateUseTiktokGiftIcon(enabled: Boolean) {
        settingsManager.settings.useTiktokGiftIcon = enabled
        settingsManager.saveSettings()
        notifOverlayManager.setUseTiktokGiftIcon(enabled)
        broadcastState()
    }

    fun updateUseCustomGiftSound(enabled: Boolean) {
        settingsManager.settings.useCustomGiftSound = enabled
        settingsManager.saveSettings()
        notifOverlayManager.setUseCustomGiftSound(enabled)
        broadcastState()
    }

    fun updateNotifConfig(shareImg: String?, giftImg: String?, shareAud: String?, giftAud: String?, duration: Int, refreshType: String? = null) {
        settingsManager.settings.apply {
            notifShareImg = shareImg
            notifGiftImg = giftImg
            notifShareAud = shareAud
            notifGiftAud = giftAud
            notifDuration = duration
        }
        settingsManager.saveSettings()
        notifOverlayManager.setConfig(shareImg, giftImg, shareAud, giftAud, duration)
        if (notifOverlayManager.isShowing || refreshType != null) {
            showNotifDummy(refreshType ?: "gift")
        }
    }

    fun toggleNotifOverlay() {
        val newState = !settingsManager.settings.notifEnabled
        settingsManager.settings.notifEnabled = newState
        settingsManager.saveSettings()
        if (newState) showNotifDummy()
        else notifOverlayManager.hide()
        broadcastState()
    }

    fun showChatDummy(persistent: Boolean = false) {
        chatOverlayManager.addDummyChat(if (persistent) 0 else 5000)
    }

    fun hideChatDummy() {
        chatOverlayManager.clearDummyChat()
    }

    fun showNotifDummy(type: String = "gift", persistent: Boolean = false) {
        val config = settingsManager.getOverlayConfig("notif")
        val action = if (type == "gift") "mengirim Gift (Preview)" else "membagikan live (Preview)"
        notifOverlayManager.showNotification("Preview User", action, type, isDummy = true, persistent = persistent)
        notifOverlayManager.applyConfig(config.x, config.y, config.scale)
    }

    fun resetNotifTimer() {
        notifOverlayManager.resetHideTimer()
    }

    fun hideNotif() {
        notifOverlayManager.hide()
    }

    fun updateNotifEnabled(enabled: Boolean) {
        settingsManager.settings.notifEnabled = enabled
        settingsManager.saveSettings()
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
        settingsManager.getOverlayConfig(key).visualPunch = enabled
        settingsManager.saveSettings()
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
        val newState = !settingsManager.settings.joinEnabled
        settingsManager.settings.joinEnabled = newState
        settingsManager.saveSettings()
        if (newState) showJoinDummy()
        else joinOverlayManager.hide()
        broadcastState()
    }

    fun toggleLikeOverlay() {
        val newState = !settingsManager.settings.likeEnabled
        settingsManager.settings.likeEnabled = newState
        settingsManager.saveSettings()
        if (newState) showLikeDummy()
        else likeOverlayManager.hide()
        broadcastState()
    }

    fun toggleFollowOverlay() {
        val newState = !settingsManager.settings.followEnabled
        settingsManager.settings.followEnabled = newState
        settingsManager.saveSettings()
        if (newState) showFollowDummy()
        else followOverlayManager.hide()
        broadcastState()
    }

    fun updateJoinEnabled(enabled: Boolean) {
        settingsManager.settings.joinEnabled = enabled
        settingsManager.saveSettings()
        broadcastState()
    }

    fun updateLikeEnabled(enabled: Boolean) {
        settingsManager.settings.likeEnabled = enabled
        settingsManager.saveSettings()
        broadcastState()
    }

    fun updateFollowEnabled(enabled: Boolean) {
        settingsManager.settings.followEnabled = enabled
        settingsManager.saveSettings()
        broadcastState()
    }

    fun updateLikeAnimationEnabled(enabled: Boolean) {
        settingsManager.settings.likeAnimationEnabled = enabled
        settingsManager.saveSettings()
        likeOverlayManager.setAnimationEnabled(enabled)
    }

    fun updateJoinDuration(seconds: Int) {
        settingsManager.settings.joinDuration = seconds
        settingsManager.saveSettings()
        joinOverlayManager.setDuration(seconds)
    }

    fun updateLikeDuration(seconds: Int) {
        settingsManager.settings.likeDuration = seconds
        settingsManager.saveSettings()
        likeOverlayManager.setDuration(seconds)
    }

    fun updateFollowDuration(seconds: Int) {
        settingsManager.settings.followDuration = seconds
        settingsManager.saveSettings()
        followOverlayManager.setDuration(seconds)
    }

    fun updateStickerAnimationEnabled(enabled: Boolean) {
        settingsManager.settings.chatStickerAnimation = enabled
        settingsManager.saveSettings()
        chatOverlayManager.setStickerAnimationEnabled(enabled)
    }

    fun showJoinDummy(persistent: Boolean = false) {
        val config = settingsManager.getOverlayConfig("join")
        joinOverlayManager.showJoin("Preview", null, isDummy = true, persistent = persistent)
        joinOverlayManager.applyConfig(config.x, config.y, config.scale)
    }

    fun showLikeDummy(persistent: Boolean = false) {
        val config = settingsManager.getOverlayConfig("like")
        likeOverlayManager.showLike("Preview", 1, null, isDummy = true, persistent = persistent)
        likeOverlayManager.applyConfig(config.x, config.y, config.scale)
    }

    fun showFollowDummy(persistent: Boolean = false) {
        val config = settingsManager.getOverlayConfig("follow")
        followOverlayManager.showFollow("Preview", null, isDummy = true, persistent = persistent)
        followOverlayManager.applyConfig(config.x, config.y, config.scale)
    }

    fun showWidthPreview(key: String, widthDp: Int) {
        when (key) {
            "player" -> overlayManager.showWidthPreview(widthDp)
            "queue"  -> queueOverlayManager.showWidthPreview(widthDp)
            "lyrics" -> lyricsOverlayManager.showWidthPreview(widthDp)
            "chat"   -> chatOverlayManager.showWidthPreview(widthDp)
        }
    }

    fun hideWidthPreview(key: String) {
        when (key) {
            "player" -> overlayManager.hideWidthPreview()
            "queue"  -> queueOverlayManager.hideWidthPreview()
            "lyrics" -> lyricsOverlayManager.hideWidthPreview()
            "chat"   -> chatOverlayManager.hideWidthPreview()
        }
    }

    fun persistSettings() {
        settingsManager.saveSettings()
    }

    fun applyOverlayConfig(key: String, x: Int, y: Int, scale: Float, width: Int = 0, height: Int = 0) {
        var finalX = x
        var finalY = y

        fun getCurrentPos(manager: Any?): Pair<Int, Int>? {
            if (manager == null) return null
            return try {
                // Cache reflection or ideally make managers expose LP
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
                val config = settingsManager.getOverlayConfig(key)
                if (finalX == -1) finalX = config.x
                if (finalY == -1) finalY = config.y
            }
        }

        val config = settingsManager.getOverlayConfig(key)
        config.x = finalX
        config.y = finalY
        config.scale = scale
        config.width = width
        config.height = height
        // REMOVED: settingsManager.saveSettings() - disk I/O on every slider move is bad

        when (key) {
            "player" -> if (overlayManager.isShowing) overlayManager.applyConfig(finalX, finalY, scale, width, height)
            "queue"  -> if (queueOverlayManager.isShowing) queueOverlayManager.applyConfig(finalX, finalY, scale, width, height)
            "lyrics" -> if (lyricsOverlayManager.isShowing) lyricsOverlayManager.applyConfig(finalX, finalY, scale, width, height)
            "chat"   -> if (chatOverlayManager.isShowing) chatOverlayManager.applyConfig(finalX, finalY, scale, width)
            "notif"  -> if (notifOverlayManager.isShowing) notifOverlayManager.applyConfig(finalX, finalY, scale)
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
                val config = settingsManager.getOverlayConfig(key)
                Pair(config.x, config.y)
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
    fun getStateMap(): Map<String, Any?> {
        val s = settingsManager.settings
        return mapOf(
            "current_song"      to cachedSongJson,
            "is_playing"        to isPlaying,
            "is_paused"         to isPaused,
            "queue_count"       to queue.size,
            "position_ms"       to positionMs,
            "duration_ms"       to durationMs,
            "shuffle_mode"      to s.shuffleMode,
            "tiktok_connected"  to tiktokConnected,
            "tiktok_connecting" to tiktokConnecting,
            "request_limit"     to s.requestLimit,
            "ytdlp_installed"   to ytDlp.isInstalled,
            "canvas_mode"       to canvasModeEnabled,
            "lyrics_visible"    to lyricsOverlayManager.isShowing,
            "queue_visible"     to queueOverlayManager.isShowing,
            "chat_visible"      to chatOverlayManager.isShowing,
            "notif_visible"     to notifOverlayManager.isShowing,
            "notif_enabled"     to s.notifEnabled,
            "join_visible"      to joinOverlayManager.isShowing,
            "like_visible"      to likeOverlayManager.isShowing,
            "follow_visible"    to followOverlayManager.isShowing,
            "join_enabled"      to s.joinEnabled,
            "like_enabled"      to s.likeEnabled,
            "follow_enabled"    to s.followEnabled,
            "join_duration"     to s.joinDuration,
            "like_duration"     to s.likeDuration,
            "follow_duration"   to s.followDuration,
            "chat_max_lines"    to s.chatMaxLines,
            "chat_width"        to settingsManager.getOverlayConfig("chat").width,
            "chat_duration"     to s.chatDuration,
            "chat_tts_enabled"  to s.chatTtsEnabled,
            "chat_tts_volume"   to s.chatTtsVolume,
            "chat_tts_max_length" to s.chatTtsMaxLength,
            "commands_enabled"  to s.commandsEnabled,
            "music_volume"      to s.musicVolume,
            "notif_volume"      to s.notifVolume,
            "use_tiktok_gift_icon" to s.useTiktokGiftIcon,
            "use_custom_gift_sound" to s.useCustomGiftSound
        )
    }

    // ── Notification ──────────────────────────────────────────────────
    private fun startServiceInForeground(): Boolean {
        return try {
            val notification = buildNotification("Starting…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Foreground service start not allowed (background restart tanpa user trigger): ${e.message}")
            } else {
                Log.e(TAG, "Failed to start foreground service", e)
            }
            // Tanpa status foreground yang valid, service ini akan di-kill sistem
            // dalam hitungan detik dan gak bisa jalanin audio/TikTok listener dengan
            // benar. Mending hentikan sekarang secara bersih daripada lanjut jalan
            // setengah-jadi.
            stopSelf()
            false
        }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL_ID, "YT Player",
            NotificationManager.IMPORTANCE_DEFAULT)
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
        val state = getStateMap()
        state.forEach { (k, v) ->
            when (v) {
                is String  -> intent.putExtra(k, v)
                is Boolean -> intent.putExtra(k, v)
                is Int     -> intent.putExtra(k, v)
                is Long    -> intent.putExtra(k, v)
            }
        }
        sendBroadcast(intent)

        // Notify AIDL Callbacks
        val n = remoteCallbacks.beginBroadcast()
        for (i in 0 until n) {
            try {
                val cb = remoteCallbacks.getBroadcastItem(i)
                cb.onPlaybackStatusChanged(isPlaying, positionMs, durationMs)
            } catch (e: RemoteException) {}
        }
        remoteCallbacks.finishBroadcast()
    }

    private fun notifyTrackChanged(song: Song?) {
        val n = remoteCallbacks.beginBroadcast()
        for (i in 0 until n) {
            try {
                val cb = remoteCallbacks.getBroadcastItem(i)
                cb.onTrackChanged(
                    song?.title ?: "",
                    song?.channel ?: "",
                    (song?.duration ?: 0).toString(),
                    song?.thumbnail ?: ""
                )
            } catch (e: RemoteException) {}
        }
        remoteCallbacks.finishBroadcast()
    }

    private fun notifyQueueChanged() {
        Log.d(TAG, "notifyQueueChanged: queue size = ${queue.size}")
        val n = remoteCallbacks.beginBroadcast()
        val json = gson.toJson(queue.toList())
        for (i in 0 until n) {
            try {
                remoteCallbacks.getBroadcastItem(i).onQueueChanged(json)
            } catch (e: RemoteException) {}
        }
        remoteCallbacks.finishBroadcast()
    }

    /**
     * Broadcast daftar Custom Web Overlay (id, name, url, dst) ke semua client AIDL
     * yang terdaftar (mis. NL Studio), setiap kali daftar overlay berubah atau
     * saat client secara eksplisit memintanya lewat requestCustomOverlays().
     */
    private fun notifyCustomOverlaysChanged() {
        val json = gson.toJson(customOverlayManager.getConfigs())
        Log.d(TAG, "notifyCustomOverlaysChanged: count=${customOverlayManager.getConfigs().size}")
        val n = remoteCallbacks.beginBroadcast()
        for (i in 0 until n) {
            try {
                remoteCallbacks.getBroadcastItem(i).onCustomOverlaysChanged(json)
            } catch (e: RemoteException) {}
        }
        remoteCallbacks.finishBroadcast()
    }

    private fun broadcastChat(chat: TikTokChat) {
        sendBroadcast(Intent(BROADCAST_CHAT).apply {
            putExtra("unique_id", chat.uniqueId)
            putExtra("nickname",  chat.nickname)
            putExtra("comment",   chat.comment)
            putExtra("cmd_type",  chat.commandType.name)
        })

        // Notify AIDL Callbacks
        val n = remoteCallbacks.beginBroadcast()
        for (i in 0 until n) {
            try {
                remoteCallbacks.getBroadcastItem(i).onChatMessage(chat.nickname, chat.comment)
            } catch (e: RemoteException) {}
        }
        remoteCallbacks.finishBroadcast()
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
        if (!settingsManager.settings.chatTtsEnabled || tts == null) return

        try {
            if (text.length > settingsManager.settings.chatTtsMaxLength) return
            val trimmed = text.trim()
            if (trimmed.startsWith("@")) return

            val cfg = buildCommandConfig()
            val isCommand = cfg.requestPrefixes.any { trimmed.startsWith(it, ignoreCase = true) } ||
                    cfg.skipPrefixes.any { trimmed.startsWith(it, ignoreCase = true) } ||
                    cfg.stopPrefixes.any { trimmed.startsWith(it, ignoreCase = true) } ||
                    cfg.queuePrefixes.any { trimmed.startsWith(it, ignoreCase = true) } ||
                    cfg.clearMusicPrefixes.any { trimmed.startsWith(it, ignoreCase = true) }

            if (isCommand) return

            val cleanedText = trimmed.replace("@", "")
            if (cleanedText.isBlank()) return

            val params = Bundle()
            val utteranceId = "chat_${System.currentTimeMillis()}"

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    if (id == utteranceId && (!ttsFile.exists() || ttsFile.length() == 0L)) {
                        serviceScope.launch(Dispatchers.Main) { applyDucking(true) }
                    }
                }
                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        serviceScope.launch(Dispatchers.Main) {
                            if (ttsFile.exists() && ttsFile.length() > 0) playTtsFile()
                            else applyDucking(false)
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    serviceScope.launch(Dispatchers.Main) { applyDucking(false) }
                }
            })

            val result = tts?.synthesizeToFile(cleanedText, params, ttsFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settingsManager.settings.chatTtsVolume.coerceIn(0f, 1f))
                tts?.speak(cleanedText, TextToSpeech.QUEUE_ADD, params, utteranceId)
            }
        } catch (e: Exception) {
            applyDucking(false)
        }
    }

    private fun applyDucking(enabled: Boolean) {
        isTtsSpeaking = enabled
        val musicVol = settingsManager.settings.musicVolume
        val ttsVol = settingsManager.settings.chatTtsVolume
        val volume = if (enabled) {
            val duckFactor = when {
                ttsVol >= 1.5f -> 0.85f
                ttsVol >= 1.0f -> 0.90f
                else -> 0.95f
            }
            musicVol * duckFactor
        } else musicVol
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
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setVolume(1.0f, 1.0f)
                setOnCompletionListener { applyDucking(false); cleanupPlayer() }
                setOnErrorListener { _, _, _ -> applyDucking(false); cleanupPlayer(); true }
                prepare()
                if (settingsManager.settings.chatTtsVolume > 1.0f) {
                    try {
                        ttsLoudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                            val targetGain = ((settingsManager.settings.chatTtsVolume - 1.0f) * 3000).toInt().coerceIn(0, 4000)
                            setTargetGain(targetGain)
                            enabled = true
                        }
                    } catch (_: Exception) {}
                }
                start()
            }
        } catch (e: Exception) {
            applyDucking(false); cleanupPlayer()
        }
    }

    private fun cleanupPlayer() {
        try {
            ttsLoudnessEnhancer?.release(); ttsLoudnessEnhancer = null
            ttsMediaPlayer?.release(); ttsMediaPlayer = null
            if (ttsFile.exists()) ttsFile.delete()
        } catch (_: Exception) {}
    }

    private fun buildCommandConfig(): TikTokLiveManager.CommandConfig {
        val s = settingsManager.settings
        return TikTokLiveManager.CommandConfig(
            enabled = s.commandsEnabled,
            requestPrefixes = s.cmdRequest.split(",").map { it.trim() }.filter { it.isNotBlank() },
            skipPrefixes = s.cmdSkip.split(",").map { it.trim() }.filter { it.isNotBlank() },
            stopPrefixes = s.cmdStop.split(",").map { it.trim() }.filter { it.isNotBlank() },
            queuePrefixes = s.cmdQueue.split(",").map { it.trim() }.filter { it.isNotBlank() },
            clearMusicPrefixes = s.cmdClearMusic.split(",").map { it.trim() }.filter { it.isNotBlank() }
        )
    }

    private fun buildTikTokManager(): TikTokLiveManager {
        val s = settingsManager.settings
        return TikTokLiveManager(s.tiktokApiKey, s.tiktokUsername, serviceScope).also { t ->
            t.setCommandConfig(buildCommandConfig())
            t.onChat = ::handleTikTokChat
            t.onLike = { nick, _, count, profile ->
                if (s.likeEnabled && System.currentTimeMillis() - tiktokConnectTime >= 5000) likeOverlayManager.showLike(nick, count, profile)

                // Notify AIDL
                val n = remoteCallbacks.beginBroadcast()
                for (i in 0 until n) {
                    try { remoteCallbacks.getBroadcastItem(i).onUserLiked(nick, profile, count) } catch (e: RemoteException) {}
                }
                remoteCallbacks.finishBroadcast()
            }
            t.onJoin = { nick, _, profile ->
                if (s.joinEnabled && System.currentTimeMillis() - tiktokConnectTime >= 5000) joinOverlayManager.showJoin(nick, profile)

                // Notify AIDL
                val n = remoteCallbacks.beginBroadcast()
                for (i in 0 until n) {
                    try { remoteCallbacks.getBroadcastItem(i).onUserJoined(nick, profile) } catch (e: RemoteException) {}
                }
                remoteCallbacks.finishBroadcast()
            }
            t.onFollow = { nick, _, profile ->
                if (s.followEnabled && System.currentTimeMillis() - tiktokConnectTime >= 5000) followOverlayManager.showFollow(nick, profile)

                // Notify AIDL
                val n = remoteCallbacks.beginBroadcast()
                for (i in 0 until n) {
                    try { remoteCallbacks.getBroadcastItem(i).onUserFollowed(nick, profile) } catch (e: RemoteException) {}
                }
                remoteCallbacks.finishBroadcast()
            }
            t.onGift = { uid, nick, gift, count, giftId, iconUrl ->
                broadcastSystemChat("$nick mengirim $gift x$count")
                if (s.notifEnabled && System.currentTimeMillis() - tiktokConnectTime >= 5000) {
                    notifOverlayManager.showNotification(nick, "mengirim $gift x$count", "gift", giftIconUrl = iconUrl, giftName = gift, giftId = giftId)
                }

                // Notify AIDL Callbacks
                val n = remoteCallbacks.beginBroadcast()
                for (i in 0 until n) {
                    try {
                        remoteCallbacks.getBroadcastItem(i).onGiftMessage(nick, gift, iconUrl, count)
                    } catch (e: RemoteException) {}
                }
                remoteCallbacks.finishBroadcast()
            }
            t.onShare = { _, nick, profile ->
                broadcastSystemChat("$nick membagikan live")
                if (s.notifEnabled && System.currentTimeMillis() - tiktokConnectTime >= 5000) notifOverlayManager.showNotification(nick, "membagikan live", "share")

                // Notify AIDL
                val n = remoteCallbacks.beginBroadcast()
                for (i in 0 until n) {
                    try { remoteCallbacks.getBroadcastItem(i).onUserShared(nick, profile) } catch (e: RemoteException) {}
                }
                remoteCallbacks.finishBroadcast()
            }
            t.onConnected = {
                tiktokConnected = true; chatOverlayManager.setTikTokConnected(true); tiktokConnecting = false
                tiktokConnectTime = System.currentTimeMillis(); overlayManager.setLiveStatus(true)
                broadcastSystemChat("Connected to TikTok Live @${s.tiktokUsername}"); broadcastState()

                // Notify AIDL Callbacks
                val n = remoteCallbacks.beginBroadcast()
                for (i in 0 until n) {
                    try {
                        remoteCallbacks.getBroadcastItem(i).onTikTokStatus(true, s.tiktokUsername)
                    } catch (e: RemoteException) {}
                }
                remoteCallbacks.finishBroadcast()
            }
            t.onDisconnected = {
                tiktokConnected = false; chatOverlayManager.setTikTokConnected(false); tiktokConnecting = false
                overlayManager.setLiveStatus(false); broadcastSystemChat("Disconnected from TikTok Live"); broadcastState()

                // Notify AIDL Callbacks
                val n = remoteCallbacks.beginBroadcast()
                for (i in 0 until n) {
                    try {
                        remoteCallbacks.getBroadcastItem(i).onTikTokStatus(false, s.tiktokUsername)
                    } catch (e: RemoteException) {}
                }
                remoteCallbacks.finishBroadcast()
            }
            t.onConnecting = { tiktokConnecting = true; broadcastState() }
            t.onError = {
                if (!it.contains("Retrying", ignoreCase = true)) tiktokConnecting = false
                broadcastSystemChat("Error: $it"); broadcastState()
            }
        }
    }
}
