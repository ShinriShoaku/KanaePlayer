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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test untuk memverifikasi fungsionalitas yt-dlp fallback.
 */
@RunWith(AndroidJUnit4::class)
class YtDlpTest {

    private lateinit var helper: YtDlpHelper
    private val TAG = "YtDlpTest"

    @Before
    fun setup() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        helper = YtDlpHelper(appContext)
    }

    @Test
    fun testYtDlpExtraction() = runBlocking {
        // 1. Inisialisasi yt-dlp (download binary jika belum ada)
        Log.d(TAG, "Memulai instalasi yt-dlp...")
        val installed = helper.ensureInstalled(
            onProgress = { Log.d(TAG, "Progress: $it%") },
            onLog = { Log.d(TAG, "Log: $it") }
        )
        assertTrue("yt-dlp harus berhasil terinstal", installed)

        // 2. Test ekstraksi paksa lewat yt-dlp
        val testUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ" // Rick Roll for testing
        Log.d(TAG, "Mencoba ekstraksi murni yt-dlp untuk: $testUrl")
        
        val result = helper.extractAudioUrlWithYtdlpOnly(testUrl)
        
        if (result.isSuccess) {
            val audioUrl = result.getOrNull()
            Log.i(TAG, "HASIL: $audioUrl")
            assertTrue("Audio URL tidak boleh kosong", !audioUrl.isNullOrBlank())
            assertTrue("URL harus valid http", audioUrl!!.startsWith("http"))
        } else {
            val error = result.exceptionOrNull()
            Log.e(TAG, "GAGAL: ${error?.message}")
            throw error ?: RuntimeException("Ekstraksi yt-dlp gagal tanpa pesan error")
        }
    }
}
