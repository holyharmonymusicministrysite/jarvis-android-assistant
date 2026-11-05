# SAM Cross-Device Gateway Contract (v1 draft)

This document is the implementation contract for the Android and Windows 11 clients. It is deliberately provider-neutral. It does not imply that a gateway is deployed.

## System boundary

- Android phone and Windows 11 laptop are separately enrolled devices under one SAM owner account.
- A remote gateway is the authority for account identity, device enrollment, task state, event delivery and audit records.
- Model/provider credentials stay on the server. Clients receive short-lived session credentials only.
- Client-to-gateway traffic uses HTTPS. Remote desktop media/control uses a separately authenticated encrypted session.
- The gateway must reject unknown devices, expired sessions, duplicate command IDs and commands outside a device's granted capabilities.

## Enrollment and pairing

1. Owner signs in to the existing SAM account using a strong authentication method.
2. An already trusted device starts a pairing ceremony and displays a short-lived, single-use pairing code/QR.
3. The new device scans/enters the code and proves possession of a locally generated key pair.
4. Owner confirms the new device name and requested capabilities on the trusted device.
5. Gateway binds the new public key to the owner and returns a revocable device record. Pairing codes expire quickly and are rate-limited.
6. Each device can be revoked independently. Recovery requires owner account recovery, not a secret embedded in the app.

The private key is generated and stored using the platform keystore (Android Keystore / Windows CNG or DPAPI-protected key material). Private keys are never uploaded.

## API surface

All routes are versioned under `/v1`. JSON timestamps are UTC RFC 3339. IDs are opaque UUIDs.

| Method | Route | Purpose |
|---|---|---|
| POST | `/auth/session` | Exchange owner authentication for a short-lived session |
| POST | `/devices/pairing-sessions` | Start an expiring pairing ceremony from a trusted device |
| POST | `/devices/enroll` | Complete new-device proof-of-possession and owner approval |
| GET | `/devices` | List enrolled devices and last-seen state |
| DELETE | `/devices/{deviceId}` | Revoke a device; requires fresh owner authentication |
| POST | `/tasks` | Submit a task from either client |
| GET | `/tasks?cursor=` | Read shared task history |
| GET | `/tasks/{taskId}` | Read task state and checkpoint |
| POST | `/tasks/{taskId}/approval` | Approve a named, scoped action |
| POST | `/tasks/{taskId}/cancel` | Request cancellation |
| GET | `/events` | Authenticated event stream for task/device updates |
| POST | `/devices/{deviceId}/commands` | Submit an allow-listed command to a paired online device |
| POST | `/sessions/remote-view` | Request a consent-based remote viewing session |

No route may accept arbitrary shell text as a privileged command. Commands use a typed enum and validated parameters.

## Core message shapes

### Device record

```json
{
  "id": "uuid",
  "displayName": "Windows laptop",
  "platform": "windows",
  "publicKey": "base64url-encoded-public-key",
  "capabilities": ["status.read", "power.shutdown"],
  "createdAt": "2026-11-05T00:00:00Z",
  "lastSeenAt": null,
  "revokedAt": null
}
```

### Task submission

```json
{
  "clientRequestId": "uuid",
  "instruction": "Prepare a project blueprint for my idea",
  "sourceDeviceId": "uuid",
  "targetDeviceId": null,
  "approvalPolicy": "confirm-sensitive-actions"
}
```

### Device command envelope

```json
{
  "commandId": "uuid",
  "targetDeviceId": "uuid",
  "issuedAt": "2026-11-05T00:00:00Z",
  "expiresAt": "2026-11-05T00:00:30Z",
  "nonce": "base64url-random-value",
  "action": "power.shutdown",
  "parameters": { "delaySeconds": 60 },
  "requiredApprovalId": "uuid",
  "signature": "base64url-signature"
}
```

The signature covers a canonical encoding of every field except `signature`. Devices reject expired envelopes, reused nonces/command IDs, invalid signatures, wrong targets, missing approval, and unsupported actions. The server also records issuer, target, action, approval, result and timestamps.

### Signature wire format

The first validator implementation uses this exact cross-platform format:

