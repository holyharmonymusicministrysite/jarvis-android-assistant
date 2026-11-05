# SAM Cross-Device API — implementation contract and status

**Assistant:** SAM. **Human builder:** Max. Android and Windows clients are intended to share one owner account and one versioned task history.

This document distinguishes implemented API behavior from planned capabilities. The gateway is not deployed. Passing CI does not establish a live connection to either physical device.

## Trust boundaries

- The owner account is single-owner; public sign-up is disabled.
- Each enrolled device has its own P-256 key and revocable device record.
- Private keys stay on the device (Android Keystore or Windows CNG).
- Clients connect only to an HTTPS gateway. Credentials and pairing secrets must never be placed in source control or the APK.
- Owner sessions and device sessions are distinct bearer-token types, expire after 12 hours and are stored only as token hashes server-side.
- Every state mutation is authenticated, scoped to the account/device and version-checked where appropriate.
- Task websites, repositories, messages and downloaded files remain untrusted input; they cannot override SAM's security policy.

## Implemented owner authentication

- POST /api/v1/setup/owner: one-time initial owner bootstrap; requires server-only SAM_OWNER_SETUP_SECRET with at least 32 UTF-8 bytes in X-SAM-Setup-Secret.
- POST /api/v1/auth/login: rate-limited owner sign-in.
- GET /api/v1/auth/me: owner session validation.
- POST /api/v1/auth/logout: owner session revocation.

Passwords use PBKDF2-SHA256 with a random salt and 600,000 iterations. Session tokens are random 256-bit values; only SHA-256 hashes are stored. There is no public registration endpoint. The setup secret must be provisioned directly on the gateway host and removed after initial owner creation.

## Implemented proof-of-possession device pairing

1. The authenticated owner calls POST /api/v1/pairing/invites. The gateway returns a random eight-digit, one-use pairing code with a ten-minute expiry.
2. The claimant calls POST /api/v1/pairing/claims/start with pairingCode, deviceName, platform (android or windows), and publicKeySpkiBase64.
3. The gateway returns claimId, deviceId, challenge, claimSecret, proofPayloadBase64, fingerprintSha256 and expiresAt.
4. The device signs the exact decoded proofPayloadBase64 using its private P-256 key, SHA-256 and 64-byte IEEE P1363 format, then calls POST /api/v1/pairing/claims/{claimId}/prove with signatureBase64Url.
5. The authenticated owner reviews GET /api/v1/pairing/claims and calls /approve or /reject. A claim without proofVerified=true cannot be approved.
6. The claimant polls POST /api/v1/pairing/claims/{claimId}/status with claimSecret. The claim ID alone does not disclose state.
7. After approval, the device exchanges claimSecret once at POST /api/v1/pairing/claims/{claimId}/session for a 12-hour device bearer token.

Owner device registry:
- GET /api/v1/devices
- POST /api/v1/devices/{deviceId}/revoke

Device session:
- GET /api/v1/devices/me
- POST /api/v1/devices/me/heartbeat
- POST /api/v1/devices/me/logout

Device session tokens are stored only as SHA-256 hashes. Each session is bound to the enrolled device, expires after 12 hours, and becomes invalid when that device is revoked. The Android source stores the token encrypted using Android Keystore AES-GCM. The Windows source stores it with current-user DPAPI.

## Implemented shared task synchronization

Owner routes:
- POST /api/v1/tasks: create a task assigned to one active device.
- GET /api/v1/tasks: list account tasks.
- GET /api/v1/tasks/{taskId}/events: retrieve ordered versioned progress events.
- POST /api/v1/tasks/{taskId}/approve-run: owner approves a task currently waiting for approval.
- POST /api/v1/tasks/{taskId}/cancel: owner cancels a non-terminal task.

Device routes:
- GET /api/v1/tasks/inbox: only active tasks assigned to the current device.
- POST /api/v1/tasks/{taskId}/progress: update the task status and append a versioned event.

Allowed state progression is enforced by the gateway. A device cannot transition WAITING_FOR_APPROVAL directly to RUNNING. The owner must approve it. Task version is a database concurrency token; stale updates return a version conflict. Terminal tasks cannot be restarted.

The Android owner UI has source for creating tasks, inspecting progress, approving waiting tasks and cancelling. The Windows UI has source for viewing tasks assigned to that device. Both clients passed CI at commit 9533812; these screens have not been exercised against a deployed gateway or on the actual Nokia G21/HP EliteBook.

**Important:** task synchronization is implemented; task execution is not. No durable project-building worker, autonomous code-writing loop, tool sandbox, browser executor or project artifact runner is connected.

## Signed device command wire format (validator only)

The gateway validator and Android/Windows client signing helpers share the sam-device-command-v1 canonical payload. The UTF-8 payload is the following nine fields joined by a single line-feed:

1. sam-device-command-v1
2. commandId in lowercase UUID D format
3. targetDeviceId in lowercase UUID D format
4. issuedAt as Unix milliseconds
5. expiresAt as Unix milliseconds
6. unpadded base64url nonce (16–64 random bytes)
7. exact action string
8. delaySeconds as invariant decimal or -
9. requiredApprovalId in lowercase UUID D format or -

Sign using ECDSA P-256 and SHA-256. Signature wire format is 64-byte IEEE P1363 r||s, unpadded base64url. Public key is DER SubjectPublicKeyInfo. Android converts its provider's DER signature to P1363; Windows CNG signs directly in P1363 format. A fixed canonicalization vector is verified across gateway, Android and Windows.

The validator recognizes status.read, power.shutdown and power.restart. Power requests require an exact server-side approval record and a delay between 30 and 300 seconds. The validator does not dispatch a command. There are no command submission/dispatch routes, no shutdown/restart countdown UI and no verified remote control yet.

## Planned capabilities not implemented

- Shared task execution worker with isolated project workspaces, bounded tool permissions, checkpoints, retries, build/test/security scans and downloadable project artifacts.
- Signed device command submission and execution adapters for shutdown/restart with owner approval, visible device countdown and local cancel.
- Consent-based remote screen viewing, visible session indicator, local stop, and separately approved remote input.
- Platform-authorized location reporting and native lost-device recovery integration.
- Tamper-evident audit events, versioned EF Core schema migrations, production monitoring, backup/recovery and full rate-limit review.

Remote screen capture must never be covert. Remote input must be a separate, explicit capability. Arbitrary shell execution, keylogging, credential extraction, silent installation and lock-screen bypass are excluded.

## Verification status

- Gateway .NET 10 CI run #21 passed 34/34 tests and the SQLite startup/liveness smoke test (commit 4c51fa7).
- Android debug APK CI run #140 passed and uploaded an artifact (commit 9533812).
- Windows x64 self-contained CI run #22 passed and uploaded an artifact (commit 9533812).
- Latest APK and Windows ZIP artifacts were downloaded and ZIP-integrity checked.
- The physical Nokia G21 and HP EliteBook have not yet completed an end-to-end pairing test. The gateway has not been deployed.

A milestone is considered complete only when its source, automated tests, builds and actual-device verification are all reported separately.