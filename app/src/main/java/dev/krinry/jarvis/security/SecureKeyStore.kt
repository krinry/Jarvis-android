package dev.krinry.jarvis.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for API keys and Jarvis agent settings.
 * Supports: Gemini Live API, Groq, OpenRouter, Model & Voice configuration.
 */
object SecureKeyStore {

    private const val TAG = "SecureKeyStore"
    private const val PREFS_NAME = "jarvis_secure_keys"
    private const val LEGACY_PREFS_NAME = "wokitoki_secure_keys"

    // Engine Mode: "gemini_live" or "groq"
    const val ENGINE_GEMINI_LIVE = "gemini_live"
    const val ENGINE_GROQ = "groq"

    // Storage Keys
    private const val KEY_ENGINE_MODE = "agent_engine_mode"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    private const val KEY_GEMINI_LIVE_MODEL = "gemini_live_model"
    private const val KEY_GEMINI_VOICE = "gemini_voice"
    private const val KEY_GROQ_API_KEY = "groq_api_key"
    private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
    private const val KEY_AI_LANGUAGE = "ai_default_language"
    private const val KEY_AGENT_ENABLED = "agent_enabled"
    private const val KEY_API_PROVIDER = "api_provider"        // "groq", "openrouter", "gemini"
    private const val KEY_PRIMARY_MODEL = "primary_model"
    private const val KEY_FALLBACK_MODEL = "fallback_model"
    private const val KEY_REQUEST_DELAY_MS = "request_delay_ms"
    private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
    private const val KEY_SOUND_EFFECTS_ENABLED = "sound_effects_enabled"

    private fun getEncryptedPrefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getLegacyPrefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            LEGACY_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // =========================================================================
    // === Engine Mode (Gemini Live vs Groq) ===
    // =========================================================================

