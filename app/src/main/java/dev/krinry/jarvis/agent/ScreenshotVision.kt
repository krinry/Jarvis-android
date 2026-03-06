package dev.krinry.jarvis.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.service.AutoAgentService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * ScreenshotVision — Take screenshot and analyze with Gemini Vision.
 *
 * AI can "see" the screen: understand images, charts, errors, CAPTCHAs, layouts.
 * Uses Gemini's multimodal API to analyze screenshots.
 *
 * Actions: analyze_screen (takes screenshot + sends to Gemini vision)
 */
object ScreenshotVision {

    private const val TAG = "ScreenshotVision"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val VISION_MODEL = "gemini-2.0-flash"
    private const val MAX_IMAGE_DIMENSION = 1024 // Resize to save tokens

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Take screenshot and analyze with AI vision.
     * AI: {"action":"analyze_screen","text":"What error is showing?"}
     *
     * @param question What to look for in the screenshot
     * @param context Android context
     * @return AI's analysis of the screenshot
     */
    suspend fun analyzeScreen(question: String, context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "❌ Screenshot vision needs Android 11+"
        }

        val service = AutoAgentService.instance
            ?: return "❌ Accessibility service not running"

        // 1. Take screenshot
        val bitmap = takeScreenshot(service)
            ?: return "❌ Screenshot nahi le paya"

        // 2. Resize and encode to base64
        val resized = resizeBitmap(bitmap)
        val base64Image = bitmapToBase64(resized)
        bitmap.recycle()
        resized.recycle()

        Log.d(TAG, "Screenshot taken: ${base64Image.length} base64 chars")

        // 3. Send to Gemini Vision
        return analyzeWithGemini(context, base64Image, question)
    }

    /**
     * Take screenshot using AccessibilityService API (Android 11+).
     */
    private suspend fun takeScreenshot(service: AutoAgentService): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val deferred = CompletableDeferred<Bitmap?>()

        service.takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hardwareBuffer = result.hardwareBuffer
                        val colorSpace = result.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        hardwareBuffer.close()
                        // Convert from HARDWARE to SOFTWARE for processing
                        val softBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        bitmap?.recycle()
                        deferred.complete(softBitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot decode failed", e)
                        deferred.complete(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed: errorCode=$errorCode")
                    deferred.complete(null)
                }
            }
        )

        return try {
            kotlinx.coroutines.withTimeout(5000) { deferred.await() }
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot timeout", e)
            null
        }
    }

    /**
     * Resize bitmap to save tokens (max 1024px on longest side).
     */
    private fun resizeBitmap(bitmap: Bitmap): Bitmap {
        val maxDim = MAX_IMAGE_DIMENSION
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDim && height <= maxDim) return bitmap

        val scale = if (width > height) {
            maxDim.toFloat() / width
        } else {
            maxDim.toFloat() / height
        }

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Convert bitmap to base64 JPEG string.
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream) // 70% quality to save tokens
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Send screenshot to Gemini Vision API for analysis.
     */
    private suspend fun analyzeWithGemini(
        context: Context, base64Image: String, question: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val apiKey = SecureKeyStore.getProviderApiKey(context, "gemini")
            if (apiKey.isNullOrEmpty()) {
                return@withContext "❌ Gemini API key chahiye vision ke liye. Settings mein set karo."
            }

            val prompt = """You are analyzing a phone screenshot. 
Answer in Hindi (Roman script). Be concise (max 100 words).
Question: $question
If you see an error, explain it and suggest a fix.
If you see a CAPTCHA, describe what to click.
If you see options/buttons, list the important ones."""

            // Build multimodal request
            val imagePart = JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", base64Image)
                })
            }
            val textPart = JSONObject().apply { put("text", prompt) }

            val content = JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(textPart)
                    put(imagePart)
                })
            }

            val payload = JSONObject().apply {
                put("contents", JSONArray().apply { put(content) })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                    put("maxOutputTokens", 300)
                })
            }

            val url = "$BASE_URL/$VISION_MODEL:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Log.d(TAG, "Sending screenshot to Gemini Vision...")

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Vision API error: ${response.code} $errBody")
                return@withContext "❌ Vision API error: ${response.code}"
            }

            val body = response.body?.string() ?: return@withContext "❌ Empty vision response"
            val result = JSONObject(body)

            val candidates = result.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val parts = candidates.getJSONObject(0)
                    .optJSONObject("content")
                    ?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val text = parts.getJSONObject(0).optString("text", "").trim()
                    Log.d(TAG, "Vision result: $text")
                    return@withContext "👁 Vision: ${text.take(500)}"
                }
            }

            "❌ Vision response samajh nahi aaya"
        } catch (e: Exception) {
            Log.e(TAG, "Vision analysis failed", e)
            "❌ Vision error: ${e.message?.take(60)}"
        }
    }
}
