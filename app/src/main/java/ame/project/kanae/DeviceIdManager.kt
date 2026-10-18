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
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.File
import java.util.UUID

object DeviceIdManager {
    private const val PREFS_NAME = "app_device_prefs"
    private const val KEY_UNIQUE_ID = "unique_device_id"
    private const val JSON_FILE_NAME = "device_id.json"

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var cachedUniqueId: String? = null

    @Volatile
    private var isJsonSynced: Boolean = false

    fun getUniqueId(context: Context): String {
        cachedUniqueId?.let { id ->
            if (isJsonSynced) return id
        }

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonFile = File(appContext.filesDir, JSON_FILE_NAME)

        var uniqueId = cachedUniqueId ?: prefs.getString(KEY_UNIQUE_ID, null)

        // 1. Jika belum ada di SharedPreferences, coba baca dari file JSON
        if (uniqueId.isNullOrBlank() && jsonFile.exists()) {
            try {
                val jsonText = jsonFile.readText()
                val jsonObject = gson.fromJson(jsonText, JsonObject::class.java)
                val idFromJson = jsonObject?.get(KEY_UNIQUE_ID)?.asString
                if (!idFromJson.isNullOrBlank()) {
                    uniqueId = idFromJson
                    prefs.edit().putString(KEY_UNIQUE_ID, uniqueId).apply()
                }
            } catch (e: Exception) {
                Log.e("DeviceIdManager", "Error reading device ID from JSON file: ${e.message}")
            }
        }

        // 2. Jika tetap null/kosong, generate UID baru (hanya 1x saat pertama kali install)
        if (uniqueId.isNullOrBlank()) {
            uniqueId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UNIQUE_ID, uniqueId).apply()
            Log.d("DeviceIdManager", "Generated new unique device ID: $uniqueId")
        }

        cachedUniqueId = uniqueId

        // 3. Simpan / pastikan file JSON terupdate (hanya 1 kali per sesi aplikasi)
        if (!isJsonSynced) {
            saveToJsonFile(appContext, jsonFile, uniqueId)
            isJsonSynced = true
        }

        return uniqueId
    }

    private fun saveToJsonFile(context: Context, file: File, uniqueId: String) {
        try {
            val versionName = getAppVersionName(context)
            val versionCode = getAppVersionCode(context)

            val jsonObject = JsonObject().apply {
                addProperty("unique_device_id", uniqueId)
                addProperty("app_version", versionName)
                addProperty("version_code", versionCode)
                addProperty("updated_at", System.currentTimeMillis())
            }
            file.writeText(gson.toJson(jsonObject))
            Log.d("DeviceIdManager", "Saved device ID to JSON ($versionName / $versionCode): ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("DeviceIdManager", "Error saving device ID to JSON file: ${e.message}")
        }
    }

    fun getAppVersionName(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            Log.e("DeviceIdManager", "Error getting app versionName: ${e.message}")
            "1.0.0"
        }
    }

    fun getAppVersionCode(context: Context): Long {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.e("DeviceIdManager", "Error getting app versionCode: ${e.message}")
            1L
        }
    }
}
