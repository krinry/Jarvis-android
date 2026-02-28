# Jarvis AI Agent

A full-fledged, local-first Android voice assistant that runs entirely on your device with direct API connectivity (Groq/OpenRouter). It doesn't use any middle-man servers, ensuring maximum privacy and security.

## Features

- **Full Device Control:** Control your Android device with simple voice commands using Accessibility Service.
- **Floating Bubble Integration:** Access Jarvis anywhere via a convenient, always-on-top floating widget.
- **Privacy-First (No Middleman Servers):** Directly connects from your phone to AI providers like Groq and OpenRouter. It does NOT route your keys or conversations through any 3rd-party servers.
- **Customizable AI Backend:** Switch seamlessly between Groq (fastest) and OpenRouter (for a huge variety of models). Set both primary and fallback models.
- **Voice Recognition (Whisper):** State-of-the-art on-device Speech-to-Text conversion using Open AI whisper models via Groq API.
- **Context-Aware:** Remembers conversation history for seamless follow-up tasks.

## Setup & Installation

You can clone this repository and build the APK using Android Studio, or just head to the **Releases** tab to download the latest pre-compiled APK.

### Using the App
1. **Accessibility Service:** Go to `Android Settings > Accessibility > Krinry` and turn it ON. This allows Jarvis to control the screen.
2. **Display over other apps:** Grant this permission when prompted so the floating bubble appears.
3. **Microphone Permissions:** Required for Jarvis to hear your voice commands.
4. **API Keys:** You will need to click the API Provider (Groq/OpenRouter) in the settings page and add your own API key to use it.

## Permissions Explained
- `RECORD_AUDIO`: To transcribe the user's voice command to text.
- `SYSTEM_ALERT_WINDOW`: To draw the floating bubble button on top of other apps.
- `BIND_ACCESSIBILITY_SERVICE`: To interact with the Android UI elements on behalf of the user (Click buttons, type text, scroll UI).
- `INTERNET`: For communicating directly with the LLM API provider.

## Contribution
We welcome contributions! Feel free to fork the repository, create a branch, and open a Pull Request.

## Branches
- `main`: The stable branch and source of releases.
- `dev`: The active development branch. Please submit Pull Requests against this branch.

## License
MIT License. See `LICENSE` for more information.
