package ame.project.kanae.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import ame.project.kanae.service.PlayerForegroundService

class KanaeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KanaeAccessibility"
        var instance: KanaeAccessibilityService? = null
            private set
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentLanguage = "id-ID"

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for now
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopListening()
    }

    fun startListening(language: String) {
        this.currentLanguage = language
        if (isListening) return

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListening = false
                    }
                    override fun onError(error: Int) {
                        isListening = false
                        Log.e(TAG, "SpeechRecognizer Error: $error")
                        // Implementation might need to restart if continuous
                        val intent = Intent("ame.project.kanae.VOICE_ERROR").apply {
                            putExtra("error", error)
                        }
                        sendBroadcast(intent)
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val intent = Intent("ame.project.kanae.VOICE_RESULT").apply {
                                putExtra("text", matches[0])
                            }
                            sendBroadcast(intent)
                        }
                        isListening = false
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val intent = Intent("ame.project.kanae.VOICE_PARTIAL_RESULT").apply {
                                putExtra("text", matches[0])
                            }
                            sendBroadcast(intent)
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                // Use VOICE_RECOGNITION source (implicitly used by SpeechRecognizer if permitted)
                // CDD requires this for parallel capture
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SpeechRecognizer", e)
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
    }
}
