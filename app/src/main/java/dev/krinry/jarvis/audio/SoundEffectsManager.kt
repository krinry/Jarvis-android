package dev.krinry.jarvis.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import dev.krinry.jarvis.R
import dev.krinry.jarvis.security.SecureKeyStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SoundEffectsManager — Instant zero-latency Stark Industries UI Sound Effects.
 *
 * Preloads synthesized 44.1kHz sci-fi audio effects:
 * - Wake chime (Stark HUD initialization resonance)
 * - Tool chime (Repulsor charge / hardware action click)
 * - Complete chime (Dual-tone task confirmation chord)
 * - Error chime (Cybernetic warning pulse)
 */
object SoundEffectsManager {

    private const val TAG = "SoundEffectsManager"

    private var soundPool: SoundPool? = null
    private var wakeSoundId = 0
    private var toolSoundId = 0
    private var completeSoundId = 0
    private var errorSoundId = 0

    private val isLoaded = AtomicBoolean(false)
    private var appContext: Context? = null

    fun init(context: Context) {
        if (isLoaded.get()) return
        appContext = context.applicationContext

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val pool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(audioAttributes)
                .build()

            soundPool = pool

            wakeSoundId = pool.load(context, R.raw.jarvis_wake, 1)
            toolSoundId = pool.load(context, R.raw.jarvis_tool, 1)
            completeSoundId = pool.load(context, R.raw.jarvis_complete, 1)
            errorSoundId = pool.load(context, R.raw.jarvis_error, 1)

            isLoaded.set(true)
            Log.d(TAG, "SoundEffectsManager initialized with Stark soundpack")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SoundPool", e)
        }
    }

    fun playWakeSound() {
        playSound(wakeSoundId, 1.0f)
    }

    fun playToolSound() {
        playSound(toolSoundId, 0.85f)
    }

    fun playCompleteSound() {
        playSound(completeSoundId, 0.95f)
    }

    fun playErrorSound() {
        playSound(errorSoundId, 0.7f)
    }

    private fun playSound(soundId: Int, volume: Float) {
        val ctx = appContext
        if (ctx != null && !SecureKeyStore.isSoundEffectsEnabled(ctx)) {
            return
        }
        val pool = soundPool ?: return
        if (soundId <= 0) return
        try {
            pool.play(soundId, volume, volume, 1, 0, 1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "Could not play sound $soundId: ${e.message}")
        }
    }

    fun release() {
        try {
            soundPool?.release()
        } catch (_: Exception) {}
        soundPool = null
        isLoaded.set(false)
    }
}
