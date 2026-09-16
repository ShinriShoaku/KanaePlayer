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
import com.google.gson.JsonArray
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

/**
 * PoTokenGenerator v9
 *
 * Perbaikan dari v8 (BREAKING FIXES):
 *
 *  1. ENDPOINT FIX — Challenge fetch dari jnn-pa.googleapis.com (bukan HTML parse).
 *     Endpoint: POST https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/Create
 *     Body: JSON array ["<requestKey>"]
 *     Content-Type: application/json+protobuf
 *
 *  2. INTEGRITY TOKEN FIX — GenerateIT juga dari jnn-pa (bukan att/get).
 *     Endpoint: POST https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT
 *     Body: JSON array ["<requestKey>", "<botguardResponse>"]
 *     Response: JSON array [integrityToken, estimatedTtlSecs, mintRefreshThreshold, ...]
 *
 *  3. CHALLENGE PARSE FIX — Response adalah array yang perlu di-descramble (base64 decode + +97).
 *     Format response: [[messageId, [script], [url], interpreterHash, program, globalName, ...]]
 *     atau [null, "<scrambled_base64>"]
 *
 *  4. MINT FIX — identifier (visitorData) harus di-encode sebagai UTF-8 bytes saat mint,
 *     bukan dikirim sebagai raw string.
 *
 *  5. SELF-TEST — generate() memiliki built-in diagnostic untuk masing-masing step.
 *
 *  Referensi: bgutils-js v3.2.0 (LuanRT/BgUtils)
 */
class PoTokenGenerator(private val context: Context) {

    companion object {
        private const val TAG = "PoTokenGenerator"

        // Request key untuk BotGuard — dipakai di semua request ke jnn-pa
        // Ini adalah public key yang digunakan YouTube web client
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"

        // jnn-pa Google API base — bukan YouTube langsung
        private const val WAA_BASE = "https://jnn-pa.googleapis.com/\$rpc/google.internal.waa.v1.Waa"
        private const val CREATE_URL  = "$WAA_BASE/Create"
        private const val GENERATE_IT_URL = "$WAA_BASE/GenerateIT"

        // API key untuk jnn-pa (berbeda dengan Innertube key)
        private const val GOOG_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"

        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class PoTokenResult(val visitorData: String, val poToken: String)

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC: generate() dengan step-by-step diagnostic
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun generate(videoId: String, visitorData: String): PoTokenResult? =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "══ PoToken Generation START (videoId=$videoId) ══")
            Log.d(TAG, "  visitorData: ${visitorData.take(20)}...")

            // ── Step 1: Fetch challenge dari jnn-pa ─────────────────────────
            Log.d(TAG, "Step 1: Fetching BotGuard challenge dari jnn-pa...")
            val challengeData = fetchChallenge()
            if (challengeData == null) {
                Log.e(TAG, "Step 1 FAIL: Could not fetch challenge data")
                return@withContext null
            }
            val program = challengeData.get("program")?.asString ?: ""
            val globalName = challengeData.get("globalName")?.asString ?: ""
            val interpreterJs = challengeData.get("interpreterJavascript")?.asString ?: ""
            Log.d(TAG, "Step 1 OK: program=${program.take(30)}..., globalName=$globalName, jsLen=${interpreterJs.length}")

            if (program.isBlank() || globalName.isBlank() || interpreterJs.isBlank()) {
                Log.e(TAG, "Step 1 FAIL: challenge data incomplete (program/globalName/interpreterJs kosong)")
                return@withContext null
            }

            // ── Step 2: Jalankan BotGuard di WebView ────────────────────────
            Log.d(TAG, "Step 2: Running BotGuard VM di WebView...")
            val botguardResponse = runBotGuardInWebView(challengeData)
            if (botguardResponse == null) {
                Log.e(TAG, "Step 2 FAIL: BotGuard VM tidak menghasilkan response")
                return@withContext null
            }
            Log.d(TAG, "Step 2 OK: botguardResponse=${botguardResponse.take(30)}...")

            // ── Step 3: Fetch integrity token dari jnn-pa ───────────────────
            Log.d(TAG, "Step 3: Fetching integrity token dari jnn-pa...")
            val integrityTokenData = fetchIntegrityToken(botguardResponse)
            if (integrityTokenData == null) {
                Log.e(TAG, "Step 3 FAIL: Could not fetch integrity token")
                return@withContext null
            }
            val integrityToken = integrityTokenData.get("integrityToken")?.asString ?: ""
            val estimatedTtl = integrityTokenData.get("estimatedTtlSecs")?.asLong ?: 0L
            Log.d(TAG, "Step 3 OK: integrityToken=${integrityToken.take(20)}..., ttl=${estimatedTtl}s")

