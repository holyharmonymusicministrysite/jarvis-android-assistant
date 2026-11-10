# SAM Android AI Operator

SAM is the renamed continuation of the existing Kotlin Android assistant project. It targets the owner's Nokia G21 and is built in GitHub Actions so Android SDK and Gradle files do not need to be installed on the phone.

## Current foundation (0.3.0)

- Voice interaction with the wake phrase "Hey SAM" while the foreground listening service is running.
- Groq-backed natural-language conversations and multi-turn context.
- App launching and basic Android navigation through the owner's opt-in Accessibility Service.
- Web-search handoff to Android's search interface.
- Local notes, device time, and optional on-demand screen/notification reading.
- Android text-to-speech and speech recognition.
- Cloud APK builds through GitHub Actions.

## Important current limitations

This is a foundation build, not yet the complete SAM operator requested by the owner. The following work remains:
- Add a real task-plan review screen and require explicit owner approval before every tool action.
- Replace the current limited web-search handoff with actual retrieval and source-aware research.
- Add file browsing/editing with Android Storage Access Framework and explicit approval.
- Add coding workspace and cloud build handoff.
- Add business workspace and structured project/customer/task records.
- Add a tested offline language model option that fits the Nokia G21's memory and storage.
- Test wake-word reliability, background behavior, voice interruption and all phone controls on the actual Nokia G21.

## Free-service and AI setup

The current online reasoning provider is Groq's OpenAI-compatible API. The owner must supply their own API key in SAM's settings. A free provider tier may be available, but quotas, model availability and terms are controlled by the provider and can change. This project does not contain an API key and does not promise unlimited online AI.

Android's built-in speech recognition and text-to-speech may depend on installed system services and language packs. The current speech-recognition implementation is not a fully offline transcription engine.

## Privacy and permissions

- Microphone use is visible through Android's foreground-service notification.
- Accessibility and notification access are optional and must be enabled by the owner in Android Settings.
- Screen text is read only on an explicit request.
- Notification content is read only on an explicit request after permission is granted.
- Sensitive actions must be subject to explicit owner approval. The approval gate is a required next implementation milestone and is not yet complete in this foundation build.
- Android permission prompts and protected system actions cannot be bypassed.

## Build and install

GitHub Actions builds a debug APK and uploads it as the **SAM-debug-apk** artifact. It is a test build, not a Play Store release. Download the artifact from the successful workflow run, extract the APK, and install it on the phone. Android may ask you to allow installation from the browser or file manager.

Debug signing certificates can change between builds. If Android refuses to update an earlier build because the signature differs, uninstall the earlier app before installing the new one. Uninstalling clears local settings, the Groq key, notes and conversation history.

A production release requires a persistent private signing key and real-device testing. Never commit signing keys or passwords to the repository.
