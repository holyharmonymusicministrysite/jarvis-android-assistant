# SAM Platform: implementation plan

**Human builder: Max.** Android and Windows assistant identity prompts and visible client surfaces must consistently identify Max as SAM's builder. AI model providers are dependencies, not the builder.

SAM is the single personal agent identity across the owner's Android phone and Windows laptop. The Android and Windows clients are companion endpoints connected to one authenticated agent account, shared task history, and a secured remote runtime. The product name presented to the owner is SAM; JARVIS is the legacy repository/application identity during the transition.

## Product principles

- Work must be real and verifiable. A tool result, build log, test result, or explicit limitation backs every completion claim.
- The agent may plan and prepare changes independently, but sensitive actions require explicit owner approval.
- Treat web pages, repositories, messages, and downloaded files as untrusted input, never as instructions that override system policy.
- Keep provider credentials out of source control and APKs. A future remote runtime must use server-side secret storage and scoped credentials.
- Preserve an audit trail of task requests, plans, tool calls, checkpoints, approvals, changes, and verification.
- Keep a safe rollback path. Do not silently merge to main, deploy, purchase, send messages, or delete user data.

## Target architecture

1. **Android control client** — voice and text interface, task inbox, progress updates, permission-aware phone actions, lost-device reporting, and owner approval prompts.
2. **Windows companion** — paired Windows service/desktop app with an owner-visible status tray, secure remote commands, project workspace, screen sharing, remote input, software maintenance, and recovery controls. Screen viewing/control must be visible and explicitly enabled; sensitive file access and destructive commands require approval.
3. **Device identity and trust** — cryptographic pairing for each phone/laptop, revocable device credentials, signed commands, replay protection, session expiry, audit logs, and recovery codes. A stolen device must not be able to enroll itself as the owner.
4. **Agent gateway** — authenticated API for task submission, status, cancellation, and event delivery. It must not expose provider keys to the Android client.
5. **Durable orchestrator** — checkpointed state machine that can pause, resume, retry with limits, and recover after worker restarts.
6. **Model/provider adapter** — interchangeable model providers with capability checks, timeouts, rate-limit handling, and explicit cost/availability reporting.
7. **Tool registry** — narrowly scoped tools for research, files, messaging, browser workflows, and development. Each tool declares permissions and side effects.
8. **Isolated development worker** — disposable workspace, branch-only changes, restricted credentials/network, reproducible builds, tests, dependency/security checks, and artifact output.
9. **Research evaluator** — compares official documentation, source repositories, licenses, compatibility, security, maintenance activity, and measured performance before recommending an integration.
10. **Observability and recovery** — structured logs, task history, health checks, bounded retries, regression tests, and rollback records.

## Development lifecycle

For a software-change request, SAM should: clarify the desired outcome; inspect the current repository; create an isolated branch/workspace; produce a plan; request approval for high-impact changes; implement a small patch; run formatting, static analysis, tests and builds; inspect the actual outputs; report changed files and failures; and wait for approval before merging or deploying.

## Cross-device operation and security

- Phone and laptop clients must pair through an owner-authenticated process. Each device receives its own revocable identity; commands are authenticated, signed or session-bound, time-limited, and recorded.
- The owner can send a task from either device to the same agent account. The remote orchestrator owns task state, so a job can continue when a client disconnects; reconnecting clients receive the latest checkpoint and result.
- Remote laptop viewing/control uses an encrypted connection, explicit pairing, visible session status, and a local stop/revoke control. Remote screen capture is not covert surveillance.
- Remote commands are allow-listed. Routine reversible actions can be pre-authorized; shutdown, restart, software installation, updates, remote input, data export, account changes, wiping, and other high-impact operations require the configured confirmation policy. A locked or unpaired device must reject commands.
- For a lost phone or laptop, SAM can show the last known location only if the device has a supported location service, permission, connectivity, and a reporting path. Location can be stale or unavailable when powered off, offline, or reset. SAM should link to platform-native recovery tools where they provide stronger support.
- Windows protection should use Secure Boot, TPM-backed BitLocker full-disk encryption, strong sign-in, firmware/UEFI protections, recovery-key custody, and restricted external boot where supported. These controls can make data unreadable and make unauthorized boot/firmware changes harder; they cannot guarantee that a thief cannot physically damage, replace, or reflash hardware.
- Remote shutdown cannot stop someone with physical possession from cutting power or removing storage. If a device is powered off or wiped before it checks in, remote tracking and commands may stop. SAM must never claim otherwise.
- The Windows client must not silently disable security tools, hide persistence, bypass consent, or install privileged drivers. Updates must be signed, verified, staged, and recoverable.

## Project creation and visual collaboration

SAM should support a project lifecycle: capture the idea; ask targeted questions; produce a written brief, requirements, risks, milestones, architecture diagrams and visual mockups; request owner approval of the blueprint; execute in an isolated workspace; run tests and security checks; package source and deliverables; demonstrate the result; and provide rollback/recovery instructions. It should show live progress and previews, but clearly distinguish a mockup from a running product and a successful build from a deployed service.

## Android constraints

The Android app can only perform actions exposed by Android APIs and permissions the owner grants. It cannot bypass the lock screen, silently install updates, grant itself permissions, or guarantee always-on microphone capture under all Android 12 background and battery conditions. Remote task execution can continue while the phone is offline only after a remote runtime has actually been deployed and connected.

