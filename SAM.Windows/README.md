# SAM for Windows 11 — companion foundation

This is the first Windows desktop milestone. It creates a per-Windows-user ECDSA P-256 signing identity in the Windows Software Key Storage Provider. The private key is configured as non-exportable; the app displays only the public key.

## Current functionality

- Windows desktop window with visible identity status.
- Explicit owner action to create the device signing identity.
- Reopens the existing identity for the same Windows user.
- Exports/copies only the public key.

## Not implemented yet

- SAM account sign-in or device pairing.
- Gateway/network connection or shared task history.
- Remote shutdown/restart.
- Screen viewing or remote input.
- Location reporting.

The app must not be described as paired or remotely manageable until those features are implemented and tested. The local key is not yet registered with a server. Removing the Windows key from the OS key store would invalidate this local identity; future server-side revocation will be a separate operation.

Build with the .NET 8 SDK on Windows:

```powershell
dotnet publish .\SAM.Windows\SAM.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```
