package dev.krinry.jarvis.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for API keys and agent settings.
 * Supports: Groq, OpenRouter, model selection, request delay.
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
        return getEncryptedPrefs(context).getLong(KEY_REQUEST_DELAY_MS, 2000L)
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
}
