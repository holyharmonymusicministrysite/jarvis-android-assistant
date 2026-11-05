# SAM Gateway — initial security foundation

This is a .NET 8 ASP.NET Core service scaffold. At this milestone it exposes only GET /health for process liveness. The response deliberately reports readiness: not-ready and gatewayReady: false.

There are no login, pairing, device enrollment, task synchronization, event-stream, remote-command, screen-view, or location endpoints yet. Do not deploy this as a production gateway or describe the devices as paired.

## Signed command validation foundation

The security library defines a typed command envelope and deterministic signing payload. It currently recognizes only:

- status.read
- power.shutdown
- power.restart

Power commands require a referenced owner approval and a 30–300 second delay. The validator checks the expected target device, time window, nonce encoding, P-256 public key, P1363 ECDSA signature, owner-approval confirmation and replay-store result. It never dispatches a command.

ICommandReplayStore is intentionally an interface. A production implementation must provide atomic, durable uniqueness across gateway instances; the test-only in-memory replay store is not suitable for deployment. If persistent replay storage is unavailable, validation must fail closed.

## Local verification

With the .NET 8 SDK:

    dotnet build SAM.Gateway/SAM.Gateway.csproj --configuration Release
    dotnet run --project SAM.Gateway.Tests/SAM.Gateway.Tests.csproj --configuration Release

The GitHub Actions workflow runs the same build and security checks on changes to this gateway foundation.

## Before production use

Implement and independently test owner authentication, secure session issuance, persistent device enrollment/revocation, durable replay storage, audit records, schema validation, request-size limits, rate limiting, TLS termination, secret management, backup/recovery, and monitoring. Add authenticated routes only after those controls exist. Do not add arbitrary shell execution.
