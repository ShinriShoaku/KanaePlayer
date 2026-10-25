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

package ame.project.kanae

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.*
import androidx.annotation.Keep
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ame.project.kanae.canvas.CanvasActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import ame.project.kanae.databinding.ActivityMainBinding
import ame.project.kanae.databinding.BottomSheetOverlaysBinding
import ame.project.kanae.model.Song
import ame.project.kanae.service.PlayerForegroundService
import ame.project.kanae.tiktok.TikTokLiveManager
import ame.project.kanae.tiktok.GiftSoundActivity
import ame.project.kanae.overlay.CustomWebOverlaySettingsActivity
import ame.project.kanae.player.FastPlaybackState
import android.content.res.Configuration
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition as GlideTransition
import com.google.android.material.bottomsheet.BottomSheetBehavior

class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "MainActivity" }

    private lateinit var binding: ActivityMainBinding
    private var overlaySheetBinding: BottomSheetOverlaysBinding? = null
    private var overlaySheetDialog: BottomSheetDialog? = null

    private var service: PlayerForegroundService? = null
    private var serviceBound = false
    private lateinit var settingsManager: SettingsManager

    private var lastSongJson: String? = null
    private var lastSyncTime: Long = 0L
    private var currentNotifs: List<NotificationItem> = emptyList()
    private var notifPollingJob: Job? = null
    private var liveReportTickerJob: Job? = null
    private var tiktokConnected = false          // sesi live dianggap aktif hanya kalau TikTok benar-benar terhubung
    private var disconnectedAtMs = 0L            // waktu TikTok terputus, dipakai untuk freeze durasi sebelum sesi ditutup service

    // ── Metro / Iconic now-playing tile ──
    private var tileExpanded = false
    private var tileColor = 0
    private var artTarget: CustomTarget<Bitmap>? = null
    private lateinit var tileBackCallback: OnBackPressedCallback

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlayerForegroundService.LocalBinder).getService()
            serviceBound = true
            Log.d(TAG, "Service connected")
            syncUi(force = true)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            serviceBound = false
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                PlayerForegroundService.BROADCAST_STATE -> {
                    syncUi()
                    // Request selesai / gift / coin: update instan tanpa nunggu tick
                    if (isLiveReportOpen()) {
                        renderLiveReport()
                        if (liveReportTickerJob?.isActive != true) refreshLiveReportTicker()
                    }
                }
                PlayerForegroundService.BROADCAST_SERVICE_READY -> syncUi(force = true)
                PlayerForegroundService.BROADCAST_CHAT  -> {
                    val uid  = intent.getStringExtra("unique_id") ?: ""
                    val nick = intent.getStringExtra("nickname") ?: return
                    val msg  = intent.getStringExtra("comment")  ?: return
                    val type = intent.getStringExtra("cmd_type") ?: ""
                    val prefix = when (type) {
                        "REQUEST"     -> "🎵"
                        "SKIP"        -> "⏭"
                        "STOP"        -> "⏹"
                        "QUEUE"       -> "📋"
                        "CLEAR_MUSIC" -> "🗑"
                        else          -> if (uid == "system") "ℹ️" else "💬"
                    }
                    val displayNick = if (uid == "system") nick else "@$nick"
                    addChatLine("$prefix $displayNick: $msg")
                }
            }
        }
    }

    private val queueAdapter = QueueAdapter(
        onRemove = { pos -> service?.removeFromQueue(pos) },
        onPlay   = { song -> service?.playSong(song) }
    )

    private val pickShareImg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsManager.settings.notifShareImg = it.toString()
            settingsManager.saveSettings()
            updateNotifToService("share")
        }
    }
    private val pickGiftImg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsManager.settings.notifGiftImg = it.toString()
            settingsManager.saveSettings()
            updateNotifToService("gift")
        }
    }
    private val pickShareAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsManager.settings.notifShareAud = it.toString()
            settingsManager.saveSettings()
            updateNotifToService("share")
        }
    }
    private val pickGiftAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsManager.settings.notifGiftAud = it.toString()
            settingsManager.saveSettings()
            updateNotifToService("gift")
        }
    }
    private val pickJoinAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsManager.settings.notifJoinAud = it.toString()
            settingsManager.saveSettings()
            updateNotifToService("join")
        }
    }
    private val pickFollowAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsManager.settings.notifFollowAud = it.toString()
            settingsManager.saveSettings()
            updateNotifToService("follow")
        }
    }

    private val requestVoicePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            service?.toggleVoiceOverlay()
            syncUi(true)
        } else {
            snack("Izin mikrofon diperlukan untuk Voice Overlay")
        }
    }

    private fun updateNotifToService(type: String? = null) {
        val s = settingsManager.settings
        service?.updateNotifConfig(
            s.notifShareImg,
            s.notifGiftImg,
            s.notifShareAud,
            s.notifGiftAud,
            s.notifJoinAud,
            s.notifFollowAud,
            s.notifDuration,
            type
        )
        syncNotifSettingsButtons()
    }

    private fun syncNotifSettingsButtons() {
        val b = overlaySheetBinding ?: return
        val s = settingsManager.settings

        fun updateBtnText(btn: Button, uriStr: String?, defaultRes: Int) {
            if (uriStr != null) {
                val name = Uri.parse(uriStr).path?.split("/")?.lastOrNull() ?: "Selected"
                btn.text = name
            } else {
                btn.text = getString(defaultRes)
            }
        }

        updateBtnText(b.extraNotifSettings.btnSelectShareImg, s.notifShareImg, R.string.btn_image)
        updateBtnText(b.extraNotifSettings.btnSelectGiftImg, s.notifGiftImg, R.string.btn_image)
        updateBtnText(b.extraNotifSettings.btnSelectShareAudio, s.notifShareAud, R.string.btn_audio)
        updateBtnText(b.extraNotifSettings.btnSelectGiftAudio, s.notifGiftAud, R.string.btn_audio)
        updateBtnText(b.extraJoinSettings.btnSelectJoinAudio, s.notifJoinAud, R.string.btn_audio)
        updateBtnText(b.extraFollowSettings.btnSelectFollowAudio, s.notifFollowAud, R.string.btn_audio)
    }

    // ─────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setupRecyclerView()
        setupButtons()
        setupMetroUi()
        loadSavedSettings()

        checkForUpdates()

        startForegroundService(Intent(this, PlayerForegroundService::class.java))
        bindService(
            Intent(this, PlayerForegroundService::class.java),
            connection, BIND_AUTO_CREATE
        )

        val filter = IntentFilter().apply {
            addAction(PlayerForegroundService.BROADCAST_STATE)
            addAction(PlayerForegroundService.BROADCAST_CHAT)
            addAction(PlayerForegroundService.BROADCAST_SERVICE_READY)
        }
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        startNotifPolling()
        refreshLiveReportTicker()
    }

    override fun onPause() {
        super.onPause()
        stopNotifPolling()
        stopLiveReportTicker()
    }

    private fun startNotifPolling() {
        stopNotifPolling()
        notifPollingJob = lifecycleScope.launch {
            while (isActive) {
                loadDevNoteNotifications()
                delay(15_000)
            }
        }
    }

    private fun stopNotifPolling() {
        notifPollingJob?.cancel()
        notifPollingJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLiveReportTicker()
        tiktokConnected = false
        artTarget?.let { Glide.with(applicationContext).clear(it) }
        artTarget = null
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
            service = null
        }
        unregisterReceiver(stateReceiver)
    }

    // ── Permissions ───────────────────────────────────────────────────
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            binding.tvOverlayWarning.visibility = View.VISIBLE
            binding.btnGrantOverlay.visibility  = View.VISIBLE
        }
        checkBatteryOptimization()
    }

    /**
     * Kalau OS masih membatasi baterai/CPU app ini, overlay auto-hide (dan chat
     * TikTok) bisa telat update saat user lagi main game lain di HP yang sama —
     * karena OEM (MIUI/ColorOS/FuntouchOS/dll) membekukan jatah CPU background
     * app selagi ada input sentuh aktif di app lain. Minta exemption di sini
     * supaya sistem Android setidaknya tidak menambah pembatasan Doze/standby.
     */
    private fun checkBatteryOptimization() {
        val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val alreadyIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
        crashlytics.setCustomKey("battery_opt_ignored", alreadyIgnoring)
        if (alreadyIgnoring) return

        val prefs = getSharedPreferences("kanae_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("battery_opt_dialog_dismissed", false)) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Izinkan Berjalan Tanpa Batasan")
            .setMessage(
                "Supaya overlay (follow, quick custom, dll) tidak telat " +
                        "hilang/update saat kamu lagi main game di HP ini, aplikasi " +
                        "perlu dikecualikan dari battery optimization.\n\n" +
                        "Kalau HP kamu Xiaomi/Oppo/Vivo/Samsung, cek juga menu " +
                        "Game Turbo / Game Space / Game Assistant lalu keluarkan " +
                        "KanaePlayer dari daftar app yang dibatasi saat mode game."
            )
            .setPositiveButton("Izinkan") { _, _ ->
                crashlytics.setCustomKey("battery_opt_user_response", "accepted")
                try {
                    batteryOptLauncher.launch(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    // Sebagian ROM (mis. MIUI) menolak intent ini; arahkan ke halaman
                    // detail app sebagai fallback supaya user tetap bisa atur manual.
                    crashlytics.recordException(e)
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("Nanti") { _, _ ->
                crashlytics.setCustomKey("battery_opt_user_response", "dismissed")
                prefs.edit().putBoolean("battery_opt_dialog_dismissed", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    private val batteryOptLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .setCustomKey("battery_opt_ignored", pm.isIgnoringBatteryOptimizations(packageName))
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) snack("Notification permission denied")
        }

    private fun requestOverlayPermission() {
        overlayPermissionLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                binding.tvOverlayWarning.visibility = View.GONE
                binding.btnGrantOverlay.visibility  = View.GONE
                syncUi()
                snack("Overlay permission granted!")
            }
        }

    // ── Metro / Iconic UI ─────────────────────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Satu tile yang bisa expand di tempat (Add / Up Next / Chat / Connect / Config). */
    private class Section(
        val tile: View,
        val header: ViewGroup,
        val body: View,
        val sibling: View?,     // tile pasangan satu baris; disembunyikan saat tile ini melebar
        val wide: Boolean,
        val collapsedDp: Int
    )

    private val sections = mutableListOf<Section>()
    private var openSection: Section? = null

    private fun contentRoot() = binding.root.getChildAt(0) as ViewGroup

    private fun metroTransition() = TransitionSet()
        .addTransition(ChangeBounds())
        .addTransition(Fade())
        .setDuration(320)
        .setInterpolator(DecelerateInterpolator(1.5f))

    private fun updateBackCallback() {
        tileBackCallback.isEnabled = tileExpanded || openSection != null
    }

    private fun setupMetroUi() {
        tileColor = ContextCompat.getColor(this, R.color.metro_accent)
        binding.tileNowPlaying.setBackgroundColor(tileColor)

        // Ketuk tile Now Playing -> expand jadi kotak Now Playing; ketuk lagi -> collapse
        binding.tileNowPlaying.setOnClickListener { setTileExpanded(!tileExpanded) }

        // Tombol Back menutup tile yang sedang terbuka dulu sebelum keluar app
        tileBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val open = openSection
                if (open != null) toggleSection(open) else setTileExpanded(false)
            }
        }
        onBackPressedDispatcher.addCallback(this, tileBackCallback)

        // Tile bergaya Start screen yang expand di tempat
        sections += Section(binding.tileAddQueue, binding.hdrAddQueue, binding.bodyAddQueue, binding.tileUpNext, false, 160)
        sections += Section(binding.tileUpNext, binding.hdrUpNext, binding.bodyUpNext, binding.tileAddQueue, false, 160)
        sections += Section(binding.tileChat, binding.hdrChat, binding.bodyChat, binding.tileConnect, false, 160)
        sections += Section(binding.tileConnect, binding.hdrConnect, binding.bodyConnect, binding.tileChat, false, 160)
        sections += Section(binding.tileLiveReport, binding.hdrLiveReport, binding.bodyLiveReport, null, true, 80)
        sections += Section(binding.tileConfig, binding.hdrConfig, binding.bodyConfig, null, true, 96)
        sections.forEach { s ->
            styleHeader(s, false)
            s.header.setOnClickListener { toggleSection(s) }
            Metro.tilt(s.header)
        }

        binding.btnExpandReportInfo.setOnClickListener {
            val isVis = binding.bodyLiveReportExpanded.visibility == View.VISIBLE
            binding.bodyLiveReportExpanded.visibility = if (isVis) View.GONE else View.VISIBLE
            binding.btnExpandReportInfo.text = if (isVis) "Expand Info" else "Sembunyikan Info"
            // Isi field detail langsung saat panel dibuka
            if (!isVis) renderLiveReport()
        }

        binding.btnOpenFullReport.setOnClickListener {
            val intent = Intent(this, ame.project.kanae.analytics.LiveReportActivity::class.java)
            startActivity(intent)
        }

        // Efek tilt khas Windows Phone di tile lain & tombol
        listOf<View>(
            binding.tileNowPlaying, binding.btnAdminList, binding.btnShowOverlays,
            binding.btnPlayPause, binding.btnSkip, binding.btnStop, binding.btnShuffle,
            binding.btnAddToQueue, binding.btnClearQueue, binding.btnConnectTiktok,
            binding.btnSaveSettings, binding.btnLangId, binding.btnLangEn, binding.btnGrantOverlay,
            binding.btnDevNote, binding.btnFeedback, binding.btnExpandReportInfo, binding.btnOpenFullReport
        ).forEach { Metro.tilt(it) }
    }

    /** Header tile: ikon di tengah + label kiri-bawah saat collapsed; jadi baris ikon + judul saat expanded. */
    private fun styleHeader(s: Section, expanded: Boolean) {
        val compact = expanded || s.wide
        val ic = s.header.getChildAt(0)
        // child ke-1 bisa TextView langsung, atau wrapper (LinearLayout) berisi TextView
        val lblView = s.header.getChildAt(1)
        val lbl = (lblView as? TextView) ?: findFirstTextView(lblView) ?: return

        s.header.layoutParams.height = dp(if (expanded) 72 else s.collapsedDp)

        (ic.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = dp(if (compact) 32 else 48)
            height = width
            gravity = if (compact) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
            val ms = if (compact) dp(20) else 0
            leftMargin = ms
            marginStart = ms
        }
        // Atur layoutParams pada view yang benar-benar child dari header (wrapper, bukan TextView di dalamnya)
        (lblView.layoutParams as? FrameLayout.LayoutParams)?.apply {
            gravity = if (compact) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.BOTTOM
            val ms = dp(if (compact) 72 else 12)
            val bm = if (compact) 0 else dp(10)
            leftMargin = ms
            marginStart = ms
            bottomMargin = bm
        }
        lbl.textSize = if (compact) 22f else 14f
        ic.requestLayout()
        lblView.requestLayout()
        s.header.requestLayout()
    }

    private fun findFirstTextView(v: View): TextView? {
        if (v is TextView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findFirstTextView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun applySection(s: Section, expand: Boolean) {
        s.body.visibility = if (expand) View.VISIBLE else View.GONE
        s.sibling?.visibility = if (expand) View.GONE else View.VISIBLE
        styleHeader(s, expand)
    }

    /** Accordion: membuka satu tile otomatis menutup tile lain. */
    private fun toggleSection(s: Section) {
        val open = openSection
        TransitionManager.beginDelayedTransition(contentRoot(), metroTransition())
        if (open != null && open !== s) applySection(open, false)
        val expand = open !== s
        applySection(s, expand)
        openSection = if (expand) s else null
        updateBackCallback()
        refreshLiveReportTicker()
        if (expand) scrollToTile(s.tile)
    }

    private fun scrollToTile(v: View) {
        binding.root.postDelayed({
            val r = Rect(0, 0, v.width, v.height)
            binding.root.offsetDescendantRectToMyCoords(v, r)
            binding.root.smoothScrollTo(0, r.top - dp(12))
        }, 350)
    }

    private fun setTileExpanded(expand: Boolean) {
        if (tileExpanded == expand) return
        tileExpanded = expand
        updateBackCallback()

        TransitionManager.beginDelayedTransition(contentRoot(), metroTransition())
        binding.nowPlayingExpanded.visibility = if (expand) View.VISIBLE else View.GONE
        binding.ivChevron.animate().rotation(if (expand) 180f else 0f).setDuration(300).start()
        if (expand) scrollToTile(binding.tileNowPlaying)
    }

    private fun animateTileColor(to: Int) {
        val from = tileColor
        if (from == to) return
        tileColor = to
        ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 600
            addUpdateListener { binding.tileNowPlaying.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    /** Muat cover lagu ke tile (kecil + besar) dan ubah warna tile mengikuti cover. */
    private fun loadNowPlayingArt(url: String?) {
        artTarget?.let { Glide.with(this).clear(it) }
        artTarget = null
        val accent = ContextCompat.getColor(this, R.color.metro_accent)

        if (url.isNullOrBlank()) {
            binding.ivNowPlayingArt.setImageDrawable(null)
            binding.ivNowPlayingArtLarge.setImageDrawable(null)
            animateTileColor(accent)
            return
        }
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: GlideTransition<in Bitmap>?) {
                listOf(binding.ivNowPlayingArt, binding.ivNowPlayingArtLarge).forEach { iv ->
                    iv.setImageBitmap(resource)
                    iv.alpha = 0f
                    iv.animate().alpha(1f).setDuration(350).start()
                }
                animateTileColor(Metro.accentFromBitmap(resource, accent))
            }
            override fun onLoadCleared(placeholder: Drawable?) {}
        }
        artTarget = target
        Glide.with(this).asBitmap().load(url).override(640, 640).into(target)
    }

    // ── RecyclerView ──────────────────────────────────────────────────
    private fun setupRecyclerView() {
        binding.rvQueue.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = queueAdapter
        }
    }

    // ── Button wiring ─────────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnPlayPause.setOnClickListener { service?.togglePlayPause() }
        binding.btnSkip.setOnClickListener     { service?.playNext() }
        binding.btnStop.setOnClickListener     { service?.stopPlayer() }
        binding.btnClearQueue.setOnClickListener { service?.clearQueue() }
        binding.btnShuffle.setOnClickListener  {
            val on = service?.toggleShuffle() ?: false
            binding.btnShuffle.text = if (on) "shuffle on" else "shuffle off"
        }

        binding.btnAddToQueue.setOnClickListener {
            val url = binding.etYoutubeUrl.text.toString().trim()
            if (url.isBlank()) { snack("Enter a YouTube URL"); return@setOnClickListener }
            service?.addToQueue(url)
            binding.etYoutubeUrl.text?.clear()
            snack("Adding to queue…")
            syncUi(force = true)
        }

        binding.btnShowOverlays.setOnClickListener {
            showOverlaysBottomSheet()
        }

        binding.btnAdminList.setOnClickListener {
            startActivity(Intent(this, AdminListActivity::class.java))
        }

        binding.btnGrantOverlay.setOnClickListener { requestOverlayPermission() }

        binding.btnFeedback.setOnClickListener { showFeedbackDialog() }
        binding.btnDevNote.setOnClickListener { showDevNoteDialog() }

        binding.sbMusicVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvMusicVolume.text = "$progress%"
                    service?.updateMusicVolume(progress.toFloat() / 100f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbEnableCommands.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val enabled = progress == 1
                    binding.tvEnableCommandsLabel.alpha = if (enabled) 1.0f else 0.5f
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnLangId.setOnClickListener {
            binding.etLyricsLang.setText("id")
            settingsManager.settings.lyricsLang = "id"
            settingsManager.saveSettings()
            snack("Lyrics language: Indonesian")
        }
        binding.btnLangEn.setOnClickListener {
            binding.etLyricsLang.setText("en")
            settingsManager.settings.lyricsLang = "en"
            settingsManager.saveSettings()
            snack("Lyrics language: English")
        }

        binding.btnConnectTiktok.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val state    = svc.getStateMap()
            val tiktokOk = state["tiktok_connected"] as? Boolean ?: false

            if (tiktokOk) {
                svc.saveSettings("", "")
                snack("Disconnecting TikTok…")
                return@setOnClickListener
            }

            val apiKey   = binding.etApiKey.text.toString().trim()
            val username = binding.etTiktokUser.text.toString().trim()

            if (apiKey.isBlank() || username.isBlank()) {
                snack("API Key and TikTok username are required")
                return@setOnClickListener
            }

            val limit = binding.etRequestLimit.text.toString().toIntOrNull() ?: 3
            val cmdConfig = buildCommandConfig()

            svc.saveSettings(apiKey, username, limit, cmdConfig)
            saveSettingsToPrefs(apiKey, username, limit, cmdConfig)

            syncUi(true)
            snack("Connecting TikTok Live…")
        }

        binding.btnSaveSettings.setOnClickListener {
            val svc = service ?: return@setOnClickListener

            val limitStr = binding.etRequestLimit.text.toString().trim()
            val limit    = limitStr.toIntOrNull() ?: 3
            val cmdConfig = buildCommandConfig()
            val lyricsLang = binding.etLyricsLang.text.toString().trim().ifBlank { "id" }

            settingsManager.settings.lyricsLang = lyricsLang
            settingsManager.saveSettings()

            val apiKey   = binding.etApiKey.text.toString().trim()
            val username = binding.etTiktokUser.text.toString().trim()

            svc.saveSettings(apiKey, username, limit, cmdConfig)
            saveSettingsToPrefs(apiKey, username, limit, cmdConfig)

            snack("Configuration updated!")
        }
    }

    private fun loadSavedSettings() {
        val s = settingsManager.settings
        binding.etApiKey.setText(s.tiktokApiKey)
        binding.etTiktokUser.setText(s.tiktokUsername)
        binding.etRequestLimit.setText(s.requestLimit.toString())

        binding.sbEnableCommands.progress = if (s.commandsEnabled) 1 else 0
        binding.tvEnableCommandsLabel.alpha = if (s.commandsEnabled) 1.0f else 0.5f

        // Handle Wit.ai Token if sheet is open, but wait, loadSavedSettings is for main UI.
        // The token is in the BottomSheet. So I should load it in openSettings("voice", ...).

        binding.etCmdRequest.setText(s.cmdRequest)
        binding.etCmdSkip.setText(s.cmdSkip)
        binding.etCmdStop.setText(s.cmdStop)
        binding.etCmdQueue.setText(s.cmdQueue)
        binding.etCmdClearMusic.setText(s.cmdClearMusic)
        binding.etCmdLyric.setText(s.cmdLyric)
        binding.etLyricsLang.setText(s.lyricsLang)
    }

    private fun saveSettingsToPrefs(
        apiKey: String,
        username: String,
        limit: Int = 3,
        cmdConfig: TikTokLiveManager.CommandConfig? = null
    ) {
        settingsManager.settings.apply {
            tiktokApiKey = apiKey
            tiktokUsername = username
            requestLimit = limit
            cmdConfig?.let { c ->
                commandsEnabled = c.enabled
                cmdRequest = c.requestPrefixes.joinToString(",")
                cmdSkip = c.skipPrefixes.joinToString(",")
                cmdStop = c.stopPrefixes.joinToString(",")
                cmdQueue = c.queuePrefixes.joinToString(",")
                cmdClearMusic = c.clearMusicPrefixes.joinToString(",")
                cmdLyric = c.lyricPrefixes.joinToString(",")
            }
        }
        settingsManager.saveSettings()
    }

    private fun buildCommandConfig(): TikTokLiveManager.CommandConfig {
        fun field(text: String) = text.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return TikTokLiveManager.CommandConfig(
            enabled            = binding.sbEnableCommands.progress == 1,
            requestPrefixes    = field(binding.etCmdRequest.text.toString()),
            skipPrefixes       = field(binding.etCmdSkip.text.toString()),
            stopPrefixes       = field(binding.etCmdStop.text.toString()),
            queuePrefixes      = field(binding.etCmdQueue.text.toString()),
            clearMusicPrefixes = field(binding.etCmdClearMusic.text.toString()),
            lyricPrefixes      = field(binding.etCmdLyric.text.toString())
        )
    }

    // ── Update Checker ───────────────────────────────────────────────
    @Keep
    private data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val updateMessage: String
    )

    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val updateUrl = if (isDebug) {
                    "https://raw.githubusercontent.com/ShinriShoaku/KanaePlayer/master/version.json"
                } else {
                    "https://raw.githubusercontent.com/ShinriShoaku/KanaePlayer/master/klukai.json"
                }

                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(updateUrl)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@launch
                    val body = response.body?.string() ?: return@launch
                    val updateInfo = Gson().fromJson(body, UpdateInfo::class.java)

                    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageInfo(packageName, 0)
                    }

                    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toLong()
                    }
                    val currentVersionName = packageInfo.versionName ?: ""

                    val isNewerCode = updateInfo.versionCode > currentVersionCode
                    val isDifferentName = (updateInfo.versionCode.toLong() == currentVersionCode && updateInfo.versionName != currentVersionName)

                    if (isNewerCode || isDifferentName) {
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(updateInfo)
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Update Baru Tersedia!")
            .setIcon(R.mipmap.ic_launcher)
            .setMessage("Versi ${info.versionName} sudah tersedia.\n\nUpdate kali ini:\n${info.updateMessage}")
            .setPositiveButton("Update Sekarang") { _, _ ->
                val url = "https://github.com/ShinriShoaku/KanaePlayer/releases"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    snack("Gagal membuka browser")
                }
            }
            .setNegativeButton("Nanti", null)
            .setCancelable(false)
            .show()
    }

    private fun showOverlaysBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetOverlaysBinding.inflate(layoutInflater)
        overlaySheetBinding = sheetBinding
        overlaySheetDialog = dialog
        dialog.setContentView(sheetBinding.root)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(ContextCompat.getColor(this, R.color.metro_black))

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                val layoutParams = it.layoutParams
                layoutParams.width = resources.displayMetrics.widthPixels / 2
                it.layoutParams = layoutParams
                it.translationX = (resources.displayMetrics.widthPixels / 4).toFloat()
            }
        }

        setupBottomSheetButtons(sheetBinding, dialog)

        dialog.setOnDismissListener {
            if (currentConfigKey == "chat") service?.hideChatDummy()
            if (currentConfigKey == "notif") service?.hideNotif()
            if (currentConfigKey == "queue") {
                service?.updateQueueAutoHide(settingsManager.settings.queueAutoHide)
            }
            overlaySheetBinding = null
            overlaySheetDialog = null
            currentConfigKey = ""
        }

        syncUi(true)
        dialog.show()
    }

    private var currentConfigKey = ""

    private fun setupBottomSheetButtons(b: BottomSheetOverlaysBinding, dialog: BottomSheetDialog) {
        fun updateRealtime(activeSeekBar: SeekBar? = null) {
            val isPosSlider = activeSeekBar == b.sbPosX || activeSeekBar == b.sbPosY
            val x = if (isPosSlider) b.sbPosX.progress else -1
            val y = if (isPosSlider) b.sbPosY.progress else -1
            val scale = b.sbScale.progress
            val rawW = b.sbWidth.progress
            val w = if (rawW == 0) 0 else if (rawW < 200) 200 else rawW
            val h = b.sbHeight.progress

            val isMaxWidthType = currentConfigKey == "player" || currentConfigKey == "queue" ||
                    currentConfigKey == "lyrics" || currentConfigKey == "chat"

            if (isMaxWidthType) {
                b.tvWidthLabel.text = if (w > 0) "Max Width: ${w}dp" else "Max Width: Auto"
            } else {
                b.tvWidthLabel.text = if (w > 0) "Width: ${w}dp" else "Width: Auto"
            }
            b.tvHeightLabel.text = if (h > 0) "Height: ${h}dp" else "Height: Auto"

            service?.applyOverlayConfig(currentConfigKey, x, y, scale.toFloat() / 100f, w, h)
            if (activeSeekBar == b.sbWidth) service?.showWidthPreview(currentConfigKey, w)
        }

        val seekBarListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) updateRealtime(s) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                if (s == b.sbWidth) service?.hideWidthPreview(currentConfigKey)
            }
        }
        b.sbPosX.setOnSeekBarChangeListener(seekBarListener)
        b.sbPosY.setOnSeekBarChangeListener(seekBarListener)
        b.sbScale.setOnSeekBarChangeListener(seekBarListener)
        b.sbWidth.setOnSeekBarChangeListener(seekBarListener)
        b.sbHeight.setOnSeekBarChangeListener(seekBarListener)

        b.cbVisualPunch.setOnCheckedChangeListener { _, isChecked ->
            if (currentConfigKey.isNotEmpty()) service?.updateVisualPunchEnabled(currentConfigKey, isChecked)
        }

        b.extraChatSettings.sbMaxLines.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraChatSettings.tvMaxLinesLabel.text = "Max Lines: $p"
                    service?.updateChatMaxLines(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.extraChatSettings.cbChatTransparent.setOnCheckedChangeListener { _, isChecked -> service?.updateChatTransparency(isChecked) }
        b.extraChatSettings.cbChatAlwaysShow.setOnCheckedChangeListener { _, isChecked -> service?.updateChatAlwaysShow(isChecked) }
        b.extraChatSettings.cbChatHistory.setOnCheckedChangeListener { _, isChecked -> service?.updateChatHistory(isChecked) }

        b.extraChatSettings.sbChatDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraChatSettings.tvChatDurationLabel.text = "Duration: ${p}s"
                    service?.updateChatDuration(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.extraChatSettings.cbChatTts.setOnCheckedChangeListener { _, isChecked -> service?.updateTtsEnabled(isChecked) }

        b.extraChatSettings.sbTtsVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraChatSettings.tvTtsVolumeLabel.text = "Volume: $p%"
                    service?.updateTtsVolume(p.toFloat() / 100f)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.extraChatSettings.sbTtsMaxLength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraChatSettings.tvTtsMaxLengthLabel.text = "Max Len: $p"
                    service?.updateTtsMaxLength(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.extraQueueSettings.cbQueueAutoHide.setOnCheckedChangeListener { _, isChecked -> service?.updateQueueAutoHide(isChecked) }

        b.extraQueueSettings.sbQueueDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraQueueSettings.tvQueueDurationLabel.text = "Duration: ${p}s"
                    service?.updateQueueDuration(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.extraJoinSettings.sbJoinDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraJoinSettings.tvJoinDurationLabel.text = "Duration: ${p}s"
                    service?.updateJoinDuration(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.extraLikeSettings.sbLikeDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraLikeSettings.tvLikeDurationLabel.text = "Duration: ${p}s"
                    service?.updateLikeDuration(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.extraFollowSettings.sbFollowDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraFollowSettings.tvFollowDurationLabel.text = "Duration: ${p}s"
                    service?.updateFollowDuration(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.extraVoiceSettings.cbVoiceOnline.setOnCheckedChangeListener { _, isChecked -> service?.updateVoiceOnline(isChecked) }
        b.extraVoiceSettings.cbVoiceContinuous.setOnCheckedChangeListener { _, isChecked -> service?.updateVoiceContinuous(isChecked) }

        val langCodes = listOf("id-ID", "en-US", "ja-JP", "ms-MY", "zh-CN")
        val langNames = listOf("Indonesia (id-ID)", "English (en-US)", "Japanese (ja-JP)", "Malay (ms-MY)", "Chinese (zh-CN)")
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        b.extraVoiceSettings.spVoiceLanguage.adapter = langAdapter
        b.extraVoiceSettings.spVoiceLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                service?.updateVoiceLanguage(langCodes[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        b.extraVoiceSettings.sbVoiceTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraVoiceSettings.tvVoiceTextSizeLabel.text = "Text Size: ${p}sp"
                    service?.updateVoiceTextSize(p.toFloat())
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        b.extraVoiceSettings.sbVoiceDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraVoiceSettings.tvVoiceDurationLabel.text = "Auto Hide: ${p}s"
                    service?.updateVoiceDuration(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        fun openSettings(key: String, title: String) {
            currentConfigKey = key
            b.panelGrid.root.visibility = View.GONE
            b.panelSettings.visibility = View.VISIBLE
            b.tvSettingsTitle.text = "$title Settings"
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

            val config = settingsManager.getOverlayConfig(key)
            val s = settingsManager.settings

            b.sbPosX.progress = config.x
            b.sbPosY.progress = config.y
            b.sbScale.progress = (config.scale * 100).toInt()

            var w = config.width
            if (w > 0 && w < 200) w = 200
            val h = config.height

            b.sbWidth.progress = w
            b.sbHeight.progress = h
            b.tvWidthLabel.text = if (w > 0) "Width: ${w}dp" else "Width: Auto"
            b.tvHeightLabel.text = if (h > 0) "Height: ${h}dp" else "Height: Auto"

            if (key == "player" || key == "queue" || key == "lyrics" || key == "chat") {
                b.sizeSettingsContainer.visibility = View.VISIBLE
                b.tvWidthLabel.text = "Max Width: ${if (w > 0) "${w}dp" else "Auto"}"
                b.sbWidth.visibility = View.VISIBLE
                b.tvHeightLabel.visibility = View.GONE
                b.sbHeight.visibility = View.GONE
            } else {
                if (key == "notif" || key == "join" || key == "like" || key == "follow") {
                    b.sizeSettingsContainer.visibility = View.GONE
                } else {
                    b.sizeSettingsContainer.visibility = View.VISIBLE
                    b.tvWidthLabel.visibility = View.VISIBLE
                    b.sbWidth.visibility = View.VISIBLE
                    b.tvHeightLabel.visibility = View.VISIBLE
                    b.sbHeight.visibility = View.VISIBLE
                }
            }

            b.cbVisualPunch.isChecked = config.visualPunch
            b.cbVisualPunch.visibility = if (key == "notif") View.GONE else View.VISIBLE
            b.posSettingsContainer.visibility = View.GONE

            b.extraChatSettings.root.visibility = View.GONE
            b.extraQueueSettings.root.visibility = View.GONE
            b.extraNotifSettings.root.visibility = View.GONE
            b.extraLikeSettings.root.visibility = View.GONE
            b.extraJoinSettings.root.visibility = View.GONE
            b.extraFollowSettings.root.visibility = View.GONE
            b.extraVoiceSettings.root.visibility = View.GONE

            if (key == "chat") {
                b.extraChatSettings.root.visibility = View.VISIBLE
                b.extraChatSettings.sbMaxLines.progress = s.chatMaxLines
                b.extraChatSettings.tvMaxLinesLabel.text = "Max Lines: ${s.chatMaxLines}"
                b.extraChatSettings.sbChatDuration.progress = s.chatDuration
                b.extraChatSettings.tvChatDurationLabel.text = "Duration: ${s.chatDuration}s"
                b.extraChatSettings.cbChatTransparent.isChecked = s.chatTransparent
                b.extraChatSettings.cbChatAlwaysShow.isChecked = s.chatAlwaysShow
                b.extraChatSettings.cbChatHistory.isChecked = s.chatHistoryEnabled
                b.extraChatSettings.cbStickerAnimation.isChecked = s.chatStickerAnimation
                b.extraChatSettings.cbChatTts.isChecked = s.chatTtsEnabled
                val ttsVolPct = (s.chatTtsVolume * 100).toInt()
                b.extraChatSettings.sbTtsVolume.progress = ttsVolPct
                b.extraChatSettings.tvTtsVolumeLabel.text = "Volume: $ttsVolPct%"
                b.extraChatSettings.sbTtsMaxLength.progress = s.chatTtsMaxLength
                b.extraChatSettings.tvTtsMaxLengthLabel.text = "Max Len: ${s.chatTtsMaxLength}"
                service?.showChatOverlay()
                service?.showChatDummy(persistent = true)
            } else if (key == "join") {
                b.extraJoinSettings.root.visibility = View.VISIBLE
                b.extraJoinSettings.sbJoinDuration.progress = s.joinDuration
                b.extraJoinSettings.tvJoinDurationLabel.text = "Duration: ${s.joinDuration}s"
                syncNotifSettingsButtons()
                service?.showJoinDummy(persistent = true)
            } else if (key == "follow") {
                b.extraFollowSettings.root.visibility = View.VISIBLE
                b.extraFollowSettings.sbFollowDuration.progress = s.followDuration
                b.extraFollowSettings.tvFollowDurationLabel.text = "Duration: ${s.followDuration}s"
                syncNotifSettingsButtons()
                service?.showFollowDummy(persistent = true)
            } else if (key == "like") {
                b.extraLikeSettings.root.visibility = View.VISIBLE
                b.extraLikeSettings.cbLikeAnimation.isChecked = s.likeAnimationEnabled
                b.extraLikeSettings.sbLikeDuration.progress = s.likeDuration
                b.extraLikeSettings.tvLikeDurationLabel.text = "Duration: ${s.likeDuration}s"
                service?.showLikeDummy(persistent = true)
            } else if (key == "queue") {
                b.extraQueueSettings.root.visibility = View.VISIBLE
                b.extraQueueSettings.cbQueueAutoHide.isChecked = s.queueAutoHide
                b.extraQueueSettings.sbQueueDuration.progress = s.queueDuration
                b.extraQueueSettings.tvQueueDurationLabel.text = "Duration: ${s.queueDuration}s"
                service?.updateQueueAutoHide(false)
                service?.showQueueOverlay()
            } else if (key == "notif") {
                b.extraNotifSettings.root.visibility = View.VISIBLE
                b.extraNotifSettings.sbNotifDuration.progress = s.notifDuration
                b.extraNotifSettings.tvNotifDurationLabel.text = "Duration: ${s.notifDuration}s"
                val vol = (s.notifVolume * 100).toInt()
                b.extraNotifSettings.sbNotifVolume.progress = vol
                b.extraNotifSettings.tvNotifVolumeLabel.text = "Volume: $vol%"
                b.extraNotifSettings.cbUseTiktokGiftIcon.isChecked = s.useTiktokGiftIcon
                b.extraNotifSettings.cbUseCustomGiftSound.isChecked = s.useCustomGiftSound
                b.panelGrid.containerGiftSound.visibility = if (s.useCustomGiftSound) View.VISIBLE else View.GONE
                syncNotifSettingsButtons()
                service?.showNotifDummy(persistent = true)
            } else if (key == "voice") {
                b.extraVoiceSettings.root.visibility = View.VISIBLE
                b.extraVoiceSettings.cbVoiceOnline.isChecked = s.voiceOnline
                b.extraVoiceSettings.cbVoiceContinuous.isChecked = s.voiceContinuous
                b.extraVoiceSettings.sbVoiceTextSize.progress = s.voiceTextSize.toInt()
                b.extraVoiceSettings.tvVoiceTextSizeLabel.text = "Text Size: ${s.voiceTextSize.toInt()}sp"
                b.extraVoiceSettings.sbVoiceDuration.progress = s.voiceDuration
                b.extraVoiceSettings.tvVoiceDurationLabel.text = "Auto Hide: ${s.voiceDuration}s"

                val currentLangIndex = langCodes.indexOf(s.voiceLanguage).coerceAtLeast(0)
                b.extraVoiceSettings.spVoiceLanguage.setSelection(currentLangIndex)

                service?.showVoiceOverlay()
            }
        }

        b.extraNotifSettings.btnSelectShareImg.setOnClickListener { pickShareImg.launch(arrayOf("image/*")) }
        b.extraNotifSettings.btnSelectGiftImg.setOnClickListener { pickGiftImg.launch(arrayOf("image/*")) }
        b.extraNotifSettings.btnSelectShareAudio.setOnClickListener { pickShareAudio.launch(arrayOf("audio/*")) }
        b.extraNotifSettings.btnSelectGiftAudio.setOnClickListener { pickGiftAudio.launch(arrayOf("audio/*")) }
        b.extraJoinSettings.btnSelectJoinAudio.setOnClickListener { pickJoinAudio.launch(arrayOf("audio/*")) }
        b.extraFollowSettings.btnSelectFollowAudio.setOnClickListener { pickFollowAudio.launch(arrayOf("audio/*")) }

        b.extraNotifSettings.sbNotifDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraNotifSettings.tvNotifDurationLabel.text = "Duration: ${p}s"
                    settingsManager.settings.notifDuration = p
                    settingsManager.saveSettings()
                    updateNotifToService()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.extraNotifSettings.sbNotifVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.extraNotifSettings.tvNotifVolumeLabel.text = "Volume: $p%"
                    service?.updateNotifVolume(p.toFloat() / 100f)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.extraNotifSettings.cbUseTiktokGiftIcon.setOnCheckedChangeListener { _, isChecked -> service?.updateUseTiktokGiftIcon(isChecked) }
        b.extraNotifSettings.cbUseCustomGiftSound.setOnCheckedChangeListener { _, isChecked ->
            b.panelGrid.containerGiftSound.visibility = if (isChecked) View.VISIBLE else View.GONE
            service?.updateUseCustomGiftSound(isChecked)
        }

        b.panelGrid.btnGiftSound.setOnClickListener { startActivity(Intent(this, GiftSoundActivity::class.java)) }
        b.extraLikeSettings.cbLikeAnimation.setOnCheckedChangeListener { _, isChecked -> service?.updateLikeAnimationEnabled(isChecked) }
        b.extraChatSettings.cbStickerAnimation.setOnCheckedChangeListener { _, isChecked -> service?.updateStickerAnimationEnabled(isChecked) }

        b.panelGrid.btnOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            Log.d("Kanae_UI", "[UI_CLICK] Player Overlay Toggle")
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            if (svc.overlayVisible) svc.hideOverlay() else svc.showOverlay()
            syncUi(true)
        }
        b.panelGrid.btnOverlay.setOnLongClickListener { openSettings("player", "Player"); true }

        b.panelGrid.btnQueueOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleQueueOverlay()
            syncUi(true)
        }
        b.panelGrid.btnQueueOverlay.setOnLongClickListener { openSettings("queue", "Queue"); true }

        b.panelGrid.btnLyricsOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleLyricsOverlay()
            syncUi(true)
        }
        b.panelGrid.btnLyricsOverlay.setOnLongClickListener { openSettings("lyrics", "Lyrics"); true }

        b.panelGrid.btnChatOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            Log.d("Kanae_UI", "[UI_CLICK] Chat Overlay Toggle")
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleChatOverlay()
            syncUi(true)
        }
        b.panelGrid.btnChatOverlay.setOnLongClickListener { openSettings("chat", "Chat"); true }

        b.panelGrid.btnNotifOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleNotifOverlay()
            syncUi(true)
        }
        b.panelGrid.btnNotifOverlay.setOnLongClickListener { openSettings("notif", "Notif"); true }

        b.panelGrid.btnJoinOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleJoinOverlay()
            syncUi(true)
        }
        b.panelGrid.btnJoinOverlay.setOnLongClickListener { openSettings("join", "Join"); true }

        b.panelGrid.btnLikeOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleLikeOverlay()
            syncUi(true)
        }
        b.panelGrid.btnLikeOverlay.setOnLongClickListener { openSettings("like", "Like"); true }

        b.panelGrid.btnFollowOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleFollowOverlay()
            syncUi(true)
        }
        b.panelGrid.btnFollowOverlay.setOnLongClickListener { openSettings("follow", "Follow"); true }

        b.panelGrid.btnVoiceOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestVoicePermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                svc.toggleVoiceOverlay()
                syncUi(true)
            }
        }
        b.panelGrid.btnVoiceOverlay.setOnLongClickListener { openSettings("voice", "Voice"); true }

        b.panelGrid.btnCanvas.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            val canvasActive = service?.getStateMap()?.get("canvas_mode") as? Boolean ?: false
            if (canvasActive) {
                service?.disableCanvasMode()
                snack("Canvas mode dinonaktifkan")
            } else {
                startActivity(Intent(this, CanvasActivity::class.java))
                dialog.dismiss()
            }
            syncUi(true)
        }
        b.panelGrid.btnCustomOverlay.setOnClickListener { startActivity(Intent(this, CustomWebOverlaySettingsActivity::class.java)); dialog.dismiss() }
        b.panelGrid.btnMapping.setOnClickListener { startActivity(Intent(this, MappingActivity::class.java)); dialog.dismiss() }
        b.panelGrid.btnGesture.setOnClickListener { startActivity(Intent(this, QuickOverlayActivity::class.java)); dialog.dismiss() }

        // Baris list Windows Phone: seluruh baris (bukan cuma kotak ikon) bisa diketuk / ditahan
        listOf<View>(
            b.panelGrid.btnOverlay, b.panelGrid.btnQueueOverlay, b.panelGrid.btnLyricsOverlay,
            b.panelGrid.btnCanvas, b.panelGrid.btnChatOverlay, b.panelGrid.btnNotifOverlay,
            b.panelGrid.btnCustomOverlay, b.panelGrid.btnJoinOverlay, b.panelGrid.btnLikeOverlay,
            b.panelGrid.btnFollowOverlay, b.panelGrid.btnMapping, b.panelGrid.btnGesture,
            b.panelGrid.btnVoiceOverlay, b.panelGrid.btnGiftSound
        ).forEach { btn ->
            (btn.parent as? View)?.apply {
                isClickable = true
                setOnClickListener { btn.performClick() }
                setOnLongClickListener { btn.performLongClick() }
            }
        }

        b.btnBackToGrid.setOnClickListener {
            if (currentConfigKey == "queue") service?.updateQueueAutoHide(settingsManager.settings.queueAutoHide)
            if (currentConfigKey == "notif") service?.hideNotif()
            if (currentConfigKey == "chat") service?.hideChatDummy()
            if (currentConfigKey == "join") service?.hideJoinOverlay()
            if (currentConfigKey == "like") service?.hideLikeOverlay()
            if (currentConfigKey == "follow") service?.hideFollowOverlay()
            if (currentConfigKey == "voice") service?.hideVoiceOverlay()
            b.panelGrid.root.visibility = View.VISIBLE
            b.panelSettings.visibility = View.GONE
        }

        b.btnSaveOverlaySettings.setOnClickListener {
            val currentPos = service?.getOverlayPosition(currentConfigKey) ?: Pair(0,0)
            val scale = b.sbScale.progress
            val rawW = b.sbWidth.progress
            val w = if (rawW == 0) 0 else if (rawW < 200) 200 else rawW
            val h = b.sbHeight.progress


            if (currentConfigKey == "queue") service?.updateQueueAutoHide(b.extraQueueSettings.cbQueueAutoHide.isChecked)
            if (currentConfigKey == "notif") service?.resetNotifTimer()
            if (currentConfigKey == "chat") service?.hideChatDummy()
            if (currentConfigKey == "join") service?.hideJoinOverlay()
            if (currentConfigKey == "like") service?.hideLikeOverlay()
            if (currentConfigKey == "follow") service?.hideFollowOverlay()
            if (currentConfigKey == "voice") service?.hideVoiceOverlay()

            snack("${b.tvSettingsTitle.text} saved!")
            b.panelGrid.root.visibility = View.VISIBLE
            b.panelSettings.visibility = View.GONE
            service?.applyOverlayConfig(currentConfigKey, currentPos.first, currentPos.second, scale.toFloat()/100f, w, h)
            service?.persistSettings()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun syncUi(force: Boolean = false) {
        val svc   = service ?: return

        // Gunakan FastPlaybackState untuk data yang sering update (Timer & Progress)
        val songJson = FastPlaybackState.currentSongJson
        val posMs    = FastPlaybackState.positionMs
        val durMs    = FastPlaybackState.durationMs
        val isPlaying = FastPlaybackState.isPlaying
        val isPaused  = FastPlaybackState.isPaused

        // 1. Update Timer & Progress (Selalu update agar halus)
        val posSec = (posMs / 1000).toInt()
        val durSec = (durMs / 1000).toInt()
        binding.tvProgress.text = "${fmt(posSec)} / ${fmt(durSec)}"
        val progress = if (durMs > 0) ((posMs * 100) / durMs).toInt().coerceIn(0, 100) else 0
        binding.progressBar.progress = progress

        // 2. Cek Throttling untuk UI statis/berat
        val now = System.currentTimeMillis()
        val shouldSyncHeavy = force || (now - lastSyncTime >= 1000) || (songJson != lastSongJson)

        if (!shouldSyncHeavy) {
            // Jika dalam masa throttle, hanya update bagian esensial BottomSheet
            val state = svc.getStateMap()
            syncBottomSheetOnly(svc, state)
            return
        }

        lastSyncTime = now
        val state = svc.getStateMap()

        // 3. Update Nama Lagu (Hanya jika berubah)
        if (songJson != lastSongJson) {
            lastSongJson = songJson
            val song = songJson?.let { Gson().fromJson(it, Song::class.java) }
            binding.tvNowPlaying.text = song?.title ?: "nothing playing"
            binding.tvNowPlayingMeta.text = listOfNotNull(
                song?.channel?.takeIf { it.isNotBlank() },
                song?.requestedBy?.takeIf { it.isNotBlank() }?.let { "requested by @$it" }
            ).joinToString("  ·  ")
            loadNowPlayingArt(song?.thumbnail)
        }

        // Queue harus selalu di-sync tiap heavy-sync tick, TIDAK boleh digantung
        // di kondisi songJson berubah — kalau tidak, nambah lagu ke antrian saat
        // lagu lain sedang diputar tidak akan pernah memperbarui rvQueue.
        val q = svc.getQueue()
        Log.d(TAG, "Syncing queue to adapter: ${q.size} items")
        runOnUiThread {
            queueAdapter.submitList(q)
        }

        val qCount    = state["queue_count"]  as? Int     ?: 0
        val shuffle   = state["shuffle_mode"] as? Boolean ?: false
        val tiktokOk  = state["tiktok_connected"] as? Boolean ?: false
        val tiktokConnecting = state["tiktok_connecting"] as? Boolean ?: false
        if (tiktokConnected != tiktokOk) {
            // Dicatat di sini (selalu jalan, tile terbuka atau tidak) supaya durasi akhir akurat
            disconnectedAtMs = if (tiktokOk) 0L else System.currentTimeMillis()
            tiktokConnected = tiktokOk
            refreshLiveReportTicker()   // mulai/berhenti ticker saat koneksi live berubah
        }
        // Setelah disconnect, service bisa menutup sesi sedikit terlambat: ambil durasi final begitu tersedia
        if (!tiktokOk && isLiveReportOpen()) renderLiveReport()
        val ytdlpOk   = state["ytdlp_installed"]  as? Boolean ?: false
        val musicVol  = state["music_volume"]     as? Float   ?: 1.0f
        val cmdsOn    = state["commands_enabled"] as? Boolean ?: true

        val playingNow = isPlaying && !isPaused
        binding.btnPlayPause.text = if (playingNow) "pause" else "play"
        binding.btnPlayPause.setIconResource(
            if (playingNow) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        binding.eqPlaying.setPlaying(playingNow)
        binding.tvQueueCount.text = "$qCount"
        binding.btnShuffle.text   = if (shuffle) "shuffle on" else "shuffle off"
        val musicVolPct = (musicVol * 100).toInt()
        binding.sbMusicVolume.progress = musicVolPct
        binding.tvMusicVolume.text = "$musicVolPct%"
        binding.sbEnableCommands.progress = if (cmdsOn) 1 else 0
        binding.tvEnableCommandsLabel.alpha = if (cmdsOn) 1.0f else 0.5f

        binding.tvTiktokStatus.text  = when {
            tiktokOk -> "🟢\ntiktok"
            tiktokConnecting -> "🟡\ntiktok"
            else -> "🔴\ntiktok"
        }
        binding.btnConnectTiktok.text = when {
            tiktokOk -> "disconnect tiktok"
            tiktokConnecting -> "connecting…"
            else -> "connect tiktok"
        }
        binding.btnConnectTiktok.isEnabled = !tiktokConnecting

        binding.btnConnectTiktok.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when {
                tiktokOk -> ContextCompat.getColor(this, R.color.metro_red)
                tiktokConnecting -> ContextCompat.getColor(this, R.color.metro_amber)
                else -> ContextCompat.getColor(this, R.color.metro_accent)
            })

        binding.tvYtdlpStatus.text = if (ytdlpOk) "🟢\nyt-dlp" else "🔴\nyt-dlp"

        syncBottomSheetOnly(svc, state)
    }

    // ── Live Report (realtime, ringan) ────────────────────────────────
    private val liveAnalytics by lazy {
        ame.project.kanae.analytics.LiveAnalyticsManager.getInstance(applicationContext)
    }

    private fun isLiveReportOpen() = openSection?.tile === binding.tileLiveReport

    /** Hanya set teks kalau berubah, jadi tidak ada relayout/redraw sia-sia. */
    private fun TextView.setIfChanged(s: String) {
        if (!text.contentEquals(s)) text = s
    }

    private fun fmtDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val sb = StringBuilder(8)
        if (h > 0) {
            if (h < 10) sb.append('0')
            sb.append(h).append(':')
        }
        if (m < 10) sb.append('0')
        sb.append(m).append(':')
        if (s < 10) sb.append('0')
        sb.append(s)
        return sb.toString()
    }

    /** Render ringkasan live report. Mengembalikan delay (ms) ke tick berikutnya, atau -1 kalau tidak perlu tick lagi. */
    private fun renderLiveReport(): Long {
        val report = liveAnalytics.getCurrentOrLastReport() ?: return -1L
        // Aktif hanya jika sesi belum ditutup DAN TikTok masih terhubung.
        // Kalau app/service mati tanpa menutup sesi (endTimeMs == 0), durasi di-freeze, tidak lanjut jalan.
        val active = report.endTimeMs == 0L && tiktokConnected
        val elapsedMs = when {
            report.endTimeMs != 0L -> report.durationMs
            active -> System.currentTimeMillis() - report.startTimeMs
            disconnectedAtMs > report.startTimeMs -> disconnectedAtMs - report.startTimeMs
            else -> report.durationMs
        }

        // Bagian ringan: berubah tiap detik / tiap event
        binding.tvLiveReportStatus.setIfChanged(
            if (active) "🔴 Sesi Live Aktif (@${report.streamerUsername})"
            else "Sesi Terakhir (@${report.streamerUsername})"
        )
        binding.tvShortLiveDuration.setIfChanged(fmtDuration(elapsedMs / 1000))
        binding.tvShortCompleted.setIfChanged("${report.songsCompleted} lagu")
        binding.tvShortGiftsCoins.setIfChanged("${report.totalGiftsCount} / ${report.totalCoinsEstimated}🪙")

        // Bagian berat: hanya dihitung kalau panel info detail sedang terlihat
        if (binding.bodyLiveReportExpanded.visibility == View.VISIBLE) {
            val topReq = report.requestersMap.values.maxByOrNull { it.requestCount }
            binding.tvShortTopRequester.setIfChanged(
                "Top Requester: ${topReq?.let { "${it.username} (${it.requestCount}x)" } ?: "-"}"
            )
            val topSong = report.songRequestsMap.maxByOrNull { it.value }
            binding.tvShortTopSong.setIfChanged(
                "Lagu Terfavorit: ${topSong?.let { "${it.key} (${it.value}x)" } ?: "-"}"
            )
            val totalExt = report.npeSuccessCount + report.ytdlpFallbackCount
            val npePct = if (totalExt > 0) (report.npeSuccessCount * 100) / totalExt else 100
            val ytdlpPct = if (totalExt > 0) (report.ytdlpFallbackCount * 100) / totalExt else 0
            binding.tvShortSystemEngine.setIfChanged("Extractor: NPE ($npePct%) vs yt-dlp ($ytdlpPct%)")
        }

        // Selaraskan tick ke batas detik asli sesi, jadi angka berganti mulus tanpa drift
        return if (active) 1000L - (elapsedMs % 1000L) else -1L
    }

    private fun startLiveReportTicker() {
        stopLiveReportTicker()
        liveReportTickerJob = lifecycleScope.launch {
            while (isActive) {
                val next = renderLiveReport()
                if (next < 0) break          // sesi tidak aktif: berhenti, nol pekerjaan
                delay(next.coerceIn(50L, 1000L))
            }
        }
    }

    private fun stopLiveReportTicker() {
        liveReportTickerJob?.cancel()
        liveReportTickerJob = null
    }

    /** Jalan hanya kalau tile Live Report terbuka DAN activity di foreground. */
    private fun refreshLiveReportTicker() {
        if (isLiveReportOpen() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            renderLiveReport()          // tampilkan nilai segar langsung, tanpa tunggu 1 detik
            startLiveReportTicker()
        } else {
            stopLiveReportTicker()
        }
    }

    private fun addChatLine(line: String) {
        val tv = binding.tvChatLog
        val lines = tv.text.toString().let { if (it.isEmpty()) emptyList() else it.lines() }
        tv.text = (lines + line).takeLast(50).joinToString("\n")
        binding.scrollChat.post { binding.scrollChat.fullScroll(View.FOCUS_DOWN) }
    }

    private fun loadDevNoteNotifications() {
        DevNoteNotificationManager.fetchNotifications(this, lifecycleScope) { state, list ->
            currentNotifs = list
            updateDevNoteButtonUi(state)
        }
    }

    private fun updateDevNoteButtonUi(state: NotifBadgeState) {
        val btnText = when (state) {
            NotifBadgeState.RED_FEEDBACK -> "🔴\ndev note"
            NotifBadgeState.GREEN_GLOBAL -> "🟢\ndev note"
            NotifBadgeState.NONE -> "📝\ndev note"
        }
        binding.btnDevNote.text = btnText

        if (state != NotifBadgeState.NONE) {
            if (binding.btnDevNote.animation == null) {
                val fadeAnim = AlphaAnimation(1.0f, 0.3f).apply {
                    duration = 800
                    repeatCount = Animation.INFINITE
                    repeatMode = Animation.REVERSE
                }
                binding.btnDevNote.startAnimation(fadeAnim)
            }
        } else {
            binding.btnDevNote.clearAnimation()
            binding.btnDevNote.alpha = 1.0f
        }
    }

    private fun showDevNoteDialog() {
        DevNoteNotificationManager.showDevNoteDialog(this, currentNotifs) {
            updateDevNoteButtonUi(NotifBadgeState.NONE)
        }
    }

    private fun showFeedbackDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val marginPx = (16 * resources.displayMetrics.density).toInt()
            setPadding(marginPx, marginPx, marginPx, marginPx)
        }
        val etUser = EditText(this).apply {
            hint = "Username"
            setText(binding.etTiktokUser.text.toString().trim())
            setSingleLine(true)
        }
        val etMsg = EditText(this).apply {
            hint = "Pesan feedback atau bug..."
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        layout.addView(etUser); layout.addView(etMsg)

        MaterialAlertDialogBuilder(this)
            .setTitle("Kirim Feedback")
            .setView(layout)
            .setPositiveButton("Kirim") { _, _ ->
                val user = etUser.text.toString().trim().ifBlank { "Unknown" }
                val feedback = etMsg.text.toString().trim()
                if (feedback.isNotEmpty()) sendFeedback(user, feedback)
                else snack("Feedback tidak boleh kosong")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun sendFeedback(username: String, feedback: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://script.google.com/macros/s/AKfycbzVFzKyAafhZlRRHH-U8cSRtbg-fggMiQ8fMTWMlwCyh1EwXgQ9gXorDzIbwBCo2WEDdw/exec"

                val uid = DeviceIdManager.getUniqueId(this@MainActivity)
                val versionName = DeviceIdManager.getAppVersionName(this@MainActivity)
                val versionCode = DeviceIdManager.getAppVersionCode(this@MainActivity)

                val jsonObject = com.google.gson.JsonObject().apply {
                    addProperty("username", username)
                    addProperty("feedback", feedback)
                    addProperty("uid", uid)
                    addProperty("version_name", versionName)
                    addProperty("version_code", versionCode)
                }
                val json = Gson().toJson(jsonObject)
                val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).build()
                OkHttpClient().newCall(request).execute().use { response ->
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) snack("Feedback terkirim, terima kasih!")
                        else snack("Gagal mengirim feedback: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { snack("Error: ${e.message}") }
            }
        }
    }

    private fun syncBottomSheetOnly(svc: PlayerForegroundService, state: Map<String, Any?>) {
        overlaySheetBinding?.let { b ->
            // Update labels & Alphas Overlay (Ini harus responsif saat diklik)
            b.panelGrid.tvLabelPlayer.text = if (svc.overlayVisible) "Player ON" else "Player"
            b.panelGrid.btnOverlay.alpha = if (svc.overlayVisible) 1.0f else 0.6f

            val queueOn = state["queue_visible"] as? Boolean ?: false
            b.panelGrid.tvLabelQueue.text = if (queueOn) "Queue ON" else "Queue"
            b.panelGrid.btnQueueOverlay.alpha = if (queueOn) 1.0f else 0.6f

            val lyricsOn = state["lyrics_visible"] as? Boolean ?: false
            b.panelGrid.tvLabelLyrics.text = if (lyricsOn) "Lyrics ON" else "Lyrics"
            b.panelGrid.btnLyricsOverlay.alpha = if (lyricsOn) 1.0f else 0.6f

            val chatOn = state["chat_visible"] as? Boolean ?: false
            b.panelGrid.tvLabelChat.text = if (chatOn) "Chat ON" else "Chat"
            b.panelGrid.btnChatOverlay.alpha = if (chatOn) 1.0f else 0.6f

            val notifEnabled = state["notif_enabled"] as? Boolean ?: false
            b.panelGrid.tvLabelNotif.text = if (notifEnabled) "Notif ON" else "Notif"
            b.panelGrid.btnNotifOverlay.alpha = if (notifEnabled) 1.0f else 0.6f

            val joinEnabled = state["join_enabled"] as? Boolean ?: false
            b.panelGrid.tvLabelJoin.text = if (joinEnabled) "Join ON" else "Join"
            b.panelGrid.btnJoinOverlay.alpha = if (joinEnabled) 1.0f else 0.6f

            val likeEnabled = state["like_enabled"] as? Boolean ?: false
            b.panelGrid.tvLabelLike.text = if (likeEnabled) "Like ON" else "Like"
            b.panelGrid.btnLikeOverlay.alpha = if (likeEnabled) 1.0f else 0.6f

            val followEnabled = state["follow_enabled"] as? Boolean ?: false
            b.panelGrid.tvLabelFollow.text = if (followEnabled) "Follow ON" else "Follow"
            b.panelGrid.btnFollowOverlay.alpha = if (followEnabled) 1.0f else 0.6f

            val voiceOn = state["voice_visible"] as? Boolean ?: false
            b.panelGrid.tvLabelVoice.text = if (voiceOn) "Voice ON" else "Voice"
            b.panelGrid.btnVoiceOverlay.alpha = if (voiceOn) 1.0f else 0.6f

            val canvasOn = state["canvas_mode"]  as? Boolean ?: false
            b.panelGrid.tvLabelCanvas.text = if (canvasOn) "Canvas ON" else "Canvas"
            b.panelGrid.btnCanvas.alpha = if (canvasOn) 1.0f else 0.6f

            // Update Panel Settings jika sedang aktif
            if (currentConfigKey == "chat" && b.panelSettings.visibility == View.VISIBLE) {
                val ttsEnabled = state["chat_tts_enabled"] as? Boolean ?: false
                if (b.extraChatSettings.cbChatTts.isChecked != ttsEnabled) b.extraChatSettings.cbChatTts.isChecked = ttsEnabled
            }
        }
    }

    private fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
    private fun snack(msg: String) = Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
}

