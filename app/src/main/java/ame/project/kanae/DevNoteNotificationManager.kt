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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.Keep
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Keep
data class NotificationItem(
    @SerializedName("notification_id") val notificationId: String = "",
    @SerializedName("target_uid") val targetUid: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("message") val message: String = "",
    @SerializedName("is_active") val isActive: Boolean = true
)

enum class NotifBadgeState {
    NONE,
    RED_FEEDBACK,
    GREEN_GLOBAL
}

object DevNoteNotificationManager {
    private const val TAG = "DevNoteNotifMgr"
    private const val NOTIF_URL_RAW = "https://raw.githubusercontent.com/ShinriShoaku/KanaePlayer/master/notif.json"
    private const val NOTIF_URL_GITHUB = "https://github.com/ShinriShoaku/KanaePlayer/raw/master/notif.json"
    private const val NOTIF_URL_JSDELIVR = "https://cdn.jsdelivr.net/gh/ShinriShoaku/KanaePlayer@master/notif.json"
    private const val PREFS_NOTIF = "app_notif_prefs"
    private const val KEY_READ_IDS = "read_notification_ids"
    private const val CACHE_FILE_NAME = "notif_cache.json"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    fun fetchNotifications(
        context: Context,
        scope: CoroutineScope,
        onResult: (NotifBadgeState, List<NotificationItem>) -> Unit
    ) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val myDeviceUid = DeviceIdManager.getUniqueId(appContext).trim()
            val jsonText = downloadJsonWithFallback(appContext)
            val allNotifs = parseNotifications(jsonText)

            val activeRelevant = allNotifs.filter { item ->
                val target = item.targetUid.trim()
                item.isActive && (target.equals("all", ignoreCase = true) ||
                        target.equals(myDeviceUid, ignoreCase = true))
            }

            val readIds = getReadNotificationIds(appContext)
            val unreadNotifs = activeRelevant.filter { it.notificationId.trim() !in readIds }

            Log.d(
                TAG,
                "Fetched ${allNotifs.size} total items, ${activeRelevant.size} relevant for UID $myDeviceUid, ${unreadNotifs.size} unread"
            )

            val hasUnreadGlobal = unreadNotifs.any { it.targetUid.trim().equals("all", ignoreCase = true) }
            val hasUnreadFeedback = unreadNotifs.any { it.targetUid.trim().equals(myDeviceUid, ignoreCase = true) }

            val state = when {
                hasUnreadGlobal -> NotifBadgeState.GREEN_GLOBAL
                hasUnreadFeedback -> NotifBadgeState.RED_FEEDBACK
                else -> NotifBadgeState.NONE
            }

