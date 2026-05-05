# Jarvis AI - Complete Project Documentation

## Project Overview

**Jarvis AI** is an Android AI assistant that uses LLM (Large Language Models) to control the phone via AccessibilityService. It can perform tasks like making calls, sending SMS, opening apps, browsing websites, and more - all through voice commands.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                      UI Layer                              │
│  ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌───────────┐   │
│  │ChatScreen│  │Settings  │  │History   │  │ChatInput  │   │
│  └────┬────┘  └────┬─────┘  └────┬─────┘  └─────┬─────┘   │
│       └─────────────┴──────────────┴────────────┘           │
│                          │                                 │
├──────────────────────────┼────────────────────────────────┤
│                    ViewModel Layer                         │
│  ┌────────────────────────────────────────────┐            │
│  │           ChatViewModel                     │            │
│  │  - messages StateFlow                      │            │
│  │  - sendMessage()                           │            │
│  └─────────────────────┬────────────────────┘            │
│                        │                                    │
├────────────────────────┼────────────────────────────────────┤
│                   Data Layer                               │
│  ┌──────────────┐ ┌──────────────┐ ┌───────────────┐     │
│  │ChatDatabase  │  │ChatMessage  │  │Attachment   │     │
│  │  (Room)      │  │  (Entity)   │  │ (Image/Audio)│     │
│  └──────────────┘ └──────────────┘ └───────────────┘     │
│                          │                                 │
├──────────────────────────┼────────────────────────────────┤
│                    AI Layer                               │
│  ┌──────────────┐ ┌──────────────┐ ┌───────────────┐   │
│  │GroqApiClient │  │LlmProvider   │  │ Providers     │   │
│  │             │  │  (Interface) │  │ (Groq/OR/Gmn)  │   │
│  └──────────────┘ └──────────────┘ └───────────────┘   │
│                          │                                 │
├──────────────────────────┼────────────────────────��───────┤
│                   Agent Layer                             │
│  ┌──────────────┐ ┌──────────────┐ ┌───────────────┐   │
│  │AgentLlmEngine│ │ActionExecutor│  │TaskMemory    │   │
│  │             │  │              │  │              │   │
│  └──────┬──────┘ └──────────────┘ └───────────────┘   │
│         │                                               │
├────────┼───────────────────────────────────────────────┤
│                 Service Layer                           │
│  ┌──────────────┐ ┌──────────────┐ ┌───────────────┐  │
│  │AutoAgentSvc  │  │FloatingBubble│ │ WakeWordList  │  │
│  │(Accessibility│  │Service      │  │              │  │
│  └──────────────┘ └──────────────┘ └───────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. UI Layer (`dev.krinry.jarvis.ui.*`)

| File | Purpose |
|------|---------|
| `ChatScreen.kt` | Main chat interface with message list, input |
| `ChatMessageItem.kt` | Individual message rendering with Markdown |
| `ChatInput.kt` | Text input with model selector |
| `ChatViewModel.kt` | Business logic, message handling |
| `ChatHistoryDrawer.kt` | Side drawer for chat history |
| `SettingsScreen.kt` | API key & model configuration |
| `Theme.kt` / `Color.kt` | Material3 theming |

**Key Features:**
- Markdown rendering for AI responses
- Thinking block display (<think>...</think>)
- Dark/Light theme toggle
- Model selection dropdown
- Voice input support

### 2. Data Layer (`dev.krinry.jarvis.data.chat`)

| File | Purpose |
|------|---------|
| `ChatDatabase.kt` | Room database singleton |
| `ChatDao.kt` | Data access operations |
| `ChatMessage.kt` | Message entity (role, content, timestamp) |
| `Attachment.kt` | Image/Audio/PDF attachments |

**Schema:**
```kotlin
@Entity(tableName = "messages")
data class ChatMessage(
    val id: Long = 0,
    val role: String,        // "user" | "assistant"
    val content: String,     // Message text
    val modelUsed: String? = null,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: String = ""
)
```

### 3. AI Layer (`dev.krinry.jarvis.ai`)

```
┌────────────────────────────────────────┐
│         GroqApiClient (Central)         │
│  - Provider registry                  │
│  - Rate limiting                       │
│  - chat() / agentChatDirect()         │
│  - transcribeAudio() (Whisper)        │
└────────────┬─────────────────────────┘
             │
    ┌────────┼────────┐
    │        │        │
┌───▼──┐ ┌──▼──┐ ┌──▼──┐
│Groq  │ │Open │ │Gmn │
│Prov │ │Rou..│ │Prov │
│     │ │Prov │ │    │
└─────┘ └─────┘ └─────┘
   LlmProvider Interface
```

| File | Purpose |
|------|---------|
| `GroqApiClient.kt` | Central API gateway, rate limiting |
| `LlmProvider.kt` | Provider interface |
| `GroqProvider.kt` | Groq API implementation (free, fast) |
| `OpenRouterProvider.kt` | OpenRouter aggregator |
| `GeminiProvider.kt` | Google Gemini API |
| `GeminiSttClient.kt` | Speech-to-text |
| `GeminiTtsClient.kt` | Text-to-speech |
| `GeminiNativeAudioClient.kt` | Native audio dialog processing |

**Supported Models:**
- Groq: `moonshotai/kimi-k2-instruct-0905`, `openai/gpt-oss-120b`
- OpenRouter: Various open-source models
- Gemini: Google's models

---

## Agent System (`dev.krinry.jarvis.agent`)

### How the Agent Works

```
┌──────────────────────────────────────────────────────────────┐
│                    AgentLlmEngine                           │
│  ─────────────────────────────────────────────────────────  │
│  Step 1: Get Voice Command                                  │
│         │                                                  │
│         ▼                                                  │
│  Step 2: Read Screen (AccessibilityService)                │
│         - UiTreeExtractor extracts UI nodes               │
│         - ScreenDiffCache optimizes (50-70% savings)      │
│         │                                                  │
│         ▼                                                  │
│  Step 3: Build 3 Messages                                  │
│         [ System Prompt                                    │
│           + Context (TaskMemory)                          │
│           + UI (full or diff) ]                            │
│         │                                                  │
│         ▼                                                  │
│  Step 4: Call LLM                                          │
│         - Returns JSON with action + plan                  │
│         │                                                  │
│         ▼                                                  │
│  Step 5: Parse & Execute Action                            │
│         - ActionExecutor.parseResponse()                  │
│         - Execute: click, type, scroll, etc.              │
│         │                                                  │
│         ▼                                                  │
│  Step 6: Update Memory + Repeat                           │
│         - TaskMemory tracks progress                      │
│         - Max 50 iterations                                │
└──────────────────────────────────────────────────────────────┘
```

### Core Agent Files

| File | Purpose |
|------|---------|
| `AgentLlmEngine.kt` | Main brain - plan-first loop |
| `ActionExecutor.kt` | Parse LLM JSON, execute actions |
| `TaskMemory.kt` | Compact context (~50 tokens) |
| `ScreenDiffCache.kt` | UI diff optimization |
| `UiTreeExtractor.kt` | Screen to JSON converter |
| `DirectIntentExecutor.kt` | Fast actions (no UI) |
| `TermuxBridge.kt` | Termux command execution |
| `AgentTtsManager.kt` | Text-to-speech |
| `AgentWebViewManager.kt` | WebView for JS execution |
| `ScreenshotVision.kt` | Screen image analysis |

### Action Types

**UI Actions (need screen):**
- `click` - Click node by ID
- `type` - Type text on node
- `tap_xy` - Tap coordinates
- `scroll_down/up` - Scroll
- `swipe` - Swipe gesture
- `back/home/recent` - System navigation

**Fast Actions (no UI):**
- `call` - Make call
- `send_sms` - Send SMS
- `set_alarm` - Set alarm
- `open_app` - Open app
- `open_url` - Open URL
- `termux_run` - Run Termux command

---

## Services (`dev.krinry.jarvis.service`)

| File | Purpose |
|------|---------|
| `AutoAgentService.kt` | AccessibilityService - screen control |
| `FloatingBubbleService.kt` | Floating bubble overlay |
| `WakeWordListener.kt` | Voice wake word detection |
| `WhisperRecorder.kt` | Audio recording for STT |
| `JarvisNotificationService.kt` | Foreground notification |
| `BubbleMenuManager.kt` | Bubble menu management |

---

## Security (`dev.krinry.jarvis.security`)

| File | Purpose |
|------|---------|
| `SecureKeyStore.kt` | Encrypted API key storage (AndroidKeyStore) |

---

## Dependencies

### Build Configuration

```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}
```

### Key Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Jetpack Compose BOM | 2024.02.00 | UI framework |
| Room | 2.6.1 | Local database |
| Kotlin Coroutines | 1.8.0 | Async operations |
| OkHttp | 4.12.0 | HTTP client |
| Coil3 | 3.0.0 | Image loading |
| Material3 | (from BOM) | Design system |
| Navigation Compose | 2.7.7 | Navigation |
| Mikanz Markdown | 0.34.0 | Markdown rendering |

---

## Data Flow

### 1. User Sends Message

```
User types message
      │
      ▼
ChatScreen.onSubmit()
      │
      ▼
ViewModel.sendMessage()
      │
      ├──▶ Insert user message to Room
      │
      ▼
GroqApiClient.chat()
      │
      ├──▶ Rate limit check
      │
      ├──▶ Call LLM API
      │
      ▼
Insert assistant response
      │
      ▼
UI auto-scrolls to bottom
```

### 2. Agent Executes Task

```
User activates agent (voice/wake word)
      │
      ▼
AgentLlmEngine.startTask()
      │
      ├──▶ Initialize TaskMemory
      │    ScreenDiffCache
      │
      ▼
for iteration in 1..50:
      │
      ├──▶ Get screen via AccessibilityService
      │
      ├──▶ Extract UI tree (UiTreeExtractor)
      │
      ├──▶ Get diff/full UI (ScreenDiffCache)
      │
      ├──▶ Build 3 messages
      │
      ├──▶ Call LLM (GroqApiClient.agentChatDirect)
      │
      ├──▶ Parse JSON (ActionExecutor.parseResponse)
      │
      ├──▶ Execute action (ActionExecutor.execute)
      │
      ├──▶ TTS speak (AgentTtsManager)
      │
      └──▶ Update TaskMemory → repeat
```

---

## Permissions Required

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.READ_NOTIFICATIONS" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />

<!-- Accessibility Service -->
<application>
    <service
        android:name=".service.AutoAgentService"
        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
        <intent-filter>
            <action android:name="android.accessibilityservice.AccessibilityService" />
        </intent-filter>
        <meta-data
            android:resource="@xml/accessibility_service_config" />
    </service>
</application>
```

---

## Configuration Files

### `accessibility_service_config.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_description"
    android:notificationTimeout="100"
    android:settingsActivity="dev.krinry.jarvis.SettingsActivity" />
```

---

## Key APIs

### GroqApiClient

```kotlin
// Main chat
suspend fun chat(context: Context, messages: List<Map<String, String>>): String?

// Agent chat (with retries)
suspend fun agentChatDirect(context: Context, messages: List<Map<String, String>>): String?

// Speech-to-text
suspend fun transcribeAudio(context: Context, audioFile: File): String?

// Fetch models
suspend fun fetchAvailableModels(context: Context): List<ModelInfo>
```

### AutoAgentService

```kotlin
// Screen control
fun getRootNode(): AccessibilityNodeInfo?
fun clickNode(node: AccessibilityNodeInfo): Boolean
fun clickAt(x: Float, y: Float)
fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean
fun scrollForward(node: AccessibilityNodeInfo): Boolean
fun scrollBackward(node: AccessibilityNodeInfo): Boolean

// Navigation
fun pressBack(): Boolean
fun pressHome(): Boolean
fun openRecents(): Boolean
fun openNotifications(): Boolean
```

---

## Error Handling

| Error | Cause | Solution |
|-------|-------|----------|
| `Empty response` | Rate limit / API error | Retry with backoff |
| `Invalid JSON` | Model output parsing failed | 3 retries, then give up |
| `Action failed` | UI element not found | Try different strategy |
| `Accessibility off` | Service not enabled | Prompt user to enable |

---

## Known Limitations

1. **max_tokens**: Currently 4096 (was 300) - ensures full responses
2. **Screen diff**: Helps with token limits on iterations 2+
3. **Termux**: Requires Termux app installed for terminal commands
4. **Accessibility**: Requires explicit permission, cannot be auto-granted

---

## Future Enhancements

- [ ] Multi-modal vision (screenshot analysis)
- [ ] Voice activation with custom wake word
- [ ] More file operations
- [ ] Calendar integration
- [ ] Email sending
- [ ] Better error recovery

---

*Generated: May 2026*