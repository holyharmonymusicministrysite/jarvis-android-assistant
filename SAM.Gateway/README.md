# SAM Gateway — .NET 10 LTS

SAM Gateway is the private account, device-trust and shared-task synchronization service for the SAM Android and Windows clients. It uses SQLite for its initial single-node persistent-store implementation.

<badge>Note</badge> The service deliberately reports readiness as not-ready. It is source-complete for owner authentication, pairing, device sessions and task synchronization, but it is not deployed and does not execute tasks or remote device commands.

## Storage and deployment boundary

- Default database: sam-gateway.db in the process working directory.
- Set ConnectionStrings__SamGateway to a database file on a persistent mounted volume.
- Current SQLite arrangement is for one gateway instance. Use a shared production database before multi-node deployment.
- The initial schema is created with EF Core EnsureCreated. Add and verify versioned migrations before evolving any existing deployment.
- Never deploy on plain HTTP. Owner passwords, setup secrets and bearer tokens must only travel over HTTPS.

## Owner authentication

The gateway has no public sign-up route. The first owner is created once through POST /api/v1/setup/owner. This route is disabled unless the server environment contains SAM_OWNER_SETUP_SECRET with at least 32 UTF-8 bytes; the value is supplied in X-SAM-Setup-Secret. Remove the setup secret after the first owner has been created.

Passwords are validated to 14–128 characters and stored as PBKDF2-SHA256 hashes with random salts and 600,000 iterations. Owner login is rate-limited. Bearer sessions are random 256-bit opaque tokens, expire after 12 hours, are revocable, and are stored only as SHA-256 hashes.

- POST /api/v1/setup/owner — one-time bootstrap; requires server-only setup secret.
- POST /api/v1/auth/login — rate-limited owner login.
- GET /api/v1/auth/me — validate owner bearer session.
- POST /api/v1/auth/logout — revoke current owner session.

## Owner-approved device pairing

1. An authenticated owner creates POST /api/v1/pairing/invites. The server returns an eight-digit, single-use code valid for ten minutes.
2. The new device submits the code, a display name, platform and its P-256 SubjectPublicKeyInfo public key to POST /api/v1/pairing/claims/start.
3. The server returns a claim ID, proof payload and one-time claim secret. The device signs the proof payload with its locally held ECDSA P-256 private key (SHA-256, IEEE P1363 64-byte signature) and posts it to POST /api/v1/pairing/claims/{claimId}/prove.
4. The owner reviews GET /api/v1/pairing/claims and explicitly approves or rejects the proof-verified request. The pairing code alone cannot enroll a device.
5. The device exchanges its claim secret once at POST /api/v1/pairing/claims/{claimId}/session for a 12-hour device bearer session.

Claim-start, proof, status and session-exchange endpoints are rate-limited. Pairing codes and claim secrets are stored as hashes, not plaintext. The claim status endpoint requires both claim ID and the one-time claim secret. Public keys and SHA-256 fingerprints are persisted.

Owner device endpoints:
- GET /api/v1/devices — list enrolled devices.
- POST /api/v1/devices/{deviceId}/revoke — revoke one device.

Device session endpoints:
- GET /api/v1/devices/me — resolve the current trusted device.
- POST /api/v1/devices/me/heartbeat — update last-seen time.
- POST /api/v1/devices/me/logout — revoke the current device session.

Device-session validation checks both session expiry and the live device-revocation state. Revoking a device invalidates its existing sessions.

## Shared task inbox — synchronization, not execution

- POST /api/v1/tasks (owner bearer) — create a task assigned to one active enrolled device.
- GET /api/v1/tasks (owner bearer) — list account tasks.
- GET /api/v1/tasks/{taskId}/events (owner bearer) — inspect versioned event history.
- GET /api/v1/tasks/inbox (device bearer) — list active tasks assigned to the current device only.
- POST /api/v1/tasks/{taskId}/progress (device bearer) — update status and append a versioned event.
- POST /api/v1/tasks/{taskId}/approve-run (owner bearer) — move an owner-approved waiting task back to RUNNING.
- POST /api/v1/tasks/{taskId}/cancel (owner bearer) — cancel a non-terminal task.

Task transitions are validated. A device cannot move WAITING_FOR_APPROVAL to RUNNING without Max's owner approval. Every progress mutation uses the expected task version, enforced by a database concurrency token; stale concurrent updates receive a conflict. Terminal tasks cannot be restarted. The inbox stores instructions and progress only; there is no execution worker, code agent, remote shell or project-build worker connected.

## Company workspace API (verified in GitHub Actions run #34: 39/39 checks)\n\nAll routes require an owner bearer session and use the authenticated owner ID for data isolation.\n\n- GET /api/v1/company/dashboard — CRM, project pipeline and issued-quote metrics.\n- POST/GET /api/v1/company/customers — customer CRM.\n- POST /api/v1/company/projects — create a project linked to an owned customer.\n- POST /api/v1/company/projects/{id}/status — enforce guarded pipeline transitions.\n- POST/GET /api/v1/company/quotes — create/list KES quotations with server-calculated totals.\n- POST /api/v1/company/quotes/{id}/issue — issue an unexpired draft quote.\n\nThe gateway build and 39/39 security/business checks passed, including owner isolation, project transition and quote total tests. This foundation does not send quotes, issue tax invoices, collect payments, reconcile M-Pesa, run payroll or track inventory. Those require their own tested workflows.\n\n## Signed command validator

The security library validates the typed command envelope for status.read, power.shutdown and power.restart. It verifies target, expiry, nonce, P-256 public key, P1363 signature, authoritative approval record and replay protection. High-impact power requests require an exact server-side approval record and a 30–300 second delay.

SQLite-backed replay and approval-verifier implementations use database constraints and exact-field matching. The validator does not dispatch commands. There is no remote-power route or command worker yet.

## Verification

GitHub Actions gateway run #21 on commit 4c51fa7 completed successfully: .NET 10 build, 34/34 authentication/pairing/device-session/task/security/persistence tests, and the runtime smoke test all passed. The smoke test starts the service, initializes SQLite and verifies GET /health returns readiness=not-ready.

## Required before production readiness

- Verify owner setup, Android and Windows pairing, session persistence and revocation on the actual Nokia G21 and HP EliteBook.
- Configure a production HTTPS endpoint and persistent database volume; add backups and monitoring.
- Add secure owner recovery and verified contact mechanisms.
- Add owner-authenticated approval records for high-impact device actions and tamper-evident audit history.
- Add versioned EF Core migrations, request-size controls and rate limits on every sensitive route.
- Add a durable, isolated project execution worker with bounded tools, checkpointing and build/test verification before claiming task execution.
- Implement signed device command transport and local visible countdown/cancel UX before enabling shutdown/restart.
- Complete independent security review and end-to-end tests.

Do not add arbitrary shell execution, hidden screen capture, keylogging, lock-screen bypass, silent installation or credential extraction.