class QueueAdapter(private val onRemove: (Int) -> Unit, private val onPlay: (Song) -> Unit) : RecyclerView.Adapter<QueueAdapter.VH>() {
    private val items = mutableListOf<Song>()
    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<Song>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
        Log.d("QueueAdapter", "Adapter items updated: ${items.size}")
    }
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_queue_metro, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = items[position]
        holder.tvTitle.text = "${position + 1}. ${song.title}"
        holder.tvMeta.text  = buildString {
            if (!song.requestedBy.isNullOrBlank()) append("by ${song.requestedBy} ")
            if (song.duration > 0) append("• ${song.durationFormatted}")
        }
        if (!song.thumbnail.isNullOrBlank()) {
            Glide.with(holder.itemView).load(song.thumbnail).centerCrop().into(holder.ivThumb)
        } else {
            Glide.with(holder.itemView).clear(holder.ivThumb)
            holder.ivThumb.setImageDrawable(null)
        }
        holder.btnPlay.setOnClickListener { onPlay(song) }
        holder.btnRemove.setOnClickListener { onRemove(position) }
    }
    override fun getItemCount() = items.size
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivThumb: ImageView = v.findViewById(R.id.iv_song_thumb)
        val tvTitle: TextView = v.findViewById(R.id.tv_song_title)
        val tvMeta: TextView = v.findViewById(R.id.tv_song_meta)
        val btnPlay: ImageButton = v.findViewById(R.id.btn_play_now)
        val btnRemove: ImageButton = v.findViewById(R.id.btn_remove)
    }
}