package dev.krinry.jarvis.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import dev.krinry.jarvis.agent.AgentTtsManager
import dev.krinry.jarvis.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object GeminiNativeAudioClient {

    private const val TAG = "GeminiNativeAudio"
    private const val MODEL_NAME = "models/gemini-2.5-flash-native-audio-preview-12-2025" 

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    data class NativeAudioResult(val transcript: String?, val jsonResponse: String?, val playedAudio: Boolean)

    suspend fun processAudioDialog(context: Context, audioFile: File, systemPrompt: String, uiData: String, ttsManager: AgentTtsManager): NativeAudioResult = withContext(Dispatchers.IO) {
        val apiKey = SecureKeyStore.getProviderApiKey(context, "gemini")
        if (apiKey.isNullOrEmpty()) throw Exception("Gemini API key is required")

        Log.d(TAG, "Preparing Audio. File exists: ${audioFile.exists()}")
        val audioBytes = audioFile.readBytes()
        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        
        val fullPrompt = systemPrompt + "\n\nCRITICAL: User is speaking directly via Audio.\nYour JSON MUST include \"transcript\": \"Hindi/English text\"."

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        mainHandler.post { android.widget.Toast.makeText(context, "WS: Connecting v1beta...", android.widget.Toast.LENGTH_SHORT).show() }

        suspendCancellableCoroutine { continuation ->
            var isCompleted = false
            var playedAudioFlag = false
            val textBuilder = StringBuilder()

            fun safeComplete(res: NativeAudioResult) {
                if (!isCompleted) { isCompleted = true; if (continuation.isActive) continuation.resume(res) }
            }

            fun safeError(e: Throwable) {
                if (!isCompleted) { isCompleted = true; if (continuation.isActive) continuation.cancel(e) }
            }

            val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WS Open. Sending Setup...")
                    mainHandler.post { android.widget.Toast.makeText(context, "WS: Handshake OK", android.widget.Toast.LENGTH_SHORT).show() }
                    try {
                        val setup = JSONObject().apply {
                            put("setup", JSONObject().apply {
                                put("model", MODEL_NAME)
                                put("generation_config", JSONObject().apply {
                                    put("response_modalities", JSONArray().put("AUDIO").put("TEXT"))
                                })
                                put("system_instruction", JSONObject().apply {
                                    put("parts", JSONArray().put(JSONObject().apply { put("text", fullPrompt) }))
                                })
                            })
                        }
                        val res = webSocket.send(setup.toString())
                        mainHandler.post { android.widget.Toast.makeText(context, "Setup Sent: $res", android.widget.Toast.LENGTH_SHORT).show() }
                        System.err.println("GeminiWS: Setup=$setup")
                    } catch (e: Exception) { safeError(e) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    System.err.println("GeminiWS Msg: $text")
                    mainHandler.post { 
                        android.widget.Toast.makeText(context, "MSG: ${text.take(50)}", android.widget.Toast.LENGTH_SHORT).show() 
                    }
                    try {
                        val json = JSONObject(text)
                        if (json.has("error")) {
                            val msg = json.getJSONObject("error").optString("message", "API Error")
                            Log.e(TAG, "API Error: $msg")
                            mainHandler.post { android.widget.Toast.makeText(context, "API Error: $msg", android.widget.Toast.LENGTH_LONG).show() }
                            safeError(Exception(msg))
                            webSocket.close(1000, "Error")
                            return
                        }

                        if (json.has("setupComplete")) {
                            Log.d(TAG, "Setup OK. Sending Content...")
                            mainHandler.post { android.widget.Toast.makeText(context, "WS: Sending Data", android.widget.Toast.LENGTH_SHORT).show() }
                            val userMsg = JSONObject().apply {
                                put("clientContent", JSONObject().apply {
                                    put("turns", JSONArray().put(JSONObject().apply {
                                        put("role", "user")
                                        put("parts", JSONArray().apply {
                                            put(JSONObject().apply { put("inlineData", JSONObject().apply { put("mimeType", "audio/wav"); put("data", base64Audio) }) })
                                            put(JSONObject().apply { put("text", "UI Context: $uiData") })
                                        })
                                    }))
                                    put("turnComplete", true)
                                })
                            }
                            webSocket.send(userMsg.toString())
                        }

                        if (json.has("serverContent")) {
                            val content = json.getJSONObject("serverContent")
                            val modelTurn = content.optJSONObject("modelTurn")
                            modelTurn?.optJSONArray("parts")?.let { parts ->
                                for (i in 0 until parts.length()) {
                                    val part = parts.getJSONObject(i)
                                    part.optString("text").takeIf { it.isNotEmpty() }?.let { textBuilder.append(it) }
                                    part.optJSONObject("inlineData")?.let { data ->
                                        if (data.optString("mimeType").startsWith("audio/")) {
                                            val b64 = data.getString("data")
                                            ttsManager.playDirectBase64Audio(b64)
                                            playedAudioFlag = true
                                        }
                                    }
                                }
                            }
                            if (content.optBoolean("turnComplete") || content.optBoolean("generationComplete")) {
                                Log.d(TAG, "Turn Finished.")
                                val responseText = textBuilder.toString()
                                val transcript = try {
                                    val clean = responseText.substringAfter("{").substringBeforeLast("}")
                                    JSONObject("{$clean}").optString("transcript", null)
                                } catch (e: Exception) { null }
                                webSocket.close(1000, "Done")
                                safeComplete(NativeAudioResult(transcript, responseText, playedAudioFlag))
                            }
                        }
                    } catch (e: Exception) { Log.e(TAG, "Parse error", e) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WS Failure: ${t.message}")
                    mainHandler.post { android.widget.Toast.makeText(context, "WS Error: ${t.message}", android.widget.Toast.LENGTH_LONG).show() }
                    safeError(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    safeComplete(NativeAudioResult(null, textBuilder.toString(), playedAudioFlag))
                }
            })

            continuation.invokeOnCancellation { 
                webSocket.cancel()
                if (audioFile.exists()) audioFile.delete()
            }
        }
    }
}
