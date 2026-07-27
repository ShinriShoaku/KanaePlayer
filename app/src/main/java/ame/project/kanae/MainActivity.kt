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
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
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
                PlayerForegroundService.BROADCAST_STATE -> syncUi()
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

    private fun updateNotifToService(type: String? = null) {
        val s = settingsManager.settings
        service?.updateNotifConfig(
            s.notifShareImg,
            s.notifGiftImg,
            s.notifShareAud,
            s.notifGiftAud,
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

        updateBtnText(b.btnSelectShareImg, s.notifShareImg, R.string.btn_image)
        updateBtnText(b.btnSelectGiftImg, s.notifGiftImg, R.string.btn_image)
        updateBtnText(b.btnSelectShareAudio, s.notifShareAud, R.string.btn_audio)
        updateBtnText(b.btnSelectGiftAudio, s.notifGiftAud, R.string.btn_audio)
    }

    // ─────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setupRecyclerView()
        setupButtons()
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

    override fun onDestroy() {
        super.onDestroy()
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
            binding.btnShuffle.text = if (on) "🔀 ON" else "🔀 OFF"
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

        binding.etCmdRequest.setText(s.cmdRequest)
        binding.etCmdSkip.setText(s.cmdSkip)
        binding.etCmdStop.setText(s.cmdStop)
        binding.etCmdQueue.setText(s.cmdQueue)
        binding.etCmdClearMusic.setText(s.cmdClearMusic)
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
            clearMusicPrefixes = field(binding.etCmdClearMusic.text.toString())
        )
    }

    // ── Update Checker ───────────────────────────────────────────────
    private data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val updateMessage: String
    )

    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://raw.githubusercontent.com/ShinriShoaku/KanaePlayer/master/version.json")
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

        b.sbMaxLines.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.tvMaxLinesLabel.text = "Max Lines: $p"
                    service?.updateChatMaxLines(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.cbChatTransparent.setOnCheckedChangeListener { _, isChecked -> service?.updateChatTransparency(isChecked) }
        b.cbChatAlwaysShow.setOnCheckedChangeListener { _, isChecked -> service?.updateChatAlwaysShow(isChecked) }
        b.cbChatHistory.setOnCheckedChangeListener { _, isChecked -> service?.updateChatHistory(isChecked) }

        b.sbChatDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.tvChatDurationLabel.text = "Duration: ${p}s"
                    service?.updateChatDuration(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.cbChatTts.setOnCheckedChangeListener { _, isChecked -> service?.updateTtsEnabled(isChecked) }

        b.sbTtsVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.tvTtsVolumeLabel.text = "Volume: $p%"
                    service?.updateTtsVolume(p.toFloat() / 100f)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.sbTtsMaxLength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.tvTtsMaxLengthLabel.text = "Max Len: $p"
                    service?.updateTtsMaxLength(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.cbQueueAutoHide.setOnCheckedChangeListener { _, isChecked -> service?.updateQueueAutoHide(isChecked) }

        b.sbQueueDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.tvQueueDurationLabel.text = "Duration: ${p}s"
                    service?.updateQueueDuration(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.sbJoinDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.tvJoinDurationLabel.text = "Duration: ${p}s"
                    service?.updateJoinDuration(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.sbLikeDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.tvLikeDurationLabel.text = "Duration: ${p}s"
                    service?.updateLikeDuration(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.sbFollowDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.tvFollowDurationLabel.text = "Duration: ${p}s"
                    service?.updateFollowDuration(p)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        fun openSettings(key: String, title: String) {
            currentConfigKey = key
            b.panelGrid.visibility = View.GONE
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

            b.extraChatSettings.visibility = View.GONE
            b.extraQueueSettings.visibility = View.GONE
            b.extraNotifSettings.visibility = View.GONE
            b.extraLikeSettings.visibility = View.GONE
            b.extraJoinSettings.visibility = View.GONE
            b.extraFollowSettings.visibility = View.GONE

            if (key == "chat") {
                b.extraChatSettings.visibility = View.VISIBLE
                b.sbMaxLines.progress = s.chatMaxLines
                b.tvMaxLinesLabel.text = "Max Lines: ${s.chatMaxLines}"
                b.sbChatDuration.progress = s.chatDuration
                b.tvChatDurationLabel.text = "Duration: ${s.chatDuration}s"
                b.cbChatTransparent.isChecked = s.chatTransparent
                b.cbChatAlwaysShow.isChecked = s.chatAlwaysShow
                b.cbChatHistory.isChecked = s.chatHistoryEnabled
                b.cbStickerAnimation.isChecked = s.chatStickerAnimation
                b.cbChatTts.isChecked = s.chatTtsEnabled
                val ttsVolPct = (s.chatTtsVolume * 100).toInt()
                b.sbTtsVolume.progress = ttsVolPct
                b.tvTtsVolumeLabel.text = "Volume: $ttsVolPct%"
                b.sbTtsMaxLength.progress = s.chatTtsMaxLength
                b.tvTtsMaxLengthLabel.text = "Max Len: ${s.chatTtsMaxLength}"
                service?.showChatOverlay()
                service?.showChatDummy(persistent = true)
            } else if (key == "join") {
                b.extraJoinSettings.visibility = View.VISIBLE
                b.sbJoinDuration.progress = s.joinDuration
                b.tvJoinDurationLabel.text = "Duration: ${s.joinDuration}s"
                service?.showJoinDummy(persistent = true)
            } else if (key == "follow") {
                b.extraFollowSettings.visibility = View.VISIBLE
                b.sbFollowDuration.progress = s.followDuration
                b.tvFollowDurationLabel.text = "Duration: ${s.followDuration}s"
                service?.showFollowDummy(persistent = true)
            } else if (key == "like") {
                b.extraLikeSettings.visibility = View.VISIBLE
                b.cbLikeAnimation.isChecked = s.likeAnimationEnabled
                b.sbLikeDuration.progress = s.likeDuration
                b.tvLikeDurationLabel.text = "Duration: ${s.likeDuration}s"
                service?.showLikeDummy(persistent = true)
            } else if (key == "queue") {
                b.extraQueueSettings.visibility = View.VISIBLE
                b.cbQueueAutoHide.isChecked = s.queueAutoHide
                b.sbQueueDuration.progress = s.queueDuration
                b.tvQueueDurationLabel.text = "Duration: ${s.queueDuration}s"
                service?.updateQueueAutoHide(false)
                service?.showQueueOverlay()
            } else if (key == "notif") {
                b.extraNotifSettings.visibility = View.VISIBLE
                b.sbNotifDuration.progress = s.notifDuration
                b.tvNotifDurationLabel.text = "Duration: ${s.notifDuration}s"
                val vol = (s.notifVolume * 100).toInt()
                b.sbNotifVolume.progress = vol
                b.tvNotifVolumeLabel.text = "Volume: $vol%"
                b.cbUseTiktokGiftIcon.isChecked = s.useTiktokGiftIcon
                b.cbUseCustomGiftSound.isChecked = s.useCustomGiftSound
                b.containerGiftSound.visibility = if (s.useCustomGiftSound) View.VISIBLE else View.GONE
                syncNotifSettingsButtons()
                service?.showNotifDummy(persistent = true)
            }
        }

        b.btnSelectShareImg.setOnClickListener { pickShareImg.launch(arrayOf("image/*")) }
        b.btnSelectGiftImg.setOnClickListener { pickGiftImg.launch(arrayOf("image/*")) }
        b.btnSelectShareAudio.setOnClickListener { pickShareAudio.launch(arrayOf("audio/*")) }
        b.btnSelectGiftAudio.setOnClickListener { pickGiftAudio.launch(arrayOf("audio/*")) }

        b.sbNotifDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.tvNotifDurationLabel.text = "Duration: ${p}s"
                    settingsManager.settings.notifDuration = p
                    settingsManager.saveSettings()
                    updateNotifToService()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.sbNotifVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.tvNotifVolumeLabel.text = "Volume: $p%"
                    service?.updateNotifVolume(p.toFloat() / 100f)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        b.cbUseTiktokGiftIcon.setOnCheckedChangeListener { _, isChecked -> service?.updateUseTiktokGiftIcon(isChecked) }
        b.cbUseCustomGiftSound.setOnCheckedChangeListener { _, isChecked ->
            b.containerGiftSound.visibility = if (isChecked) View.VISIBLE else View.GONE
            service?.updateUseCustomGiftSound(isChecked)
        }

        b.btnGiftSound.setOnClickListener { startActivity(Intent(this, GiftSoundActivity::class.java)) }
        b.cbLikeAnimation.setOnCheckedChangeListener { _, isChecked -> service?.updateLikeAnimationEnabled(isChecked) }
        b.cbStickerAnimation.setOnCheckedChangeListener { _, isChecked -> service?.updateStickerAnimationEnabled(isChecked) }

        b.btnOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            Log.d("Kanae_UI", "[UI_CLICK] Player Overlay Toggle")
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            if (svc.overlayVisible) svc.hideOverlay() else svc.showOverlay()
            syncUi(true)
        }
        b.btnOverlay.setOnLongClickListener { openSettings("player", "Player"); true }

        b.btnQueueOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleQueueOverlay()
            syncUi(true)
        }
        b.btnQueueOverlay.setOnLongClickListener { openSettings("queue", "Queue"); true }

        b.btnLyricsOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleLyricsOverlay()
            syncUi(true)
        }
        b.btnLyricsOverlay.setOnLongClickListener { openSettings("lyrics", "Lyrics"); true }

        b.btnChatOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            Log.d("Kanae_UI", "[UI_CLICK] Chat Overlay Toggle")
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleChatOverlay()
            syncUi(true)
        }
        b.btnChatOverlay.setOnLongClickListener { openSettings("chat", "Chat"); true }

        b.btnNotifOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleNotifOverlay()
            syncUi(true)
        }
        b.btnNotifOverlay.setOnLongClickListener { openSettings("notif", "Notif"); true }

        b.btnJoinOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleJoinOverlay()
            syncUi(true)
        }
        b.btnJoinOverlay.setOnLongClickListener { openSettings("join", "Join"); true }

        b.btnLikeOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleLikeOverlay()
            syncUi(true)
        }
        b.btnLikeOverlay.setOnLongClickListener { openSettings("like", "Like"); true }

        b.btnFollowOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return@setOnClickListener }
            svc.toggleFollowOverlay()
            syncUi(true)
        }
        b.btnFollowOverlay.setOnLongClickListener { openSettings("follow", "Follow"); true }

        b.btnCanvas.setOnClickListener {
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
        b.btnCustomOverlay.setOnClickListener { startActivity(Intent(this, CustomWebOverlaySettingsActivity::class.java)); dialog.dismiss() }
        b.btnMapping.setOnClickListener { startActivity(Intent(this, MappingActivity::class.java)); dialog.dismiss() }
        b.btnGesture.setOnClickListener { startActivity(Intent(this, QuickOverlayActivity::class.java)); dialog.dismiss() }

        b.btnBackToGrid.setOnClickListener {
            if (currentConfigKey == "queue") service?.updateQueueAutoHide(settingsManager.settings.queueAutoHide)
            if (currentConfigKey == "notif") service?.hideNotif()
            if (currentConfigKey == "chat") service?.hideChatDummy()
            if (currentConfigKey == "join") service?.hideJoinOverlay()
            if (currentConfigKey == "like") service?.hideLikeOverlay()
            if (currentConfigKey == "follow") service?.hideFollowOverlay()
            b.panelGrid.visibility = View.VISIBLE
            b.panelSettings.visibility = View.GONE
        }

        b.btnSaveOverlaySettings.setOnClickListener {
            val currentPos = service?.getOverlayPosition(currentConfigKey) ?: Pair(0,0)
            val scale = b.sbScale.progress
            val rawW = b.sbWidth.progress
            val w = if (rawW == 0) 0 else if (rawW < 200) 200 else rawW
            val h = b.sbHeight.progress

            if (currentConfigKey == "queue") service?.updateQueueAutoHide(b.cbQueueAutoHide.isChecked)
            if (currentConfigKey == "notif") service?.resetNotifTimer()
            if (currentConfigKey == "chat") service?.hideChatDummy()
            if (currentConfigKey == "join") service?.hideJoinOverlay()
            if (currentConfigKey == "like") service?.hideLikeOverlay()
            if (currentConfigKey == "follow") service?.hideFollowOverlay()

            snack("${b.tvSettingsTitle.text} saved!")
            b.panelGrid.visibility = View.VISIBLE
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
            binding.tvNowPlaying.text = if (songJson != null) {
                val song = Gson().fromJson(songJson, Song::class.java)
                "▶ ${song.title}"
            } else "– Nothing playing –"
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
        val ytdlpOk   = state["ytdlp_installed"]  as? Boolean ?: false
        val musicVol  = state["music_volume"]     as? Float   ?: 1.0f
        val cmdsOn    = state["commands_enabled"] as? Boolean ?: true

        binding.btnPlayPause.text = if (isPlaying && !isPaused) "Pause" else "Play"
        binding.tvQueueCount.text = "Queue: $qCount"
        binding.btnShuffle.text   = if (shuffle) "🔀 ON" else "🔀 OFF"
        val musicVolPct = (musicVol * 100).toInt()
        binding.sbMusicVolume.progress = musicVolPct
        binding.tvMusicVolume.text = "$musicVolPct%"
        binding.sbEnableCommands.progress = if (cmdsOn) 1 else 0
        binding.tvEnableCommandsLabel.alpha = if (cmdsOn) 1.0f else 0.5f

        binding.tvTiktokStatus.text  = when {
            tiktokOk -> "🟢 TikTok Live"
            tiktokConnecting -> "🟡 Connecting..."
            else -> "🔴 TikTok Live"
        }
        binding.btnConnectTiktok.text = when {
            tiktokOk -> "🔌 DISCONNECT"
            tiktokConnecting -> "⏳ CONNECTING..."
            else -> "💾 CONNECT TIKTOK"
        }
        binding.btnConnectTiktok.isEnabled = !tiktokConnecting

        binding.btnConnectTiktok.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when {
                tiktokOk -> ContextCompat.getColor(this, R.color.red)
                tiktokConnecting -> ContextCompat.getColor(this, R.color.yellow)
                else -> ContextCompat.getColor(this, R.color.orange)
            })

        binding.tvYtdlpStatus.text = if (ytdlpOk) "🟢 yt-dlp ready" else "🔴 yt-dlp missing"

        syncBottomSheetOnly(svc, state)
    }

    private fun addChatLine(line: String) {
        val tv = binding.tvChatLog
        val lines = tv.text.toString().let { if (it.isEmpty()) emptyList() else it.lines() }
        tv.text = (lines + line).takeLast(50).joinToString("\n")
        binding.scrollChat.post { binding.scrollChat.fullScroll(View.FOCUS_DOWN) }
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
                val url = "https://script.google.com/macros/s/AKfycbzZbHEo56-_zHnfl1VnthnNaMIBVJK78RtRosRqKbTDTR1KqD2DVrAbsxxJhkKSSlQB/exec"
                val json = """{"username": "$username", "feedback": "$feedback"}"""
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
            b.tvLabelPlayer.text = if (svc.overlayVisible) "Player ON" else "Player"
            b.btnOverlay.alpha = if (svc.overlayVisible) 1.0f else 0.6f

            val queueOn = state["queue_visible"] as? Boolean ?: false
            b.tvLabelQueue.text = if (queueOn) "Queue ON" else "Queue"
            b.btnQueueOverlay.alpha = if (queueOn) 1.0f else 0.6f

            val lyricsOn = state["lyrics_visible"] as? Boolean ?: false
            b.tvLabelLyrics.text = if (lyricsOn) "Lyrics ON" else "Lyrics"
            b.btnLyricsOverlay.alpha = if (lyricsOn) 1.0f else 0.6f

            val chatOn = state["chat_visible"] as? Boolean ?: false
            b.tvLabelChat.text = if (chatOn) "Chat ON" else "Chat"
            b.btnChatOverlay.alpha = if (chatOn) 1.0f else 0.6f

            val notifEnabled = state["notif_enabled"] as? Boolean ?: false
            b.tvLabelNotif.text = if (notifEnabled) "Notif ON" else "Notif"
            b.btnNotifOverlay.alpha = if (notifEnabled) 1.0f else 0.6f

            val joinEnabled = state["join_enabled"] as? Boolean ?: false
            b.tvLabelJoin.text = if (joinEnabled) "Join ON" else "Join"
            b.btnJoinOverlay.alpha = if (joinEnabled) 1.0f else 0.6f

            val likeEnabled = state["like_enabled"] as? Boolean ?: false
            b.tvLabelLike.text = if (likeEnabled) "Like ON" else "Like"
            b.btnLikeOverlay.alpha = if (likeEnabled) 1.0f else 0.6f

            val followEnabled = state["follow_enabled"] as? Boolean ?: false
            b.tvLabelFollow.text = if (followEnabled) "Follow ON" else "Follow"
            b.btnFollowOverlay.alpha = if (followEnabled) 1.0f else 0.6f

            val canvasOn = state["canvas_mode"]  as? Boolean ?: false
            b.tvLabelCanvas.text = if (canvasOn) "Canvas ON" else "Canvas"
            b.btnCanvas.alpha = if (canvasOn) 1.0f else 0.6f

            // Update Panel Settings jika sedang aktif
            if (currentConfigKey == "chat" && b.panelSettings.visibility == View.VISIBLE) {
                val ttsEnabled = state["chat_tts_enabled"] as? Boolean ?: false
                if (b.cbChatTts.isChecked != ttsEnabled) b.cbChatTts.isChecked = ttsEnabled
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
        val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = items[position]
        holder.tvTitle.text = "${position + 1}. ${song.title}"
        holder.tvMeta.text  = buildString {
            if (!song.requestedBy.isNullOrBlank()) append("by ${song.requestedBy} ")
            if (song.duration > 0) append("• ${song.durationFormatted}")
        }
        holder.btnPlay.setOnClickListener { onPlay(song) }
        holder.btnRemove.setOnClickListener { onRemove(position) }
    }
    override fun getItemCount() = items.size
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tv_song_title)
        val tvMeta: TextView = v.findViewById(R.id.tv_song_meta)
        val btnPlay: ImageButton = v.findViewById(R.id.btn_play_now)
        val btnRemove: ImageButton = v.findViewById(R.id.btn_remove)
    }
}
