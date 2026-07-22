package ame.project.kanae.overlay

import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ame.project.kanae.R
import ame.project.kanae.service.PlayerForegroundService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class CustomWebOverlaySettingsActivity : AppCompatActivity() {

    private var customMgr: CustomOverlayManager? = null
    private var service: PlayerForegroundService? = null

    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView
    private val activeHolders = mutableMapOf<String, SlotViewHolder>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? PlayerForegroundService.LocalBinder
            service = localBinder?.getService()
            customMgr = service?.getCustomOverlayManager()
            
            customMgr?.onWidgetVisibilityChanged = { id, isEnabled ->
                runOnUiThread { refreshSlotButton(id, isEnabled) }
            }
            customMgr?.onConfigUpdated = { updated ->
                runOnUiThread { syncSlotView(updated) }
            }
            setupUI()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            customMgr = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_overlay_settings)

        container = findViewById(R.id.slots_container)
        tvEmpty = findViewById(R.id.tv_empty_state)

        // Bind to existing service to get the persistent manager
        val intent = Intent(this, PlayerForegroundService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)

        findViewById<Button>(R.id.btn_hide_all).setOnClickListener {
            customMgr?.hideAll()
            refreshAllButtons()
            snack("Semua overlay dinonaktifkan")
        }

        findViewById<Button>(R.id.btn_add_slot).setOnClickListener {
            showAddDialog()
        }
    }

    override fun onDestroy() {
        unbindService(serviceConnection)
        super.onDestroy()
    }

    private fun setupUI() {
        container.removeAllViews()
        activeHolders.clear()
        val configs = customMgr?.getConfigs() ?: mutableListOf()
        
        tvEmpty.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        
        configs.forEach { config ->
            addSlotView(config)
        }
    }

    private fun syncSlotView(config: CustomOverlayConfig) {
        val holder = activeHolders[config.id] ?: return
        
        holder.etName.setText(config.name)
        holder.etUrl.setText(config.url)

        // Update checkbox Visual Punch tanpa memicu listener (loop)
        holder.cbVisualPunch.setOnCheckedChangeListener(null)
        holder.cbVisualPunch.isChecked = config.visualPunch
        holder.cbVisualPunch.setOnCheckedChangeListener { _, isChecked ->
            updateConfigById(config.id) { it.visualPunch = isChecked }
        }
        
        // Update Auto-Hide & Duration jika berubah dari bottom sheet (jika nanti ditambahkan)
        holder.cbAutoHide.setOnCheckedChangeListener(null)
        holder.cbAutoHide.isChecked = config.autoHide
        holder.layoutDuration.visibility = if (config.autoHide) View.VISIBLE else View.GONE
        holder.cbAutoHide.setOnCheckedChangeListener { _, isChecked ->
            holder.layoutDuration.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateConfigById(config.id) { it.autoHide = isChecked }
        }
        
        holder.sbDuration.progress = config.durationSec
        holder.tvDurationLabel.text = "Durasi: ${config.durationSec} detik"
    }

    private fun addSlotView(config: CustomOverlayConfig) {
        val m = customMgr ?: return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.item_custom_overlay_slot, container, false)
        val holder = SlotViewHolder(view)
        
        holder.etName.setText(config.name)
        holder.etUrl.setText(config.url)
        holder.cbAutoHide.isChecked = config.autoHide
        holder.layoutDuration.visibility = if (config.autoHide) View.VISIBLE else View.GONE
        holder.sbDuration.progress = config.durationSec
        holder.tvDurationLabel.text = "Durasi: ${config.durationSec} detik"
        holder.cbVisualPunch.isChecked = config.visualPunch

        holder.cbAutoHide.setOnCheckedChangeListener { _, isChecked ->
            holder.layoutDuration.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateConfigById(config.id) { it.autoHide = isChecked }
        }

        holder.cbVisualPunch.setOnCheckedChangeListener { _, isChecked ->
            updateConfigById(config.id) { it.visualPunch = isChecked }
        }

        holder.sbDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                if (b) {
                    val duration = p.coerceAtLeast(1)
                    holder.tvDurationLabel.text = "Durasi: $duration detik"
                    updateConfigById(config.id) { it.durationSec = duration }
                }
            }
            override fun onStartTrackingTouch(p: SeekBar?) {}
            override fun onStopTrackingTouch(p: SeekBar?) {}
        })
        
        holder.btnSave.setOnClickListener {
            val name = holder.etName.text.toString().trim()
            val url = holder.etUrl.text.toString().trim()
            
            if (name.isEmpty() || url.isEmpty()) {
                snack("Nama dan URL tidak boleh kosong")
                return@setOnClickListener
            }
            
            updateConfigById(config.id) {
                it.name = name
                it.url = url
            }
            
            snack("Overlay '$name' disimpan")
        }
        
        holder.btnToggle.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) { requestOverlay(); return@setOnClickListener }
            if (config.url.isEmpty()) { snack("Isi URL terlebih dahulu"); return@setOnClickListener }
            
            m.toggleWidget(config.id)
        }
        
        holder.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Hapus Overlay")
                .setMessage("Apakah Anda yakin ingin menghapus '${config.name}'?")
                .setPositiveButton("Hapus") { _, _ ->
                    m.removeConfig(config.id)
                    container.removeView(view)
                    activeHolders.remove(config.id)
                    tvEmpty.visibility = if (m.getConfigs().isEmpty()) View.VISIBLE else View.GONE
                    snack("Overlay dihapus")
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        
        container.addView(view)
        activeHolders[config.id] = holder
        refreshSlotButton(config.id, m.isEnabled(config.id))
    }

    private fun updateConfigById(id: String, block: (CustomOverlayConfig) -> Unit) {
        val m = customMgr ?: return
        val configs = m.getConfigs()
        val index = configs.indexOfFirst { it.id == id }
        if (index == -1) return
        
        // Create a copy to modify so the manager can detect changes
        val config = configs[index].copy()
        block(config)
        m.updateConfig(config)
    }

    private fun showAddDialog() {
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_add_overlay, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_new_name)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_new_url)

        MaterialAlertDialogBuilder(this)
            .setTitle("Tambah Overlay Baru")
            .setView(dialogView)
            .setPositiveButton("Tambah") { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    val newConfig = customMgr?.addConfig(name, url)
                    if (newConfig != null) {
                        tvEmpty.visibility = View.GONE
                        addSlotView(newConfig)
                        snack("Overlay baru ditambahkan")
                    }
                } else {
                    snack("Nama dan URL harus diisi")
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun refreshAllButtons() {
        val m = customMgr ?: return
        activeHolders.forEach { (id, _) ->
            refreshSlotButton(id, m.isEnabled(id))
        }
    }

    private fun refreshSlotButton(id: String, isEnabled: Boolean) {
        val holder = activeHolders[id] ?: return
        if (isEnabled) {
            holder.btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.orange))
            holder.btnToggle.text = "AKTIF"
            holder.btnToggle.setTextColor(Color.BLACK)
        } else {
            holder.btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1F1F3A.toInt())
            holder.btnToggle.text = "NONAKTIF"
            holder.btnToggle.setTextColor(Color.WHITE)
        }
    }

    private fun requestOverlay() {
        overlayLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) snack("Izin overlay diberikan ✓")
        }

    private fun snack(msg: String) =
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show()

    private class SlotViewHolder(view: View) {
        val etName: EditText = view.findViewById(R.id.et_name)
        val etUrl: EditText = view.findViewById(R.id.et_url)
        val btnToggle: Button = view.findViewById(R.id.btn_toggle)
        val btnSave: Button = view.findViewById(R.id.btn_save)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
        val cbAutoHide: CheckBox = view.findViewById(R.id.cb_autohide)
        val cbVisualPunch: CheckBox = view.findViewById(R.id.cb_visual_punch)
        val layoutDuration: View = view.findViewById(R.id.layout_duration)
        val tvDurationLabel: TextView = view.findViewById(R.id.tv_duration_label)
        val sbDuration: SeekBar = view.findViewById(R.id.sb_duration)
    }
}
