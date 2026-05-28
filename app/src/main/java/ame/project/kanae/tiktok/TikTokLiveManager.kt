package ame.project.kanae.tiktok

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import ame.project.kanae.model.TikTokChat
import kotlinx.coroutines.*
import okhttp3.*

/**
 * TikTokLiveManager
 *
 * Connects to EulerStream API to receive TikTok Live chat in real-time.
 *
 * EulerStream WebSocket endpoint:
 *   wss://eulerstream.com/ws?api_key=KEY&unique_id=USERNAME
 *
 * HTTP fallback (polling every 2 s):
 *   GET https://eulerstream.com/api/v1/fetch/chat?unique_id=USERNAME&limit=20
 *   Header: X-API-Key: KEY
 *
 * Register a listener via [onChat] to receive parsed TikTokChat objects.
 */
class TikTokLiveManager(
    private val apiKey: String,
    private val tiktokUsername: String,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TikTokLiveManager"
        private const val WS_URL = "wss://eulerstream.com/ws"
        private const val HTTP_URL = "https://eulerstream.com/api/v1/fetch/chat"
        private const val POLL_INTERVAL_MS = 2_000L

        // ── Command prefixes (mirror config from main.py) ──────────────────
        private val CMD_REQUEST = listOf("#req", "#request", "#lagu", "#song")
        private val CMD_SKIP    = listOf("#skip", "#next", "#lewat")
        private val CMD_STOP    = listOf("#stop")
        private val CMD_QUEUE   = listOf("#queue", "#antrian", "#q")
    }

    // ── Public callbacks ──────────────────────────────────────────────────────

    var onChat: ((TikTokChat) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var isConnected: Boolean = false
        private set

    // ── Private ───────────────────────────────────────────────────────────────

    private val client = OkHttpClient()
    private val gson = Gson()
    private var wsConnection: WebSocket? = null
    private var pollJob: Job? = null
    private var lastChatIds = mutableSetOf<String>()   // dedup for HTTP polling

    // ── Public API ────────────────────────────────────────────────────────────

    /** Starts WebSocket connection; falls back to HTTP polling on failure. */
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

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun connectWebSocket() {
        val url = "$WS_URL?api_key=${apiKey.trim()}&unique_id=${tiktokUsername.trim()}"
        val request = Request.Builder().url(url).build()

        wsConnection = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS onOpen")
                isConnected = true
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
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

    /** Parses an EulerStream WebSocket JSON message. */
    private fun parseWsMessage(text: String) {
        try {
            val obj = gson.fromJson(text, JsonObject::class.java)
            // EulerStream emits { "event": "chat", "data": { ... } }
            val event = obj["event"]?.asString ?: return
            val data  = obj["data"]?.asJsonObject ?: return

            when (event) {
                "chat", "comment" -> {
                    val msg = buildChat(data) ?: return
                    scope.launch(Dispatchers.Main) { onChat?.invoke(msg) }
                }
                "connect_success" -> {
                    isConnected = true
                    scope.launch(Dispatchers.Main) { onConnected?.invoke() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseWsMessage error: ${e.message}")
        }
    }

    // ── HTTP Polling Fallback ─────────────────────────────────────────────────

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
            val request = Request.Builder()
                .url("$HTTP_URL?unique_id=${tiktokUsername.trim()}&limit=20")
                .header("X-API-Key", apiKey.trim())
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val arr = gson.fromJson(body, com.google.gson.JsonArray::class.java) ?: return

                arr.forEach { el ->
                    val obj = el.asJsonObject
                    val msgId = obj["id"]?.asString ?: obj["msgId"]?.asString ?: ""
                    if (msgId in lastChatIds) return@forEach

                    lastChatIds.add(msgId)
                    if (lastChatIds.size > 200) lastChatIds = lastChatIds.toList().takeLast(100).toMutableSet()

                    val chat = buildChat(obj) ?: return@forEach
                    scope.launch(Dispatchers.Main) { onChat?.invoke(chat) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchChatHttp error: ${e.message}")
        }
    }

    // ── Chat Builder ──────────────────────────────────────────────────────────

    private fun buildChat(data: JsonObject): TikTokChat? {
        return try {
            val uniqueId = data["uniqueId"]?.asString
                ?: data["unique_id"]?.asString
                ?: data["user"]?.asJsonObject?.get("uniqueId")?.asString
                ?: return null
            val nickname = data["nickname"]?.asString ?: uniqueId
            val comment  = data["comment"]?.asString
                ?: data["message"]?.asString
                ?: return null

            val (cmdType, cmdArg) = parseCommand(comment)

            TikTokChat(
                uniqueId = uniqueId,
                nickname = nickname,
                comment  = comment,
                commandType = cmdType,
                commandArg  = cmdArg
            )
        } catch (e: Exception) {
            null
        }
    }

    // ── Command Parser ────────────────────────────────────────────────────────

    private fun parseCommand(text: String): Pair<TikTokChat.CommandType, String?> {
        val lower = text.lowercase().trim()
        CMD_REQUEST.forEach { prefix ->
            if (lower.startsWith(prefix)) {
                val arg = text.substring(prefix.length).trim()
                return TikTokChat.CommandType.REQUEST to arg
            }
        }
        CMD_SKIP.forEach { if (lower.startsWith(it)) return TikTokChat.CommandType.SKIP to null }
        CMD_STOP.forEach { if (lower.startsWith(it)) return TikTokChat.CommandType.STOP to null }
        CMD_QUEUE.forEach { if (lower.startsWith(it)) return TikTokChat.CommandType.QUEUE to null }
        return TikTokChat.CommandType.NONE to null
    }
}
