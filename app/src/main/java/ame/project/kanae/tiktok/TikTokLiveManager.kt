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
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var isConnected: Boolean = false
        private set

    private val client = OkHttpClient()
    private val gson = Gson()
    private var wsConnection: WebSocket? = null
    private var pollJob: Job? = null
    private var lastChatIds = mutableSetOf<String>()
    private var commandConfig = CommandConfig()

    fun setCommandConfig(config: CommandConfig) {
        commandConfig = config
    }

    fun connect() {
        if (tiktokUsername.isBlank() || apiKey.isBlank()) {
            onError?.invoke("TikTok username or EulerStream API key is empty")
            return
        }
        Log.d(TAG, "Connecting to EulerStream for @$tiktokUsername")
        connectWebSocket()
    }

    fun disconnect() {
        wsConnection?.close(1000, "User disconnected")
        wsConnection = null
        pollJob?.cancel()
        isConnected = false
        onDisconnected?.invoke()
        Log.d(TAG, "Disconnected")
    }

    private fun connectWebSocket() {
        val cleanUsername = tiktokUsername.trim().removePrefix("@")
        val url = "$WS_URL?apiKey=${apiKey.trim()}&uniqueId=$cleanUsername"
        Log.d(TAG, "WS Connecting to: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TikTokLiveManager/1.0")
            .build()

        wsConnection = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS onOpen")
                isConnected = true
                scope.launch(Dispatchers.Main) { onConnected?.invoke() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS Message: $text")
                parseWsMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: ${t.message}. Falling back to HTTP polling.")
                isConnected = false
                onError?.invoke("WebSocket failed: ${t.message}. Using HTTP polling.")
                startHttpPolling()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed: $code $reason")
                isConnected = false
                onDisconnected?.invoke()
            }
        })
    }

    private fun parseWsMessage(text: String) {
        try {
            val obj = gson.fromJson(text, JsonObject::class.java)

            // 1. Bundled: { "messages": [ { "type": "...", "data": {...} } ] }
            if (obj.has("messages") && obj["messages"].isJsonArray) {
                obj.getAsJsonArray("messages").forEach { el ->
                    if (!el.isJsonObject) return@forEach
                    val msgObj = el.asJsonObject
                    val type = msgObj["type"]?.asString ?: ""
                    val data = msgObj["data"]?.asJsonObject ?: return@forEach
                    when (type) {
                        "WebcastChatMessage", "chat", "comment", "message" -> {
                            val chat = buildChat(data) ?: return@forEach
                            scope.launch(Dispatchers.Main) { onChat?.invoke(chat) }
                        }
                        "roomInfo", "connect_success", "roomUser", "tiktok.connect" -> {
                            if (!isConnected) {
                                isConnected = true
                                scope.launch(Dispatchers.Main) { onConnected?.invoke() }
                            }
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
                        val msg = buildChat(data) ?: return
                        scope.launch(Dispatchers.Main) { onChat?.invoke(msg) }
                    }
                    "connect_success", "roomUser" -> {
                        isConnected = true
                        scope.launch(Dispatchers.Main) { onConnected?.invoke() }
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
                "connect_success", "roomUser" -> {
                    isConnected = true
                    scope.launch(Dispatchers.Main) { onConnected?.invoke() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseWsMessage error: ${e.message} | raw: $text")
        }
    }

    private fun startHttpPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            isConnected = true
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
                    val msgId = obj["id"]?.asString
                        ?: obj["msgId"]?.asString
                        ?: obj["msg_id"]?.asString
                        ?: ""
                    if (msgId.isNotEmpty() && msgId in lastChatIds) return@forEach
                    if (msgId.isNotEmpty()) lastChatIds.add(msgId)
                    if (lastChatIds.size > 200) {
                        val keep = lastChatIds.toList().takeLast(100)
                        lastChatIds.clear(); lastChatIds.addAll(keep)
                    }
                    val chat = buildChat(obj) ?: return@forEach
                    scope.launch(Dispatchers.Main) { onChat?.invoke(chat) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchChatHttp error: ${e.message}")
        }
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
}
