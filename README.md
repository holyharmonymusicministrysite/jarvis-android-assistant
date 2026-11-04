# JARVIS Android Assistant

A Kotlin Android voice-assistant project for Android 8.0 (API 26) and later.

## Implemented in the current repair branch

- Branded JARVIS launcher icon and Android 12+ launch screen.
- Start/stop voice recognition while the app is open, with the wake phrase "Hey JARVIS".
- Spoken responses through Android Text-to-Speech.
- Basic commands for time, web search, Settings and launching installed apps by name.
- Opt-in Android Accessibility Service for explicit Home, Back, Recents and Lock Screen commands.
- Optional Gemini AI connection using a key supplied by the device owner.
- Recent conversation history stored locally on the device.

## Android permission and safety requirements

- Microphone permission is required for voice input.
- The Accessibility Service is off until the device owner explicitly enables JARVIS in Android Settings. It is limited to requested global navigation actions and does not retrieve screen content.
- Android controls whether an app can perform protected operations. A normal third-party app cannot silently power off the device, bypass Android security, or grant itself privileged system permissions.
- Listening stops when JARVIS leaves the foreground. This build is not an always-on background wake-word service.
- AI requests require an internet connection and a user-provided Google AI Studio API key. The app stores that key in local app preferences; do not share the key. A production release should move key handling to a secure backend or encrypted storage before wider distribution.
- AI conversation history is stored locally and can be cleared by clearing the app's storage.

## Build and install

GitHub Actions produces a **debug APK** for testing. It is not a Play Store release and is not Play Protect-certified. Android may require the owner to allow installation from the browser or file manager used to download it. If an older JARVIS build is installed with a different signing certificate, Android may require uninstalling it first; uninstalling clears its local settings.

For a trusted public release, configure a persistent private release-signing key and publish through a recognised app store. Never commit signing keys or passwords to the repository.

## Known limitations

This is not yet a full autonomous software-building agent, a background always-listening assistant, or a system-level phone controller. It does not shut down the phone, operate every screen in every third-party app, send messages or make payments automatically. These require additional platform permissions, integrations, safeguards and real-device testing.
