# SAM Gateway — initial security foundation

This is a .NET 10 ASP.NET Core service scaffold. At this milestone it exposes only GET /health for process liveness. The response deliberately reports readiness: not-ready and gatewayReady: false.

There is a one-owner bootstrap and bearer-session API plus owner-approved device pairing, public-key proof and device revocation. Shared tasks, event-stream, remote-command, screen-view and location endpoints are still absent. Do not deploy this as a production gateway or describe the devices as paired.

## Signed command validation foundation

The security library defines a typed command envelope and deterministic signing payload. It currently recognizes only:

- status.read
- power.shutdown
- power.restart

Power commands require a server-side approval record bound to the exact approval ID, target device, command ID, action, delay and validity window. A client-provided boolean is not accepted as proof of approval. Power requests also enforce a 30–300 second delay.

The validator checks the expected target device, time window, nonce encoding, P-256 public key, P1363 ECDSA signature, authoritative approval-verifier result and replay-store result. It never dispatches a command.

SQLite-backed implementations now exist for both interfaces. Command IDs and nonces are protected by database-enforced unique indexes; approval lookups match the exact approval ID, target device, command ID, action, delay and validity timestamps, and reject expired or revoked rows. Storage errors fail closed. Tests use a temporary file-backed SQLite database and separate DbContext instances to verify persistence. SQLite is intended for a single gateway node on a persistent volume; switch to a shared production database before scaling to multiple gateway instances. Approval records can only be inserted by server-side code for now; no login or approval-management HTTP route exists.

## Local verification

With the .NET 10 SDK:

    dotnet build SAM.Gateway/SAM.Gateway.csproj --configuration Release
    dotnet run --project SAM.Gateway.Tests/SAM.Gateway.Tests.csproj --configuration Release

The service initializes its first SQLite schema at startup. Configure ConnectionStrings__SamGateway to point to a database file on a persistent mounted volume; the default sam-gateway.db path is relative to the process working directory. This first schema uses EnsureCreated; introduce versioned EF Core migrations before changing the schema in an existing deployment.

The GitHub Actions workflow builds the service, runs all 16 security/persistence checks, and starts the app to smoke-test the SQLite schema and /health response. Run #9 passed the build and 16 checks; the runtime smoke test was added immediately after and is being verified in the next run.

## Owner account bootstrap

There is no public registration route. The first owner can be initialized once through POST /api/v1/setup/owner only when the server has a SAM_OWNER_SETUP_SECRET configured with at least 32 UTF-8 bytes. Supply it in the X-SAM-Setup-Secret header. The setup endpoint is rate-limited and returns the initial bearer token once. It cannot create a second owner. Remove the setup secret from the hosting environment after the owner account is created.

Owner passwords are validated at 14–128 characters and stored as PBKDF2-SHA256 hashes with random salts and 600,000 iterations. Login uses generic unauthorized responses and is rate-limited per source IP. Sessions are random 256-bit opaque bearer tokens, expire after 12 hours, are revocable, and are stored only as SHA-256 hashes. Clients must store tokens using their platform secure storage and send them only over HTTPS.

Endpoints:
- POST /api/v1/setup/owner — one-time bootstrap; disabled unless the server setup secret exists.
- POST /api/v1/auth/login — rate-limited login.
- GET /api/v1/auth/me — bearer-token validation.
- POST /api/v1/auth/logout — revoke the current session.

The setup secret is not a substitute for HTTPS. Never place it in source control, an APK, a Windows client, URL query parameters, or logs. No public signup, password-reset, or email-verification route is provided.

## Owner-approved device pairing

1. An authenticated owner creates POST /api/v1/pairing/invites. The gateway returns a random eight-digit, ten-minute, one-use code.
2. The new device submits the code, a display name, platform and its P-256 SubjectPublicKeyInfo public key to POST /api/v1/pairing/claims/start. The gateway returns a claim ID, random challenge, one-time claim secret and base64 proof payload.
3. The device signs the returned proof payload with its non-exportable private key (ECDSA P-256, SHA-256, IEEE P1363 64-byte signature) and posts the base64url signature to POST /api/v1/pairing/claims/{claimId}/prove.
4. The owner reviews GET /api/v1/pairing/claims and explicitly approves or rejects the claim. Only a proof-verified, unexpired claim can be enrolled.
5. The device polls POST /api/v1/pairing/claims/{claimId}/status using its one-time claim secret. After owner approval, it exchanges the claim secret once at POST /api/v1/pairing/claims/{claimId}/session for a 12-hour device bearer session. The session token is stored only as a SHA-256 hash, is bound to the enrolled device and is invalidated immediately when the owner revokes that device. Devices can check GET /api/v1/devices/me, refresh last-seen time with POST /api/v1/devices/me/heartbeat, and revoke their own session with POST /api/v1/devices/me/logout. The owner can list devices and revoke one at any time.

Claim-start, proof and status routes are rate-limited. Pairing codes and claim secrets are never stored in plaintext. The claim ID alone is not enough to read status. Device public keys and fingerprints are persisted. The one-time code alone cannot enroll a device; proof of private-key possession and owner approval are both required.

The pairing API is implemented and has database-backed tests, but the Android and Windows user interfaces have not yet been connected to these endpoints. The gateway is not deployed and still reports not-ready.

## Before production use

Add secure owner account recovery and optional verified email controls; implement persistent device enrollment/revocation, owner-authenticated approval creation/revocation, tamper-evident audit records, schema migrations, request-size limits, rate limits on every sensitive route, TLS termination, secret management, backup/recovery, and monitoring. Add authenticated routes only after those controls exist. Do not add arbitrary shell execution.
