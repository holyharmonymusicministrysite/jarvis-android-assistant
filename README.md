# SAM Android AI Operator

SAM is a Kotlin Android assistant targeting the owner's Nokia G21. Android builds run in GitHub Actions so an Android IDE and SDK do not need to be installed on the phone.

## Implemented in this branch

- Voice assistant with the wake phrase **Hey SAM** while the foreground microphone service is active.
- Local offline command handling for supported phone actions, time/date, battery status, notes and business-record summaries. No AI-provider API key is required for these built-in commands.
- Routine reversible voice tools: launch installed apps, basic Home/Back/Recents/Lock Screen navigation, web-search handoff, read device time, save/list notes.
- **Wikipedia background research tool**: searches Wikipedia's public API, returns short extracts and source links. This is background research, not comprehensive web crawling or primary-source verification.
- **Local business workspace**: create and list customer, project, task and follow-up records. Up to 200 records are stored in app-private preferences on this device.
- **Android document picker**: owner-selected files can be previewed as text; the owner can create a text file and choose its save location. This is not unrestricted filesystem access and binary files are not previewed.
- Optional on-demand screen and notification reading. The owner must explicitly request reading and grant the relevant Android permission.
- Text-to-speech, Android speech recognition and background foreground-service listening.

## Autonomy and safety

Routine, reversible actions clearly requested by the owner are executed without asking for a second approval. Screen and notification reading remain request-specific. Payments, purchases, transfers, sending/posting messages, sharing private files, important deletion, security-setting changes and other irreversible/high-impact actions are not implemented. SAM must explain these limits rather than pretend. Android permissions and protected system controls cannot be bypassed.

## Not yet implemented or verified

- Full general-purpose web search and multi-site source verification.
- Arbitrary file editing, folder-wide access, and code repository operations from the phone.
- A full CRM with customer contact workflows, quotes, invoices, M-Pesa, email/SMS or cloud sync.
- A true coding IDE/remote build workspace inside the app.
- A downloadable on-device general-purpose language model. The current offline command engine is not a full conversational LLM.
- Reliable wake-word performance and end-to-end testing on the physical Nokia G21.

## AI provider and costs

The online AI provider is Groq's OpenAI-compatible API. The owner must supply a Groq API key in SAM settings. Provider quotas, model availability and terms may change; unlimited use is not promised. Android speech recognition may depend on installed Google/system speech services and is not guaranteed to work offline. Wikipedia research requires internet access.

## Privacy and permissions

- Microphone use is indicated by Android's foreground-service notification.
- Accessibility and notification access are optional and enabled by the owner in Android Settings.
- Screen and notification content are read only after a specific user request.
- Notes and business records are stored locally in app-private preferences. They are not automatically synced or backed up.
- File access uses Android's system document picker; the owner chooses each file and save location.
- Never commit API keys, signing keys or passwords.

## Build and install

GitHub Actions builds a debug APK and uploads it as the **SAM-debug-apk** artifact. This is a test build, not a Play Store release. Download the artifact from a successful workflow run, extract the APK and install it on the phone. Android may ask permission to install apps from the browser or file manager.

A debug signing certificate may change between builds. If Android refuses to update an earlier build due to a signature mismatch, uninstall the earlier app before installing the new one. Uninstalling clears local settings, the Groq key, notes and business records.

A production release needs a persistent private signing key and physical-device testing. Never commit signing keys or passwords.