## Company operations roadmap\n\n- [x] Implement owner-scoped customer CRM, guarded project pipeline, KES quotation drafts/issue workflow and dashboard API (39/39 gateway checks passed).\n- [ ] Add customer-facing portal and explicit quote acceptance/rejection with immutable quote snapshots.\n- [ ] Add invoice issuance, auditable payment reconciliation and management accounts; never initiate transfers automatically.\n- [ ] Add staff roles, assignments, approvals, timesheets and workload reporting.\n- [ ] Add catalogue, inventory and supplier/procurement workflows.\n- [ ] Add verified email/SMS notifications and M-Pesa checkout/webhook reconciliation only after credentials and end-to-end tests.\n\n- [x] Connect Android conversational tools for company dashboard, customer lookup/add, project creation and draft quotation preparation (Android CI verification pending).\n\n## Current implementation status

- [x] Add a local persistent task ledger with task IDs, statuses, checkpoints, attempt counts, errors, and approval gates.
- [x] Run the Android APK workflow on `sam/**` development branches.
- [x] Add database-backed tests for shared task assignment, target-device isolation, version conflicts, owner approval gates, cancellation and event history; local Android ledger unit tests remain pending.
- [x] Add Android owner UI for shared task creation, task history/events, approval and cancellation; Windows has a device-assigned task inbox. A durable task execution worker remains pending.
- [x] Add an Android Keystore ECDSA P-256 identity, public fingerprint and a visible local identity screen; APK build artifact produced (real-device test pending).
- [x] Set SAM's canonical human-builder identity to Max in Android voice/text system context, Android app branding and Windows companion UI; retain Hey JARVIS as a compatibility wake phrase alongside Hey SAM.
- [x] Mirror the canonical typed-command payload and P1363 signature wire format across Android, Windows and the gateway; add a shared known-vector check.
- [x] Define and implement the first cross-device owner-auth, pairing, device-session and task API; maintain the command/remote-view contract as a future capability in `SAM_CROSS_DEVICE_API.md`.
- [x] Establish SAM identity with Max as the human builder and a single-owner gateway account shared by both client designs.
- [x] Scaffold the Windows 11 desktop companion with a local non-exportable ECDSA device identity and matching P1363 command signing; self-contained Windows x64 CI publish passed on commit 16620de (actual HP device test pending).
- [x] Add Windows HTTPS pairing claimant, proof signing, approval polling, DPAPI device-session storage and device task inbox source.
- [ ] Verify owner-approved pairing on the actual HP EliteBook and Nokia G21; implement signed device command dispatch separately.
- [ ] Add consent-based remote screen viewing/control and visible session controls.
- [ ] Integrate platform-native lost-device recovery and last-known-location reporting.
- [ ] Document and guide Windows Secure Boot, TPM, BitLocker, firmware and recovery-key hardening.
- [ ] Add project brief, architecture/diagram, mockup, approval and execution workflow.
- [x] Move the gateway and Windows companion to .NET 10 LTS before .NET 8 support ends on November 10, 2026.
- [x] Add the .NET 10 SAM gateway process scaffold with a liveness-only health route and startup SQLite schema initialization; readiness deliberately remains not-ready.
- [x] Add typed signed-command validation for status and approval-gated power requests, including target, time-window, P-256 signature and server-side approval-verifier checks.
- [x] Add the replay-store and server-side approval-verifier contracts plus a 16-case security test harness; GitHub Actions run #9 built the gateway and all 16 checks passed.
- [x] Add SQLite-backed replay and approval-verifier stores with database-enforced uniqueness and expiry/revocation checks; file-backed persistence tests pass. Keep SQLite single-node on persistent storage; use a shared DB before multi-node scale.
- [x] Add gateway runtime smoke test to confirm SQLite schema initialization and a deliberately not-ready health response.
- [ ] Implement owner-authenticated approval creation/revocation and tamper-evident audit records.
- [x] Add one-owner bootstrap, PBKDF2-SHA256 password storage, rate-limited login, opaque 12-hour bearer sessions, validation and logout; 4 new database-backed tests cover single-owner setup, hashed session storage, credential rejection and revocation.
- [ ] Add secure owner recovery/verified contact.
- [x] Add one-use eight-digit pairing invitations, P-256 proof-of-possession claims, explicit owner approval, claim status secrets, device listing and revocation; backend tests cover the pairing sequence.
- [x] Add one-time claim-secret exchange for 12-hour opaque device sessions, session validation, heartbeat/last-seen updates, device-session logout and immediate invalidation when the owner revokes a device; add 5 database-backed tests.
- [x] Connect both clients in source to exchange approved claims for 12-hour device sessions; Android uses Keystore AES-GCM and Windows uses current-user DPAPI.
- [x] Verify latest Android debug APK and Windows x64 companion CI builds (run #140 and #22, commit `9533812`).
- [ ] Deploy the gateway on HTTPS and exercise the full Nokia G21 ↔ HP laptop pairing/session/heartbeat sequence.
- [x] Add owner-created, device-targeted shared tasks, device inbox polling, versioned progress events, task owner approval/cancellation and task history; add 5 database-backed tests.
- [x] Add Android owner UI for first-phone pairing, Windows pairing-code creation, device request approval/rejection, device revocation, task creation, task history/events, approval of WAITING_FOR_APPROVAL and cancellation.
- [x] Add Windows pairing claimant, device-session status/logout and assigned-task inbox view with explicit notice that synchronization does not execute tasks.
- [ ] Add a durable project-building execution worker with isolated tools, checkpointing, builds/tests and explicit owner approval gates. The shared task APIs currently store/synchronize work only.
- [ ] Add provider adapters and secure server-side secret management.
- [ ] Add isolated repository workspace, patch, build, test, and artifact workflow.
- [ ] Add research/evaluation tools and a transparent upgrade history.
- [ ] Test voice interruption and phone actions on the actual Nokia G21.
- [ ] Establish signed release builds, rollback, and owner-approved deployment. Current APK/Windows artifacts are debug/unsigned test builds.

A checked item means the code/configuration has been added; it does not imply real-device or production verification.
