package ame.project.kanae

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ame.project.kanae.player.YtDlpHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)

        val versionName = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName
        } catch (_: Exception) {
            "1.0.0"
        }
        tvVersion.text = getString(R.string.version_format, versionName)

        val ytDlp = YtDlpHelper(this)

        lifecycleScope.launch {
            // Kita inisialisasi yt-dlp di sini agar MainActivity tidak berat
            val success = ytDlp.ensureInstalled(
                onProgress = { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        progressBar.progress = progress
                    }
                },
                onLog = { message ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        tvStatus.text = message
                    }
                }
            )

            // Tunggu sebentar biar keliatan progress 100%
            withContext(Dispatchers.Main) {
                progressBar.progress = 100
                tvStatus.text = if (success) "Ready!" else "Initialization failed"
                
                val nlInstalled = isPackageInstalled("ame.project.nlstudio")
                Log.d("SplashActivity", "ytdlp success: $success, nlStudio installed: $nlInstalled")
                
                if (success && nlInstalled) {
                    val btnLinkNL = findViewById<android.view.View>(R.id.btnLinkNL)
                    val btnSkip = findViewById<android.view.View>(R.id.btnSkip)
                    
                    if (btnLinkNL != null && btnSkip != null) {
                        Log.d("SplashActivity", "NL Studio detected, showing selection")
                        btnLinkNL.visibility = android.view.View.VISIBLE
                        btnSkip.visibility = android.view.View.VISIBLE
                        
                        btnLinkNL.setOnClickListener {
                            val intent = packageManager.getLaunchIntentForPackage("ame.project.nlstudio")
                            if (intent != null) {
                                startActivity(intent)
                                // Tetap ke MainActivity atau tunggu? 
                                // Biasanya user ingin buka keduanya, tapi untuk sekarang kita lanjut ke Main setelah klik
                                navigateToMain()
                            } else {
                                Log.e("SplashActivity", "Launch intent for NL Studio is null")
                                navigateToMain()
                            }
                        }
                        
                        btnSkip.setOnClickListener {
                            Log.d("SplashActivity", "User skipped NL Studio")
                            navigateToMain()
                        }
                        
                        // Hentikan coroutine agar tidak otomatis pindah
                        return@withContext
                    }
                }
                
                // Jika NL tidak ada, tunggu sebentar lalu pindah otomatis
                kotlinx.coroutines.delay(500)
                navigateToMain()
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this@SplashActivity, MainActivity::class.java))
        finish()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            Log.d("SplashActivity", "Package $packageName found")
            true
        } catch (e: Exception) {
            Log.w("SplashActivity", "Package $packageName NOT found: ${e.message}")
            false
        }
    }
}
