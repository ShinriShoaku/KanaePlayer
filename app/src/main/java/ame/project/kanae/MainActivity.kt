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
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
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

        startForegroundService(Intent(this, PlayerForegroundService::class.java))
        bindService(
            Intent(this, PlayerForegroundService::class.java),
            connection, BIND_AUTO_CREATE
        )

        val filter = IntentFilter().apply {
            addAction(PlayerForegroundService.BROADCAST_STATE)
            addAction(PlayerForegroundService.BROADCAST_CHAT)
        }
        registerReceiver(stateReceiver, filter, RECEIVER_EXPORTED)

        checkPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) { unbindService(connection); serviceBound = false }
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
        binding.progressBar.progress = if (durMs > 0) (posMs * 100 / durMs).toInt() else 0

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
