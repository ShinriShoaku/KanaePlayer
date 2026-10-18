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
import androidx.annotation.Keep
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

@Keep
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
