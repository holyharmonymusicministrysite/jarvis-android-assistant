# SAM for Windows 11 — companion foundation

This is the first Windows desktop milestone. It creates a per-Windows-user ECDSA P-256 signing identity in the Windows Software Key Storage Provider. The private key is configured as non-exportable; the app displays only the public key.

## Current functionality

- Visible Windows desktop identity setup window.
- Explicit owner action to create a device signing identity and reopen it for the same Windows user.
- Signing/verification self-test during identity creation or reopen.
- SHA-256 public-key fingerprint and public-key copy.
- Typed device-command canonicalization matching the Android client and gateway.
- 24-byte cryptographically random nonce generation and SHA-256 ECDSA signing with the 64-byte IEEE P1363 wire format.
- Shared fixed-vector self-test and command-signature verification.
- HTTPS pairing controls: enter the gateway URL and one-time owner code; the companion submits its public key, signs the server challenge and checks Max's approval.

## Build verification

The Windows x64 self-contained publish workflow passed after the command-signing implementation and its missing-namespace compile fix. The successful run is GitHub Actions run #10 (commit 16620de04bc4ec174f304621483d0cc3314065a1). The uploaded artifact was downloaded and ZIP-tested; it contains SAM.Windows.exe and six runtime/support files.

The companion build is currently unsigned and has not been tested on the owner's HP EliteBook. The artifact is a portable build ZIP, not an MSI installer or a signed production release.

## Not implemented yet

- SAM owner sign-in (owner approval is performed from the Android client).
- Shared task history, signed status/command APIs or remote control. The pairing UI requires a live HTTPS gateway and has not yet been exercised on the HP laptop.
- Remote shutdown/restart.
- Screen viewing or remote input.
- Location reporting.

The local key is not registered with a server. The app must not be described as paired or remotely manageable until those features are implemented and tested. Removing the Windows key from the OS key store invalidates this local identity; future server-side revocation will be a separate operation.

Build with the .NET 10 SDK on Windows:

```powershell
dotnet publish .\SAM.Windows\SAM.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```