- Canonical bytes are UTF-8 fields joined by a single line-feed, in this order: sam-device-command-v1, commandId in lowercase UUID D format, targetDeviceId in lowercase UUID D format, issuedAt as Unix milliseconds, expiresAt as Unix milliseconds, the unpadded base64url nonce, the exact action string, delaySeconds as invariant decimal or -, and requiredApprovalId in lowercase UUID D format or -.
- nonce is 16–64 cryptographically random bytes encoded as unpadded base64url.
- Sign with ECDSA P-256 and SHA-256. The signature is the 64-byte IEEE P1363 fixed-field form (r followed by s), encoded as unpadded base64url. Platform APIs returning ASN.1 DER must convert to this wire form before transmission.
- The public key is SubjectPublicKeyInfo DER, base64 encoded for the current Windows pairing display.
- For power actions, the server-side approval record must match the exact approval ID, target device, command ID, action, delay and validity window. A client-supplied boolean is not approval evidence.

The validator and canonicalizer are implemented in SAM.Gateway/Security. This protocol code is not yet called by either device client; no command endpoint or device action is enabled.


## Initial command capability set

- `status.read`: report online state, OS version, battery/power state when available.
- `power.shutdown` and `power.restart`: require a fresh owner approval and a visible local countdown/cancel opportunity.
- `screen.view.request`: prompts the local user and starts capture only after operating-system consent; a visible indicator and local stop control remain present.
- `input.control.request`: separately approved, time-limited remote input session; never enabled by default.
- `location.report`: report a platform-authorized location fix with timestamp and accuracy, or explicitly report unavailable.

Arbitrary command execution, hidden screen capture, keylogging, silent installation, lock-screen bypass, credential extraction and remote wipe are excluded from v1.

## Remote screen session

A remote-view request creates a pending invitation, not an automatic stream. The target device must show who is requesting access and allow the local user to accept or reject. Sessions are encrypted, time-limited, auditable and revocable locally. Remote input is a distinct capability requiring a second approval. No recording is enabled by default.

## Task lifecycle

Tasks use the shared statuses already introduced in `SamTaskLedger`: QUEUED, PLANNING, RUNNING, WAITING_FOR_APPROVAL, VERIFYING, COMPLETED, FAILED and CANCELLED. The server becomes the source of truth once a task is accepted remotely. Clients cache a read-only projection and reconcile by task ID/version after reconnecting. Updates carry monotonically increasing versions to prevent stale state overwrites.

## Reliability and abuse controls

- Idempotency key on task submission and command submission.
- Per-account/device rate limits and short session lifetimes.
- Server-side authorization on every request; never trust a client-supplied owner/device ID alone.
- Strict request-size limits, schema validation, TLS, secure headers and audit retention limits.
- No secrets in logs; redact prompts and sensitive outputs where possible.
- Worker queues use bounded retries, dead-letter handling, leases and durable checkpoints.
- Every external tool has a declared capability, input schema, timeout and side-effect classification.
- High-impact actions require fresh owner approval bound to the exact action, target, parameters and expiry.

## Windows 11 companion responsibilities

The Windows companion is an explicitly installed, signed desktop application with a visible tray/status surface. It must:
- enroll using the pairing ceremony above;
- store its private key using Windows platform cryptography;
- show connection, active task and remote-session state;
- expose only allow-listed commands;
- require local confirmation for remote viewing/input and offer an immediate stop/revoke control;
- use Windows APIs for power actions and report their actual result;
- never run as a hidden agent or attempt to defeat Windows security controls.

The first Windows milestone is **pairing + online status only**. Power actions follow after signed command verification and tests. Screen sharing follows only after consent UX and session security are independently reviewed.

## Delivery gates

1. Contract/schema tests and threat-model review.
2. Gateway authentication, pairing and revocation tests.
3. Android pairing UI and device list.
4. Windows 11 companion pairing and status.
5. Signed allow-listed commands with approval enforcement.
6. Consent-based screen view, then separately approved remote input.
7. End-to-end tests across the actual Nokia G21 and HP Windows 11 laptop.

A milestone is not complete until automated tests pass and both actual devices are exercised. This draft is not a deployed service or a working remote-control feature.
