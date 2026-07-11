package ame.project.kanae

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ame.project.kanae.overlay.QuickOverlayManager
import ame.project.kanae.overlay.SoundButtonConfig

data class SoundMapping(
    var id: String = System.currentTimeMillis().toString(),
    var label: String,
    var audioUri: String? = null,
    var reactionText: String? = null,
    var reactionImageUri: String? = null,
    var layoutType: Int = 0,
    var autoHide: Boolean = true,
    var audioDurationMs: Long = 0,
    var posX: Int = 0,
    var posY: Int = 0,
    var mappingType: Int = 0 // 0: Sound, 1: Overlay, 2: Animation
)

class QuickOverlayActivity : AppCompatActivity() {

    companion object {
        private var overlayManager: QuickOverlayManager? = null
    }

    private lateinit var rvMappings: RecyclerView
    private lateinit var adapter: QuickOverlayAdapter
    private lateinit var rvPreviewButtons: RecyclerView
    private lateinit var previewPanel: View
    private lateinit var previewMinimized: View

    private val mappings = mutableListOf<SoundMapping>()
    private val gson = Gson()
    private var mediaPlayer: MediaPlayer? = null
    
    private var activeBottomSheet: BottomSheetDialog? = null
    private var editingMapping: SoundMapping? = null
    private var tvAudioPath: TextView? = null
    private var tvImagePath: TextView? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val duration = getAudioDuration(it)
            if (duration > 11000) {
                snack("Error: Audio too long (${duration / 1000}s). Max 11s.")
                return@let
            }
            editingMapping?.audioUri = it.toString()
            editingMapping?.audioDurationMs = duration
            tvAudioPath?.text = it.path?.split("/")?.lastOrNull() ?: it.toString()
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            editingMapping?.reactionImageUri = it.toString()
            tvImagePath?.text = it.path?.split("/")?.lastOrNull() ?: it.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_overlay)
        loadMappings()

        rvMappings = findViewById(R.id.rv_mappings)
        rvMappings.layoutManager = LinearLayoutManager(this)
        adapter = QuickOverlayAdapter()
        rvMappings.adapter = adapter
        
        setupPreview()
        setupControls()
    }

    private fun setupPreview() {
        val previewRoot = findViewById<View>(R.id.included_preview)
        previewPanel = previewRoot.findViewById(R.id.panel_expanded)
        previewMinimized = previewRoot.findViewById(R.id.btn_minimized)
        rvPreviewButtons = previewRoot.findViewById(R.id.rv_sound_buttons)
        
        previewPanel.visibility = View.VISIBLE
        previewMinimized.visibility = View.GONE
        
        val spanCount = resources.getInteger(R.integer.quick_overlay_span_count)
        rvPreviewButtons.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, spanCount)
        rvPreviewButtons.adapter = PreviewSoundAdapter()
    }

    private fun setupControls() {
        val switch = findViewById<SwitchMaterial>(R.id.switch_enable)
        switch.isChecked = overlayManager?.isShowing() == true
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                    switch.isChecked = false
                } else {
                    startOverlay()
                }
            } else {
                stopOverlay()
            }
        }

        findViewById<View>(R.id.btn_add_mapping).setOnClickListener {
            showTypeSelectionDialog()
        }

        val togglePosition = findViewById<MaterialButtonToggleGroup>(R.id.toggle_position)
        val savedPos = getSharedPreferences("quick_overlay_prefs", MODE_PRIVATE).getString("position", "MID")
        togglePosition.check(when(savedPos) {
            "TOP" -> R.id.btn_pos_top
            "BOTTOM" -> R.id.btn_pos_bottom
            else -> R.id.btn_pos_center
        })
        
        togglePosition.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val pos = when(checkedId) {
                    R.id.btn_pos_top -> "TOP"
                    R.id.btn_pos_bottom -> "BOTTOM"
                    else -> "MID"
                }
                getSharedPreferences("quick_overlay_prefs", MODE_PRIVATE).edit().putString("position", pos).apply()
                overlayManager?.setPosition(pos)
            }
        }
        syncOverlay()
        updatePreview()
    }

    private fun showTypeSelectionDialog() {
        val options = arrayOf("Soundbox Button", "Overlay Reaction", "Animation Reaction")
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose Mapping Type")
            .setItems(options) { _, which ->
                val newMapping = SoundMapping(label = "NEW", mappingType = which)
                showEditBottomSheet(newMapping, isNew = true)
            }
            .show()
    }

    private fun showEditBottomSheet(mapping: SoundMapping, isNew: Boolean) {
        editingMapping = mapping
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_quick_mapping, null)
        dialog.setContentView(view)
        activeBottomSheet = dialog

        val etLabel = view.findViewById<TextInputEditText>(R.id.et_label)
        val etReaction = view.findViewById<TextInputEditText>(R.id.et_reaction_text)
        val spinnerLayout = view.findViewById<Spinner>(R.id.spinner_layout)
        val spinnerAnim = view.findViewById<Spinner>(R.id.spinner_animation_effect)
        val cbAutoHide = view.findViewById<CheckBox>(R.id.cb_item_autohide)
        tvAudioPath = view.findViewById(R.id.tv_audio_path)
        tvImagePath = view.findViewById(R.id.tv_image_path)

        val sectionSound = view.findViewById<View>(R.id.section_sound)
        val sectionOverlay = view.findViewById<View>(R.id.section_overlay)
        val sectionAnimation = view.findViewById<View>(R.id.section_animation)

        // Show/hide sections based on type
        sectionSound.visibility = if (mapping.mappingType == 0) View.VISIBLE else View.GONE
        sectionOverlay.visibility = if (mapping.mappingType == 1) View.VISIBLE else View.GONE
        sectionAnimation.visibility = if (mapping.mappingType == 2) View.VISIBLE else View.GONE

        etLabel.setText(mapping.label)
        etReaction.setText(mapping.reactionText)
        cbAutoHide.isChecked = mapping.autoHide
        
        tvAudioPath?.text = mapping.audioUri?.let { Uri.parse(it).path?.split("/")?.lastOrNull() } ?: "No file selected"
        tvImagePath?.text = mapping.reactionImageUri?.let { Uri.parse(it).path?.split("/")?.lastOrNull() } ?: "No image selected"

        val layouts = arrayOf("Default", "Sketch", "Glass")
        spinnerLayout.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, layouts)
        spinnerLayout.setSelection(mapping.layoutType)

        val animations = arrayOf(
            "Burst", "Hearts", "Snow", "Rainbow", "Confetti", "Fireworks",
            "Sparkle", "Bubbles", "Glitter", "Spiral", "Petals", "Leaves", "Stardust", "Meteor"
        )
        spinnerAnim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, animations)
        if (mapping.mappingType == 2) spinnerAnim.setSelection(mapping.layoutType)

        view.findViewById<View>(R.id.btn_pick_audio).setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        view.findViewById<View>(R.id.btn_pick_image).setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        
        view.findViewById<View>(R.id.btn_save).setOnClickListener {
            mapping.label = etLabel.text.toString().trim().takeIf { it.isNotEmpty() } ?: "BTN"
            mapping.reactionText = etReaction.text.toString().trim().takeIf { it.isNotEmpty() }
            
            mapping.layoutType = if (mapping.mappingType == 2) {
                spinnerAnim.selectedItemPosition
            } else {
                spinnerLayout.selectedItemPosition
            }

            mapping.autoHide = cbAutoHide.isChecked
            
            if (isNew) mappings.add(mapping)
            saveMappings()
            syncOverlay()
            adapter.notifyDataSetChanged()
            updatePreview()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun startOverlay() {
        if (overlayManager == null) {
            overlayManager = QuickOverlayManager(applicationContext).apply {
                onSoundClicked = { config -> playAudio(config.audioUri) }
                onPositionUpdated = { id, x, y ->
                    mappings.find { it.id == id }?.let {
                        it.posX = x
                        it.posY = y
                        saveMappings()
                    }
                }
                onTextUpdated = { id, text ->
                    mappings.find { it.id == id }?.let {
                        it.reactionText = text
                        saveMappings()
                        runOnUiThread { adapter.notifyDataSetChanged() }
                    }
                }
                val prefs = getSharedPreferences("quick_overlay_prefs", MODE_PRIVATE)
                val pos = prefs.getString("position", "MID") ?: "MID"
                setPosition(pos)
            }
        }
        overlayManager?.show()
        syncOverlay()
    }

    private fun stopOverlay() {
        overlayManager?.hide()
        overlayManager = null
    }

    private fun syncOverlay() {
        // Ensure all mappings have IDs before syncing
        mappings.forEach { 
            if (it.id.isNullOrEmpty()) {
                it.id = "id_" + System.currentTimeMillis() + "_" + it.label.hashCode()
            }
        }

        overlayManager?.updateButtons(mappings.map { 
            SoundButtonConfig(it.id, it.label, it.audioUri, it.reactionText, it.reactionImageUri, it.layoutType, it.autoHide, it.audioDurationMs, it.posX, it.posY, it.mappingType)
        })
    }
    
    private fun updatePreview() {
        rvPreviewButtons.adapter?.notifyDataSetChanged()
        previewPanel.findViewById<View>(R.id.tv_empty_hint)?.visibility = 
            if (mappings.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun playAudio(uriStr: String?) {
        if (uriStr == null) return
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@QuickOverlayActivity, Uri.parse(uriStr))
                prepare()
                start()
            }
        } catch (e: Exception) {
            snack("Error: ${e.message}")
        }
    }

    private fun getAudioDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (_: Exception) { 0L } finally { retriever.release() }
    }

    private fun saveMappings() {
        getSharedPreferences("quick_overlay_prefs", MODE_PRIVATE).edit().putString("mappings", gson.toJson(mappings)).apply()
    }

    private fun loadMappings() {
        val json = getSharedPreferences("quick_overlay_prefs", MODE_PRIVATE).getString("mappings", null) ?: return
        val type = object : TypeToken<List<SoundMapping>>() {}.type
        val list: List<SoundMapping> = gson.fromJson(json, type) ?: return
        
        mappings.clear()
        var needsSave = false
        list.forEach { m ->
            if (m.id.isNullOrEmpty()) {
                m.id = "id_" + System.currentTimeMillis() + "_" + (m.label.hashCode())
                needsSave = true
            }
            mappings.add(m)
        }
        if (needsSave) saveMappings()
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun snack(msg: String) = Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }

    inner class QuickOverlayAdapter : RecyclerView.Adapter<QuickOverlayAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_quick_overlay_mapping, parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = mappings[position]
            holder.tvLabel.text = m.label
            holder.tvFile.text = when(m.mappingType) {
                0 -> m.audioUri?.let { Uri.parse(it).path?.split("/")?.lastOrNull() } ?: "No Sound"
                1 -> "Overlay: ${m.reactionText ?: m.reactionImageUri?.let { "Image" } ?: "Empty"}"
                2 -> {
                    val anims = arrayOf(
                        "Burst", "Hearts", "Snow", "Rainbow", "Confetti", "Fireworks",
                        "Sparkle", "Bubbles", "Glitter", "Spiral", "Petals", "Leaves", "Stardust", "Meteor"
                    )
                    "Animation: ${if (m.layoutType < anims.size) anims[m.layoutType] else "Unknown"}"
                }
                else -> "Unknown"
            }
            holder.itemView.setOnClickListener { showEditBottomSheet(m, isNew = false) }
            holder.btnDelete.setOnClickListener {
                mappings.removeAt(position)
                saveMappings()
                syncOverlay()
                notifyDataSetChanged()
                updatePreview()
            }
            holder.btnTest.setOnClickListener { playAudio(m.audioUri) }
        }
        override fun getItemCount() = mappings.size
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvLabel: TextView = v.findViewById(R.id.tv_button_label)
            val tvFile: TextView = v.findViewById(R.id.tv_audio_file)
            val btnTest: View = v.findViewById(R.id.btn_test)
            val btnDelete: View = v.findViewById(R.id.btn_delete)
        }
    }

    inner class PreviewSoundAdapter : RecyclerView.Adapter<PreviewSoundAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sound_button, parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tvLabel.text = mappings[position].label
        }
        override fun getItemCount() = mappings.size
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvLabel: TextView = v.findViewById(R.id.tv_sound_label)
        }
    }
}
