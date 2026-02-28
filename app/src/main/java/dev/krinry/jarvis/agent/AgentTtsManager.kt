package dev.krinry.jarvis.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dev.krinry.jarvis.security.SecureKeyStore
import java.util.Locale

/**
 * AgentTtsManager — Handles Text-to-Speech for the Jarvis Agent.
 *
 * Forces audio through the LOUDSPEAKER (not earpiece).
 * Uses USAGE_ASSISTANT stream for proper audio routing.
 */
class AgentTtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    companion object {
        private const val TAG = "AgentTtsManager"
    }

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val languageCode = SecureKeyStore.getDefaultLanguage(context)
            val locale = Locale(languageCode, "IN")
            val result = tts?.setLanguage(locale)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Language $languageCode not supported, falling back to default")
                tts?.setLanguage(Locale.getDefault())
            }

            // Route audio through ASSISTANT stream (plays through loudspeaker, not earpiece)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            tts?.setAudioAttributes(audioAttributes)

            isInitialized = true
            Log.d(TAG, "Agent TTS initialized (LOUDSPEAKER routing via USAGE_ASSISTANT)")
        } else {
            Log.e(TAG, "Agent TTS initialization failed")
        }
    }

    /**
     * Speaks the given text out loud through the LOUDSPEAKER.
     * @param text The text to speak
     * @param onDone Callback when speech playback finishes
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isInitialized || text.isBlank()) {
            onDone?.invoke()
            return
        }

        // Force SPEAKER_ON so audio comes from loudspeaker, not earpiece
        forceLoudspeaker(true)

        val utteranceId = "agent_${System.currentTimeMillis()}"

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    forceLoudspeaker(false)
                    onDone?.invoke()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                Log.e(TAG, "TTS error for utterance: $id")
                if (id == utteranceId) {
                    forceLoudspeaker(false)
                    onDone?.invoke()
                }
            }
        })

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        // QUEUE_FLUSH interrupts any ongoing speech immediately
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    /**
     * Force or release loudspeaker mode.
     */
    private fun forceLoudspeaker(on: Boolean) {
        try {
            if (on) {
                // Request audio focus
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .build()
                    audioFocusRequest = focusRequest
                    audioManager.requestAudioFocus(focusRequest)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                }

                // Force speaker on (prevents earpiece routing)
                audioManager.isSpeakerphoneOn = true
                audioManager.mode = AudioManager.MODE_NORMAL
            } else {
                // Release audio focus & restore normal routing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(null)
                }
                // Only turn off speakerphone if a call isn't active
                if (audioManager.mode == AudioManager.MODE_NORMAL) {
                    audioManager.isSpeakerphoneOn = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force loudspeaker: ${e.message}")
        }
    }

    /**
     * Stops current speech.
     */
    fun stop() {
        if (isInitialized) {
            tts?.stop()
            forceLoudspeaker(false)
        }
    }

    /**
     * Shuts down the TTS engine.
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        forceLoudspeaker(false)
        isInitialized = false
    }
}
