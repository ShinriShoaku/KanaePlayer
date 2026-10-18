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

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.Keep
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Keep
data class KeyMapping(
    val keyCode: Int,
    val keyName: String,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    var audioUri: String? = null
) {
    fun getDisplayName(): String {
        val parts = mutableListOf<String>()
        if (ctrl) parts.add("Ctrl")
        if (shift) parts.add("Shift")
        if (alt) parts.add("Alt")
        parts.add(keyName)
        return parts.joinToString(" + ")
    }
}

class MappingActivity : AppCompatActivity() {

    private lateinit var rvMappings: RecyclerView
    private lateinit var adapter: MappingAdapter
    private val mappings = mutableListOf<KeyMapping>()
    private lateinit var settingsManager: SettingsManager
    private val gson = Gson()

    private var pendingMapping: KeyMapping? = null
    private var mediaPlayer: MediaPlayer? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pendingMapping?.audioUri = it.toString()
            pendingMapping?.let { m ->
                mappings.add(m)
                saveMappings()
                adapter.notifyDataSetChanged()
                snack("Mapping added: ${m.getDisplayName()}")
            }
            pendingMapping = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mapping)

        settingsManager = SettingsManager.getInstance(this)
        loadMappings()

        rvMappings = findViewById(R.id.rv_mappings)
        rvMappings.layoutManager = LinearLayoutManager(this)
        adapter = MappingAdapter()
        rvMappings.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab_add_mapping).setOnClickListener {
            showAddMappingDialog()
        }
    }

    private fun showAddMappingDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Add New Mapping")
            .setMessage("Press a key on your external keyboard...")
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnKeyListener { d, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
                    return@setOnKeyListener false
                }

                val keyName = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                val newMapping = KeyMapping(
                    keyCode = keyCode,
                    keyName = keyName,
                    ctrl = event.isCtrlPressed,
                    shift = event.isShiftPressed,
                    alt = event.isAltPressed
                )

                d.dismiss()
                pendingMapping = newMapping
                pickAudio.launch(arrayOf("audio/*"))
                true
            } else false
        }
        dialog.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val device = event.device
        val isExternal = device != null && !device.isVirtual && 
                (device.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD)
        
        if (!isExternal) return super.onKeyDown(keyCode, event)

        val mapping = mappings.find {
            it.keyCode == keyCode && 
            it.ctrl == event.isCtrlPressed && 
            it.shift == event.isShiftPressed && 
            it.alt == event.isAltPressed
        }

        if (mapping != null) {
            playAudio(mapping.audioUri)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun playAudio(uriStr: String?) {
        if (uriStr == null) return
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MappingActivity, Uri.parse(uriStr))
                prepare()
                start()
            }
        } catch (e: Exception) {
            snack("Error playing audio: ${e.message}")
        }
    }

    private fun saveMappings() {
        settingsManager.settings.keyboardMappingJson = gson.toJson(mappings)
        settingsManager.saveSettings()
    }

    private fun loadMappings() {
        val json = settingsManager.settings.keyboardMappingJson
        if (json.isEmpty()) return
        try {
            val type = object : TypeToken<List<KeyMapping>>() {}.type
            val list: List<KeyMapping> = gson.fromJson(json, type)
            mappings.clear()
            mappings.addAll(list)
        } catch (_: Exception) {}
    }

    private fun snack(msg: String) =
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }

    inner class MappingAdapter : RecyclerView.Adapter<MappingAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_key_mapping, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = mappings[position]
            holder.tvCombo.text = m.getDisplayName()
            holder.tvPath.text = Uri.parse(m.audioUri ?: "").path?.split("/")?.lastOrNull() ?: "No File"
            
            holder.btnDelete.setOnClickListener {
                mappings.removeAt(position)
                saveMappings()
                notifyDataSetChanged()
            }
            holder.btnTest.setOnClickListener {
                playAudio(m.audioUri)
            }
        }
        override fun getItemCount() = mappings.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvCombo: TextView = v.findViewById(R.id.tv_key_combo)
            val tvPath: TextView = v.findViewById(R.id.tv_audio_path)
            val btnTest: ImageButton = v.findViewById(R.id.btn_test_sound)
            val btnDelete: ImageButton = v.findViewById(R.id.btn_delete_mapping)
        }
    }
}
