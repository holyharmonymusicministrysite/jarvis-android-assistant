# SAM for Windows 11 — companion

**Builder: Max.** This is the Windows 11 companion for the SAM Android assistant. It is a visible WinForms desktop app; it is not a hidden service.

## Implemented

- Per-Windows-user ECDSA P-256 identity in the Windows Software Key Storage Provider; private key is non-exportable.
- Signing/verification self-test and SHA-256 public-key fingerprint.
- Shared canonical command payload and ECDSA P1363 signature format matching Android and the gateway.
- HTTPS pairing claimant: enter gateway URL and Max's one-use pairing code; submit the public key; sign the gateway proof challenge.
- Check pairing status and wait for Max's explicit approval.
- Exchange approved pairing claim for a 12-hour device session.
- Store the device bearer token encrypted with current-user Windows DPAPI at LocalAppData\SAM\device-session.bin.
- Check `/api/v1/devices/me`, send heartbeat/last-seen updates and revoke the local device session.
- View tasks assigned to this laptop from the shared SAM task inbox.

The task inbox only synchronizes assigned instructions and progress. It does not execute tasks or claim that a project has been built.

## Build verification

GitHub Actions run #22 passed the .NET 10 self-contained Windows x64 publish on commit `9533812420cf325d843b24f80cfeb070ccc772ec`. The artifact was downloaded and ZIP-integrity checked; it contains `SAM.Windows.exe` and `SAM.Windows.pdb`. The EXE is a Windows PE x64 executable.

The artifact is a portable ZIP, not an MSI installer. It is unsigned and has not been exercised on the owner's HP EliteBook. The pairing UI requires a deployed HTTPS gateway and owner-created code.

## Not yet implemented

- Project-building execution worker, isolated workspace, tool runner, build/test pipeline or packaged project artifacts.
- Signed remote shutdown/restart command dispatch and visible countdown/cancel UX.
- Remote screen viewing or remote input.
- Device location reporting or lost-device tracking.
- Production installer/signing/update and rollback system.

## Run locally

Extract the Windows ZIP and launch `SAM.Windows.exe` on Windows 11. Create the local identity first. To pair, the gateway must be deployed over HTTPS and Max must create a fresh eight-digit code in the Android owner controls. The code expires after ten minutes and remains subject to device proof and owner approval.

Never place owner passwords, bearer tokens, setup secrets or private key material in source control. The companion stores its device private key in Windows CNG and its session token using DPAPI.