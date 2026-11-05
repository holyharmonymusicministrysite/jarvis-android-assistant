# JARVIS Android Assistant

A Kotlin Android personal AI assistant for Android 8.0 (API 26) and later. Current repair release: **0.3.0**.

## Current agent capabilities

- Groq-backed natural-language AI with automatic selection from models available to the user's API key.
- Multi-turn conversation context stored locally on the device.
- AI function calling from the background voice service (not only from the open app).
- Launch installed apps, Home, Back, Recents and Lock Screen through Android's user-enabled Accessibility Service.
- Open web search, read local time, save and list notes.
- Optional, on-demand screen-text reading after the owner enables JARVIS Accessibility.
- Optional notification reading after the owner grants Android notification access.
- Voice wake phrase "Hey JARVIS", background foreground-service listening, speech output and an attempt at interruption detection that filters obvious recognition of JARVIS's own speech.
- Notification with a Stop action while the microphone foreground service is active.

## Required owner setup

1. Install JARVIS and open it.
2. Grant microphone permission.
3. Open **AI Settings** and enter a valid Groq API key from the owner's Groq account. The key is stored in app-private preferences on the device.
4. Press **Start Listening**. Android displays an ongoing microphone notification while the voice service runs.
5. For phone navigation and screen reading, open **Phone Control Setup** and explicitly enable JARVIS in Android Accessibility settings.
6. To read notifications, ask JARVIS to open notification access settings and explicitly grant access in Android Settings. This is optional.

## Privacy and safety

- Screen text is retrieved only when the owner explicitly asks JARVIS to read or explain the current screen. The accessibility service does not continuously collect or upload screen events.
- Notification text is kept in a short local history only after the owner enables notification access. JARVIS reads that history only when explicitly asked.
- When the owner asks an AI question that requires screen or notification content, that requested content is sent to Groq as part of that specific AI request. Do not ask JARVIS to read sensitive content unless the owner intends to share it with the connected AI provider.
- The Groq key and conversation history are stored in app-private local preferences. Do not share the key. A future public multi-user release should use a secure backend rather than embedding a personal key in a distributed app.
- The Accessibility Service is opt-in and uses Android's standard assistive APIs. Android still controls protected actions and permission prompts.

## Android platform limits

- Android may stop background services for battery or system reasons. The owner can stop listening from the persistent notification.
- After a reboot, Android displays a reminder notification; the owner must open JARVIS and start listening again. The app does not silently restart microphone capture on boot.
- Android does not let an ordinary third-party app bypass the lock screen, silently unlock the phone, power the phone off, or grant itself privileged permissions.
- Some apps do not expose readable accessibility text. JARVIS cannot inspect or control every third-party screen.
- Interruption detection depends on the phone's speech-recognition provider, microphone, speaker and acoustic conditions. It must be tested on the actual Nokia G21 before being described as reliable.
- The web-search tool opens Android's search UI; it does not yet autonomously browse pages or retrieve search results into the AI context.
- AI model access, rate limits and free usage are controlled by Groq and may change. A free API key does not guarantee unlimited usage.

## Build and install

GitHub Actions produces an installable **debug APK** for testing. It is not a Play Store release and is not Play Protect-certified. Android may require the owner to allow installation from the browser or file manager used to download it.

Each GitHub-hosted debug build may use a different temporary debug signing certificate. If Android refuses to update an older build because its signature differs, uninstall the old JARVIS app first, then install the new APK. Uninstalling clears local preferences, the saved Groq key, notes and conversation history, so keep the API key available to enter again.

A production release requires a persistent private signing key and real-device testing. Never commit signing keys or passwords to the repository.

## SAM cross-device development status

SAM is being developed as one assistant for the Android phone and Windows 11 laptop. The cross-device gateway and pairing protocol are still under development; the clients are not connected to a shared account.

### Windows 11 companion foundation

The separate SAM.Windows project provides a visible Windows Forms setup window and creates a per-user ECDSA P-256 signing key in the Windows Software Key Storage Provider with private-key export disabled. It exposes the public key and fingerprint. It now contains a matching typed-command canonicalizer and P1363 signature helper. It is not paired and has no working remote commands, screen viewing, shared tasks, or location reporting. The newest Windows x64 self-contained build passed after a compile fix; its ZIP artifact was downloaded and integrity-checked. It is not a signed installer and has not been tested on the owner's HP laptop.


### Android device identity foundation

The Android client now includes a local Android Keystore ECDSA P-256 identity, with a non-exportable private key, public-key fingerprint, signing self-test and the same canonical command/signature wire format as the Windows and gateway code. A SAM DEVICE IDENTITY screen displays the fingerprint and public key. This does not enroll or pair the phone. The debug APK built successfully and was uploaded from the SAM branch; it has not been exercised on the actual Nokia G21 yet.

### Gateway foundation

The SAM.Gateway project is a .NET 8 ASP.NET Core scaffold. It exposes only a process-liveness health route and deliberately reports readiness as not-ready. It has no login, device pairing, task-sync, event, or remote-command API.

A separate security library validates a narrowly typed signed command envelope for status and approval-gated power actions. It does not execute any action. The replay-store and server-side approval-verifier are interfaces; production database-backed implementations, authentication, enrollment and audit storage are still required. Twelve executable security checks passed in GitHub Actions (12/12), including a cross-platform canonicalization vector. The gateway build also succeeded. This verifies the current validator code, not production deployment or a real device connection.

See SAM.Windows/README.md, SAM.Gateway/README.md, SAM_CROSS_DEVICE_API.md, and SAM_PLATFORM_PLAN.md.