            if (integrityToken.isBlank()) {
                Log.e(TAG, "Step 3 FAIL: integrityToken kosong")
                return@withContext null
            }

            // ── Step 4: Mint PoToken di WebView ─────────────────────────────
            Log.d(TAG, "Step 4: Minting PoToken (identifier=visitorData)...")
            val poToken = mintPoTokenInWebView(
                challengeData = challengeData,
                integrityToken = integrityToken,
                identifier = visitorData
            )
            if (poToken == null) {
                Log.e(TAG, "Step 4 FAIL: Mint PoToken gagal")
                return@withContext null
            }
            Log.i(TAG, "Step 4 OK: poToken=${poToken.take(30)}...")
            Log.i(TAG, "══ PoToken Generation SUCCESS ══")

            PoTokenResult(visitorData, poToken)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1: Fetch challenge dari jnn-pa.googleapis.com/Create
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * POST https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/Create
     * Body: ["O43z0dpjhgX20SCx4KAo"]
     * Content-Type: application/json+protobuf
     *
     * Response adalah JSON array:
     *   - Format 1: [[messageId, [script], [url], hash, program, globalName, ...]]
     *   - Format 2: [null, "<scrambled_base64>"] → perlu di-descramble
     *
     * Returns JsonObject dengan field: program, globalName, interpreterJavascript
     */
    private fun fetchChallenge(): JsonObject? {
        return try {
            val payload = gson.toJson(listOf(REQUEST_KEY))
            val req = Request.Builder()
                .url(CREATE_URL)
                .post(payload.toRequestBody("application/json+protobuf".toMediaType()))
                .header("x-goog-api-key", GOOG_API_KEY)
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json+protobuf")
                .build()

            val resp = http.newCall(req).execute()
            Log.d(TAG, "  Create response code: ${resp.code}")

            if (!resp.isSuccessful) {
                Log.e(TAG, "  Create HTTP error: ${resp.code}")
                return null
            }

            val body = resp.body?.string() ?: return null
            Log.d(TAG, "  Create response: ${body.take(200)}")

            parseChallengeResponse(body)
        } catch (e: Exception) {
            Log.e(TAG, "  fetchChallenge exception: ${e.message}", e)
            null
        }
    }

    /**
     * Parse challenge response dari jnn-pa.
     * Sesuai dengan challengeFetcher.js dari bgutils-js.
     */
    private fun parseChallengeResponse(rawJson: String): JsonObject? {
        return try {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(rawJson))
            reader.isLenient = true
            val outer = JsonParser.parseReader(reader).asJsonArray

            // Cek format response
            var challengeArray: JsonArray? = null

            if (outer.size() > 1 && outer[1].isJsonPrimitive && outer[1].asJsonPrimitive.isString) {
                // Format 2: [null, "<scrambled>"] → descramble
                val scrambled = outer[1].asString
                val descrambled = descramble(scrambled)
                Log.d(TAG, "  Descrambled challenge: ${descrambled?.take(100)}")
                if (descrambled != null) {
                    val inner = JsonParser.parseString(descrambled)
                    challengeArray = if (inner.isJsonArray) inner.asJsonArray else null
                }
            } else if (outer.size() > 0 && outer[0].isJsonArray) {
                // Format 1: [[...]]
                challengeArray = outer[0].asJsonArray
            }

            if (challengeArray == null) {
                Log.e(TAG, "  Could not determine challenge array from: ${rawJson.take(200)}")
                return null
            }

            // challengeArray = [messageId, wrappedScript, wrappedUrl, interpreterHash, program, globalName, ...]
            // index:              0          1              2           3                4        5
            if (challengeArray.size() < 6) {
                Log.e(TAG, "  Challenge array too short: ${challengeArray.size()} elements")
                return null
            }

            val program = challengeArray[4]?.asString ?: ""
            val globalName = challengeArray[5]?.asString ?: ""

            // wrappedScript adalah array, ambil string pertama yang tidak null
            val interpreterJs = if (challengeArray[1].isJsonArray) {
                challengeArray[1].asJsonArray
                    .firstOrNull { !it.isJsonNull && it.isJsonPrimitive && it.asString.isNotBlank() }
                    ?.asString ?: ""
            } else {
                challengeArray[1]?.asString ?: ""
            }

            Log.d(TAG, "  Parsed: globalName=$globalName, programLen=${program.length}, jsLen=${interpreterJs.length}")

            JsonObject().apply {
                addProperty("program", program)
                addProperty("globalName", globalName)
                addProperty("interpreterJavascript", interpreterJs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "  parseChallengeResponse error: ${e.message}", e)
            null
        }
    }

