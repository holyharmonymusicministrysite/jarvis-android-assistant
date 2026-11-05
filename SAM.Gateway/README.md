# SAM Gateway — initial security foundation

This is a .NET 10 ASP.NET Core service scaffold. At this milestone it exposes only GET /health for process liveness. The response deliberately reports readiness: not-ready and gatewayReady: false.

There are no login, pairing, device enrollment, task synchronization, event-stream, remote-command, screen-view, or location endpoints yet. Do not deploy this as a production gateway or describe the devices as paired.

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

## Before production use

Implement and independently test owner authentication, secure session issuance, persistent device enrollment/revocation, owner-authenticated approval creation/revocation, tamper-evident audit records, schema migrations, schema validation, request-size limits, rate limiting, TLS termination, secret management, backup/recovery, and monitoring. Add authenticated routes only after those controls exist. Do not add arbitrary shell execution.
