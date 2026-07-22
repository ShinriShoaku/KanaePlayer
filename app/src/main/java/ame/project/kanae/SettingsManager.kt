package ame.project.kanae

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import java.io.File
import ame.project.kanae.overlay.CustomOverlayConfig

data class OverlayConfig(
    var x: Int = 100,
    var y: Int = 100,
    var scale: Float = 1.0f,
    var width: Int = 0,
    var height: Int = 0,
    var visualPunch: Boolean = false,
    var layoutKey: String? = null,
    var itemKey: String? = null,
    var bgKey: String? = null,
    var bgColor: Int = 0,
    @Deprecated("Use layoutKey") var layoutId: Int = 0,
    @Deprecated("Use itemKey") var itemId: Int = 0,
    @Deprecated("Use bgKey") var bgId: Int = 0
)

data class CustomThemeConfig(
    var bgPrimary: Int? = null,
    var bgSecondary: Int? = null,
    var textPrimary: Int? = null,
    var textSecondary: Int? = null,
    var alpha: Int = 255
)

data class GiftSoundConfig(
    val giftId: String,
    val giftName: String,
    val soundUri: String
)

data class AppSettings(
    var tiktokApiKey: String = "",
    var tiktokUsername: String = "",
    var requestLimit: Int = 3,
    var lyricsLang: String = "id",
    var shuffleMode: Boolean = false,
    var musicVolume: Float = 1.0f,
    var notifVolume: Float = 1.0f,
    var useTiktokGiftIcon: Boolean = true,
    var useCustomGiftSound: Boolean = false,
    
    var commandsEnabled: Boolean = true,
    var cmdRequest: String = "#req,#request,#lagu,#song",
    var cmdSkip: String = "#skip,#next,#lewat",
    var cmdStop: String = "#stop",
    var cmdQueue: String = "#queue,#antrian,#q",
    var cmdClearMusic: String = "#cm,#hapus",

    var notifEnabled: Boolean = true,
    var joinEnabled: Boolean = true,
    var likeEnabled: Boolean = true,
    var followEnabled: Boolean = false,
    
    var chatMaxLines: Int = 5,
    var chatTransparent: Boolean = true,
    var chatDuration: Int = 6,
    var chatAlwaysShow: Boolean = false,
    var chatHistoryEnabled: Boolean = false,
    var chatStickerAnimation: Boolean = true,
    var chatTtsEnabled: Boolean = false,
    var chatTtsVolume: Float = 1.0f,
    var chatTtsMaxLength: Int = 100,

    var queueAutoHide: Boolean = false,
    var queueDuration: Int = 10,

    var notifShareImg: String? = null,
    var notifGiftImg: String? = null,
    var notifShareAud: String? = null,
    var notifGiftAud: String? = null,
    var notifDuration: Int = 5,

    var joinDuration: Int = 4,
    var likeDuration: Int = 4,
    var followDuration: Int = 4,
    var likeAnimationEnabled: Boolean = true,

    var overlays: MutableMap<String, OverlayConfig> = mutableMapOf(),
    var themes: MutableMap<String, CustomThemeConfig> = mutableMapOf(),
    
    var authorizedUsers: String = "",

    var canvasModeEnabled: Boolean = false,
    var canvasPlayerX: Int = 16,
    var canvasPlayerY: Int = 100,
    var canvasQueueX: Int = 16,
    var canvasQueueY: Int = 440,

    var customWebConfigs: MutableList<CustomOverlayConfig> = mutableListOf(),
    var giftSounds: MutableList<GiftSoundConfig> = mutableListOf(),
    var quickOverlayPosition: String = "BOTTOM_RIGHT",
    var quickOverlayMappingsJson: String = "",
    var keyboardMappingJson: String = ""
)

class SettingsManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val settingsFile = File(appContext.filesDir, "app_settings.json")
    
    var settings: AppSettings = AppSettings()
        private set

    init {
        loadSettings()
    }

    companion object {
        @Volatile private var INSTANCE: SettingsManager? = null
        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) { INSTANCE ?: SettingsManager(context).also { INSTANCE = it } }
        }
    }

    fun loadSettings() {
        if (settingsFile.exists()) {
            try {
                val json = settingsFile.readText()
                settings = gson.fromJson(json, AppSettings::class.java) ?: AppSettings()
                Log.d("SettingsManager", "Settings loaded from JSON")
            } catch (e: Exception) {
                Log.e("SettingsManager", "Error loading settings: ${e.message}")
                settings = AppSettings()
            }
        } else {
            Log.d("SettingsManager", "No settings file found, generating defaults...")
            settings = AppSettings()
            prepopulateDefaults()
            migrateFromPrefs()
            saveSettings()
        }
    }

    fun prepopulateDefaults() {
        val keys = listOf("player", "queue", "lyrics", "chat", "notif", "join", "like", "follow")
        keys.forEach { getOverlayConfig(it) }
        Log.d("SettingsManager", "Default overlay configs prepopulated")
    }

    fun saveSettings() {
        try {
            val json = gson.toJson(settings)
            settingsFile.writeText(json)
            Log.d("SettingsManager", "Settings saved to JSON")
        } catch (e: Exception) {
            Log.e("SettingsManager", "Error saving settings: ${e.message}")
        }
    }

    private fun migrateFromPrefs() {
        val mainPrefs = appContext.getSharedPreferences("ytplayer_prefs", Context.MODE_PRIVATE)
        if (mainPrefs.all.isNotEmpty()) {
            settings.apply {
                tiktokApiKey = mainPrefs.getString("euler_api_key", "") ?: ""
                tiktokUsername = mainPrefs.getString("tiktok_username", "") ?: ""
                requestLimit = mainPrefs.getInt("request_limit", 3)
                lyricsLang = mainPrefs.getString("lyrics_lang", "id") ?: "id"
                shuffleMode = mainPrefs.getBoolean("shuffle_mode", false)
                musicVolume = mainPrefs.getFloat("music_volume", 1.0f)
                notifVolume = mainPrefs.getFloat("notif_volume", 1.0f)
                useTiktokGiftIcon = mainPrefs.getBoolean("use_tiktok_gift_icon", true)
                useCustomGiftSound = mainPrefs.getBoolean("use_custom_gift_sound", false)
                commandsEnabled = mainPrefs.getBoolean("commands_enabled", true)
                cmdRequest = mainPrefs.getString("cmd_request", "#req,#request,#lagu,#song") ?: "#req,#request,#lagu,#song"
                cmdSkip = mainPrefs.getString("cmd_skip", "#skip,#next,#lewat") ?: "#skip,#next,#lewat"
                cmdStop = mainPrefs.getString("cmd_stop", "#stop") ?: "#stop"
                cmdQueue = mainPrefs.getString("cmd_queue", "#queue,#antrian,#q") ?: "#queue,#antrian,#q"
                cmdClearMusic = mainPrefs.getString("cmd_clear_music", "#cm,#hapus") ?: "#cm,#hapus"
                notifEnabled = mainPrefs.getBoolean("notif_enabled", true)
                joinEnabled = mainPrefs.getBoolean("join_enabled", true)
                likeEnabled = mainPrefs.getBoolean("like_enabled", true)
                followEnabled = mainPrefs.getBoolean("follow_enabled", false)
                chatMaxLines = mainPrefs.getInt("chat_max_lines", 5)
                chatTransparent = mainPrefs.getBoolean("chat_transparent", true)
                chatDuration = mainPrefs.getInt("chat_duration", 6)
                chatAlwaysShow = mainPrefs.getBoolean("chat_always_show", false)
                chatHistoryEnabled = mainPrefs.getBoolean("chat_history_enabled", false)
                chatStickerAnimation = mainPrefs.getBoolean("chat_sticker_animation", true)
                chatTtsEnabled = mainPrefs.getBoolean("chat_tts_enabled", false)
                chatTtsVolume = mainPrefs.getFloat("chat_tts_volume", 1.0f)
                chatTtsMaxLength = mainPrefs.getInt("chat_tts_max_length", 100)
                queueAutoHide = mainPrefs.getBoolean("queue_auto_hide", false)
                queueDuration = mainPrefs.getInt("queue_duration", 10)
                notifShareImg = mainPrefs.getString("notif_share_img", null)
                notifGiftImg = mainPrefs.getString("notif_gift_img", null)
                notifShareAud = mainPrefs.getString("notif_share_aud", null)
                notifGiftAud = mainPrefs.getString("notif_gift_aud", null)
                notifDuration = mainPrefs.getInt("notif_duration", 5)
                joinDuration = mainPrefs.getInt("join_duration", 4)
                likeDuration = mainPrefs.getInt("like_duration", 4)
                followDuration = mainPrefs.getInt("follow_duration", 4)
                likeAnimationEnabled = mainPrefs.getBoolean("like_animation_enabled", true)
                authorizedUsers = mainPrefs.getString("authorized_users", "") ?: ""
                canvasPlayerX = mainPrefs.getInt("canvas_px", 16)
                canvasPlayerY = mainPrefs.getInt("canvas_py", 100)
                canvasQueueX = mainPrefs.getInt("canvas_qx", 16)
                canvasQueueY = mainPrefs.getInt("canvas_qy", 440)
                
                listOf("player", "queue", "lyrics", "chat", "notif", "join", "like", "follow").forEach { key ->
                    val cfg = OverlayConfig(
                        x = mainPrefs.getInt("${key}_x", 100), y = mainPrefs.getInt("${key}_y", 100),
                        scale = mainPrefs.getFloat("${key}_scale", 1.0f),
                        width = mainPrefs.getInt("${key}_width", if (key == "chat") 150 else 0),
                        height = mainPrefs.getInt("${key}_height", 0),
                        visualPunch = mainPrefs.getBoolean("${key}_visual_punch", false),
                        layoutId = mainPrefs.getInt("canvas_${key}_layout", 0),
                        itemId = if (key == "queue") mainPrefs.getInt("canvas_queue_item_layout", 0) else 0,
                        bgId = if (key == "chat") mainPrefs.getInt("canvas_chat_bg", 0) else 0
                    )
                    overlays[key] = cfg
                }
            }
        }

        appContext.getSharedPreferences("custom_overlay_prefs_v5", Context.MODE_PRIVATE).getString("configs_json", null)?.let {
            try { settings.customWebConfigs = com.google.gson.Gson().fromJson(it, object : com.google.gson.reflect.TypeToken<MutableList<CustomOverlayConfig>>() {}.type) } catch (_: Exception) {}
        }
        val quickPrefs = appContext.getSharedPreferences("quick_overlay_prefs", Context.MODE_PRIVATE)
        quickPrefs.getString("mappings", null)?.let { settings.quickOverlayMappingsJson = it }
        settings.quickOverlayPosition = quickPrefs.getString("position", "BOTTOM_RIGHT") ?: "BOTTOM_RIGHT"
        appContext.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE).getString("key_mappings", null)?.let { settings.keyboardMappingJson = it }
        appContext.getSharedPreferences("gift_sounds_prefs", Context.MODE_PRIVATE).getString("gift_sounds_json", null)?.let {
            try { settings.giftSounds = com.google.gson.Gson().fromJson(it, object : com.google.gson.reflect.TypeToken<MutableList<GiftSoundConfig>>() {}.type) } catch (_: Exception) {}
        }
    }

    fun getOverlayConfig(key: String): OverlayConfig {
        val config = settings.overlays.getOrPut(key) {
            val default = when (key) {
                "player" -> OverlayConfig(x = 16, y = 100, layoutKey = "player_standard")
                "queue" -> OverlayConfig(x = 16, y = 420, width = 300, layoutKey = "queue_standard", itemKey = "item_queue")
                "lyrics" -> OverlayConfig(x = 16, y = 750, layoutKey = "lyrics_standard")
                "chat" -> OverlayConfig(x = 16, y = 500, width = 150, layoutKey = "chat_boxed")
                "notif" -> OverlayConfig(x = 16, y = 100, layoutKey = "notif_standard", bgKey = "overlay_bg")
                "join" -> OverlayConfig(x = 16, y = 200, layoutKey = "join_card")
                "like" -> OverlayConfig(x = 16, y = 300, layoutKey = "like_card")
                "follow" -> OverlayConfig(x = 16, y = 400, layoutKey = "follow_standard")
                else -> OverlayConfig()
            }
            Log.d("SettingsManager", "Generated fresh default for $key: ${default.layoutKey}")
            default
        }
        
        // Migration: If keys are missing but old IDs exist, map them once
        // WARNING: R.layout IDs are not stable across builds.
        if (config.layoutKey == null && config.layoutId != 0) {
            val potentialKey = LayoutMapper.getLayoutKey(config.layoutId)
            // Validate that the mapped key actually belongs to this overlay type
            if (potentialKey != null && potentialKey.startsWith(key)) {
                config.layoutKey = potentialKey
                
                // Also migrate itemId if relevant
                if (key == "queue" && config.itemId != 0) {
                    val potItemKey = LayoutMapper.getLayoutKey(config.itemId)
                    if (potItemKey != null && potItemKey.startsWith("item_")) {
                        config.itemKey = potItemKey
                    }
                }
                
                // Also migrate bgId if relevant
                if (config.bgId != 0) {
                    val potBgKey = LayoutMapper.getDrawableKey(config.bgId)
                    if (potBgKey != null && (potBgKey.startsWith("bg_") || potBgKey == "overlay_bg")) {
                        config.bgKey = potBgKey
                    }
                }
            }
        }

        // Anti-bug: Jika layoutKey masih null, paksa balik ke default
        if (config.layoutKey == null) {
            Log.w("SettingsManager", "Overlay $key had no layoutKey, resetting to default...")
            when(key) {
                "chat" -> config.layoutKey = "chat_boxed"
                "player" -> config.layoutKey = "player_standard"
                "queue" -> { config.layoutKey = "queue_standard"; config.itemKey = "item_queue" }
                "lyrics" -> config.layoutKey = "lyrics_standard"
                "notif" -> { config.layoutKey = "notif_standard"; config.bgKey = "overlay_bg" }
                "join" -> config.layoutKey = "join_card"
                "like" -> config.layoutKey = "like_card"
                "follow" -> config.layoutKey = "follow_standard"
            }
        }
        return config
    }

    fun getThemeConfig(key: String): CustomThemeConfig {
        return settings.themes.getOrPut(key) { CustomThemeConfig() }
    }
}
