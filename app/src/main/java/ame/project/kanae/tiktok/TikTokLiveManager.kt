package ame.project.kanae.tiktok

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import ame.project.kanae.model.TikTokChat
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
        val requestPrefixes: List<String>    = listOf("#req", "#request", "#lagu", "#song"),
        val skipPrefixes: List<String>       = listOf("#skip", "#next", "#lewat"),
        val stopPrefixes: List<String>       = listOf("#stop"),
        val queuePrefixes: List<String>      = listOf("#queue", "#antrian", "#q"),
        /** #cm 1, #cm 2 … clear song at that queue position */
        val clearMusicPrefixes: List<String> = listOf("#cm", "#hapus")
    )

    var onChat: ((TikTokChat) -> Unit)? = null
    var onLike: ((String, String, Int, String?) -> Unit)? = null // nick, uid, count, profile
    var onGift: ((String, String, String, Int, String?) -> Unit)? = null
    var onShare: ((String, String) -> Unit)? = null
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
    private var lastChatIds = mutableSetOf<String>()
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
                    val data = msgObj["data"]?.asJsonObject ?: return@forEach
                    when (type) {
                        "WebcastChatMessage", "chat", "comment", "message" -> {
                            if (!shouldProcessMessage(data)) return@forEach
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
                            val (uniqueId, nickname, _) = getUserInfo(data)
                            
                            val giftName = extractGiftName(data)
                            val count = extractGiftCount(data)
                            val giftIcon = extractGiftIcon(data)
                            
                            scope.launch(Dispatchers.Main) { onGift?.invoke(uniqueId, nickname, giftName, count, giftIcon) }
                        }
                        "WebcastSocialMessage", "share" -> {
                            val (uniqueId, nickname, _) = getUserInfo(data)
                            scope.launch(Dispatchers.Main) { onShare?.invoke(uniqueId, nickname) }
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
                val data = obj["data"].asJsonObject
                when (event) {
                    "chat", "comment", "message", "WebcastChatMessage" -> {
                        if (!shouldProcessMessage(data)) return
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
                        val (uniqueId, nickname, _) = getUserInfo(data)
                        val giftName = extractGiftName(data)
                        val count = extractGiftCount(data)
                        val giftIcon = extractGiftIcon(data)
                        scope.launch(Dispatchers.Main) { onGift?.invoke(uniqueId, nickname, giftName, count, giftIcon) }
                    }
                    "share", "WebcastSocialMessage" -> {
                        val (uniqueId, nickname, _) = getUserInfo(data)
                        scope.launch(Dispatchers.Main) { onShare?.invoke(uniqueId, nickname) }
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
                    val msg = buildChat(obj) ?: return
                    scope.launch(Dispatchers.Main) { onChat?.invoke(msg) }
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
        var uObj = data["user"]?.asJsonObject
        
        if (uObj == null && data.has("displayText")) {
            try {
                val pieces = data["displayText"].asJsonObject["piecesList"]?.asJsonArray
                pieces?.forEach { piece ->
                    val uv = piece.asJsonObject["userValue"]?.asJsonObject
                    if (uv != null && uv.has("user")) {
                        uObj = uv["user"].asJsonObject
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
                val imgObj = user[container]?.asJsonObject
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

    private fun buildChat(data: JsonObject): TikTokChat? {
        return try {
            val uniqueId = data["uniqueId"]?.asString
                ?: data["unique_id"]?.asString
                ?: data["user"]?.asJsonObject?.get("uniqueId")?.asString
                ?: data["user"]?.asJsonObject?.get("unique_id")?.asString
                ?: data["nickname"]?.asString
                ?: return null

            val nickname = data["nickname"]?.asString
                ?: data["user"]?.asJsonObject?.get("nickname")?.asString
                ?: uniqueId

            val comment = data["comment"]?.asString
                ?: data["message"]?.asString
                ?: data["text"]?.asString
                ?: data["content"]?.asString
                ?: data["msg"]?.asString
                ?: return null

            val (cmdType, cmdArg) = parseCommand(comment)

            TikTokChat(
                uniqueId    = uniqueId,
                nickname    = nickname,
                comment     = comment,
                commandType = cmdType,
                commandArg  = cmdArg
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
            ?: data["common"]?.asJsonObject?.get("msgId")?.asString
            ?: data["common"]?.asJsonObject?.get("msg_id")?.asString
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

    private fun extractGiftName(data: JsonObject): String {
        // 1. Check in giftDetails (Found in log!)
        data["giftDetails"]?.asJsonObject?.let { gd ->
            gd["giftName"]?.asString?.let { return it }
        }

        // 2. Direct fields
        data["giftName"]?.asString?.let { return it }
        data["gift_name"]?.asString?.let { return it }

        // 3. Nested in gift object
        data["gift"]?.asJsonObject?.let { g ->
            g["name"]?.asString?.let { return it }
            g["describe"]?.asString?.let { return it }
        }

        // 3. Fallback from describe string (misal: "user gifted the host 1 Rose")
        data["describe"]?.asString?.let { desc ->
            if (desc.contains("gifted the host")) {
                val parts = desc.split("gifted the host")
                if (parts.size > 1) {
                    // Ambil bagian setelah "gifted the host" (misal: "1 Rose")
                    val giftPart = parts[1].trim()
                    // Hilangkan angka di depan jika ada (misal: "1 Rose" -> "Rose")
                    return giftPart.replace(Regex("^\\d+\\s+"), "")
                }
            }
        }

        return "Gift"
    }

    private fun extractGiftCount(data: JsonObject): Int {
        return data["repeatCount"]?.asInt
            ?: data["repeat_count"]?.asInt
            ?: data["comboCount"]?.asInt
            ?: 1
    }

    private fun extractGiftIcon(data: JsonObject): String? {
        // 1. Check in giftDetails -> icon or giftImage (Found in log!)
        data["giftDetails"]?.asJsonObject?.let { gd ->
            val iconObj = gd["icon"]?.asJsonObject ?: gd["giftImage"]?.asJsonObject
            iconObj?.get("url")?.asJsonArray?.let { list ->
                if (list.size() > 0) return list[0].asString
            }
        }

        // 2. Direct fields
        data["giftIcon"]?.asString?.let { return it }
        data["gift_icon"]?.asString?.let { return it }

        // 3. Nested in gift object
        data["gift"]?.asJsonObject?.let { g ->
            val img = g["image"]?.asJsonObject ?: g["icon"]?.asJsonObject
            img?.get("url_list")?.asJsonArray?.let { list ->
                if (list.size() > 0) return list[0].asString
            }
        }
        
        // 3. Try to find in profilePicture as absolute fallback (not recommended but for debug)
        // data["user"]?.asJsonObject?.get("profilePicture")?.asJsonObject...
        
        return null
    }
}
