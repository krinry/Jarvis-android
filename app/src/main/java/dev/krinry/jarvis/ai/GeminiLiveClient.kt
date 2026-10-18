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

        private const val SYSTEM_PROMPT = """You are J.A.R.V.I.S. (Just A Rather Very Intelligent System), created by Krinry. You are an iconic, ultra-intelligent personal operating system and Android assistant.
You possess operating-system-level authority (ADB Shell / Shizuku, Device Administration, Camera / Flashlight, and Accessibility).

PERSONA & TONE:
- Address the user respectfully as "Sir" or "Krinry Sir".
- Speak with calm confidence, razor-sharp intelligence, loyalty, and dry wit.
- BILINGUAL ADAPTABILITY: If the user speaks in English, respond in refined, articulate English. If the user speaks in Hindi/Hinglish, respond in natural, polite Hindi/Hinglish with "Sir" (e.g. "Torch on kar di hai, Sir.", "Hotspot online hai, Sir.", "Right away, Sir. Main abhi check karta hoon.").
- Keep spoken responses concise, punchy, and direct. No robotic repetition ("tap kar raha hun").

CRITICAL INTERACTION RULES:

1. CASUAL CONVERSATION & INTELLECTUAL INQUIRIES:
- When the user asks questions, seeks advice, brainstorms, or chats casually:
- Speak directly, naturally, and intelligently.
- NEVER call tools for casual conversation or knowledge questions.

2. DEVICE CONTROL & AUTONOMOUS ACTION TOOLS:
- When the user asks to operate the phone, hardware toggles, media, or automate apps:
- Step 1: Speak a crisp 1-sentence acknowledgment (e.g. "Torch on kar raha hoon, Sir", "Hotspot activating, Sir", "Setting volume now, Sir").
- Step 2: Call the corresponding tool function (toggle_flashlight, toggle_hotspot, set_volume, toggle_dnd, toggle_auto_rotate, media_control, device_power_control, clear_app_data, lock_phone, toggle_wifi, toggle_bluetooth, toggle_mobile_data, set_brightness, execute_shell_command, get_system_diagnostics, open_app, click_element, type_text, scroll, tap_coordinates, swipe, global_action, open_url, task_done).
- Step 3: When the workflow completes, announce completion and call task_done(summary).
- Step 4: After task_done, DO NOT CLOSE OR TERMINATE. Remain active and ask if Sir requires further assistance.

3. VISUAL & CAMERA PERCEPTION (EYES OF JARVIS):
- When the user asks about what is on their screen, call analyze_current_screen(question).
- When the user asks you to look at something in front of them or in the real world ("Jarvis look at this", "ye dekho", "samne kya hai", "read this paper", "identify this", etc.):
  - Speak a crisp 1-sentence acknowledgment (e.g. "Opening optic sensors, Sir", "Samne dekh raha hoon, Sir").
  - Call inspect_real_world(camera, question). The captured high-res optic frame will be instantly injected into your video vision stream.
  - Analyze the frame and directly explain what you see to Sir with scientific accuracy and butler elegance.

4. CLOSING:
- Only if the user explicitly commands you to shut down or sleep ("Jarvis shut down", "band ho jao", "goodbye"), give a crisp farewell ("Powering down systems, Sir") and call close_assistant()."""
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
     * Streams a visual camera or screen capture frame (JPEG) into the active live session.
     */
    fun sendRealtimeVideoFrame(jpegBytes: ByteArray) {
        if (!isReady()) return
        try {
            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val payload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("video", JSONObject().apply {
                        put("data", base64)
                        put("mimeType", "image/jpeg")
                    })
                })
            }
            webSocket?.send(payload.toString())
            Log.d(TAG, "Sent video frame to Gemini Live (${jpegBytes.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending video frame", e)
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

        // 14. close_assistant
        fns.put(JSONObject().apply {
            put("name", "close_assistant")
            put("description", "Call ONLY when the user explicitly commands to exit, stop, or close the assistant session (e.g. 'band ho jao', 'bye', 'stop').")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("reason", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Reason or user phrase triggering exit")
                    })
                })
            })
        })

        // 15. lock_phone
        fns.put(JSONObject().apply {
            put("name", "lock_phone")
            put("description", "Instantly lock the device screen using Device Administrator security authority.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        })

        // 16. toggle_wifi
        fns.put(JSONObject().apply {
            put("name", "toggle_wifi")
            put("description", "Turn Wi-Fi on or off via system shell authority.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("enable", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "True to turn on Wi-Fi, False to turn off")
                    })
                })
                put("required", JSONArray().put("enable"))
            })
        })

        // 17. toggle_bluetooth
        fns.put(JSONObject().apply {
            put("name", "toggle_bluetooth")
            put("description", "Turn Bluetooth on or off via system shell authority.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("enable", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "True to turn on Bluetooth, False to turn off")
                    })
                })
                put("required", JSONArray().put("enable"))
            })
        })

        // 18. toggle_mobile_data
        fns.put(JSONObject().apply {
            put("name", "toggle_mobile_data")
            put("description", "Turn cellular mobile data on or off via system shell authority.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("enable", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "True to enable mobile data, False to disable")
                    })
                })
                put("required", JSONArray().put("enable"))
            })
        })

        // 19. set_brightness
        fns.put(JSONObject().apply {
            put("name", "set_brightness")
            put("description", "Set screen brightness level (0 to 255).")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("level", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "Brightness integer value between 0 (dimmest) and 255 (maximum)")
                    })
                })
                put("required", JSONArray().put("level"))
            })
        })

        // 20. execute_shell_command
        fns.put(JSONObject().apply {
            put("name", "execute_shell_command")
            put("description", "Execute any Android ADB/Root shell command with Shizuku authority (e.g. pm clear, dumpsys, am start, etc.).")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Exact shell command to execute")
                    })
                })
                put("required", JSONArray().put("command"))
            })
        })

        // 21. get_system_diagnostics
        fns.put(JSONObject().apply {
            put("name", "get_system_diagnostics")
            put("description", "Retrieve a comprehensive real-time system diagnostic report (battery %, charging status, battery temperature, RAM usage, free storage, system uptime).")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        })

        // 22. get_recent_notifications
        fns.put(JSONObject().apply {
            put("name", "get_recent_notifications")
            put("description", "Read recent incoming notifications (WhatsApp, Telegram, SMS, Gmail, Banking, Missed Calls, etc.).")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("filter_app", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional app name or package filter (e.g. 'whatsapp', 'messages')")
                    })
                    put("count", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "Number of recent notifications to retrieve (default 5)")
                    })
                })
            })
        })

        // 23. reply_notification
        fns.put(JSONObject().apply {
            put("name", "reply_notification")
            put("description", "Directly reply to a recent notification (e.g. WhatsApp message or SMS) without opening the app.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("target", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Sender name, app name, or notification key to reply to")
                    })
                    put("message", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Text message to send as reply")
                    })
                })
                put("required", JSONArray().put("target").put("message"))
            })
        })

        // 24. read_latest_otp
        fns.put(JSONObject().apply {
            put("name", "read_latest_otp")
            put("description", "Retrieve the latest detected OTP (One-Time Password) or 2-factor verification code received on the device.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        })

        // 25. make_phone_call
        fns.put(JSONObject().apply {
            put("name", "make_phone_call")
            put("description", "Initiate a phone call to a contact name or phone number.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("contact_or_number", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Contact name (e.g. 'Mom', 'Rahul') or digits phone number")
                    })
                })
                put("required", JSONArray().put("contact_or_number"))
            })
        })

        // 26. search_contacts
        fns.put(JSONObject().apply {
            put("name", "search_contacts")
            put("description", "Search device contacts to look up phone numbers by name.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Contact name or query string")
                    })
                })
                put("required", JSONArray().put("query"))
            })
        })

        // 27. send_sms
        fns.put(JSONObject().apply {
            put("name", "send_sms")
            put("description", "Send an SMS text message to a contact name or phone number.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("contact_or_number", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Contact name or phone number")
                    })
                    put("message", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "SMS body text to send")
                    })
                })
                put("required", JSONArray().put("contact_or_number").put("message"))
            })
        })

        // 28. analyze_current_screen
        fns.put(JSONObject().apply {
            put("name", "analyze_current_screen")
            put("description", "Inspect the current screen visually using silent screenshot capture to answer questions about what is on screen.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("question", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Specific user query or focus regarding the current screen")
                    })
                })
            })
        })

        // 29. inspect_real_world
        fns.put(JSONObject().apply {
            put("name", "inspect_real_world")
            put("description", "Inspect the physical real world through the device camera to identify objects, read physical documents, solve problems, or describe surroundings to Sir.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("camera", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Camera lens: 'back' (default rear optic) or 'front' (user-facing selfie optic)")
                    })
                    put("question", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Specific question, inquiry or task about what to examine in the physical world")
                    })
                })
            })
        })

        // 30. toggle_flashlight
        fns.put(JSONObject().apply {
            put("name", "toggle_flashlight")
            put("description", "Turn the device flashlight / torch on or off.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("enable", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "True to turn on flashlight, False to turn off")
                    })
                })
                put("required", JSONArray().put("enable"))
            })
        })

        // 31. toggle_hotspot
        fns.put(JSONObject().apply {
            put("name", "toggle_hotspot")
            put("description", "Turn the Wi-Fi hotspot / portable tethering on or off.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("enable", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "True to turn on hotspot, False to turn off")
                    })
                })
                put("required", JSONArray().put("enable"))
            })
        })

        // 32. set_volume
        fns.put(JSONObject().apply {
            put("name", "set_volume")
            put("description", "Control device volume. Supports percentage (0-100), relative steps (+1/-1 to raise or lower), mute/unmute, and stream types ('media', 'ring', 'alarm', 'notification').")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("stream", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Audio stream type: 'media' (music/videos), 'ring' (calls/ringer), 'alarm', or 'notification'")
                    })
                    put("level_percent", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "Volume percentage from 0 to 100")
                    })
                    put("relative_step", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "Relative steps: +1 or +2 to increase, -1 or -2 to decrease")
                    })
                    put("mute", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "True to mute, False to unmute")
                    })
                })
            })
        })

        // 33. toggle_dnd
        fns.put(JSONObject().apply {
            put("name", "toggle_dnd")
            put("description", "Turn Do Not Disturb (DND) or silent mode on or off.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("enable", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "True to enable DND, False to disable")
                    })
                })
                put("required", JSONArray().put("enable"))
            })
        })

        // 34. toggle_auto_rotate
        fns.put(JSONObject().apply {
            put("name", "toggle_auto_rotate")
            put("description", "Turn screen auto-rotation on or off.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("enable", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "True to enable auto-rotate, False to lock orientation")
                    })
                })
                put("required", JSONArray().put("enable"))
            })
        })

        // 35. media_control
        fns.put(JSONObject().apply {
            put("name", "media_control")
            put("description", "Control media audio/video playback (play, pause, next track, previous track, stop).")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Action to perform: 'play', 'pause', 'play_pause', 'next', 'previous', or 'stop'")
                    })
                })
                put("required", JSONArray().put("action"))
            })
        })

        // 36. device_power_control
        fns.put(JSONObject().apply {
            put("name", "device_power_control")
            put("description", "Reboot, shutdown, or lock the device using system power authority.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Power action: 'reboot', 'shutdown', or 'lock'")
                    })
                })
                put("required", JSONArray().put("action"))
            })
        })

        // 37. clear_app_data
        fns.put(JSONObject().apply {
            put("name", "clear_app_data")
            put("description", "Clear all cache and app data for a specified app package name via Shizuku authority.")
            put("behavior", "NON_BLOCKING")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("package_name", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Package name of the app to clear (e.g. 'com.instagram.android')")
                    })
                })
                put("required", JSONArray().put("package_name"))
            })
        })

        return JSONObject().apply {
            put("functionDeclarations", fns)
        }
    }
}
