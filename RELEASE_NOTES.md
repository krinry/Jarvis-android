# ⚡ J.A.R.V.I.S. 2.0 — Made by Krinry

> *"Just A Rather Very Intelligent System"* — Operating-System-Level AI Butler for Android.

Welcome to **J.A.R.V.I.S. 2.0**, a monumental evolution from a standard voice assistant into a true Iron Man operating-system-level Butler. Powered by Google Gemini Live 24kHz bidirectional streaming, Stark Holographic Command Deck, Canvas Arc Reactor HUD, Shizuku Shell, Device Admin, and a 37-Tool Autonomous Action Suite.

---

## 🌟 Major Highlights & What's New

### 🎙️ 1. Gemini Live 24kHz Multimodal Butler Engine
- **Direct BidiGenerateContent WebSocket Streaming**: Real-time microphone audio input (16kHz PCM little-endian) and native loudspeaker playback (24kHz PCM little-endian) using dedicated `AudioTrackPlayer`.
- **Sub-300ms Turn-Taking**: Natural spoken dialogue with hardware echo suppression, Voice Activity Detection (VAD), and automatic voice barge-in interruption.
- **Butler Persona & Bilingual Intelligence**: Natural Hindi/Hinglish and refined English addressing the user respectfully as *"Sir"* or *"Krinry Sir"*.
- **Offline Conversation Memory**: Persistent SQLite turn history (`ConversationHistoryStore`) retaining past context across app restarts.
- **Context Window Compression & Session Resumption**: Prevents 15-minute drops with sliding window compression and auto-reconnects with exponential backoff.

### 🛡️ 2. 37-Tool Autonomous Action Suite & Security Gate
- **Navigation & Apps**: `open_app`, `open_url`, `close_assistant`, `task_done`.
- **Optic Vision Perception**: `analyze_current_screen`, `inspect_real_world` (seamless front/back camera lens frame capture into vision stream), `read_screen`, `take_screenshot`.
- **Hardware Controls**: `toggle_flashlight`, `toggle_hotspot`, `set_volume` (smart stream routing), `toggle_dnd`, `toggle_auto_rotate`, `media_control`, `device_power_control`, `lock_phone`, `set_brightness`.
- **System Authority & Shell**: `execute_shell_command` (ADB / Shizuku / Root), `clear_app_data`, `get_system_diagnostics`.
- **Telecom & Notifications**: `get_recent_notifications`, `reply_notification`, `read_latest_otp`, `make_phone_call`, `search_contacts`, `send_sms`.
- **Anti-Prompt Injection Security Confirmation Gate**: Defends against untrusted text in screens and notifications. High-risk actions (`execute_shell_command`, `clear_app_data`, `send_sms`, power off/reboot) are intercepted client-side, requiring Sir's explicit vocal authorization before execution.

### ⚛️ 3. Stark Holographic Command Deck & Arc Reactor HUD
- **Canvas Arc Reactor View**: Animated holographic Arc Reactor (`ArcReactorView.kt`) with 5 states (`IDLE`, `LISTENING`, `THINKING`, `SPEAKING`, `ERROR`).
- **Real-Time System Telemetry Matrix**: Live gauges for Battery, Thermal sensors, Charging mode, Neural Core status, Memory & Storage diagnostics.
- **Krinry Branding**: Clean `KRINRY • MADE BY KRINRY` & `JARVIS BY KRINRY` header hierarchy.
- **Always-On-Top Floating Arc Reactor Bubble**: Draggable overlay bubble with Stark HUD Quick Controls long-press menu.

### 👂 4. Hands-Free Wake Word & Marvel Soundpack
- **"Hey Jarvis" Voice Activation**: Low-power background wake word detection (`WakeWordDetector.kt`).
- **Authentic Iron Man UI Sound FX**: Custom audio feedback for system boot, arc reactor engage, repulsor charge, and target lock.

---

## 📦 Verified Release APKs (Signed via GitHub Actions)
- `app-universal-release.apk`: Universal build for all Android devices (Android 7.0 to Android 15/16).
- `app-arm64-v8a-release.apk`: Optimized for modern 64-bit ARM devices.
- `app-armeabi-v7a-release.apk`: For 32-bit ARM devices.
- `app-x86_64-release.apk` / `app-x86-release.apk`: For Android emulators and x86 hardware.

---
*Created with ❤️ by Krinry.*