    fun getEngineMode(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_ENGINE_MODE, ENGINE_GEMINI_LIVE) ?: ENGINE_GEMINI_LIVE
    }

    fun setEngineMode(context: Context, mode: String) {
        getEncryptedPrefs(context).edit().putString(KEY_ENGINE_MODE, mode).apply()
    }

    // =========================================================================
    // === Gemini Live API Settings ===
    // =========================================================================

    fun saveGeminiApiKey(context: Context, apiKey: String) {
        getEncryptedPrefs(context).edit().putString(KEY_GEMINI_API_KEY, apiKey).apply()
        getEncryptedPrefs(context).edit().putString("provider_key_gemini", apiKey).apply()
    }

    fun getGeminiApiKey(context: Context): String? {
        val key = getEncryptedPrefs(context).getString(KEY_GEMINI_API_KEY, null)
        if (!key.isNullOrEmpty()) return key
        val generic = getEncryptedPrefs(context).getString("provider_key_gemini", null)
        if (!generic.isNullOrEmpty()) return generic
        return getLegacyPrefs(context).getString(KEY_GEMINI_API_KEY, null)
    }

    fun hasGeminiApiKey(context: Context): Boolean = !getGeminiApiKey(context).isNullOrEmpty()

    fun getGeminiLiveModel(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_GEMINI_LIVE_MODEL, "gemini-3.8-live") ?: "gemini-3.8-live"
    }

    fun setGeminiLiveModel(context: Context, model: String) {
        getEncryptedPrefs(context).edit().putString(KEY_GEMINI_LIVE_MODEL, model).apply()
    }

    fun getGeminiVoice(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_GEMINI_VOICE, "Puck") ?: "Puck"
    }

    fun setGeminiVoice(context: Context, voice: String) {
        getEncryptedPrefs(context).edit().putString(KEY_GEMINI_VOICE, voice).apply()
    }

    // =========================================================================
    // === Groq API Key ===
    // =========================================================================

    fun saveGroqApiKey(context: Context, apiKey: String) {
        getEncryptedPrefs(context).edit().putString(KEY_GROQ_API_KEY, apiKey).apply()
    }

    fun getGroqApiKey(context: Context): String? {
        val key = getEncryptedPrefs(context).getString(KEY_GROQ_API_KEY, null)
        if (!key.isNullOrEmpty()) return key
        return getLegacyPrefs(context).getString(KEY_GROQ_API_KEY, null)
    }

    fun hasGroqApiKey(context: Context): Boolean = !getGroqApiKey(context).isNullOrEmpty()

    fun clearGroqApiKey(context: Context) {
        getEncryptedPrefs(context).edit().remove(KEY_GROQ_API_KEY).apply()
    }

    // =========================================================================
    // === OpenRouter API Key ===
    // =========================================================================

    fun saveOpenRouterApiKey(context: Context, apiKey: String) {
        getEncryptedPrefs(context).edit().putString(KEY_OPENROUTER_API_KEY, apiKey).apply()
    }

    fun getOpenRouterApiKey(context: Context): String? {
        val key = getEncryptedPrefs(context).getString(KEY_OPENROUTER_API_KEY, null)
        if (!key.isNullOrEmpty()) return key
        return getLegacyPrefs(context).getString(KEY_OPENROUTER_API_KEY, null)
    }

    fun hasOpenRouterApiKey(context: Context): Boolean = !getOpenRouterApiKey(context).isNullOrEmpty()

    // =========================================================================
    // === Generic Provider API Key ===
    // =========================================================================

    fun saveProviderApiKey(context: Context, providerId: String, apiKey: String) {
        getEncryptedPrefs(context).edit().putString("provider_key_$providerId", apiKey).apply()
        when (providerId) {
            "gemini" -> getEncryptedPrefs(context).edit().putString(KEY_GEMINI_API_KEY, apiKey).apply()
            "groq" -> saveGroqApiKey(context, apiKey)
            "openrouter" -> saveOpenRouterApiKey(context, apiKey)
        }
    }

    fun getProviderApiKey(context: Context, providerId: String): String? {
        val generic = getEncryptedPrefs(context).getString("provider_key_$providerId", null)
        if (!generic.isNullOrEmpty()) return generic
        return when (providerId) {
            "gemini" -> getGeminiApiKey(context)
            "groq" -> getGroqApiKey(context)
            "openrouter" -> getOpenRouterApiKey(context)
            else -> null
        }
    }

    // =========================================================================
    // === API Provider (for REST fallback / Groq mode) ===
    // =========================================================================

    fun setApiProvider(context: Context, provider: String) {
        getEncryptedPrefs(context).edit().putString(KEY_API_PROVIDER, provider).apply()
    }

    fun getApiProvider(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_API_PROVIDER, "groq") ?: "groq"
    }

    // =========================================================================
    // === Model Selection ===
    // =========================================================================

    fun setPrimaryModel(context: Context, model: String) {
        getEncryptedPrefs(context).edit().putString(KEY_PRIMARY_MODEL, model).apply()
    }

    fun getPrimaryModel(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_PRIMARY_MODEL, "") ?: ""
    }

    fun setFallbackModel(context: Context, model: String) {
        getEncryptedPrefs(context).edit().putString(KEY_FALLBACK_MODEL, model).apply()
    }

    fun getFallbackModel(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_FALLBACK_MODEL, "") ?: ""
    }

    // =========================================================================
    // === Request Delay (ms between API calls) ===
    // =========================================================================

    fun setRequestDelayMs(context: Context, delayMs: Long) {
        getEncryptedPrefs(context).edit().putLong(KEY_REQUEST_DELAY_MS, delayMs).apply()
    }

    fun getRequestDelayMs(context: Context): Long {
        return getEncryptedPrefs(context).getLong(KEY_REQUEST_DELAY_MS, 500L)
    }

    // =========================================================================
    // === Language & Agent Settings ===
    // =========================================================================

    fun setDefaultLanguage(context: Context, languageCode: String) {
        getEncryptedPrefs(context).edit().putString(KEY_AI_LANGUAGE, languageCode).apply()
    }

    fun getDefaultLanguage(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_AI_LANGUAGE, "hi") ?: "hi"
    }

    fun setAgentEnabled(context: Context, enabled: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean(KEY_AGENT_ENABLED, enabled).apply()
    }

    fun isAgentEnabled(context: Context): Boolean {
        return getEncryptedPrefs(context).getBoolean(KEY_AGENT_ENABLED, false)
    }

    // =========================================================================
    // === Iron Man Wake Word & Sci-Fi SFX Settings ===
    // =========================================================================

    fun isWakeWordEnabled(context: Context): Boolean {
        return getEncryptedPrefs(context).getBoolean(KEY_WAKE_WORD_ENABLED, true)
    }

    fun setWakeWordEnabled(context: Context, enabled: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
    }

    fun isSoundEffectsEnabled(context: Context): Boolean {
        return getEncryptedPrefs(context).getBoolean(KEY_SOUND_EFFECTS_ENABLED, true)
    }

    fun setSoundEffectsEnabled(context: Context, enabled: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean(KEY_SOUND_EFFECTS_ENABLED, enabled).apply()
    }
}
