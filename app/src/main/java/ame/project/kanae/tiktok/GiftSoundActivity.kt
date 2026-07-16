package ame.project.kanae.tiktok

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ame.project.kanae.R
import ame.project.kanae.SettingsManager
import ame.project.kanae.GiftSoundConfig
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.InputStreamReader

class GiftSoundActivity : AppCompatActivity() {

    data class Gift(val ids: List<Int>, val name: String, val image_url: String, val diamondCount: Int)

    private lateinit var rvGifts: RecyclerView
    private lateinit var tvSelectedAudio: TextView
    private lateinit var tvInstructions: TextView
    
    private var selectedGift: Gift? = null
    private val gifts = mutableListOf<Gift>()
    private val giftAssets = mutableSetOf<String>()
    private lateinit var adapter: GiftAdapter
    private lateinit var settingsManager: SettingsManager

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            saveGiftSound(it.toString())
            updateUI(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gift_sound)

        settingsManager = SettingsManager.getInstance(this)

        rvGifts = findViewById(R.id.rv_gifts)
        tvSelectedAudio = findViewById(R.id.tv_selected_audio)
        tvInstructions = findViewById(R.id.tv_instructions)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_pick_audio).setOnClickListener {
            if (selectedGift == null) {
                tvInstructions.text = "Pilih gift dulu!"
                tvInstructions.setTextColor(android.graphics.Color.RED)
                return@setOnClickListener
            }
            pickAudio.launch(arrayOf("audio/*"))
        }
        findViewById<View>(R.id.btn_clear_audio).setOnClickListener {
            saveGiftSound(null)
            updateUI(true)
        }

        loadGifts()
        loadGiftAssets()
        setupRecyclerView()
    }

    private fun loadGiftAssets() {
        try {
            assets.list("gift")?.forEach { giftAssets.add(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadGifts() {
        try {
            val inputStream = assets.open("tiktok_gifts.json")
            val reader = InputStreamReader(inputStream)
            val jsonObject = Gson().fromJson(reader, JsonObject::class.java)
            val giftsArray = jsonObject.getAsJsonArray("gifts")
            
            val loadedGifts = mutableListOf<Gift>()
            giftsArray.forEach {
                val obj = it.asJsonObject
                val idElement = obj.get("id") ?: return@forEach
                val idsList = mutableListOf<Int>()
                if (idElement.isJsonArray) {
                    idElement.asJsonArray.forEach { el -> idsList.add(el.asInt) }
                } else if (idElement.isJsonPrimitive) {
                    idsList.add(idElement.asInt)
                }
                
                loadedGifts.add(Gift(
                    idsList,
                    obj.get("name").asString,
                    obj.get("image_url").asString,
                    obj.get("diamond_count").asInt
                ))
            }
            gifts.clear()
            gifts.addAll(loadedGifts.sortedBy { it.diamondCount })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupRecyclerView() {
        adapter = GiftAdapter(gifts) { gift ->
            val oldIds = selectedGift?.ids
            selectedGift = gift
            tvInstructions.text = "Mengatur suara untuk: ${gift.name}"
            tvInstructions.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))
            
            if (oldIds != null) {
                val oldIdx = gifts.indexOfFirst { it.ids == oldIds }
                if (oldIdx != -1) adapter.notifyItemChanged(oldIdx)
            }
            val newIdx = gifts.indexOfFirst { it.ids == gift.ids }
            if (newIdx != -1) adapter.notifyItemChanged(newIdx)

            updateUI(false)
        }
        rvGifts.layoutManager = GridLayoutManager(this, 3)
        rvGifts.adapter = adapter
    }

    private fun updateUI(refreshList: Boolean) {
        val gift = selectedGift ?: return
        val soundConfig = settingsManager.settings.giftSounds.find { it.giftName == gift.name }
        
        if (soundConfig != null) {
            val name = Uri.parse(soundConfig.soundUri).path?.split("/")?.lastOrNull() ?: "Selected"
            tvSelectedAudio.text = name
        } else {
            tvSelectedAudio.text = "No Audio Selected"
        }
        
        if (refreshList) {
            val idx = gifts.indexOfFirst { it.ids == gift.ids }
            if (idx != -1) adapter.notifyItemChanged(idx)
        }
    }

    private fun saveGiftSound(uriString: String?) {
        val gift = selectedGift ?: return
        val cleanName = gift.name.trim()
        val giftId = gift.ids.firstOrNull()?.toString() ?: "0"
        
        settingsManager.settings.giftSounds.removeAll { it.giftName == cleanName }
        if (uriString != null) {
            settingsManager.settings.giftSounds.add(GiftSoundConfig(giftId, cleanName, uriString))
        }
        settingsManager.saveSettings()
    }

    inner class GiftAdapter(
        private val list: List<Gift>,
        private val onClick: (Gift) -> Unit
    ) : RecyclerView.Adapter<GiftAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.iv_gift_icon)
            val tv: TextView = v.findViewById(R.id.tv_gift_name)
            val diamonds: TextView = v.findViewById(R.id.tv_diamond_count)
            val dot: TextView = v.findViewById(R.id.tv_has_sound)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_gift_sound, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val gift = list[position]
            holder.tv.text = gift.name
            holder.diamonds.text = "${gift.diamondCount} 💎"
            
            val baseName = gift.name.replace("'", "")
            val fileName = when {
                giftAssets.contains("$baseName.png") -> "$baseName.png"
                giftAssets.contains("$baseName.webp") -> "$baseName.webp"
                else -> "$baseName.png"
            }
            
            Glide.with(holder.itemView).load("file:///android_asset/gift/$fileName")
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(holder.iv)

            val hasSound = settingsManager.settings.giftSounds.any { it.giftName == gift.name }
            holder.dot.visibility = if (hasSound) View.VISIBLE else View.GONE
            
            val isSelected = selectedGift?.ids == gift.ids
            holder.itemView.alpha = if (isSelected) 1.0f else 0.7f
            holder.itemView.setBackgroundColor(if (isSelected) 0xFF3F3F5A.toInt() else 0xFF1F1F3A.toInt())

            holder.itemView.setOnClickListener { onClick(gift) }
        }

        override fun getItemCount() = list.size
    }
}
