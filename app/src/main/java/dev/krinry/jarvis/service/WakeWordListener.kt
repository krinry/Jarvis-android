package dev.krinry.jarvis.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.krinry.jarvis.ai.GeminiSttClient
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.security.SecureKeyStore
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * WakeWordListener — Always-on microphone listener with on-device VAD.
 *
 * Detects voice energy locally, buffers audio, then sends to STT provider
 * (Groq/Gemini) to check for \"Jarvis\" or \"Krinry\" wake words.
 */
class WakeWordListener(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordListener"
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SECONDS = 3 // Sent to STT for checking
        private const val SILENCE_THRESHOLD = 500 // Adjust based on mic sensitivity
        private const val VAD_FRAME_SIZE = 160 // 10ms at 16kHz
        
        private val WAKE_WORDS = listOf(
            "krinry", "cranary", "kri nri", "crinary", "cranery", "krinari", "jarvis",
            "crinri", "grini", "krinry,", "jarvis,"
        )
    }

    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null
    var isListening = false
        private set

    @Volatile
    private var isPaused = false

    /**
     * Start the continuous background listener.
     */
    fun startListening(scope: CoroutineScope, onWakeWordDetected: (String, File?) -> Unit) {
        if (isListening) return
        isListening = true

        listenJob = scope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                
                // Use VOICE_RECOGNITION for better AGC / Noise Suppression
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 4
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed")
                    isListening = false
                    return@launch
                }

                audioRecord?.startRecording()
                Log.d(TAG, "Wake word listener started")

                val readBuffer = ShortArray(VAD_FRAME_SIZE)
                val ringBuffer = mutableListOf<Short>()
                val maxSamples = SAMPLE_RATE * BUFFER_SECONDS
                
                var speakingFrames = 0
                var silentFrames = 0
                var isRecordingSpeech = false

                while (isListening) {
                    if (isPaused) {
                        delay(200)
                        ringBuffer.clear()
                        isRecordingSpeech = false
                        continue
                    }

                    val read = audioRecord?.read(readBuffer, 0, VAD_FRAME_SIZE) ?: 0
                    if (read <= 0) continue

                    // Simple energy-based Voice Activity Detection (VAD)
                    var energy = 0L
                    for (i in 0 until read) {
                        energy += abs(readBuffer[i].toInt())
                    }
                    val avgEnergy = energy / read

                    if (avgEnergy > SILENCE_THRESHOLD) {
                        speakingFrames++
                        silentFrames = 0
                        if (speakingFrames > 5) { // Needs ~50ms of energy to start
                            isRecordingSpeech = true
                        }
                    } else {
                        silentFrames++
                        if (silentFrames > 80) { // ~800ms of silence
                            if (isRecordingSpeech && ringBuffer.size > SAMPLE_RATE) { // At least 1s of audio
                                // We have a complete utterance, process it!
                                val audioCopy = ringBuffer.toList()
                                ringBuffer.clear()
                                isRecordingSpeech = false
                                speakingFrames = 0
                                
                                // Pause self while processing so we don't catch echoes
                                val wasPaused = isPaused
                                isPaused = true
                                launch {
                                    processWakeWord(audioCopy, onWakeWordDetected)
                                    if (!wasPaused) isPaused = false
                                }
                            } else {
                                // False alarm or too short
                                ringBuffer.clear()
                                isRecordingSpeech = false
                                speakingFrames = 0
                            }
                        }
                    }

                    if (isRecordingSpeech) {
                        for (i in 0 until read) {
                            ringBuffer.add(readBuffer[i])
                        }
                        // Cap buffer size
                        if (ringBuffer.size > maxSamples) {
                            ringBuffer.subList(0, ringBuffer.size - maxSamples).clear()
                        }
                    } else {
                        // Keep a small pre-roll buffer (0.5s) to catch the start of words
                        val preRollSize = SAMPLE_RATE / 2
                        for (i in 0 until read) {
                            ringBuffer.add(readBuffer[i])
                        }
                        if (ringBuffer.size > preRollSize) {
                            ringBuffer.subList(0, ringBuffer.size - preRollSize).clear()
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Listener error", e)
            } finally {
                cleanup()
            }
        }
    }

    /**
     * Pauses the listener (e.g., when agent is speaking or executing).
     */
    fun pause() {
        Log.d(TAG, "Wake word listener paused")
        isPaused = true
    }

    /**
     * Resumes the listener.
     */
    fun resume() {
        if (!SecureKeyStore.isWakeWordEnabled(context)) return
        Log.d(TAG, "Wake word listener resumed")
        // Small delay so it skips remaining TTS echo
        CoroutineScope(Dispatchers.IO).launch {
            delay(500)
            isPaused = false
        }
    }

    /**
     * Stop completely.
     */
    fun stop() {
        isListening = false
        listenJob?.cancel()
        cleanup()
    }

    private fun cleanup() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
    }

    private suspend fun processWakeWord(audioData: List<Short>, onWakeWordDetected: (String, File?) -> Unit) {
        Log.d(TAG, "Processing potential wake word audio (${audioData.size} samples)")
        val wavFile = saveAsWav(audioData) ?: return
        var keepFile = false

        try {
            val sttProvider = SecureKeyStore.getSttProvider(context)
            val transcript = if (sttProvider == "gemini") {
                GeminiSttClient.transcribeAudio(context, wavFile, null)
                    ?: GroqApiClient.transcribeAudio(context, wavFile, null) // Fallback
            } else {
                GroqApiClient.transcribeAudio(context, wavFile, null)
            }
            
            val detectedText = transcript?.trim() ?: ""
            Log.d(TAG, "Wake word listener heard: '$detectedText'")
            
            if (detectedText.isNotEmpty()) {
                val lower = detectedText.lowercase()
                
                // Check if any wake word is in the transcript
                for (wake in WAKE_WORDS) {
                    if (lower.startsWith(wake) || lower.contains(wake)) {
                        Log.d(TAG, "✅ Wake word matched: $wake")
                        
                        // Strip wake word
                        val lowerText = detectedText.lowercase()
                        val command = if (lowerText.startsWith(wake)) {
                            detectedText.substring(wake.length).trim().trimStart(',', ' ', '.', '!')
                        } else {
                            detectedText
                        }
                        
                        val nativeAudioEnabled = SecureKeyStore.isNativeAudioEnabled(context)
                        if (nativeAudioEnabled) {
                            keepFile = true
                            withContext(Dispatchers.Main) {
                                onWakeWordDetected(command, wavFile)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                onWakeWordDetected(command, null)
                            }
                        }
                        break
                    }
                }
            }
        } finally {
            if (!keepFile) {
                wavFile.delete()
            }
        }
    }

    private fun saveAsWav(audioData: List<Short>): File? {
        return try {
            val file = File(context.cacheDir, "wake_word_${System.currentTimeMillis()}.wav")
            val totalPcmBytes = audioData.size * 2
            FileOutputStream(file).use { fos ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()); header.putInt(36 + totalPcmBytes)
                header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
                header.putInt(16); header.putShort(1); header.putShort(1)
                header.putInt(SAMPLE_RATE); header.putInt(SAMPLE_RATE * 2)
                header.putShort(2); header.putShort(16)
                header.put("data".toByteArray()); header.putInt(totalPcmBytes)
                fos.write(header.array())
                
                val pcmBytes = ByteBuffer.allocate(totalPcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in audioData) pcmBytes.putShort(sample)
                fos.write(pcmBytes.array())
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save WAV", e)
            null
        }
    }
}
