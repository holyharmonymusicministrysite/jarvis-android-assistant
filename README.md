## JARVIS Android Assistant

A free Kotlin Android voice-assistant starter.

### Features
- Start and stop speech recognition while the app is open.
- Detects the phrase Hey JARVIS after listening has been started.
- Uses Android Text-to-Speech (UK English locale where installed).
- Basic commands: tell the time, open YouTube, open WhatsApp, open Android Settings, web search, and greetings.
- GitHub Actions builds a debug APK artifact.

### Important limitations
This is an early starter, not a complete always-on AI assistant. It uses the phone's installed speech-recognition service, which may require internet and device support. The wake phrase is detected only after pressing START LISTENING; it is not an offline background wake-word engine. Listening stops when the app goes into the background. Commands are rule-based and are not yet connected to a conversational AI service.

### Build
Open in Android Studio and build the app debug variant, or push to main and download the JARVIS-debug-apk artifact from a successful GitHub Actions run. Microphone permission is required. No external AI API key is included.
