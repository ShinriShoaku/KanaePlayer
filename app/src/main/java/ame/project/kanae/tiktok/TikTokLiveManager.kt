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

package ame.project.kanae.tiktok

import android.util.Log
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import java.util.concurrent.ConcurrentHashMap
import ame.project.kanae.model.TikTokChat
import ame.project.kanae.model.TikTokEmote
import kotlinx.coroutines.*
import okhttp3.*

class TikTokLiveManager(
    private val apiKey: String,
    private val tiktokUsername: String,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TikTokLiveManager"
        private const val WS_URL = "wss://ws.eulerstream.com"
        private const val HTTP_URL = "https://tiktok.eulerstream.com/webcast/fetch"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_RETRY_COUNT = 5
        private const val INITIAL_RECONNECT_DELAY = 3_000L
    }

    data class CommandConfig(
        val enabled: Boolean = true,
        val requestPrefixes: List<String>    = listOf("#req", "#request", "#lagu", "#song"),
        val skipPrefixes: List<String>       = listOf("#skip", "#next", "#lewat"),
        val stopPrefixes: List<String>       = listOf("#stop"),
        val queuePrefixes: List<String>      = listOf("#queue", "#antrian", "#q"),
        /** #cm 1, #cm 2 … clear song at that queue position */
        val clearMusicPrefixes: List<String> = listOf("#cm", "#hapus")
    )

    var onChat: ((TikTokChat) -> Unit)? = null
    var onLike: ((String, String, Int, String?) -> Unit)? = null // nick, uid, count, profile
    var onGift: ((String, String, String, Int, Int, String?) -> Unit)? = null // uid, nick, name, count, giftId, icon
    var onShare: ((String, String, String?) -> Unit)? = null
    var onFollow: ((String, String, String?) -> Unit)? = null // nick, uid, profile
    var onJoin: ((String, String, String?) -> Unit)? = null // nick, uid, profile
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onConnecting: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var isConnected: Boolean = false
        private set

    private val client = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(30))
        .build()
        
    private val gson = Gson()
    private var wsConnection: WebSocket? = null
    private var pollJob: Job? = null

    private var lastChatIds: MutableSet<String> = if (Build.VERSION.SDK_INT == 30) {
        ConcurrentHashMap.newKeySet<String>()
    } else {
        mutableSetOf<String>()
    }

    private var commandConfig = CommandConfig()
    private var connectTime = 0L
    private var isConnectCallbackTriggered = false
    
    private var retryCount = 0
    private var isManuallyDisconnected = false

    fun setCommandConfig(config: CommandConfig) {
        commandConfig = config
    }

    fun connect() {
        isManuallyDisconnected = false
        retryCount = 0
        if (isConnected) {
            Log.d(TAG, "Already connected or connecting...")
            return
        }
        performConnect()
    }

    private fun performConnect() {
        isConnectCallbackTriggered = false
        if (tiktokUsername.isBlank() || apiKey.isBlank()) {
            scope.launch(Dispatchers.Main) {
                onError?.invoke("TikTok username or EulerStream API key is empty")
            }
            return
        }
        Log.d(TAG, "Connecting to EulerStream for @$tiktokUsername (Attempt ${retryCount + 1})")
        scope.launch(Dispatchers.Main) { onConnecting?.invoke() }
        connectWebSocket()
    }

    fun disconnect() {
        isManuallyDisconnected = true
        wsConnection?.close(1000, "User disconnected")
        wsConnection = null
        pollJob?.cancel()
        pollJob = null
        isConnected = false
        isConnectCallbackTriggered = false
        scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
        Log.d(TAG, "Disconnected")
    }

    /**
     * Panggil ini saat Manager benar-benar tidak akan digunakan lagi
     * (misal di onDestroy) untuk mencegah memory leak.
     */
    fun release() {
        disconnect()
        onChat = null
        onLike = null
        onGift = null
        onConnected = null
        onDisconnected = null
        onError = null
        lastChatIds.clear()
    }

    private fun connectWebSocket() {
        val cleanUsername = tiktokUsername.trim().removePrefix("@")
        // Tambahkan timestamp untuk menghindari cache/sticky session di server
        val url = "$WS_URL?apiKey=${apiKey.trim()}&uniqueId=$cleanUsername&t=${System.currentTimeMillis()}"
        
        Log.d(TAG, "WS Connecting to: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TikTokLiveManager/1.0")
            .build()

        wsConnection = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS Connection Opened (Initializing...)")
                connectTime = System.currentTimeMillis()
                isConnected = true
                retryCount = 0 // Reset retry count on successful connection
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseWsMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closing: $code / $reason")
                webSocket.close(1000, null)
                isConnected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = t.message ?: "Unknown error"
                Log.w(TAG, "WS failure: $errorMsg")
                isConnected = false
                
                if (!isManuallyDisconnected) {
                    handleReconnection("WebSocket Error: $errorMsg")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed: $code $reason")
                isConnected = false
                
                if (!isManuallyDisconnected && code != 1000) {
                    handleReconnection("WS Closed ($code): $reason")
                } else if (code == 1000) {
                    isConnectCallbackTriggered = false
                    scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
                }
            }
        })
    }

    private fun handleReconnection(reason: String) {
        if (isManuallyDisconnected) return
        
        if (retryCount < MAX_RETRY_COUNT) {
            val delayMs = INITIAL_RECONNECT_DELAY * (retryCount + 1)
            Log.d(TAG, "Attempting reconnect in ${delayMs}ms... (Reason: $reason)")
            
            scope.launch(Dispatchers.Main) {
                onError?.invoke("$reason. Retrying in ${delayMs/1000}s...")
            }
            
            pollJob?.cancel()
            pollJob = scope.launch {
                delay(delayMs)
                retryCount++
                performConnect()
            }
        } else {
            Log.w(TAG, "Max retries reached. Falling back to HTTP Polling.")
            scope.launch(Dispatchers.Main) {
                onError?.invoke("WebSocket failed after $MAX_RETRY_COUNT retries. Switching to HTTP...")
            }
            startHttpPolling()
        }
    }

    private fun parseWsMessage(text: String) {
        try {
            val obj = gson.fromJson(text, JsonObject::class.java)

            // If we receive ANY message from WS, it means we are connected
            if (!isConnectCallbackTriggered && isConnected) {
                isConnectCallbackTriggered = true
                scope.launch(Dispatchers.Main) { onConnected?.invoke() }
            }

            // 1. Bundled: { "messages": [ { "type": "...", "data": {...} } ] }
            if (obj.has("messages") && obj["messages"].isJsonArray) {
                obj.getAsJsonArray("messages").forEach { el ->
                    if (!el.isJsonObject) return@forEach
                    val msgObj = el.asJsonObject
                    val type = msgObj["type"]?.asString ?: ""
                    val data = msgObj["data"].optObj() ?: return@forEach
                    when (type) {
                        "WebcastChatMessage", "chat", "comment", "message" -> {
                            if (!shouldProcessMessage(data)) return@forEach
                            logLongString("RAW CHAT", data.toString())
                            val chat = buildChat(data) ?: return@forEach
                            scope.launch(Dispatchers.Main) { onChat?.invoke(chat) }
                        }
                        "WebcastLikeMessage", "like" -> {
                            if (!shouldProcessMessage(data)) return@forEach
                            val (uniqueId, nickname, profile) = getUserInfo(data)
                            val count = data["likeCount"]?.asInt ?: 1
                            scope.launch(Dispatchers.Main) { onLike?.invoke(nickname, uniqueId, count, profile) }
                        }
                        "WebcastGiftMessage", "gift" -> {
                            if (!shouldProcessMessage(data)) return@forEach
                            
                            // Check for combo/repeat to avoid duplicate sound/notification for the same gift sequence
                            // TikTok often sends multiple messages for combo gifts.
                            // We only process if it's the first one or if we want to handle every increment.
                            // But usually, we only want the sound once or on completion.
                            val isRepeat = data["repeatEnd"]?.asInt == 0 // 0 usually means it's still combo-ing
                            if (data.has("repeatEnd") && isRepeat) {
                                Log.d(TAG, "Skipping gift message because repeatEnd is 0 (combo in progress)")
                                return@forEach
                            }

                            logLongString("RAW GIFT", data.toString())
                            val (uniqueId, nickname, _) = getUserInfo(data)
                            
                            val giftName = extractGiftName(data)
                            val count = extractGiftCount(data)
                            val giftId = extractGiftId(data)
                            val giftIcon = extractGiftIcon(data)
                            
                            scope.launch(Dispatchers.Main) { onGift?.invoke(uniqueId, nickname, giftName, count, giftId, giftIcon) }
                        }
                        "WebcastSocialMessage", "share", "follow" -> {
                            logLongString("RAW SOCIAL", data.toString())
                            val (uniqueId, nickname, profile) = getUserInfo(data)
                            
                            // Ambil tipe display dari berbagai kemungkinan lokasi
                            val displayType = data["displayType"]?.asString 
                                ?: data["common"].optObj()?.get("displayType")?.asString
                                ?: data["displayText"].optObj()?.get("key")?.asString
                                ?: ""
                            
                            val action = data["action"]?.asString ?: ""
                                
                            // Action "1" biasanya adalah Follow di protokol TikTok
                            if (displayType.contains("follow", ignoreCase = true) || action == "1") {
                                scope.launch(Dispatchers.Main) { onFollow?.invoke(nickname, uniqueId, profile) }
                            } else {
                                scope.launch(Dispatchers.Main) { onShare?.invoke(uniqueId, nickname, profile) }
                            }
                        }
                        "WebcastMemberMessage", "member", "join" -> {
                            val (uniqueId, nickname, profile) = getUserInfo(data)
                            scope.launch(Dispatchers.Main) { onJoin?.invoke(nickname, uniqueId, profile) }
                        }
                        "roomInfo", "connect_success", "roomUser", "tiktok.connect" -> {
                            // Handled above via isConnectCallbackTriggered
                        }
                    }
                }
                return
            }

            // 2. Legacy wrapped: { "event": "chat", "data": {...} }
            if (obj.has("event") && obj.has("data") && obj["data"].isJsonObject) {
                val event = obj["event"].asString
                val data = obj["data"].optObj() ?: return
                when (event) {
                    "chat", "comment", "message", "WebcastChatMessage" -> {
                        if (!shouldProcessMessage(data)) return
                        logLongString("RAW CHAT (Legacy)", data.toString())
                        val msg = buildChat(data) ?: return
                        scope.launch(Dispatchers.Main) { onChat?.invoke(msg) }
                    }
                    "like", "WebcastLikeMessage" -> {
                        if (!shouldProcessMessage(data)) return
                        val (uniqueId, nickname, profile) = getUserInfo(data)
                        val count = data["likeCount"]?.asInt ?: 1
                        scope.launch(Dispatchers.Main) { onLike?.invoke(nickname, uniqueId, count, profile) }
                    }
                    "gift", "WebcastGiftMessage" -> {
                        if (!shouldProcessMessage(data)) return
                        
                        val isRepeat = data["repeatEnd"]?.asInt == 0
                        if (data.has("repeatEnd") && isRepeat) {
                            Log.d(TAG, "Skipping legacy gift message because repeatEnd is 0")
                            return
                        }

                        logLongString("RAW GIFT (Legacy)", data.toString())
                        val (uniqueId, nickname, _) = getUserInfo(data)
                        val giftName = extractGiftName(data)
                        val count = extractGiftCount(data)
                        val giftId = extractGiftId(data)
                        val giftIcon = extractGiftIcon(data)
                        scope.launch(Dispatchers.Main) { onGift?.invoke(uniqueId, nickname, giftName, count, giftId, giftIcon) }
                    }
                    "share", "follow", "WebcastSocialMessage" -> {
                        logLongString("RAW SOCIAL (Legacy)", data.toString())
                        val (uniqueId, nickname, profile) = getUserInfo(data)
                        
                        val displayType = data["displayType"]?.asString 
                            ?: data["common"].optObj()?.get("displayType")?.asString
                            ?: data["displayText"].optObj()?.get("key")?.asString
                            ?: ""

                        val action = data["action"]?.asString ?: ""

                        if (displayType.contains("follow", ignoreCase = true) || action == "1") {
                            scope.launch(Dispatchers.Main) { onFollow?.invoke(nickname, uniqueId, profile) }
                        } else {
                            scope.launch(Dispatchers.Main) { onShare?.invoke(uniqueId, nickname, profile) }
                        }
                    }
                    "member", "WebcastMemberMessage" -> {
                        val (uniqueId, nickname, profile) = getUserInfo(data)
                        scope.launch(Dispatchers.Main) { onJoin?.invoke(nickname, uniqueId, profile) }
                    }
                }
                return
            }

            // 3. Flat: { "type": "chat", "comment": "..." }
            val type = (obj["type"] ?: obj["event"])?.asString
            when (type) {
                "chat", "comment", "message", "WebcastChatMessage" -> {
                    if (!shouldProcessMessage(obj)) return
                    val msg = buildChat(obj) ?: return
                    scope.launch(Dispatchers.Main) { onChat?.invoke(msg) }
                }
                "gift", "WebcastGiftMessage" -> {
                    if (!shouldProcessMessage(obj)) return
                    
                    val isRepeat = obj["repeatEnd"]?.asInt == 0
                    if (obj.has("repeatEnd") && isRepeat) {
                        return
                    }

                    logLongString("RAW GIFT (Flat)", obj.toString())
                    val (uniqueId, nickname, _) = getUserInfo(obj)
                    val giftName = extractGiftName(obj)
                    val count = extractGiftCount(obj)
                    val giftId = extractGiftId(obj)
                    val giftIcon = extractGiftIcon(obj)
                    scope.launch(Dispatchers.Main) { onGift?.invoke(uniqueId, nickname, giftName, count, giftId, giftIcon) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseWsMessage error: ${e.message} | raw: $text")
        }
    }

    private fun startHttpPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            connectTime = System.currentTimeMillis()
            isConnected = true
            isConnectCallbackTriggered = true
            withContext(Dispatchers.Main) { onConnected?.invoke() }
            while (isActive) {
                fetchChatHttp()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun fetchChatHttp() {
        try {
            val cleanUsername = tiktokUsername.trim().removePrefix("@")
            val url = "$HTTP_URL?uniqueId=$cleanUsername&apiKey=${apiKey.trim()}&limit=20"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "TikTokLiveManager/1.0")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val json = gson.fromJson(body, JsonElement::class.java)
                val arr = when {
                    json.isJsonArray -> json.asJsonArray
                    json.isJsonObject -> json.asJsonObject.let { o ->
                        when {
                            o.has("messages") && o["messages"].isJsonArray -> o.getAsJsonArray("messages")
                            o.has("data") && o["data"].isJsonArray -> o.getAsJsonArray("data")
                            o.has("chats") && o["chats"].isJsonArray -> o.getAsJsonArray("chats")
                            else -> null
                        }
                    }
                    else -> null
                } ?: return

                arr.forEach { el ->
                    if (!el.isJsonObject) return@forEach
                    val obj = el.asJsonObject
                    if (!shouldProcessMessage(obj)) return@forEach
                    
                    val chat = buildChat(obj) ?: return@forEach
                    scope.launch(Dispatchers.Main) { onChat?.invoke(chat) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchChatHttp error: ${e.message}")
        }
    }

    private fun getUserInfo(data: JsonObject): Triple<String, String, String?> {
        // 1. Find user object (could be at root, or nested in displayText for some events like Join)
        var uObj = data["user"].optObj()
        
        if (uObj == null && data.has("displayText")) {
            try {
                val pieces = data["displayText"].optObj()?.get("piecesList")?.asJsonArray
                pieces?.forEach { piece ->
                    val uv = piece.optObj()?.get("userValue").optObj()
                    if (uv != null && uv.has("user")) {
                        uObj = uv["user"].optObj()
                        return@forEach
                    }
                }
            } catch (e: Exception) {}
        }

        // 2. Extract Identity
        val uniqueId = uObj?.get("uniqueId")?.asString 
            ?: data["uniqueId"]?.asString 
            ?: "User"
        val nickname = uObj?.get("nickname")?.asString 
            ?: data["nickname"]?.asString 
            ?: uniqueId
            
        // 3. Extract Profile Picture (Search in various possible locations)
        var profileUrl: String? = null
        val containers = listOf("profilePicture", "avatarThumb", "avatar_thumb", "profile_picture")
        val fields = listOf("url_list", "url")

        uObj?.let { user ->
            for (container in containers) {
                val imgObj = user[container].optObj()
                if (imgObj != null) {
                    for (field in fields) {
                        val arr = imgObj[field]?.asJsonArray
                        if (arr != null && arr.size() > 0) {
                            profileUrl = arr[0].asString
                            break
                        }
                    }
                }
                if (profileUrl != null) break
            }
        }
            
        return Triple(uniqueId, nickname, profileUrl)
    }

    /**
     * Cek status follow pengirim chat terhadap streamer.
     *
     * CATATAN PENTING: field ini BELUM aku verifikasi 100% terhadap payload asli dari
     * EulerStream, karena aku tidak punya akses ke log live TikTok kamu. Aku sudah
     * cover beberapa kemungkinan path field yang paling umum dipakai di berbagai
     * implementasi TikTok Live protocol (followInfo.followStatus, follow_info.follow_status,
     * dst - followStatus TikTok: 0 = belum follow, 1 = follow, 2 = mutual/friends).
     *
     * Cara verifikasi: jalankan live, buka Logcat, filter tag "RAW CHAT" (atau "RAW CHAT
     * (Legacy)" / "RAW CHAT (Flat)"), lalu kirim chat dari akun yang KAMU TAHU PASTI sudah
     * follow. Cari field follow di JSON tsb (biasanya di dalam objek "user"). Kalau nama
     * field-nya beda dari yang di-cover di sini, kirim ke aku contoh JSON-nya (boleh
     * disensor username-nya) dan aku sesuaikan lagi.
     */
    private fun extractIsFollower(data: JsonObject): Boolean {
        val uObj = data["user"].optObj()

        // 1. followInfo.followStatus (path paling umum di protokol Webcast TikTok)
        val followInfo = uObj?.get("followInfo").optObj() ?: data["followInfo"].optObj()
        followInfo?.get("followStatus")?.asInt?.let { status ->
            Log.d(TAG, "extractIsFollower: found followInfo.followStatus=$status")
            return status >= 1
        }
        followInfo?.get("follow_status")?.asInt?.let { status ->
            Log.d(TAG, "extractIsFollower: found followInfo.follow_status=$status")
            return status >= 1
        }

        // 2. follow_info (snake_case root variant)
        val followInfoSnake = uObj?.get("follow_info").optObj() ?: data["follow_info"].optObj()
        followInfoSnake?.get("follow_status")?.asInt?.let { status ->
            Log.d(TAG, "extractIsFollower: found follow_info.follow_status=$status")
            return status >= 1
        }

        // 3. followRole langsung di user object (dipakai di beberapa scraper: 0/1/2)
        uObj?.get("followRole")?.asInt?.let { role ->
            Log.d(TAG, "extractIsFollower: found user.followRole=$role")
            return role >= 1
        }

        // 4. followStatus / isFollower boolean langsung di root data
        data["followStatus"]?.asInt?.let { status ->
            Log.d(TAG, "extractIsFollower: found root followStatus=$status")
            return status >= 1
        }
        data["isFollower"]?.asBoolean?.let { flag ->
            Log.d(TAG, "extractIsFollower: found root isFollower=$flag")
            return flag
        }
        uObj?.get("isFollower")?.asBoolean?.let { flag ->
            Log.d(TAG, "extractIsFollower: found user.isFollower=$flag")
            return flag
        }

        // Tidak ketemu field follow sama sekali -> anggap belum follow (aman/fail-closed
        // untuk mode Followers, daripada salah kasih akses skip ke yang belum follow)
        return false
    }

    private fun buildChat(data: JsonObject): TikTokChat? {
        return try {
            val uniqueId = data["uniqueId"]?.asString
                ?: data["unique_id"]?.asString
                ?: data["user"].optObj()?.get("uniqueId")?.asString
                ?: data["user"].optObj()?.get("unique_id")?.asString
                ?: data["nickname"]?.asString
                ?: return null

            val nickname = data["nickname"]?.asString
                ?: data["user"].optObj()?.get("nickname")?.asString
                ?: uniqueId

            var comment = data["comment"]?.asString
                ?: data["message"]?.asString
                ?: data["text"]?.asString
                ?: data["content"]?.asString
                ?: data["msg"]?.asString
                ?: ""

            if (comment.isBlank()) {
                comment = extractEmoteOrSticker(data) ?: ""
            }

            val emotes = mutableListOf<TikTokEmote>()
            if (data.has("emotes") && data["emotes"].isJsonArray) {
                val emotesArray = data.getAsJsonArray("emotes")
                for (i in 0 until emotesArray.size()) {
                    val item = emotesArray[i].optObj()
                    val place = item?.get("placeInComment")?.asInt ?: -1
                    val emote = item?.get("emote").optObj()
                    val imageUrl = emote?.get("image").optObj()?.get("imageUrl")?.asString
                    if (imageUrl != null) {
                        emotes.add(TikTokEmote(place, imageUrl))
                    }
                }
            }

            if (comment.isBlank() && emotes.isEmpty()) return null

            val (cmdType, cmdArg) = parseCommand(comment)
            val isFollower = extractIsFollower(data)

            TikTokChat(
                uniqueId    = uniqueId,
                nickname    = nickname,
                comment     = comment,
                commandType = cmdType,
                commandArg  = cmdArg,
                emotes      = emotes,
                isFollower  = isFollower
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses the chat comment text into a [TikTokChat.CommandType] + optional argument.
     *
     * New #cm command:
     *   "#cm 1"  → CLEAR_MUSIC, arg = "1"
     *   "#cm 3"  → CLEAR_MUSIC, arg = "3"
     */
    private fun parseCommand(text: String): Pair<TikTokChat.CommandType, String?> {
        val lower = text.lowercase().trim()

        // Handle #seekbar command separately so it can be used even when commands are disabled
        if (lower.startsWith("#seekbar")) {
            val arg = text.substring("#seekbar".length).trim()
            return TikTokChat.CommandType.COMMAND_TOGGLE to arg.ifBlank { null }
        }

        if (!commandConfig.enabled) return TikTokChat.CommandType.NONE to null

        // REQUEST (must extract arg = everything after prefix)
        commandConfig.requestPrefixes.forEach { prefix ->
            if (lower.startsWith(prefix)) {
                val arg = text.substring(prefix.length).trim()
                return TikTokChat.CommandType.REQUEST to arg.ifBlank { null }
            }
        }

        // CLEAR_MUSIC: #cm <number>  (e.g. "#cm 2")
        commandConfig.clearMusicPrefixes.forEach { prefix ->
            if (lower.startsWith(prefix)) {
                val arg = text.substring(prefix.length).trim()
                return TikTokChat.CommandType.CLEAR_MUSIC to arg.ifBlank { null }
            }
        }

        commandConfig.skipPrefixes.forEach  { if (lower.startsWith(it)) return TikTokChat.CommandType.SKIP  to null }
        commandConfig.stopPrefixes.forEach  { if (lower.startsWith(it)) return TikTokChat.CommandType.STOP  to null }
        commandConfig.queuePrefixes.forEach { if (lower.startsWith(it)) return TikTokChat.CommandType.QUEUE to null }

        return TikTokChat.CommandType.NONE to null
    }

    private fun shouldProcessMessage(data: JsonObject): Boolean {
        val msgId = data["id"]?.asString
            ?: data["msgId"]?.asString
            ?: data["msg_id"]?.asString
            ?: data["common"].optObj()?.get("msgId")?.asString
            ?: data["common"].optObj()?.get("msg_id")?.asString
            ?: ""
        
        if (msgId.isEmpty()) return true // No ID, assume not duplicate

        if (msgId in lastChatIds) return false
        
        lastChatIds.add(msgId)
        if (lastChatIds.size > 300) {
            val keep = lastChatIds.toList().takeLast(150)
            lastChatIds.clear()
            lastChatIds.addAll(keep)
        }
        return true
    }

    private fun extractGiftId(data: JsonObject): Int {
        return data["giftId"]?.asInt
            ?: data["gift_id"]?.asInt
            ?: data["gift"].optObj()?.get("id")?.asInt
            ?: data["giftDetails"].optObj()?.get("giftId")?.asInt
            ?: 0
    }

    private fun extractGiftName(data: JsonObject): String {
        // 1. Check in giftDetails (Found in log!)
        data["giftDetails"].optObj()?.let { gd ->
            gd["giftName"]?.asString?.let { return it.trim() }
        }

        // 2. Direct fields
        data["giftName"]?.asString?.let { return it.trim() }
        data["gift_name"]?.asString?.let { return it.trim() }

        // 3. Nested in gift object
        data["gift"].optObj()?.let { g ->
            g["name"]?.asString?.let { return it.trim() }
            g["describe"]?.asString?.let { return it.trim() }
        }

        // 3. Fallback from describe string (misal: "user gifted the host 1 Rose")
        data["describe"]?.asString?.let { desc ->
            if (desc.contains("gifted the host")) {
                val parts = desc.split("gifted the host")
                if (parts.size > 1) {
                    val giftPart = parts[1].trim()
                    return giftPart.replace(Regex("^\\d+\\s+"), "").trim()
                }
            }
        }

        return "Gift"
    }

    private fun extractGiftCount(data: JsonObject): Int {
        // Log specifically for combo/repeat count to debug double sound
        val repeatCount = data["repeatCount"]?.asInt
        val comboCount = data["comboCount"]
        Log.d(TAG, "Gift Count Debug - repeatCount: $repeatCount, comboCount: $comboCount")

        return repeatCount
            ?: data["repeat_count"]?.asInt
            ?: data["comboCount"]?.asInt
            ?: 1
    }

    private fun extractGiftIcon(data: JsonObject): String? {
        // 1. Check in giftDetails -> icon or giftImage (Found in log!)
        data["giftDetails"].optObj()?.let { gd ->
            val iconObj = gd["icon"].optObj() ?: gd["giftImage"].optObj()
            iconObj?.get("url")?.asJsonArray?.let { list ->
                if (list.size() > 0) return list[0].asString
            }
        }

        // 2. Direct fields
        data["giftIcon"]?.asString?.let { return it }
        data["gift_icon"]?.asString?.let { return it }

        // 3. Nested in gift object
        data["gift"].optObj()?.let { g ->
            val img = g["image"].optObj() ?: g["icon"].optObj()
            img?.get("url_list")?.asJsonArray?.let { list ->
                if (list.size() > 0) return list[0].asString
            }
        }
        
        // 3. Try to find in profilePicture as absolute fallback (not recommended but for debug)
        // data["user"]?.asJsonObject?.get("profilePicture")?.asJsonObject...
        
        return null
    }

    private fun extractEmoteOrSticker(data: JsonObject): String? {
        // Some TikTok APIs return emojis/stickers in a list or specific field
        // For now, let's look for common patterns or just return a placeholder if we find traces
        if (data.has("emojis") && data["emojis"].isJsonArray) {
            val emojis = data.getAsJsonArray("emojis")
            if (emojis.size() > 0) return "[Emoji]"
        }
        if (data.has("stickers") || data.has("sticker")) return "[Sticker]"
        return null
    }

    private fun logLongString(tag: String, content: String) {
        val maxLogSize = 3000
        for (i in 0..content.length / maxLogSize) {
            val start = i * maxLogSize
            var end = (i + 1) * maxLogSize
            end = if (end > content.length) content.length else end
            Log.d(TAG, "$tag [$i]: ${content.substring(start, end)}")
        }
    }

    private fun JsonElement?.optObj(): JsonObject? {
        if (Build.VERSION.SDK_INT == 30) {
            return if (this != null && this.isJsonObject) this.asJsonObject else null
        }
        return this?.asJsonObject
    }
}
