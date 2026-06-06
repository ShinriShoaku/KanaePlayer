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
import ame.project.kanae.databinding.ActivityMainBinding
import ame.project.kanae.model.Song
import ame.project.kanae.service.PlayerForegroundService
import ame.project.kanae.tiktok.TikTokLiveManager

class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "MainActivity" }

    private lateinit var binding: ActivityMainBinding

    private var service: PlayerForegroundService? = null
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlayerForegroundService.LocalBinder).getService()
            serviceBound = true
            Log.d(TAG, "Service connected")
            syncUi()
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
                PlayerForegroundService.BROADCAST_CHAT  -> {
                    val nick = intent.getStringExtra("nickname") ?: return
                    val msg  = intent.getStringExtra("comment")  ?: return
                    val type = intent.getStringExtra("cmd_type") ?: ""
                    val prefix = when (type) {
                        "REQUEST"     -> "🎵"
                        "SKIP"        -> "⏭"
                        "STOP"        -> "⏹"
                        "QUEUE"       -> "📋"
                        "CLEAR_MUSIC" -> "🗑"
                        else          -> "💬"
                    }
                    addChatLine("$prefix @$nick: $msg")
                }
            }
        }
    }

    private val queueAdapter = QueueAdapter(
        onRemove = { pos -> service?.removeFromQueue(pos) },
        onPlay   = { song -> service?.playSong(song) }
    )

    // ─────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        loadSavedSettings()          // populate fields ONLY — do not connect

        checkForUpdates()

        startForegroundService(Intent(this, PlayerForegroundService::class.java))
        bindService(
            Intent(this, PlayerForegroundService::class.java),
            connection, BIND_AUTO_CREATE
        )

        val filter = IntentFilter().apply {
            addAction(PlayerForegroundService.BROADCAST_STATE)
            addAction(PlayerForegroundService.BROADCAST_CHAT)
        }
        //registerReceiver(stateReceiver, filter, RECEIVER_EXPORTED)

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
            binding.btnOverlay.isEnabled      = false
            binding.btnQueueOverlay.isEnabled = false
            binding.btnCanvas.isEnabled       = false
            binding.tvOverlayWarning.visibility = View.VISIBLE
            binding.btnGrantOverlay.visibility  = View.VISIBLE
        }
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
                binding.btnOverlay.isEnabled      = true
                binding.btnQueueOverlay.isEnabled = true
                binding.btnCanvas.isEnabled       = true
                binding.tvOverlayWarning.visibility = View.GONE
                binding.btnGrantOverlay.visibility  = View.GONE
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
        }

        // ── Player overlay ─────────────────────────────────────────────
        binding.btnOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission(); return@setOnClickListener
            }
            if (svc.overlayVisible) {
                svc.hideOverlay()
                binding.btnOverlay.text = "Playing Overlay"
            } else {
                svc.showOverlay()
                binding.btnOverlay.text = "Hide Overlay"
            }
        }

        // ── Queue overlay ──────────────────────────────────────────────
        binding.btnQueueOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission(); return@setOnClickListener
            }
            svc.toggleQueueOverlay()
        }

        // ── Lyrics overlay ─────────────────────────────────────────────
        binding.btnLyricsOverlay.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission(); return@setOnClickListener
            }
            svc.toggleLyricsOverlay()
        }

        // ── Canvas mode ────────────────────────────────────────────────
        // Jika canvas sudah ON → klik tombol langsung OFF (disable canvas mode).
        // Jika canvas OFF → buka CanvasActivity untuk atur posisi lalu lock.
        binding.btnCanvas.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission(); return@setOnClickListener
            }
            val canvasActive = service?.getStateMap()?.get("canvas_mode") as? Boolean ?: false
            if (canvasActive) {
                service?.disableCanvasMode()
                snack("Canvas mode dinonaktifkan")
            } else {
                startActivity(Intent(this, CanvasActivity::class.java))
            }
        }

        binding.btnGrantOverlay.setOnClickListener { requestOverlayPermission() }

        binding.btnFeedback.setOnClickListener { showFeedbackDialog() }

        // ── Lyrics language quick-pick ─────────────────────────────────
        binding.btnLangId.setOnClickListener {
            binding.etLyricsLang.setText("id")
            saveLyricsLang("id")
            snack("Lyrics language: Indonesian")
        }
        binding.btnLangEn.setOnClickListener {
            binding.etLyricsLang.setText("en")
            saveLyricsLang("en")
            snack("Lyrics language: English")
        }

        // ── Save & Connect / Disconnect ────────────────────────────────
        binding.btnSaveSettings.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val state    = svc.getStateMap()
            val tiktokOk = state["tiktok_connected"] as? Boolean ?: false

            if (tiktokOk) {
                // Disconnect
                svc.saveSettings("", "", null)
                snack("Disconnecting…")
                return@setOnClickListener
            }

            val apiKey   = binding.etApiKey.text.toString().trim()
            val username = binding.etTiktokUser.text.toString().trim()
            if (apiKey.isBlank() || username.isBlank()) {
                snack("API Key and TikTok username are required")
                return@setOnClickListener
            }

            val cmdConfig = buildCommandConfig()
            val lyricsLang = binding.etLyricsLang.text.toString().trim().ifBlank { "id" }
            saveLyricsLang(lyricsLang)
            svc.saveSettings(apiKey, username, cmdConfig)
            saveSettingsToPrefs(apiKey, username, cmdConfig)
            snack("Settings saved – connecting TikTok Live…")
        }
    }

    // ── Prefs ─────────────────────────────────────────────────────────
    /**
     * Loads saved values into the EditText fields.
     * Does NOT trigger any connection.
     */
    private fun loadSavedSettings() {
        val p = getSharedPreferences("ytplayer_prefs", MODE_PRIVATE)
        binding.etApiKey.setText(p.getString("euler_api_key", ""))
        binding.etTiktokUser.setText(p.getString("tiktok_username", ""))
        binding.etCmdRequest.setText(p.getString("cmd_request",    "#req,#request,#lagu,#song"))
        binding.etCmdSkip.setText(p.getString("cmd_skip",          "#skip,#next,#lewat"))
        binding.etCmdStop.setText(p.getString("cmd_stop",          "#stop"))
        binding.etCmdQueue.setText(p.getString("cmd_queue",        "#queue,#antrian,#q"))
        binding.etCmdClearMusic.setText(p.getString("cmd_clear_music", "#cm,#hapus"))
        binding.etLyricsLang.setText(p.getString("lyrics_lang", "id"))
    }

    private fun saveLyricsLang(lang: String) {
        getSharedPreferences("ytplayer_prefs", MODE_PRIVATE)
            .edit().putString("lyrics_lang", lang).apply()
    }

    private fun saveSettingsToPrefs(
        apiKey: String,
        username: String,
        cmdConfig: TikTokLiveManager.CommandConfig? = null
    ) {
        val e = getSharedPreferences("ytplayer_prefs", MODE_PRIVATE).edit()
            .putString("euler_api_key",  apiKey)
            .putString("tiktok_username", username)
        cmdConfig?.let { c ->
            e.putString("cmd_request",    c.requestPrefixes.joinToString(","))
            e.putString("cmd_skip",       c.skipPrefixes.joinToString(","))
            e.putString("cmd_stop",       c.stopPrefixes.joinToString(","))
            e.putString("cmd_queue",      c.queuePrefixes.joinToString(","))
            e.putString("cmd_clear_music", c.clearMusicPrefixes.joinToString(","))
        }
        e.apply()
    }

    private fun buildCommandConfig(): TikTokLiveManager.CommandConfig {
        fun field(text: String) = text.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return TikTokLiveManager.CommandConfig(
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
        Log.d("UpdateCheck", "Checking for updates...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://raw.githubusercontent.com/ShinriShoaku/KanaePlayer/master/version.json")
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d("UpdateCheck", "Response code: ${response.code}")
                    if (!response.isSuccessful) {
                        Log.e("UpdateCheck", "Failed to fetch version info: ${response.message}")
                        return@launch
                    }
                    val body = response.body?.string() ?: return@launch
                    Log.d("UpdateCheck", "JSON Body: $body")
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

                    Log.d("UpdateCheck", "Current: $currentVersionCode ($currentVersionName), Remote: ${updateInfo.versionCode} (${updateInfo.versionName})")

                    val isNewerCode = updateInfo.versionCode > currentVersionCode
                    val isDifferentName = (updateInfo.versionCode.toLong() == currentVersionCode && updateInfo.versionName != currentVersionName)

                    if (isNewerCode || isDifferentName) {
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(updateInfo)
                        }
                    } else {
                        Log.d("UpdateCheck", "App is up to date.")
                    }
                }
            } catch (e: Exception) {
                Log.e("UpdateCheck", "Error: ${e.message}", e)
            }
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

    // ── Sync UI from service state ────────────────────────────────────
    @SuppressLint("SetTextI18n")
    private fun syncUi() {
        val svc   = service ?: return
        val state = svc.getStateMap()

        val songJson  = state["current_song"] as? String
        val isPlaying = state["is_playing"]   as? Boolean ?: false
        val isPaused  = state["is_paused"]    as? Boolean ?: false
        val posMs     = state["position_ms"]  as? Long    ?: 0L
        val durMs     = state["duration_ms"]  as? Long    ?: 0L
        val qCount    = state["queue_count"]  as? Int     ?: 0
        val shuffle   = state["shuffle_mode"] as? Boolean ?: false
        val tiktokOk  = state["tiktok_connected"] as? Boolean ?: false
        val ytdlpOk   = state["ytdlp_installed"]  as? Boolean ?: false
        val canvas    = state["canvas_mode"]  as? Boolean ?: false

        binding.tvNowPlaying.text = if (songJson != null) {
            val song = Gson().fromJson(songJson, Song::class.java)
            "▶ ${song.title}"
        } else "– Nothing playing –"

        val posSec = (posMs / 1000).toInt()
        val durSec = (durMs / 1000).toInt()
        binding.tvProgress.text = "${fmt(posSec)} / ${fmt(durSec)}"
        
        val progress = if (durMs > 0) ((posMs * 100) / durMs).toInt().coerceIn(0, 100) else 0
        binding.progressBar.progress = progress

        binding.btnPlayPause.text = if (isPlaying && !isPaused) "⏸ Pause" else "▶ Play"
        binding.tvQueueCount.text = "Queue: $qCount"
        binding.btnShuffle.text   = if (shuffle) "🔀 ON" else "🔀 OFF"

        binding.tvTiktokStatus.text  = if (tiktokOk) "🟢 TikTok Live" else "🔴 TikTok Live"
        binding.btnSaveSettings.text = if (tiktokOk) "🔌 DISCONNECT"  else "💾 Save & Connect"
        binding.btnSaveSettings.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                if (tiktokOk) ContextCompat.getColor(this, R.color.red)
                else          ContextCompat.getColor(this, R.color.orange)
            )

        binding.tvYtdlpStatus.text   = if (ytdlpOk) "🟢 yt-dlp ready" else "🔴 yt-dlp missing"
        binding.btnOverlay.text      = if (svc.overlayVisible) "Hide Overlay" else "Show Overlay"

        // Lyrics overlay button
        val lyricsOn = state["lyrics_visible"] as? Boolean ?: false
        binding.btnLyricsOverlay.text = if (lyricsOn) "🎤 Lyrics ON  ✕" else "🎤 Lyrics"
        binding.btnLyricsOverlay.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                if (lyricsOn) ContextCompat.getColor(this, R.color.orange)
                else          ContextCompat.getColor(this, R.color.lyrics_default)
            )

        // Canvas button: teks dan warna mencerminkan status ON/OFF
        binding.btnCanvas.text = if (canvas) "🎨 Canvas ON  ✕" else "🎨 Canvas"
        binding.btnCanvas.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                if (canvas) ContextCompat.getColor(this, R.color.orange)
                else        ContextCompat.getColor(this, R.color.canvas_default)
            )

        queueAdapter.submitList(svc.getQueue())
    }

    // ── Chat log ──────────────────────────────────────────────────────
    private fun addChatLine(line: String) {
        val tv = binding.tvChatLog
        val lines = tv.text.toString().let {
            if (it.isEmpty()) emptyList() else it.lines()
        }
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

        layout.addView(etUser)
        layout.addView(etMsg)

        MaterialAlertDialogBuilder(this)
            .setTitle("Kirim Feedback")
            .setView(layout)
            .setPositiveButton("Kirim") { _, _ ->
                val user = etUser.text.toString().trim().ifBlank { "Unknown" }
                val feedback = etMsg.text.toString().trim()
                if (feedback.isNotEmpty()) {
                    sendFeedback(user, feedback)
                } else {
                    snack("Feedback tidak boleh kosong")
                }
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

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                OkHttpClient().newCall(request).execute().use { response ->
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            snack("Feedback terkirim, terima kasih!")
                        } else {
                            snack("Gagal mengirim feedback: ${response.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snack("Error: ${e.message}")
                }
            }
        }
    }

    private fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
}

// ── QueueAdapter ──────────────────────────────────────────────────────
class QueueAdapter(
    private val onRemove: (Int) -> Unit,
    private val onPlay:   (Song) -> Unit
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private val items = mutableListOf<Song>()

    fun submitList(list: List<Song>) {
        items.clear(); items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = items[position]
        holder.tvTitle.text = "${position + 1}. ${song.title}"
        holder.tvMeta.text  = buildString {
            if (!song.requestedBy.isNullOrBlank()) append("by ${song.requestedBy} ")
            if (song.duration > 0) append("• ${song.durationFormatted}")
        }
        holder.btnPlay.setOnClickListener   { onPlay(song) }
        holder.btnRemove.setOnClickListener { onRemove(position) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView     = v.findViewById(R.id.tv_song_title)
        val tvMeta: TextView      = v.findViewById(R.id.tv_song_meta)
        val btnPlay: ImageButton  = v.findViewById(R.id.btn_play_now)
        val btnRemove: ImageButton = v.findViewById(R.id.btn_remove)
    }
}
