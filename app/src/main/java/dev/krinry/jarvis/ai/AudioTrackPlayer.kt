package dev.krinry.jarvis.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioTrackPlayer — Plays raw 24kHz 16-bit Mono PCM audio chunks directly from Gemini Live API.
 *
 * Features:
 * - Ultra-low latency streaming playback
 * - Routes to LOUDSPEAKER via USAGE_ASSISTANT
 * - Instant barge-in / interruption support (flushes buffer immediately)
 * - Safe lifecycle management
 */
class AudioTrackPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AudioTrackPlayer"
        const val SAMPLE_RATE = 24000 // Gemini Live API output sample rate
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private val isPlaying = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    private val playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playbackJob: Job? = null

    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isRunning.set(true)
            startPlaybackLoop()
            Log.d(TAG, "AudioTrack initialized (24kHz Mono PCM)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = playerScope.launch {
            while (isRunning.get() && isActive) {
                val chunk = audioQueue.poll()
                if (chunk != null) {
                    if (!isPlaying.getAndSet(true)) {
                        requestAudioFocus(true)
                        onPlaybackStateChanged?.invoke(true)
                    }
                    try {
                        audioTrack?.write(chunk, 0, chunk.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing to AudioTrack", e)
                    }
                } else {
                    if (isPlaying.get()) {
                        delay(120) // Allow small gap between chunks before declaring playback ended
                        if (audioQueue.isEmpty() && isPlaying.getAndSet(false)) {
                            requestAudioFocus(false)
                            onPlaybackStateChanged?.invoke(false)
                        }
                    } else {
                        delay(15)
                    }
                }
            }
        }
    }

    /**
     * Feed a raw PCM chunk (24kHz 16-bit mono) from Gemini Live API.
     */
    fun enqueueChunk(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return
        audioQueue.offer(pcmData)
    }

    /**
     * Barge-in / Interruption: Stops playback and immediately purges pending audio.
     */
    fun stopAndClear() {
        try {
            audioQueue.clear()
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
            }
            if (isPlaying.getAndSet(false)) {
                requestAudioFocus(false)
                onPlaybackStateChanged?.invoke(false)
            }
            Log.d(TAG, "Audio playback interrupted & queue cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }
    }

    private fun requestAudioFocus(acquire: Boolean) {
        try {
            if (acquire) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .build()
                    audioFocusRequest = req
                    audioManager.requestAudioFocus(req)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(
                        null,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    )
                }
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed audio focus change", e)
        }
    }

    fun release() {
        isRunning.set(false)
        stopAndClear()
        playbackJob?.cancel()
        playerScope.cancel()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}
