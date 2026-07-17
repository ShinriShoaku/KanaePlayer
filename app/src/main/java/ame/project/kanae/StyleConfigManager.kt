package ame.project.kanae

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

data class StyleThemeConfig(
    var bgPrimary: Int? = null,
    var bgSecondary: Int? = null,
    var textPrimary: Int? = null,
    var textSecondary: Int? = null,
    var alpha: Int = 255
)

class StyleConfigManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile = File(appContext.filesDir, "style_configs.json")
    
    // Key: ComponentName_LayoutName (e.g., "chat_item_chat_bubble_boxed")
    private var styleThemes: MutableMap<String, StyleThemeConfig> = mutableMapOf()

    init {
        loadConfigs()
    }

    companion object {
        @Volatile private var INSTANCE: StyleConfigManager? = null
        fun getInstance(context: Context): StyleConfigManager {
            return INSTANCE ?: synchronized(this) { 
                INSTANCE ?: StyleConfigManager(context).also { INSTANCE = it } 
            }
        }
    }

    fun loadConfigs() {
        if (configFile.exists()) {
            try {
                val json = configFile.readText()
                val type = object : TypeToken<MutableMap<String, StyleThemeConfig>>() {}.type
                styleThemes = gson.fromJson(json, type) ?: mutableMapOf()
            } catch (_: Exception) {
                styleThemes = mutableMapOf()
            }
        }
    }

    fun saveConfigs() {
        try {
            val json = gson.toJson(styleThemes)
            configFile.writeText(json)
        } catch (_: Exception) {}
    }

    fun getStyleTheme(component: String, layoutKey: String?): StyleThemeConfig {
        if (layoutKey == null) return StyleThemeConfig()
        val key = "${component.lowercase()}_$layoutKey"
        return styleThemes.getOrPut(key) { StyleThemeConfig() }
    }

    fun setStyleTheme(component: String, layoutKey: String?, config: StyleThemeConfig) {
        if (layoutKey == null) return
        val key = "${component.lowercase()}_$layoutKey"
        styleThemes[key] = config
        saveConfigs()
    }
}
