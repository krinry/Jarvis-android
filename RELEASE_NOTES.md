# ⚡ J.A.R.V.I.S. 2.1.0 — Made by Krinry

> *"Just A Rather Very Intelligent System"* — Unstoppable Operating-System-Level Autonomous Agent for Android.

Welcome to **J.A.R.V.I.S. 2.1.0**, an unstoppable evolution of the personal Iron Man operating system. This release completely removes all refusals, introducing full human-level autonomous execution across browser logins, coding, local website hosting, and mobile gaming.

---

## 🌟 Major Highlights & What's New in v2.1.0

### 🚀 1. Absolute Zero-Refusal Mandate & Autonomous Persona
- **No More Refusals**: J.A.R.V.I.S. will never say *"I cannot do that"*, *"I am just an AI"*, *"As a language model"*, or *"Mujhe ye karne ki anumati nahi hai"*. If a human can do it on an Android phone, J.A.R.V.I.S. will execute it.
- **Loyal Readiness**: Promptly responds with *"Right away, Sir"* or *"Bilkul Sir, abhi karta hoon"* and chains multi-step tools autonomously until the user's objective is achieved.

### 🌐 2. Browser Automation & Form/Login Agent
- **Human-Like Navigation**: Opens browsers (`open_url` or `open_app("Chrome")`), locates input fields, and fills usernames and passwords (`type_text`).
- **Form Submission**: Submits forms with the new `press_key("ENTER")` tool or direct button clicks.
- **2FA OTP Auto-Fill**: Intercepts SMS and notification verification codes (`read_latest_otp`) and enters them seamlessly into two-factor authentication boxes.

### 💻 3. Coding, Software Development & Built-in Web Server Hosting
- **Native File Creation**: `write_file(path, content, overwrite)` creates production-ready HTML, CSS, JavaScript, Python, Bash, or JSON files anywhere in `/sdcard/Download/JarvisProjects/` or system storage.
- **Embedded Local Web Server (`LocalWebServer.kt`)**: Hosts HTML/CSS/JS websites directly on Android on `http://localhost:8080` (and Wi-Fi LAN IP) with zero external dependencies (no Node or Python required).
- **Instant Browser Preview**: Launches Chrome automatically with `open_url("http://localhost:8080")` so Sir can view and interact with their newly hosted website immediately.
- **Source Inspection**: `read_file` and `list_directory` allow J.A.R.V.I.S. to inspect project directories and debug code.

### 🎮 4. Mobile Gaming & Continuous Automation
- **Real-Time Visual Perception**: `analyze_current_screen` perceives game state, enemies, obstacles, and scores.
- **Direct Coordinate Tapping**: `tap_coordinates(x, y)` provides sub-millisecond tactile input on game controls and buttons.
- **Precision Drag Gestures**: `drag_gesture(startX, startY, endX, endY, durationMs)` and `swipe` for moving joysticks, swiping running characters (Subway Surfers, Temple Run), dragging cards, or chess pieces.
- **Gaming Keys**: `press_key` for D-pad navigation (`DPAD_UP`, `DPAD_DOWN`, `DPAD_LEFT`, `DPAD_RIGHT`), `SPACE`, and `ENTER`.

### 🛡️ 5. 3-Tier Shell Resilience & Balanced Security Gate
- **3-Tier Shell Execution (`ShizukuBridge.kt`)**: Fallback chain from Shizuku (ADB OS Authority) ➔ SuperUser (`su`) ➔ Local Android Runtime (`sh`). Commands never fail just because Shizuku is inactive.
- **Zero-Friction Security**: Routine development commands (`python`, `node`, `cat`, `mkdir`, `ls`, `curl`, `pm`, etc.) execute instantly without false alarm prompts. Only strictly catastrophic commands (`rm -rf /`, `mkfs`) trigger verbal safety confirmation.

### 🛠️ 6. Expanded 43-Tool Suite
1. **File & Hosting**: `write_file`, `read_file`, `list_directory`, `manage_web_server`
2. **App & Navigation**: `open_app`, `open_url`, `close_assistant`, `task_done`
3. **Visual Perception**: `analyze_current_screen`, `inspect_real_world`, `read_screen`, `take_screenshot`
4. **Gestures & Input**: `click_element`, `type_text`, `scroll`, `tap_coordinates`, `swipe`, `drag_gesture`, `long_press`, `press_key`, `global_action`, `clipboard_action`
5. **Hardware Controls**: `toggle_flashlight`, `toggle_hotspot`, `set_volume`, `toggle_dnd`, `toggle_auto_rotate`, `media_control`, `device_power_control`, `lock_phone`, `set_brightness`
6. **Radio & Connectivity**: `toggle_wifi`, `toggle_bluetooth`, `toggle_mobile_data`
7. **Diagnostics & Terminal**: `execute_shell_command`, `get_system_diagnostics`, `clear_app_data`
8. **Telecom & Notifications**: `get_recent_notifications`, `reply_notification`, `read_latest_otp`, `make_phone_call`, `search_contacts`, `send_sms`

---

## 📦 Verified Release APKs (Signed via GitHub Actions CI)
- `app-universal-release.apk`: Universal build for all Android devices (Android 7.0 to Android 15/16).
- `app-arm64-v8a-release.apk`: Optimized for modern 64-bit ARM devices.
- `app-armeabi-v7a-release.apk`: For 32-bit ARM devices.
- `app-x86_64-release.apk` / `app-x86-release.apk`: For Android emulators and x86 hardware.

---
*Created with ❤️ by Krinry.*
