package dev.krinry.jarvis.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import dev.krinry.jarvis.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GeminiTtsClient — Text-to-Speech using Gemini 2.5 Flash's native audio output.
 *
 * Calls `generateContent` with `responseModalities: ["AUDIO"]` to receive
 * base64-encoded raw PCM audio. Plays it via AudioTrack on the LOUDSPEAKER.
 */
object GeminiTtsClient {

    private const val TAG = "GeminiTtsClient"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val TTS_MODEL = "gemini-2.5-flash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private var audioTrack: AudioTrack? = null

    /**
     * Converts text to speech using Gemini API and plays it through the loudspeaker.
     * @return true if successful, false otherwise (so fallback can be used).
     */
    suspend fun speakText(context: Context, text: String, onDone: (() -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext false

        val apiKey = SecureKeyStore.getProviderApiKey(context, "gemini")
        if (apiKey.isNullOrEmpty()) {
            Log.e(TAG, "Gemini API key missing for TTS")
            return@withContext false
        }

        try {
            // Build the payload for Gemini 2.5 Flash TTS
            val content = JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", text) })
                })
            }

            val generationConfig = JSONObject().apply {
                put("responseModalities", JSONArray().apply { put("AUDIO") })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", "Aoede") // High-quality voice, handles Hindi/English well
                        })
                    })
                })
            }

            val payload = JSONObject().apply {
                put("contents", JSONArray().apply { put(content) })
                put("generationConfig", generationConfig)
            }

            val url = "$BASE_URL/$TTS_MODEL:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Log.d(TAG, "Gemini TTS: requesting audio for text length ${text.length}")

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Gemini TTS error: ${response.code} $errBody")
                return@withContext false
            }

            val bodyText = response.body?.string() ?: return@withContext false
            val resultJson = JSONObject(bodyText)

            val candidates = resultJson.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val candidateObj = candidates.getJSONObject(0)
                val contentObj = candidateObj.optJSONObject("content")
                val parts = contentObj?.optJSONArray("parts")

                if (parts != null) {
                    // Look for the part containing inlineData with MIME type audio/pcm
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null && inlineData.optString("mimeType").startsWith("audio/")) {
                            val base64Audio = inlineData.optString("data")
                            if (base64Audio.isNotEmpty()) {
                                playAudioBase64(base64Audio, onDone)
                                return@withContext true
                            }
                        }
                    }
                }
            }

            Log.w(TAG, "Gemini TTS: No audio data found in response")
            return@withContext false

        } catch (e: Exception) {
            Log.e(TAG, "Gemini TTS failed", e)
            return@withContext false
        }
    }

    /**
     * Decodes Base64 PCM audio and plays it through the loud speaker.
     */
    fun playAudioBase64(base64Audio: String, onDone: (() -> Unit)? = null) {
        try {
            stop() // Stop any current playback

            // Gemini returns 24kHz, 16-bit, Mono PCM audio by default for 2.5-flash
            val sampleRate = 24000
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val audioData = Base64.decode(base64Audio, Base64.DEFAULT)
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            // Force Loudspeaker routing via USAGE_ASSISTANT
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(audioFormat)
                .setChannelMask(channelConfig)
                .build()

            audioTrack = AudioTrack(
                audioAttributes,
                format,
                bufferSize.coerceAtLeast(audioData.size),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            audioTrack?.play()
            audioTrack?.write(audioData, 0, audioData.size)
            
            // Wait for playback to finish
            audioTrack?.setNotificationMarkerPosition(audioData.size / 2) // Since 16-bit = 2 bytes per sample
            audioTrack?.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) {
                    onDone?.invoke()
                    stop()
                }

                override fun onPeriodicNotification(track: AudioTrack?) {}
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error playing Gemini audio", e)
            onDone?.invoke()
        }
    }

    /**
     * Stops the current audio playback.
     */
    fun stop() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio track", e)
        } finally {
            audioTrack = null
        }
    }
}
