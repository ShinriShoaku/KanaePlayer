package ame.project.kanae.saweria

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ame.project.kanae.R
import ame.project.kanae.overlay.SaweriaOverlayManager
import ame.project.kanae.overlay.SaweriaWidget
import com.google.android.material.snackbar.Snackbar

class SaweriaSettingsActivity : AppCompatActivity() {

    companion object {
        // Singleton so overlays survive activity lifecycle
        private var mgr: SaweriaOverlayManager? = null
    }

    private lateinit var etKey: EditText
    private lateinit var tvPreview: TextView
    private lateinit var tvLiveLabel: TextView
    private lateinit var dotLive: View
    private var keyVisible = false

    // widget button id → enum
    private val widgetButtons = mutableMapOf<SaweriaWidget, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saweria_settings)

        if (mgr == null) mgr = SaweriaOverlayManager(applicationContext, lifecycleScope)
        val m = mgr!!

        // Sync button states when widgets are closed from the overlay [X] button
        m.onWidgetVisibilityChanged = { widget, isShowing ->
            runOnUiThread { setButtonActive(widget, isShowing) }
        }

        etKey        = findViewById(R.id.et_stream_key)
        tvPreview    = findViewById(R.id.tv_url_preview)
        tvLiveLabel  = findViewById(R.id.saweria_live_label)
        dotLive      = findViewById(R.id.saweria_live_dot)

        etKey.setText(m.streamKey)
        refreshPreview()
        setLiveStatus(m.streamKey.isNotBlank())

        // Toggle key visibility
        findViewById<ImageButton>(R.id.btn_toggle_key_visibility).setOnClickListener {
            keyVisible = !keyVisible
            etKey.inputType = if (keyVisible)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            etKey.setSelection(etKey.text.length)
        }

        etKey.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshPreview()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // Save
        findViewById<Button>(R.id.btn_save_saweria).setOnClickListener {
            val key = etKey.text.toString().trim()
            if (key.isBlank()) { snack("Masukkan Stream Key"); return@setOnClickListener }
            m.streamKey = key
            setLiveStatus(true)
            refreshPreview()
            refreshAllButtons()
            snack("Stream Key disimpan ✓")
        }

        // Map view IDs → widget enum
        mapOf(
            R.id.btn_widget_alert        to SaweriaWidget.ALERT,
            R.id.btn_widget_topup        to SaweriaWidget.TOPUP,
            R.id.btn_widget_mediashare   to SaweriaWidget.MEDIASHARE,
            R.id.btn_widget_qr           to SaweriaWidget.QR,
            R.id.btn_widget_milestone    to SaweriaWidget.MILESTONE,
            R.id.btn_widget_leaderboard  to SaweriaWidget.LEADERBOARD,
            R.id.btn_widget_recent       to SaweriaWidget.RECENT,
            R.id.btn_widget_wheel        to SaweriaWidget.WHEEL,
            R.id.btn_widget_subathon     to SaweriaWidget.SUBATHON,
            R.id.btn_widget_vote         to SaweriaWidget.VOTE
        ).forEach { (id, widget) ->
            val btn = findViewById<Button>(id)
            widgetButtons[widget] = btn
            btn.setOnClickListener { toggle(widget) }
        }
        refreshAllButtons()

        // Global actions
        findViewById<Button>(R.id.btn_hide_all_saweria).setOnClickListener {
            m.hideAll(); refreshAllButtons(); snack("Semua widget disembunyikan")
        }
        findViewById<Button>(R.id.btn_reload_all_saweria).setOnClickListener {
            if (m.streamKey.isBlank()) { snack("Simpan Stream Key dulu"); return@setOnClickListener }
            if (!Settings.canDrawOverlays(this)) { requestOverlay(); return@setOnClickListener }
            m.reloadAll(); refreshAllButtons(); snack("Semua widget di-reload")
        }
    }

    override fun onResume() { super.onResume(); refreshAllButtons() }

    // ── Toggle a widget ────────────────────────────────────────────────
    private fun toggle(widget: SaweriaWidget) {
        if (!Settings.canDrawOverlays(this)) { requestOverlay(); return }
        val m = mgr ?: return
        if (m.streamKey.isBlank()) { snack("Simpan Stream Key dulu"); return }
        
        val on = m.toggleWidget(widget)
        setButtonActive(widget, on)
        
        if (on) {
            // Jika widget baru dinyalakan, otomatis buka panel pengaturnya
            m.showAdjuster(widget)
            snack("${widget.emoji} ${widget.displayName} aktif & siap diatur")
        } else {
            snack("${widget.displayName} disembunyikan")
        }
    }

    // ── Button styling ─────────────────────────────────────────────────
    private fun refreshAllButtons() {
        val m = mgr ?: return
        SaweriaWidget.entries.forEach { setButtonActive(it, m.isShowing(it)) }
    }

    private fun setButtonActive(widget: SaweriaWidget, active: Boolean) {
        val btn = widgetButtons[widget] ?: return
        if (active) {
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.orange))
            btn.text = "${widget.emoji} ${widget.displayName} ✓"
            btn.setTextColor(android.graphics.Color.BLACK)
        } else {
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1F1F3A.toInt())
            btn.text = "${widget.emoji} ${widget.displayName}"
            btn.setTextColor(android.graphics.Color.WHITE)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────
    private fun refreshPreview() {
        val key = etKey.text.toString().trim()
        tvPreview.text = if (key.isBlank()) "saweria.co/widgets/alert?streamKey=—"
        else "saweria.co/widgets/alert?streamKey=${key.take(8)}…"
    }

    private fun setLiveStatus(ok: Boolean) {
        dotLive.setBackgroundColor(if (ok) 0xFF4CAF50.toInt() else 0xFFFF5252.toInt())
        tvLiveLabel.text = if (ok) "Stream Key tersimpan" else "Belum dikonfigurasi"
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
}