# SAM Android AI Operator

SAM is the renamed continuation of the Kotlin Android assistant project. It targets the owner's Nokia G21 and is built in GitHub Actions so the Android SDK and Gradle do not need to be installed on the phone.

## Implemented foundation

- Voice interaction using the wake phrase **“Hey SAM”** while the foreground microphone service is running.
- Groq-backed natural-language conversations and multi-turn context. The owner supplies their own API key; no key is embedded.
- Launch installed Android apps and perform basic Home, Back, Recents and Lock Screen navigation through the owner's optional Accessibility Service.
- Hand off a query to Android web search. This opens a search page; it does not retrieve or summarize web results inside SAM.
- Save and list up to 50 local notes.
- Optional on-demand visible-screen and notification reading. These are only invoked when the owner explicitly asks and enables the relevant Android permission.
- Android speech recognition and text-to-speech, including voice interruption handling in the background service.
- Cloud debug-APK builds through GitHub Actions.

## Autonomy and safety policy

Routine, reversible actions that the owner clearly requests are handled automatically: opening apps, basic navigation, web-search handoff, reading the device time, and saving/listing notes. The owner does not need to approve each of these separately.

Screen and notification reading remain opt-in and request-specific. Payments, purchases, transfers, sending or posting messages, sharing private files/data, important deletion, security-setting changes and other irreversible/high-impact actions are not enabled. SAM must not pretend to perform them. Android permissions, lock screens and protected system controls cannot be bypassed.

This is an early operator foundation, not a fully autonomous general-purpose agent. It does not yet include real web retrieval, general file management, a coding workspace, a business CRM/workspace, or a tested offline language model.

## Free-service and AI setup

The current online reasoning provider is Groq's OpenAI-compatible API. The owner must supply their own Groq API key in SAM's settings. A free provider tier may be available, but quotas, model availability and terms are controlled by the provider and can change. Unlimited AI use is not guaranteed.

Android speech recognition and text-to-speech may depend on installed system services and language packs. Speech recognition is not a fully offline transcription engine. A local language model has not yet been integrated or tested on the Nokia G21.

## Privacy and permissions

- Microphone use is visible through Android's foreground-service notification.
- Accessibility and notification access are optional and must be enabled by the owner in Android Settings.
- Screen text and notification content are read only on an explicit request.
- The Groq API key and local notes are stored in app-private preferences on the device.
- Never commit API keys, signing keys or passwords to this repository.

## Build and install

GitHub Actions builds a debug APK and uploads it as the **SAM-debug-apk** artifact. It is a test build, not a Play Store release. Download the artifact from the successful workflow run, extract the APK, and install it on the phone. Android may ask you to allow installation from the browser or file manager.

Debug signing certificates can change between builds. If Android refuses to update an earlier build because the signature differs, uninstall the earlier app before installing the new one. Uninstalling clears local settings, the Groq key, notes and conversation history.

A production release requires a persistent private signing key and real-device testing. Never commit signing keys or passwords to the repository.
