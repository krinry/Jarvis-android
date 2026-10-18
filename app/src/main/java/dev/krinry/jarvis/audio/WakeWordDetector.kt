package dev.krinry.jarvis.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dev.krinry.jarvis.security.SecureKeyStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WakeWordDetector — Background hands-free continuous wake-word listener.
 *
 * Listens for: "Jarvis", "Hey Jarvis", "Ok Jarvis", "Hi Jarvis", "Hello Jarvis"
 * Automatically pauses when Gemini Live or mic recording is active, and resumes when idle.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: (matchedPhrase: String) -> Unit
) {

    companion object {
        private const val TAG = "WakeWordDetector"

        private val WAKE_KEYWORDS = listOf(
            "jarvis",
            "hey jarvis",
            "ok jarvis",
            "okay jarvis",
            "hi jarvis",
            "hello jarvis",
            "zarvis",
            "javes",
            "service", // common phonetic misrecognition for jarvis in noisy environments
            "krinry"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var speechRecognizer: SpeechRecognizer? = null
    private val isListeningActive = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private var restartRunnable: Runnable? = null

    fun start() {
        if (!SecureKeyStore.isWakeWordEnabled(context)) {
            Log.d(TAG, "Wake word is disabled in settings")
            return
        }

        if (isListeningActive.getAndSet(true)) return
        isPaused.set(false)

        mainHandler.post {
            initRecognizerAndListen()
        }
    }

    fun pause() {
        isPaused.set(true)
        cancelPendingRestart()
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
        }
    }

    fun resume() {
        if (!SecureKeyStore.isWakeWordEnabled(context)) return
        isPaused.set(false)
        cancelPendingRestart()
        mainHandler.post {
            if (isListeningActive.get()) {
                startListeningInternal()
            }
        }
    }

    fun stop() {
        isListeningActive.set(false)
        isPaused.set(true)
        cancelPendingRestart()
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }
    }

    private fun cancelPendingRestart() {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun scheduleRestart(delayMs: Long) {
        cancelPendingRestart()
        if (!isListeningActive.get() || isPaused.get()) return

        val runnable = Runnable {
            if (isListeningActive.get() && !isPaused.get()) {
                startListeningInternal()
            }
        }
        restartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun initRecognizerAndListen() {
        try {
            speechRecognizer?.destroy()

            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                Log.d(TAG, "Creating On-Device SpeechRecognizer")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                Log.d(TAG, "Creating Standard SpeechRecognizer")
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    restoreBeepStreams()
                }

                override fun onBeginningOfSpeech() {
                    restoreBeepStreams()
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    restoreBeepStreams()
                }

                override fun onError(error: Int) {
                    restoreBeepStreams()
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> 200L
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 3000L
                        else -> 500L
                    }
                    Log.d(TAG, "Recognition error ($error), restarting in ${delay}ms")
                    scheduleRestart(delay)
                }

                override fun onResults(results: Bundle?) {
                    restoreBeepStreams()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!checkAndTriggerMatches(matches)) {
                        scheduleRestart(250L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    checkAndTriggerMatches(matches)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer = recognizer
            startListeningInternal()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
            scheduleRestart(2000L)
        }
    }

    private fun startListeningInternal() {
        if (!isListeningActive.get() || isPaused.get()) return
        val recognizer = speechRecognizer ?: run {
            initRecognizerAndListen()
            return
        }

        try {
            silenceBeepStreams()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            recognizer.startListening(intent)
            Log.d(TAG, "WakeWord listening started...")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            restoreBeepStreams()
            scheduleRestart(1000L)
        }
    }

    private fun checkAndTriggerMatches(matches: ArrayList<String>?): Boolean {
        if (matches.isNullOrEmpty()) return false
        if (isPaused.get()) return false

        for (phrase in matches) {
            val lower = phrase.trim().lowercase()
            for (keyword in WAKE_KEYWORDS) {
                if (lower.contains(keyword)) {
                    Log.d(TAG, "⚡ WAKE WORD DETECTED: \"$keyword\" inside \"$phrase\"")
                    pause()
                    onWakeWordDetected(phrase)
                    return true
                }
            }
        }
        return false
    }

    private fun silenceBeepStreams() {
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
        } catch (_: Exception) {}
    }

    private fun restoreBeepStreams() {
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {}
    }
}