            withContext(Dispatchers.Main) {
                onResult(state, activeRelevant)
            }
        }
    }

    private fun downloadJsonWithFallback(context: Context): String? {
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)

        // 1. Try RAW Github URL
        var json = tryFetchUrl(NOTIF_URL_RAW)
        if (json.isNullOrBlank()) {
            // 2. Try secondary Github URL
            json = tryFetchUrl(NOTIF_URL_GITHUB)
        }
        if (json.isNullOrBlank()) {
            // 3. Try jsDelivr CDN URL
            json = tryFetchUrl(NOTIF_URL_JSDELIVR)
        }

        if (!json.isNullOrBlank()) {
            try {
                cacheFile.writeText(json)
                Log.d(TAG, "Saved fresh notification JSON to local cache (${json.length} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write cache file: ${e.message}")
            }
            return json
        }

        Log.w(TAG, "Network fetch failed for all URLs, loading local cached JSON if available")
        // Return cache if available when network fails
        return if (cacheFile.exists()) {
            try { cacheFile.readText() } catch (_: Exception) { null }
        } else null
    }

    private fun tryFetchUrl(baseUrl: String): String? {
        return try {
            val separator = if (baseUrl.contains("?")) "&" else "?"
            val url = "$baseUrl${separator}t=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KanaePlayer/1.0 (Android)")
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    if (body.isNotBlank()) {
                        Log.d(TAG, "Fetch success from $baseUrl")
                        return body
                    }
                } else {
                    Log.w(TAG, "Fetch HTTP ${response.code} (${response.message}) from $baseUrl")
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed from $baseUrl: ${e.message}")
            null
        }
    }

    private fun parseNotifications(json: String?): List<NotificationItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<NotificationItem>>() {}.type
            gson.fromJson<List<NotificationItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Parse notifications JSON failed: ${e.message}")
            emptyList()
        }
    }

    private fun getReadNotificationIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_READ_IDS, emptySet()) ?: emptySet()
    }

    fun markAllAsRead(context: Context, notifications: List<NotificationItem>) {
        val prefs = context.getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE)
        val currentRead = prefs.getStringSet(KEY_READ_IDS, emptySet()) ?: emptySet()
        val newRead = currentRead.toMutableSet()
        notifications.forEach { newRead.add(it.notificationId) }
        prefs.edit().putStringSet(KEY_READ_IDS, newRead).apply()
    }

    fun showDevNoteDialog(
        context: Context,
        notifications: List<NotificationItem>,
        onDismiss: () -> Unit
    ) {
        val appContext = context.applicationContext
        val myUid = DeviceIdManager.getUniqueId(context)

        // Mark as read immediately when viewed
        markAllAsRead(appContext, notifications)

        val density = context.resources.displayMetrics.density
        val paddingPx = (16 * density).toInt()

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        // 1. Device UID Section
        val uidContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = 12 * density
                setStroke((1 * density).toInt(), Color.parseColor("#313244"))
            }
            background = bg
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * density).toInt() }
        }

        val tvUidLabel = TextView(context).apply {
            text = "UID Device:\n$myUid"
            setTextColor(Color.parseColor("#CDD6F4"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnCopyUid = TextView(context).apply {
            text = "Salin UID"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Device UID", myUid)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "UID berhasil disalin!", Toast.LENGTH_SHORT).show()
            }
        }

        uidContainer.addView(tvUidLabel)
        uidContainer.addView(btnCopyUid)
        container.addView(uidContainer)

        // 2. Dev Note Changelog Section
        val tvDevNoteTitle = TextView(context).apply {
            text = "📌 Developer Note"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        }
        container.addView(tvDevNoteTitle)

        val devNoteText = """
            • Realtime Speech-to-Text & Voice Overlay
            • Custom Sound untuk event Join & Follow
            • Integrasi TikTok Live & YouTube DL-P
            • Perintah Chat Remote (#lyric on/off)
            • Peningkatan Stabilitas Overlay & Android 12+
        """.trimIndent()

        val tvDevNoteContent = TextView(context).apply {
            text = devNoteText
            setTextColor(Color.parseColor("#A6ADC8"))
            textSize = 13f
            setLineSpacing(4f * density, 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * density).toInt() }
        }
        container.addView(tvDevNoteContent)

        // 3. Notifications & Feedback Replies Section
        val tvNotifHeader = TextView(context).apply {
            text = "📢 Notifikasi & Feedback"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() }
        }
        container.addView(tvNotifHeader)

        if (notifications.isEmpty()) {
            val tvEmpty = TextView(context).apply {
                text = "Belum ada pesan atau balasan feedback baru."
                setTextColor(Color.parseColor("#6C7086"))
                textSize = 12f
                setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
            }
            container.addView(tvEmpty)
        } else {
            notifications.forEach { item ->
                val isGlobal = item.targetUid.equals("all", ignoreCase = true)
                val cardBgColor = if (isGlobal) "#183028" else "#3A1C24"
                val strokeColor = if (isGlobal) "#10B981" else "#EF4444"
                val titleColor = if (isGlobal) "#34D399" else "#FCA5A5"

                val itemCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val bg = GradientDrawable().apply {
                        setColor(Color.parseColor(cardBgColor))
                        cornerRadius = 10 * density
                        setStroke((1 * density).toInt(), Color.parseColor(strokeColor))
                    }
                    background = bg
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (10 * density).toInt() }
                }

                val tvTitle = TextView(context).apply {
                    text = item.title
                    setTextColor(Color.parseColor(titleColor))
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4 * density).toInt() }
                }

                val tvMessage = TextView(context).apply {
                    text = item.message
                    setTextColor(Color.parseColor("#E6E1E5"))
                    textSize = 12f
                    setLineSpacing(2f * density, 1f)
                }

                itemCard.addView(tvTitle)
                itemCard.addView(tvMessage)
                container.addView(itemCard)
            }
        }

        scrollView.addView(container)

        MaterialAlertDialogBuilder(context)
            .setTitle("Developer Note & Info")
            .setView(scrollView)
            .setPositiveButton("Tutup") { dialog, _ ->
                dialog.dismiss()
            }
            .setOnDismissListener {
                onDismiss()
            }
            .show()
    }
}
