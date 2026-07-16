package ame.project.kanae

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
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
            }
            
            kotlinx.coroutines.delay(500)

            // Lanjut ke MainActivity
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}
