# SAM Platform: implementation plan

SAM is the planned evolution of this Android assistant into a permissioned personal agent. The Android app remains the user's control surface; long-running research and software-development work will ultimately run in a separately secured remote runtime.

## Product principles

- Work must be real and verifiable. A tool result, build log, test result, or explicit limitation backs every completion claim.
- The agent may plan and prepare changes independently, but sensitive actions require explicit owner approval.
- Treat web pages, repositories, messages, and downloaded files as untrusted input, never as instructions that override system policy.
- Keep provider credentials out of source control and APKs. A future remote runtime must use server-side secret storage and scoped credentials.
- Preserve an audit trail of task requests, plans, tool calls, checkpoints, approvals, changes, and verification.
- Keep a safe rollback path. Do not silently merge to main, deploy, purchase, send messages, or delete user data.

## Target architecture

1. **Android control client** — voice and text interface, task inbox, progress updates, permission-aware phone actions, and owner approval prompts.
2. **Agent gateway** — authenticated API for task submission, status, cancellation, and event delivery. It must not expose provider keys to the Android client.
3. **Durable orchestrator** — checkpointed state machine that can pause, resume, retry with limits, and recover after worker restarts.
4. **Model/provider adapter** — interchangeable model providers with capability checks, timeouts, rate-limit handling, and explicit cost/availability reporting.
5. **Tool registry** — narrowly scoped tools for research, files, messaging, browser workflows, and development. Each tool declares permissions and side effects.
6. **Isolated development worker** — disposable workspace, branch-only changes, restricted credentials/network, reproducible builds, tests, dependency/security checks, and artifact output.
7. **Research evaluator** — compares official documentation, source repositories, licenses, compatibility, security, maintenance activity, and measured performance before recommending an integration.
8. **Observability and recovery** — structured logs, task history, health checks, bounded retries, regression tests, and rollback records.

## Development lifecycle

For a software-change request, SAM should: clarify the desired outcome; inspect the current repository; create an isolated branch/workspace; produce a plan; request approval for high-impact changes; implement a small patch; run formatting, static analysis, tests and builds; inspect the actual outputs; report changed files and failures; and wait for approval before merging or deploying.

## Android constraints

The Android app can only perform actions exposed by Android APIs and permissions the owner grants. It cannot bypass the lock screen, silently install updates, grant itself permissions, or guarantee always-on microphone capture under all Android 12 background and battery conditions. Remote task execution can continue while the phone is offline only after a remote runtime has actually been deployed and connected.

## Current implementation status

- [x] Add a local persistent task ledger with task IDs, statuses, checkpoints, attempt counts, errors, and approval gates.
- [x] Run the Android APK workflow on `sam/**` development branches.
- [ ] Add automated tests for task-state transitions and persistence.
- [ ] Connect the ledger to an owner-visible task inbox in the Android app.
- [ ] Implement an authenticated remote agent gateway and durable worker.
- [ ] Add provider adapters and secure server-side secret management.
- [ ] Add isolated repository workspace, patch, build, test, and artifact workflow.
- [ ] Add research/evaluation tools and a transparent upgrade history.
- [ ] Test voice interruption and phone actions on the actual Nokia G21.
- [ ] Establish signed release builds, rollback, and owner-approved deployment.

A checked item means the code/configuration has been added; it does not imply real-device or production verification.
