# SAM Platform: implementation plan

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

## Current implementation status

- [x] Add a local persistent task ledger with task IDs, statuses, checkpoints, attempt counts, errors, and approval gates.
- [x] Run the Android APK workflow on `sam/**` development branches.
- [ ] Add automated tests for task-state transitions and persistence.
- [ ] Connect the ledger to an owner-visible task inbox in the Android app.
- [x] Define the first cross-device gateway, pairing, device identity, task, command and remote-view API contract in `SAM_CROSS_DEVICE_API.md` (draft; not deployed or implemented).
- [ ] Define SAM identity and shared account across Android and Windows clients.
- [x] Scaffold the Windows 11 desktop companion with a local non-exportable ECDSA device identity and a Windows x64 CI build workflow (build result pending).
- [ ] Connect the Windows companion to the authenticated gateway and complete owner-approved device pairing; implement signed, revocable device commands.
- [ ] Add consent-based remote screen viewing/control and visible session controls.
- [ ] Integrate platform-native lost-device recovery and last-known-location reporting.
- [ ] Document and guide Windows Secure Boot, TPM, BitLocker, firmware and recovery-key hardening.
- [ ] Add project brief, architecture/diagram, mockup, approval and execution workflow.
- [x] Add the .NET 8 SAM gateway process scaffold with a liveness-only health route; readiness deliberately remains not-ready.
- [x] Add typed signed-command validation for status and approval-gated power requests, including target, time-window, P-256 signature and server-side approval-verifier checks.
- [x] Add the replay-store contract and an executable 11-case security test harness; CI is configured, with its first run pending.
- [ ] Implement durable database-backed replay and approval stores with audit records.
- [ ] Implement owner authentication, secure session issuance, device enrollment and revocation.
- [ ] Add authenticated shared task APIs, event delivery and durable orchestration.
- [ ] Add provider adapters and secure server-side secret management.
- [ ] Add isolated repository workspace, patch, build, test, and artifact workflow.
- [ ] Add research/evaluation tools and a transparent upgrade history.
- [ ] Test voice interruption and phone actions on the actual Nokia G21.
- [ ] Establish signed release builds, rollback, and owner-approved deployment.

A checked item means the code/configuration has been added; it does not imply real-device or production verification.
