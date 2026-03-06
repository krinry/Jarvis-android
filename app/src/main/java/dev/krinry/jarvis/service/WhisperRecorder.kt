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

/**
 * WhisperRecorder — Handles microphone recording, WAV encoding, and STT via Groq Whisper.
 *
 * Extracted from FloatingBubbleService for cleaner separation of concerns.
 */
class WhisperRecorder(private val context: Context) {

    companion object {
        private const val TAG = "WhisperRecorder"
        private const val MAX_RECORDING_SECONDS = 10
        private const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    var isListening = false
        private set

    /**
     * Start recording from microphone.
     * @param scope CoroutineScope to run in
     * @param onStateChange Called when listening state changes
     * @param onTranscript Called with transcript result (null on failure)
     */
    fun startRecording(
        scope: CoroutineScope,
        onStateChange: (Boolean) -> Unit,
        onStatus: (String) -> Unit,
        onTranscript: (String?) -> Unit
    ) {
        isListening = true
        onStateChange(true)

        recordingJob = scope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        onStatus("❌ Microphone unavailable")
                        isListening = false
                        onStateChange(false)
                    }
                    return@launch
                }

                val audioBuffer = mutableListOf<Short>()
                val buffer = ShortArray(bufferSize)
                audioRecord?.startRecording()
                val maxSamples = SAMPLE_RATE * MAX_RECORDING_SECONDS

                while (isListening && audioBuffer.size < maxSamples) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) for (i in 0 until read) audioBuffer.add(buffer[i])
                }

                audioRecord?.stop(); audioRecord?.release(); audioRecord = null

                if (audioBuffer.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        isListening = false
                        onStateChange(false)
                        onStatus("❌ No audio captured")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    isListening = false
                    onStateChange(false)
                    onStatus("🔄 Transcribing...")
                }

                val wavFile = saveAsWav(audioBuffer)
                if (wavFile == null) {
                    withContext(Dispatchers.Main) { onStatus("❌ Failed to save audio") }
                    return@launch
                }

                // Route to selected STT provider
                val sttProvider = SecureKeyStore.getSttProvider(context)
                val transcript = if (sttProvider == "gemini") {
                    withContext(Dispatchers.Main) { onStatus("🔄 Gemini se transcribe ho raha hai...") }
                    GeminiSttClient.transcribeAudio(context, wavFile, null)
                        ?: run {
                            // Fallback to Whisper if Gemini fails
                            withContext(Dispatchers.Main) { onStatus("🔄 Whisper fallback...") }
                            GroqApiClient.transcribeAudio(context, wavFile, null)
                        }
                } else {
                    GroqApiClient.transcribeAudio(context, wavFile, null)
                }
                wavFile.delete()

                withContext(Dispatchers.Main) {
                    onTranscript(transcript)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Whisper recording failed", e)
                withContext(Dispatchers.Main) {
                    isListening = false
                    onStateChange(false)
                    onStatus("❌ Error: ${e.message?.take(40)}")
                }
            }
        }
    }

    /**
     * Stop recording (triggers processing in the startRecording coroutine).
     */
    fun stopListening() {
        isListening = false
    }

    /**
     * Cancel and cleanup.
     */
    fun cancel() {
        isListening = false
        recordingJob?.cancel()
        recordingJob = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun saveAsWav(audioData: List<Short>): File? {
        return try {
            val file = File(context.cacheDir, "whisper_input_${System.currentTimeMillis()}.wav")
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
        } catch (e: Exception) { Log.e(TAG, "Failed to save WAV", e); null }
    }
}
