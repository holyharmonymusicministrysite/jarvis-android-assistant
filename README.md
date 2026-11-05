# SAM — Cross-device personal AI assistant

**Human builder: Max.** SAM is being developed as one personal AI assistant shared by the Android Nokia G21 and Windows 11 HP EliteBook. The Android app retains the legacy JARVIS application ID and source package for compatibility; the assistant identity and visible branding are SAM.

## Identity and voice

- SAM's Android text and voice system context identifies Max as its human builder.
- Android branding and the Windows companion display BUILT BY MAX.
- Primary wake phrase: Hey SAM. Compatibility wake phrase: Hey JARVIS.
- Groq is the configured model/API provider, not SAM's builder.

## Current Android capabilities

- Groq-backed natural-language conversation using the owner's provider key.
- Local conversation context and existing phone-assistance actions.
- Voice service with a visible Android microphone notification and Stop action.
- Optional Android Accessibility-based navigation/screen text after the owner enables the permission.
- Local non-exportable Android Keystore ECDSA P-256 device identity, public-key fingerprint and signing self-test.
- Owner login to the SAM HTTPS gateway; owner token encrypted using Android Keystore AES-GCM.
- Owner controls for creating a pairing code, pairing the phone, reviewing/approving/rejecting device claims, and listing/revoking devices.
- First-phone enrollment using key proof-of-possession and an owner-approved 12-hour device session.
- Shared-task owner UI: create a task for an enrolled device, inspect event history, approve WAITING_FOR_APPROVAL and cancel tasks.

## Current Windows companion capabilities

- Visible Windows desktop app with a non-exportable ECDSA P-256 CNG identity and public-key fingerprint.
- Matching canonical command-signature primitives using ECDSA P-256, SHA-256 and P1363 format.
- HTTPS pairing claimant UI: submit public key, sign server challenge and check owner approval.
- Exchange approved claim for a 12-hour device session; persist token using current-user Windows DPAPI.
- View device identity/heartbeat, revoke the local device session, and view tasks assigned to this laptop.
- Task inbox is a synchronization view only. It does not execute projects or arbitrary commands.

## Company-management foundation (new; CI verification pending)\n\n- Owner-only customer CRM records with input validation and account isolation.\n- Project pipeline beginning at LEAD, with guarded transitions into planning, active, on-hold, completed or cancelled states.\n- KES quotation drafts with scope, exclusions, validity dates and server-calculated line totals; owner can issue an unexpired draft.\n- Company dashboard counts customers, leads, active projects and issued quote value.\n- This is not a complete accounting/ERP system: no invoices, tax handling, payroll, stock, customer portal, quote delivery or M-Pesa settlement yet.\n\n## Current gateway capabilities

The .NET 10 gateway has SQLite-backed persistence and a liveness-only `/health` endpoint that deliberately returns `gatewayReady: false`.

- One-owner bootstrap (no public registration), rate-limited login, PBKDF2-SHA256 password hashes and revocable 12-hour owner sessions.
- One-use pairing codes, P-256 proof-of-possession, explicit owner approval, device listing and revocation.
- One-time claim-secret exchange for revocable 12-hour device sessions; heartbeat and last-seen updates.
- Shared task inbox with per-device assignment, versioned progress events, owner approval gates, cancellation and event history.
- Signed command envelope validation and replay/approval checks; no command dispatch route or remote power operation yet.

## Verified build and test results

- Android CI run #140 passed on commit `9533812` and uploaded a debug APK artifact. The downloaded ZIP passed integrity checks and contains `app-debug.apk`.
- Windows CI run #22 passed on commit `9533812` and uploaded a self-contained Windows x64 artifact. The downloaded ZIP passed integrity checks and contains `SAM.Windows.exe` and its PDB.
- Gateway CI run #21 passed the .NET 10 build, **34/34 tests**, and the SQLite startup/health smoke test on commit `4c51fa7`.

These are automated build/test results. Neither physical device has yet completed an end-to-end pairing test against a deployed gateway.

## Important limitations — not yet complete

- The gateway has not been deployed to a public HTTPS host. Owner first-time setup needs a server-only `SAM_OWNER_SETUP_SECRET` of at least 32 UTF-8 bytes; never put it in the Android app, Windows client or repository.
- The latest client screens are built but have not been exercised on the actual Nokia G21 or HP EliteBook.
- Shared tasks synchronize instructions and status; there is no autonomous project-building execution worker, isolated code workspace or verified project artifact runner yet.
- There is no signed remote shutdown/restart dispatch, no countdown/cancel command UX, no remote screen/input session, and no live location service.
- The gateway intentionally remains not-ready until production HTTPS, database migrations/backups, audit records and remaining device/command security controls are completed.
- Android background microphone use remains subject to Android permissions, foreground-service and battery rules. SAM cannot bypass the lock screen or silently power on a switched-off phone.

## Development status

All work remains on the private `sam/autonomous-agent-foundation` branch. `main` has not been changed, no deployment has been performed and no production release has been signed.

See `SAM_BUILDER_IDENTITY.md`, `SAM.Windows/README.md`, `SAM.Gateway/README.md`, `SAM_CROSS_DEVICE_API.md` and `SAM_PLATFORM_PLAN.md`.