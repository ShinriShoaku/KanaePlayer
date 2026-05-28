package ame.project.kanae.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class PoTokenGenerator(private val context: Context) {

    companion object {
        private const val TAG = "PoTokenGenerator"
        private const val ATT_GET_URL = "https://www.youtube.com/youtubei/v1/att/get?key="
        private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class PoTokenResult(val visitorData: String, val poToken: String)

    suspend fun generate(videoId: String, visitorData: String): PoTokenResult? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting PoToken generation for video: $videoId")

            // 1. Fetch challenge data dari HTML watch page (lebih reliable daripada API untuk video diblok)
            val challengeData = fetchChallengeDataFromHtml(videoId)
                ?: fetchChallengeDataFromApi(videoId, visitorData)
                ?: run { Log.w(TAG, "Could not fetch challenge data"); return@withContext null }

            val program = challengeData.get("program")?.asString
            Log.d(TAG, "Fetched challenge data, program length: ${program?.length ?: 0}")

            // 2. Fetch integrity token
            val integrityToken = fetchIntegrityToken(visitorData) ?: run {
                Log.w(TAG, "Could not fetch integrity token"); return@withContext null
            }
            Log.d(TAG, "Fetched integrity token: ${integrityToken.take(20)}...")

            // 3. Jalankan BotGuard + obtainPoToken dalam satu sesi WebView
            val base64PoToken = generateInWebView(challengeData, integrityToken, visitorData) ?: run {
                Log.w(TAG, "WebView PoToken generation failed"); return@withContext null
            }

            Log.d(TAG, "Generated PoToken: ${base64PoToken.take(30)}...")
            return@withContext PoTokenResult(visitorData, base64PoToken)

        } catch (e: Exception) {
            Log.e(TAG, "Generation failed: ${e.message}", e)
            null
        }
    }

    // ── Challenge dari HTML watch page ──────────────────────────────────────

    private fun fetchChallengeDataFromHtml(videoId: String): JsonObject? {
        return try {
            val req = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+{}")
                .build()

            val resp = http.newCall(req).execute()
            Log.d(TAG, "HTML fetch code: ${resp.code} for $videoId")
            val html = resp.body?.string() ?: return null

            // Strategy 1: Search for botguardData directly with flexible regex
            val bgRegex = Regex("""\"botguardData\"\s*:\s*(\{.*?"program"\s*:\s*\"[^\"]+\".*?\})""", RegexOption.DOT_MATCHES_ALL)
            bgRegex.find(html)?.let { match ->
                try {
                    val jsonStr = match.groupValues[1]
                    val obj = parseLenient(jsonStr)
                    if (!obj.get("program")?.asString.isNullOrEmpty()) {
                        Log.d(TAG, "Challenge found via Strategy 1 (Regex)")
                        return obj
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Regex botguardData parse failed: ${e.message}")
                }
            }

            // Strategy 2: Extract ytInitialPlayerResponse using balanced brace matching
            val startTokens = listOf(
                "var ytInitialPlayerResponse = ",
                "window[\"ytInitialPlayerResponse\"] = ",
                "ytInitialPlayerResponse = ",
                "ytcfg.set("
            )
            for (token in startTokens) {
                val index = html.indexOf(token)
                if (index == -1) continue

                val jsonStart = index + token.length
                val jsonStr = extractBalancedJson(html, jsonStart) ?: continue

                try {
                    val root = parseLenient(jsonStr)
                    
                    // If it was ytcfg.set, the data is likely deeper
                    val actualData = if (token == "ytcfg.set(") {
                        root.get("PLAYER_VARS")?.asJsonObject?.get("embedded_player_response")?.asString?.let {
                            parseLenient(it)
                        } ?: root
                    } else root

                    val res = actualData.get("playerAttestationRenderer")?.asJsonObject
                        ?.get("botguardData")?.asJsonObject
                        ?: actualData.get("attestation")?.asJsonObject
                            ?.get("playerAttestationRenderer")?.asJsonObject
                            ?.get("botguardData")?.asJsonObject

                    if (res != null && !res.get("program")?.asString.isNullOrEmpty()) {
                        Log.d(TAG, "Challenge found via Strategy 2 (Balanced HTML: $token)")
                        return res
                    }
                } catch (e: Exception) {}
            }
            // Strategy 3: Direct "botguardData" search and balanced extraction
            val bgMarker = "\"botguardData\""
            var lastIndex = 0
            while (true) {
                val found = html.indexOf(bgMarker, lastIndex)
                if (found == -1) break
                
                // Backtrack to find the start of the object { "botguardData": ... }
                val objStart = html.lastIndexOf("{", found)
                if (objStart != -1) {
                    val jsonStr = extractBalancedJson(html, objStart)
                    if (jsonStr != null) {
                        try {
                            val obj = parseLenient(jsonStr)
                            val res = if (obj.has("botguardData")) obj.get("botguardData").asJsonObject else obj
                            if (res.has("program") && !res.get("program").asString.isNullOrEmpty()) {
                                Log.d(TAG, "Challenge found via Strategy 3 (Direct Marker)")
                                return res
                            }
                        } catch (e: Exception) {}
                    }
                }
                lastIndex = found + bgMarker.length
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "HTML challenge fetch failed: ${e.message}")
            null
        }
    }

    private fun extractBalancedJson(html: String, start: Int): String? {
        var firstBrace = -1
        for (i in start until html.length) {
            if (html[i] == '{') { firstBrace = i; break }
            if (html[i] == '<' || html[i] == ';') return null // Early exit if we hit next tag
        }
        if (firstBrace == -1) return null

        var balance = 0
        var inString = false
        var escaped = false

        for (i in firstBrace until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') balance++
                else if (c == '}') {
                    balance--
                    if (balance == 0) return html.substring(firstBrace, i + 1)
                }
            }
        }
        return null
    }

    private fun parseLenient(json: String): JsonObject {
        val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
        reader.isLenient = true
        return JsonParser.parseReader(reader).asJsonObject
    }

    // ── Fallback: Challenge dari API (kalau video tidak diblok) ──────────

    private fun fetchChallengeDataFromApi(videoId: String, visitorData: String): JsonObject? {
        return try {
            val body = """
                {
                  "context": {
                    "client": {
                      "clientName": "WEB_EMBEDDED_PLAYER",
                      "clientVersion": "1.20241021.01.00",
                      "hl": "en",
                      "gl": "US",
                      "visitorData": "$visitorData"
                    }
                  },
                  "videoId": "$videoId",
                  "playbackContext": {
                    "contentPlaybackContext": {
                      "signatureTimestamp": 20436
                    }
                  }
                }
            """.trimIndent()

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?key=$INNERTUBE_KEY")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("X-Goog-Visitor-Id", visitorData)
                .build()

            val resp = http.newCall(req).execute()
            val json = resp.body?.string() ?: return null
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
            reader.isLenient = true
            val root = JsonParser.parseReader(reader).asJsonObject

            val res = root.get("playerAttestationRenderer")?.asJsonObject
                ?.get("botguardData")?.asJsonObject
                ?: root.get("attestation")?.asJsonObject
                    ?.get("playerAttestationRenderer")?.asJsonObject
                    ?.get("botguardData")?.asJsonObject

            if (res != null && !res.get("program")?.asString.isNullOrEmpty()) {
                Log.d(TAG, "Challenge found via Strategy 3 (API)")
                return res
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "API challenge fetch failed: ${e.message}")
            null
        }
    }

    // ── Integrity Token ─────────────────────────────────────────────────────

    private fun fetchIntegrityToken(visitorData: String): String? {
        return try {
            val body = """
                {
                  "context": {
                    "client": {
                      "clientName": "WEB",
                      "clientVersion": "1.20241021.01.00",
                      "hl": "en",
                      "gl": "US",
                      "visitorData": "$visitorData"
                    }
                  }
                }
            """.trimIndent()

            val req = Request.Builder()
                .url("$ATT_GET_URL$INNERTUBE_KEY")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("X-Goog-Visitor-Id", visitorData)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .build()

            val resp = http.newCall(req).execute()
            val json = resp.body?.string() ?: return null
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
            reader.isLenient = true
            val root = JsonParser.parseReader(reader).asJsonObject

            val token = root.get("integrityToken")?.asString
                ?: root.get("integrityTokenId")?.asString
                ?: root.get("integrityToken")?.asJsonObject?.get("token")?.asString
                ?: root.getAsJsonObject("response")?.get("integrityToken")?.asString
                ?: root.get("attestation")?.asJsonObject?.get("playerAttestationRenderer")?.asJsonObject?.get("integrityToken")?.asString

            if (token.isNullOrBlank()) {
                Log.w(TAG, "integrityToken empty in response: $json")
            }
            token
        } catch (e: Exception) {
            Log.w(TAG, "Integrity token fetch failed: ${e.message}")
            null
        }
    }

    // ── WebView: BotGuard + obtainPoToken dalam satu sesi ─────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun generateInWebView(
        challengeData: JsonObject,
        integrityToken: String,
        identifier: String
    ): String? {
        val deferred = CompletableDeferred<String?>()

        mainHandler.post {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true

            val bridge = object {
                @JavascriptInterface
                fun onResult(jsonArrayStr: String) {
                    mainHandler.post {
                        try {
                            val jsonArray = JsonParser.parseString(jsonArrayStr).asJsonArray
                            val bytes = ByteArray(jsonArray.size()) { i -> jsonArray[i].asInt.toByte() }
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
                            deferred.complete(base64)
                        } catch (e: Exception) {
                            Log.e(TAG, "Result parse error: ${e.message}")
                            deferred.complete(null)
                        }
                        webView.destroy()
                    }
                }

                @JavascriptInterface
                fun onError(msg: String) {
                    mainHandler.post {
                        Log.e(TAG, "JS error: $msg")
                        deferred.complete(null)
                        webView.destroy()
                    }
                }
            }
            webView.addJavascriptInterface(bridge, "PoTokenBridge")

            webView.loadUrl("file:///android_asset/po_token.html")

            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val challengeJson = gson.toJson(challengeData)
                    val script = """
                        (async function() {
                            try {
                                // Step 1: Jalankan BotGuard VM
                                var botResult = await runBotGuard($challengeJson);
                                var webPoSignalOutput = botResult.webPoSignalOutput;
                                
                                // Step 2: Mint PoToken (identifier = visitorData)
                                var rawToken = obtainPoToken(webPoSignalOutput, "$integrityToken", "$identifier");
                                
                                // Step 3: Kirim array byte ke Kotlin
                                var arr = Array.from(rawToken);
                                window.PoTokenBridge.onResult(JSON.stringify(arr));
                            } catch(e) {
                                window.PoTokenBridge.onError(e.toString() + " | " + e.stack);
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(script, null)
                }
            }
        }

        return withTimeoutOrNull(25000) { deferred.await() }
    }
}