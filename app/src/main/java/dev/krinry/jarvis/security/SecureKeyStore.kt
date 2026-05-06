package dev.krinry.jarvis.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for API keys and agent settings.
 * Supports: generic provider API keys, model selection, request delay.
 */
object SecureKeyStore {

    private const val TAG = "SecureKeyStore"
    private const val PREFS_NAME = "wokitoki_secure_keys"

    // Keys
    private const val KEY_GROQ_API_KEY = "groq_api_key"
    private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
    private const val KEY_AI_ENABLED = "ai_call_enabled"
    private const val KEY_AI_LANGUAGE = "ai_default_language"
    private const val KEY_USE_EDGE_FUNCTION = "use_edge_function"
    private const val KEY_AGENT_ENABLED = "agent_enabled"
    private const val KEY_API_PROVIDER = "api_provider"        // "groq" or "openrouter"
    private const val KEY_PRIMARY_MODEL = "primary_model"
    private const val KEY_FALLBACK_MODEL = "fallback_model"
    private const val KEY_REQUEST_DELAY_MS = "request_delay_ms" // Configurable delay
    private const val KEY_STT_PROVIDER = "stt_provider"          // "whisper" or "gemini"
    private const val KEY_TTS_PROVIDER = "tts_provider"          // "platform" or "gemini"
    private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
    private const val KEY_NATIVE_AUDIO_DIALOG_ENABLED = "native_audio_dialog_enabled"
    private const val KEY_DARK_MODE = "dark_mode_enabled"
    private const val KEY_TOOL_CALLING_ENABLED = "tool_calling_enabled"

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

    // =========================================================================
    // === Groq API Key ===
    // =========================================================================

    fun saveGroqApiKey(context: Context, apiKey: String) {
        getEncryptedPrefs(context).edit().putString(KEY_GROQ_API_KEY, apiKey).apply()
    }

    fun getGroqApiKey(context: Context): String? {
        return getEncryptedPrefs(context).getString(KEY_GROQ_API_KEY, null)
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
        return getEncryptedPrefs(context).getString(KEY_OPENROUTER_API_KEY, null)
    }

    fun hasOpenRouterApiKey(context: Context): Boolean = !getOpenRouterApiKey(context).isNullOrEmpty()

    // =========================================================================
    // === Generic Provider API Key ===
    // =========================================================================

    fun saveProviderApiKey(context: Context, providerId: String, apiKey: String) {
        getEncryptedPrefs(context).edit().putString("provider_key_$providerId", apiKey).apply()
        // Also save in legacy keys for backward compat
        when (providerId) {
            "groq" -> saveGroqApiKey(context, apiKey)
            "openrouter" -> saveOpenRouterApiKey(context, apiKey)
        }
    }

    fun getProviderApiKey(context: Context, providerId: String): String? {
        // Check new generic key first, then fallback to legacy
        val generic = getEncryptedPrefs(context).getString("provider_key_$providerId", null)
        if (!generic.isNullOrEmpty()) return generic
        return when (providerId) {
            "groq" -> getGroqApiKey(context)
            "openrouter" -> getOpenRouterApiKey(context)
            else -> null
        }
    }

    // =========================================================================
    // === API Provider (groq / openrouter) ===
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
    // === AI Call Settings ===
    // =========================================================================

    fun setAiCallEnabled(context: Context, enabled: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean(KEY_AI_ENABLED, enabled).apply()
    }

    fun isAiCallEnabled(context: Context): Boolean {
        return getEncryptedPrefs(context).getBoolean(KEY_AI_ENABLED, false)
    }

    fun setDefaultLanguage(context: Context, languageCode: String) {
        getEncryptedPrefs(context).edit().putString(KEY_AI_LANGUAGE, languageCode).apply()
    }

    fun getDefaultLanguage(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_AI_LANGUAGE, "hi") ?: "hi"
    }

    fun setUseEdgeFunction(context: Context, useEdge: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean(KEY_USE_EDGE_FUNCTION, useEdge).apply()
    }

    fun getUseEdgeFunction(context: Context): Boolean {
        return getEncryptedPrefs(context).getBoolean(KEY_USE_EDGE_FUNCTION, true)
    }

    fun shouldUseEdgeFunction(context: Context): Boolean = getUseEdgeFunction(context)

    // =========================================================================
    // === Agent Settings ===
    // =========================================================================

    fun setAgentEnabled(context: Context, enabled: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean(KEY_AGENT_ENABLED, enabled).apply()
    }

    fun isAgentEnabled(context: Context): Boolean {
        return getEncryptedPrefs(context).getBoolean(KEY_AGENT_ENABLED, false)
    }

    // =========================================================================
    // === STT Provider (whisper / gemini) ===
    // =========================================================================

    fun setSttProvider(context: Context, provider: String) {
        getEncryptedPrefs(context).edit().putString(KEY_STT_PROVIDER, provider).apply()
    }

    fun getSttProvider(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_STT_PROVIDER, "whisper") ?: "whisper"
    }

    // =========================================================================
    // === TTS Provider (platform / gemini) ===
    // =========================================================================

    fun setTtsProvider(context: Context, provider: String) {
        getEncryptedPrefs(context).edit().putString(KEY_TTS_PROVIDER, provider).apply()
    }

    fun getTtsProvider(context: Context): String {
        return getEncryptedPrefs(context).getString(KEY_TTS_PROVIDER, "platform") ?: "platform"
    }

    // =========================================================================
    // === Wake Word Settings ===
    // =========================================================================

    fun setWakeWordEnabled(context: Context, enabled: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
    }

    fun isWakeWordEnabled(context: Context): Boolean {
        return getEncryptedPrefs(context).getBoolean(KEY_WAKE_WORD_ENABLED, true)
    }

    fun setNativeAudioEnabled(context: Context, enabled: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean(KEY_NATIVE_AUDIO_DIALOG_ENABLED, enabled).apply()
    }

    fun isNativeAudioEnabled(context: Context): Boolean {
        return getEncryptedPrefs(context).getBoolean(KEY_NATIVE_AUDIO_DIALOG_ENABLED, false)
    }

    // =========================================================================
    // === Dark / Light Theme ===
    // =========================================================================

    fun setDarkMode(context: Context, enabled: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean(KEY_DARK_MODE, enabled).apply()
    }

    fun isDarkMode(context: Context): Boolean {
        return getEncryptedPrefs(context).getBoolean(KEY_DARK_MODE, true)
    }

    // =========================================================================
    // === Native Tool Calling (Function Calling) ===
    // =========================================================================

    fun setToolCallingEnabled(context: Context, enabled: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean(KEY_TOOL_CALLING_ENABLED, enabled).apply()
    }

    fun isToolCallingEnabled(context: Context): Boolean {
        return getEncryptedPrefs(context).getBoolean(KEY_TOOL_CALLING_ENABLED, false)
    }
}
