package dev.krinry.jarvis.ai

import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GeminiLiveClient — Direct WebSocket client for Google Gemini Live API.
 *
 * Implements real-time bidirectional streaming:
 * - Real-time Audio input (16kHz PCM little-endian)
 * - Native Audio output (24kHz PCM little-endian)
 * - Live Speech-to-Text transcription (inputAudioTranscription)
 * - Live Text-to-Speech transcription (outputAudioTranscription)
 * - Function Calling / UI Action Tools execution
 * - Hybrid VAD (audioStreamEnd) & Barge-in (interrupted)
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String = "gemini-3.8-live",
    private val voiceName: String = "Puck"
) {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val WS_HOST = "generativelanguage.googleapis.com"
        private const val WS_PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        private const val SYSTEM_PROMPT = """You are Jarvis (Krinry), an ultra-smart, responsive AI phone assistant with direct device control via Accessibility tools.
The user speaks to you naturally. You receive their voice in real-time, along with the device's visible UI screen state.

RULES:
1. Spoken Language: Speak naturally and concisely in Hindi (or Hinglish if appropriate). Confirm user requests in 1 short sentence, announce actions briefly, and confirm when done.
2. Device Actions: You MUST use the provided tools to control the phone (open_app, click_element, type_text, scroll, tap_coordinates, swipe, global_action, open_url, task_done).
3. Always complete the entire user flow: e.g. when asked to send a WhatsApp message, open WhatsApp -> find/click contact -> type message -> click Send button -> verify -> call task_done!
4. If an element cannot be clicked by node_id, use text match, scroll, or tap_coordinates (x, y).
5. When the user's request is completely fulfilled, call task_done and inform the user in Hindi."""
    }

    data class LiveFunctionCall(
        val id: String,
        val name: String,
        val args: JSONObject
    )

    interface LiveListener {
        fun onConnected()
        fun onSetupComplete()
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onInputTranscript(text: String)
        fun onOutputTranscript(text: String)
        fun onToolCall(call: LiveFunctionCall)
        fun onToolCancellation(ids: List<String>)
        fun onInterrupted()
        fun onTurnComplete()
        fun onInteractionStatus(status: String)
        fun onError(error: String)
        fun onClosed(code: Int, reason: String)
    }

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupDone = AtomicBoolean(false)

    var listener: LiveListener? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect() {
        if (apiKey.isBlank()) {
            listener?.onError("Gemini API key is missing")
            return
        }

        val url = "wss://$WS_HOST$WS_PATH?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected, sending setup message")
                isConnected.set(true)
                listener?.onConnected()
                sendSetupMessage()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "onMessage (text, ${text.length} chars)")
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                Log.d(TAG, "onMessage (binary, ${bytes.size} bytes)")
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                isConnected.set(false)
                isSetupDone.set(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                isConnected.set(false)
                isSetupDone.set(false)
                listener?.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected.set(false)
                isSetupDone.set(false)
                listener?.onError(t.message ?: "Connection failure")
            }
        })
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (_: Exception) {}
        webSocket = null
        isConnected.set(false)
        isSetupDone.set(false)
    }

    fun isReady(): Boolean = isConnected.get() && isSetupDone.get()

    // =========================================================================
    // === Send Messages ===
    // =========================================================================

    private fun sendSetupMessage() {
        try {
            val setupObj = JSONObject().apply {
                put("model", "models/$model")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voiceName)
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
                })
                put("tools", JSONArray().put(buildToolsDeclaration()))
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
            }

            val payload = JSONObject().apply {
                put("setup", setupObj)
            }

            webSocket?.send(payload.toString())
            Log.d(TAG, "Setup message sent for model $model with voice $voiceName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send setup message", e)
            listener?.onError("Failed to send setup: ${e.message}")
        }
    }

    private var sentAudioChunkCount = 0

    /**
     * Streams raw PCM audio chunk (16kHz 16-bit little-endian) from microphone.
     */
    fun sendRealtimeAudio(pcmChunk: ByteArray) {
        if (!isReady()) {
            Log.w(TAG, "sendRealtimeAudio dropped: isConnected=${isConnected.get()} isSetupDone=${isSetupDone.get()}")
            return
        }
        try {
            val base64 = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
            val payload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("data", base64)
                        put("mimeType", "audio/pcm;rate=16000")
                    })
                })
            }
            webSocket?.send(payload.toString())
            sentAudioChunkCount++
            if (sentAudioChunkCount % 30 == 1) {
                Log.d(TAG, "Streaming audio to Gemini Live (chunk #$sentAudioChunkCount)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending realtime audio", e)
        }
    }

    /**
     * Hybrid VAD: Signal that the audio stream or user utterance ended.
     */
    fun sendAudioStreamEnd() {
        if (!isReady()) return
        try {
            val payload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audioStreamEnd", true)
                })
            }
            webSocket?.send(payload.toString())
            Log.d(TAG, "Sent audioStreamEnd (Hybrid VAD)")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audioStreamEnd", e)
        }
    }

    /**
     * Send real-time text input into active live session.
     */
    fun sendRealtimeText(text: String) {
        if (!isReady()) return
        try {
            val payload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("text", text)
                })
            }
            webSocket?.send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending realtime text", e)
        }
    }

    /**
     * Injects context turns (e.g. current UI screen state) as client content.
     */
    fun sendClientContent(text: String, turnComplete: Boolean = false) {
        if (!isReady()) return
        try {
            val payload = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", text)))
                    }))
                    put("turnComplete", turnComplete)
                })
            }
            webSocket?.send(payload.toString())
            Log.d(TAG, "Sent clientContent (turnComplete=$turnComplete)")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending clientContent", e)
        }
    }

    /**
     * Sends the execution response for a tool call back to Gemini Live API.
     */
    fun sendToolResponse(callId: String, functionName: String, responseData: JSONObject) {
        if (!isConnected.get()) return
        try {
            val functionResponse = JSONObject().apply {
                put("id", callId)
                put("name", functionName)
                put("response", responseData)
            }

            val payload = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().put(functionResponse))
                })
            }

            webSocket?.send(payload.toString())
            Log.d(TAG, "Sent toolResponse for $functionName ($callId)")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending toolResponse", e)
        }
    }

    // =========================================================================
    // === Handle Server Messages ===
    // =========================================================================

    private fun handleServerMessage(jsonString: String) {
        try {
            val root = JSONObject(jsonString)

            // 1. Setup complete
            if (root.has("setupComplete")) {
                Log.d(TAG, "Setup complete confirmed by Gemini Live server")
                isSetupDone.set(true)
                listener?.onSetupComplete()
                return
            }

            // 2. Server Content (Audio, Transcripts, Interruption)
            if (root.has("serverContent")) {
                val sc = root.getJSONObject("serverContent")

                // Audio chunks in modelTurn
                if (sc.has("modelTurn")) {
                    val parts = sc.getJSONObject("modelTurn").optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Data = inlineData.optString("data")
                                if (base64Data.isNotEmpty()) {
                                    val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    listener?.onAudioDataReceived(audioBytes)
                                }
                            }
                        }
                    }
                }

                // Input transcription (user speech STT)
                if (sc.has("inputTranscription")) {
                    val text = sc.getJSONObject("inputTranscription").optString("text")
                    if (text.isNotEmpty()) listener?.onInputTranscript(text)
                }

                // Output transcription (Gemini speech TTS)
                if (sc.has("outputTranscription")) {
                    val text = sc.getJSONObject("outputTranscription").optString("text")
                    if (text.isNotEmpty()) listener?.onOutputTranscript(text)
                }

                // Barge-in Interruption
                if (sc.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Barge-in: Gemini speech interrupted")
                    listener?.onInterrupted()
                }

                // Turn complete
                if (sc.optBoolean("turnComplete", false)) {
                    listener?.onTurnComplete()
                }

                // Interaction status (IN_PROGRESS vs IDLE)
                if (sc.has("interactionStatus")) {
                    val status = sc.optString("interactionStatus")
                    listener?.onInteractionStatus(status)
                }
            }

            // 3. Tool Calls (UI Actions)
            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                val fcs = toolCall.optJSONArray("functionCalls")
                if (fcs != null) {
                    for (i in 0 until fcs.length()) {
                        val fc = fcs.getJSONObject(i)
                        val call = LiveFunctionCall(
                            id = fc.optString("id"),
                            name = fc.optString("name"),
                            args = fc.optJSONObject("args") ?: JSONObject()
                        )
                        Log.d(TAG, "Received functionCall: ${call.name} (id=${call.id})")
                        listener?.onToolCall(call)
                    }
                }
            }

            // 4. Tool Call Cancellation
            if (root.has("toolCallCancellation")) {
                val tcc = root.getJSONObject("toolCallCancellation")
                val idsArray = tcc.optJSONArray("ids")
                val ids = mutableListOf<String>()
                if (idsArray != null) {
                    for (i in 0 until idsArray.length()) ids.add(idsArray.getString(i))
                }
                listener?.onToolCancellation(ids)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse live server message", e)
        }
    }

    // =========================================================================
    // === Tools Declarations ===
    // =========================================================================

    private fun buildToolsDeclaration(): JSONObject {
        val fns = JSONArray()

        // 1. open_app
        fns.put(JSONObject().apply {
            put("name", "open_app")
            put("description", "Open an application on the Android phone by name (e.g. WhatsApp, YouTube, Chrome, Settings).")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("appName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The name of the app to launch")
                    })
                })
                put("required", JSONArray().put("appName"))
            })
        })

        // 2. click_element
        fns.put(JSONObject().apply {
            put("name", "click_element")
            put("description", "Click a visible UI element on the screen using its node ID or label text.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("nodeId", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "The integer node ID (i) from the screen UI tree")
                    })
                    put("text", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional text or label to search for as fallback")
                    })
                    put("reason", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Why this element is being clicked")
                    })
                })
            })
        })

        // 3. type_text
        fns.put(JSONObject().apply {
            put("name", "type_text")
            put("description", "Type text into an editable text field (EditText) on the screen.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("nodeId", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "The integer node ID of the field to focus and type into")
                    })
                    put("text", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The exact text string to input")
                    })
                    put("reason", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Explanation of what is being typed")
                    })
                })
                put("required", JSONArray().put("text"))
            })
        })

        // 4. scroll
        fns.put(JSONObject().apply {
            put("name", "scroll")
            put("description", "Scroll the screen down (forward) or up (backward).")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("direction", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Scroll direction: 'down' or 'up'")
                    })
                })
                put("required", JSONArray().put("direction"))
            })
        })

        // 5. tap_coordinates
        fns.put(JSONObject().apply {
            put("name", "tap_coordinates")
            put("description", "Directly tap at pixel coordinates (x, y) on the screen.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("x", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "X pixel coordinate")
                    })
                    put("y", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "Y pixel coordinate")
                    })
                    put("reason", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Reason for tapping at these coordinates")
                    })
                })
                put("required", JSONArray().put("x").put("y"))
            })
        })

        // 6. swipe
        fns.put(JSONObject().apply {
            put("name", "swipe")
            put("description", "Perform a swipe gesture on the screen.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("direction", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "'left', 'right', 'up', or 'down'")
                    })
                })
                put("required", JSONArray().put("direction"))
            })
        })

        // 7. global_action
        fns.put(JSONObject().apply {
            put("name", "global_action")
            put("description", "Press a system navigation button: 'back', 'home', 'recent', or 'notifications'.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "'back', 'home', 'recent', or 'notifications'")
                    })
                })
                put("required", JSONArray().put("action"))
            })
        })

        // 8. open_url
        fns.put(JSONObject().apply {
            put("name", "open_url")
            put("description", "Open a website URL in the default browser.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Full URL to open (e.g. https://google.com)")
                    })
                })
                put("required", JSONArray().put("url"))
            })
        })

        // 9. long_press
        fns.put(JSONObject().apply {
            put("name", "long_press")
            put("description", "Long press at coordinates (x, y).")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("x", JSONObject().apply { put("type", "INTEGER") })
                    put("y", JSONObject().apply { put("type", "INTEGER") })
                })
                put("required", JSONArray().put("x").put("y"))
            })
        })

        // 10. take_screenshot
        fns.put(JSONObject().apply {
            put("name", "take_screenshot")
            put("description", "Take a system screenshot.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        })

        // 11. clipboard_action
        fns.put(JSONObject().apply {
            put("name", "clipboard_action")
            put("description", "Perform clipboard operations: 'copy', 'paste', or 'select_all'.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "'copy', 'paste', or 'select_all'")
                    })
                })
                put("required", JSONArray().put("action"))
            })
        })

        // 12. read_screen
        fns.put(JSONObject().apply {
            put("name", "read_screen")
            put("description", "Re-inspect the current screen's full UI tree and visible elements.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        })

        // 13. task_done
        fns.put(JSONObject().apply {
            put("name", "task_done")
            put("description", "Call when the user's requested task is completely finished and verified.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("summary", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Brief Hindi summary of completed actions")
                    })
                })
                put("required", JSONArray().put("summary"))
            })
        })

        return JSONObject().apply {
            put("functionDeclarations", fns)
        }
    }
}