    /**
     * Descramble challenge: base64 decode, lalu setiap byte + 97.
     * Sesuai dengan descramble() di challengeFetcher.js.
     */
    private fun descramble(scrambled: String): String? {
        return try {
            // Convert base64url ke base64 standar
            val std = scrambled
                .replace('-', '+')
                .replace('_', '/')
                .replace('.', '=')
            val bytes = Base64.decode(std, Base64.DEFAULT)
            // Setiap byte + 97
            String(bytes.map { (it + 97).toByte() }.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "  descramble error: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2: Jalankan BotGuard di WebView → ambil botguardResponse
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runBotGuardInWebView(challengeData: JsonObject): String? {
        val deferred = CompletableDeferred<String?>()
        var webViewRef: WebView? = null

        mainHandler.post {
            val webView = WebView(context)
            webViewRef = webView
            webView.settings.javaScriptEnabled = true

            val bridge = object {
                @JavascriptInterface
                fun onBotGuardResult(response: String) {
                    mainHandler.post {
                        if (deferred.isCompleted) { webView.destroy(); return@post }
                        Log.d(TAG, "  BotGuard response received: ${response.take(30)}...")
                        deferred.complete(response)
                        webView.destroy()
                        webViewRef = null
                    }
                }

                @JavascriptInterface
                fun onError(msg: String) {
                    mainHandler.post {
                        if (deferred.isCompleted) { webView.destroy(); return@post }
                        Log.e(TAG, "  BotGuard JS error: $msg")
                        deferred.complete(null)
                        webView.destroy()
                        webViewRef = null
                    }
                }
            }
            webView.addJavascriptInterface(bridge, "BotGuardBridge")

            // Load blank page sebagai base
            webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                "<html><body></body></html>",
                "text/html", "utf-8", null
            )

            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val interpreterJs = challengeData.get("interpreterJavascript")?.asString ?: ""
                    val program = challengeData.get("program")?.asString ?: ""
                    val globalName = challengeData.get("globalName")?.asString ?: ""
                    val programJson = gson.toJson(program)
                    val globalNameJson = gson.toJson(globalName)

                    // Script ini meniru botGuardClient.js dari bgutils-js:
                    // 1. Load interpreter VM
                    // 2. Init VM dengan program
                    // 3. Tunggu asyncSnapshotFunction tersedia
                    // 4. Ambil snapshot (botguardResponse)
                    val script = """
                        (async function() {
                            try {
                                // Load BotGuard VM interpreter
                                (new Function(${gson.toJson(interpreterJs)}))();
                                
                                const globalName = $globalNameJson;
                                const program = $programJson;
                                
                                if (!window[globalName]) {
                                    window.BotGuardBridge.onError('VM not found: ' + globalName);
                                    return;
                                }
                                if (!window[globalName].a) {
                                    window.BotGuardBridge.onError('VM init function not found');
                                    return;
                                }
                                
                                // Setup deferred untuk vmFunctions
                                let resolveVm;
                                const vmPromise = new Promise(r => { resolveVm = r; });
                                
                                const vmFunctionsCallback = function(asyncSnapshotFn, shutdownFn, passEventFn, checkCameraFn) {
                                    resolveVm({
                                        asyncSnapshotFunction: asyncSnapshotFn,
                                        shutdownFunction: shutdownFn
                                    });
                                };
                                
                                // Init VM — syncSnapshotFunction di index [0]
                                const webPoSignalOutput = [];
                                window[globalName].a(
                                    program,
                                    vmFunctionsCallback,
                                    true,
                                    undefined,
                                    function() {},
                                    [[], []]
                                );
                                
                                // Tunggu asyncSnapshotFunction (max 10 detik)
                                const vmFunctions = await Promise.race([
                                    vmPromise,
                                    new Promise((_, reject) => setTimeout(() => reject(new Error('VM timeout')), 10000))
                                ]);
                                
                                if (!vmFunctions.asyncSnapshotFunction) {
                                    window.BotGuardBridge.onError('asyncSnapshotFunction not available');
                                    return;
                                }
                                
                                // Ambil snapshot
                                const botguardResponse = await new Promise((resolve, reject) => {
                                    vmFunctions.asyncSnapshotFunction(
                                        function(response) { resolve(response); },
                                        [undefined, undefined, webPoSignalOutput, undefined]
                                    );
                                    setTimeout(() => reject(new Error('snapshot timeout')), 10000);
                                });
                                
                                window.BotGuardBridge.onBotGuardResult(botguardResponse || '');
                                
                            } catch(e) {
                                window.BotGuardBridge.onError(e.toString() + ' | stack: ' + (e.stack || 'none'));
                            }
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(script, null)
                }
            }
        }

        return try {
            withTimeoutOrNull(25_000) { deferred.await() }
        } finally {
            if (!deferred.isCompleted) deferred.complete(null)
            mainHandler.post { webViewRef?.destroy(); webViewRef = null }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3: Fetch integrity token dari jnn-pa/GenerateIT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * POST https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT
     * Body: ["<requestKey>", "<botguardResponse>"]
     *
     * Response: JSON array [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken]
     *
     * integrityToken adalah base64 string yang di-pass ke WebView untuk mint.
     */
    private fun fetchIntegrityToken(botguardResponse: String): JsonObject? {
        return try {
            val payload = gson.toJson(listOf(REQUEST_KEY, botguardResponse))
            val req = Request.Builder()
                .url(GENERATE_IT_URL)
                .post(payload.toRequestBody("application/json+protobuf".toMediaType()))
                .header("x-goog-api-key", GOOG_API_KEY)
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json+protobuf")
                .build()

            val resp = http.newCall(req).execute()
            Log.d(TAG, "  GenerateIT response code: ${resp.code}")

            if (!resp.isSuccessful) {
                Log.e(TAG, "  GenerateIT HTTP error: ${resp.code}")
                return null
            }

            val body = resp.body?.string() ?: return null
            Log.d(TAG, "  GenerateIT response: ${body.take(200)}")

            // Response: [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken]
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(body))
            reader.isLenient = true
            val arr = JsonParser.parseReader(reader).asJsonArray

            if (arr.size() == 0) {
                Log.e(TAG, "  GenerateIT empty array response")
                return null
            }

            val integrityToken = arr[0]?.asString ?: ""
            val estimatedTtl = if (arr.size() > 1) arr[1]?.asLong ?: 0L else 0L
            val mintRefreshThreshold = if (arr.size() > 2) arr[2]?.asLong ?: 0L else 0L

            if (integrityToken.isBlank()) {
                Log.e(TAG, "  integrityToken kosong di response: $body")
                return null
            }

            JsonObject().apply {
                addProperty("integrityToken", integrityToken)
                addProperty("estimatedTtlSecs", estimatedTtl)
                addProperty("mintRefreshThreshold", mintRefreshThreshold)
            }
        } catch (e: Exception) {
            Log.e(TAG, "  fetchIntegrityToken exception: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 4: Mint PoToken di WebView
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mint PoToken menggunakan webPoSignalOutput dari BotGuard dan integrityToken.
     *
     * Flow sesuai WebPoMinter.create() + mintAsWebsafeString() dari bgutils-js:
     *   1. webPoSignalOutput[0] adalah getMinter function
     *   2. getMinter(base64ToU8(integrityToken)) → mintCallback (mungkin async)
     *   3. mintCallback(TextEncoder.encode(identifier)) → Uint8Array
     *   4. u8ToBase64(result, true) → websafe base64
     *
     * Identifier = visitorData (bukan videoId).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun mintPoTokenInWebView(
        challengeData: JsonObject,
        integrityToken: String,
        identifier: String
    ): String? {
        val deferred = CompletableDeferred<String?>()
        var webViewRef: WebView? = null

        mainHandler.post {
            val webView = WebView(context)
            webViewRef = webView
            webView.settings.javaScriptEnabled = true

            val bridge = object {
                @JavascriptInterface
                fun onResult(base64Token: String) {
                    mainHandler.post {
                        if (deferred.isCompleted) { webView.destroy(); return@post }
                        Log.d(TAG, "  Mint result: ${base64Token.take(30)}...")
                        deferred.complete(base64Token)
                        webView.destroy()
                        webViewRef = null
                    }
                }

                @JavascriptInterface
                fun onError(msg: String) {
                    mainHandler.post {
                        if (deferred.isCompleted) { webView.destroy(); return@post }
                        Log.e(TAG, "  Mint JS error: $msg")
                        deferred.complete(null)
                        webView.destroy()
                        webViewRef = null
                    }
                }
            }
            webView.addJavascriptInterface(bridge, "MintBridge")

            webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                "<html><body></body></html>",
                "text/html", "utf-8", null
            )

            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val interpreterJs = challengeData.get("interpreterJavascript")?.asString ?: ""
                    val program = challengeData.get("program")?.asString ?: ""
                    val globalName = challengeData.get("globalName")?.asString ?: ""
                    val programJson = gson.toJson(program)
                    val globalNameJson = gson.toJson(globalName)
                    val integrityTokenJson = gson.toJson(integrityToken)
                    val identifierJson = gson.toJson(identifier)

                    // Helper: u8ToBase64 websafe (sesuai bgutils-js)
                    val script = """
                        (async function() {
                            try {
                                // Helper functions
                                function base64ToU8(base64) {
                                    var std = base64.replace(/-/g,'+').replace(/_/g,'/').replace(/\./g,'=');
                                    var bin = atob(std);
                                    return new Uint8Array([...bin].map(c => c.charCodeAt(0)));
                                }
                                function u8ToBase64(u8, websafe) {
                                    var result = btoa(String.fromCharCode(...u8));
                                    if (websafe) {
                                        result = result.replace(/\+/g,'-').replace(/\//g,'_');
                                    }
                                    return result;
                                }
                                
                                // Load BotGuard VM interpreter
                                (new Function(${gson.toJson(interpreterJs)}))();
                                
                                const globalName = $globalNameJson;
                                const program = $programJson;
                                const integrityToken = $integrityTokenJson;
                                const identifier = $identifierJson;
                                
                                if (!window[globalName] || !window[globalName].a) {
                                    window.MintBridge.onError('VM not available: ' + globalName);
                                    return;
                                }
                                
                                // Setup VM
                                let resolveVm;
                                const vmPromise = new Promise(r => { resolveVm = r; });
                                const webPoSignalOutput = [];
                                
                                window[globalName].a(
                                    program,
                                    function(asyncSnapshotFn, shutdownFn, passEventFn, checkCameraFn) {
                                        resolveVm({ asyncSnapshotFunction: asyncSnapshotFn });
                                    },
                                    true, undefined, function() {}, [[], []]
                                );
                                
                                // Tunggu VM siap
                                const vmFunctions = await Promise.race([
                                    vmPromise,
                                    new Promise((_, reject) => setTimeout(() => reject(new Error('VM timeout')), 10000))
                                ]);
                                
                                // Jalankan snapshot untuk populate webPoSignalOutput
                                await new Promise((resolve, reject) => {
                                    vmFunctions.asyncSnapshotFunction(
                                        function(response) { resolve(response); },
                                        [undefined, undefined, webPoSignalOutput, undefined]
                                    );
                                    setTimeout(() => reject(new Error('snapshot timeout')), 10000);
                                });
                                
                                // Mint PoToken (WebPoMinter.create + mintAsWebsafeString)
                                const getMinter = webPoSignalOutput[0];
                                if (!getMinter) {
                                    window.MintBridge.onError('PMD:Undefined - webPoSignalOutput[0] null');
                                    return;
                                }
                                
                                // integrityToken perlu di-decode dari base64 ke Uint8Array
                                const integrityTokenBytes = base64ToU8(integrityToken);
                                
                                // getMinter mungkin async (Promise) atau sync
                                const mintCallback = await Promise.resolve(getMinter(integrityTokenBytes));
                                
                                if (!(mintCallback instanceof Function)) {
                                    window.MintBridge.onError('APF:Failed - mintCallback bukan Function, type: ' + typeof mintCallback);
                                    return;
                                }
                                
                                // identifier di-encode sebagai UTF-8 bytes
                                const identifierBytes = new TextEncoder().encode(identifier);
                                const result = await Promise.resolve(mintCallback(identifierBytes));
                                
                                if (!result) {
                                    window.MintBridge.onError('YNJ:Undefined - mint result null');
                                    return;
                                }
                                if (!(result instanceof Uint8Array)) {
                                    window.MintBridge.onError('ODM:Invalid - result bukan Uint8Array: ' + typeof result);
                                    return;
                                }
                                
                                // Convert ke websafe base64
                                const poToken = u8ToBase64(result, true);
                                window.MintBridge.onResult(poToken);
                                
                            } catch(e) {
                                window.MintBridge.onError(e.toString() + ' | stack: ' + (e.stack || 'none'));
                            }
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(script, null)
                }
            }
        }

        return try {
            withTimeoutOrNull(30_000) { deferred.await() }
        } finally {
            if (!deferred.isCompleted) deferred.complete(null)
            mainHandler.post { webViewRef?.destroy(); webViewRef = null }
        }
    }
}
