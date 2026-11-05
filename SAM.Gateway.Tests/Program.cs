using System.Security.Cryptography;
using Microsoft.EntityFrameworkCore;
using SAM.Gateway.Persistence;
using SAM.Gateway.Security;

internal static class Program
{
    private static async Task<int> Main()
    {
        var tests = new (string Name, Func<Task> Run)[]
        {
            ("accepts a valid signed status command", AcceptsValidStatus),
            ("isolates company CRM records by owner", CompanyCrmIsOwnerScoped),
            ("enforces the company project pipeline", CompanyProjectPipelineIsEnforced),
            ("calculates KES quotations on the server", CompanyQuoteTotalIsServerCalculated),
            ("builds an owner-scoped company dashboard", CompanyDashboardIsOwnerScoped),
            ("creates only one owner account", OwnerBootstrapIsOneTime),
            ("stores a password hash and issues an expiring session", OwnerLoginIssuesHashedSession),
            ("rejects wrong passwords and unknown emails", OwnerLoginRejectsInvalidCredentials),
            ("revokes an owner session", OwnerSessionCanBeRevoked),
            ("issues device sessions only after owner approval", DeviceSessionRequiresOwnerApproval),
            ("does not exchange an approved but expired pairing claim", ExpiredPairingClaimCannotIssueSession),
            ("issues one-time hashed device sessions and validates them", DeviceSessionIsOneTimeAndHashed),
            ("device heartbeat updates last-seen status", DeviceSessionHeartbeatUpdatesLastSeen),
            ("revoking a device invalidates its existing sessions", RevokedDeviceInvalidatesSession),
            ("device sessions expire after twelve hours", DeviceSessionExpires),
            ("owner can assign a task to an active paired device", SharedTaskOwnerAssignment),
            ("device inbox only returns tasks assigned to that device", SharedTaskDeviceIsolation),
            ("task progress is versioned and state transitions are enforced", SharedTaskProgressVersioning),
            ("waiting tasks require owner approval before running", SharedTaskApprovalGate),
            ("owner can cancel a task and inspect its event history", SharedTaskCancellationAndHistory),
            ("creates a one-use device pairing invite", PairingCreatesOneUseInvite),
            ("requires a valid P-256 proof before owner approval", PairingRequiresPrivateKeyProof),
            ("enrolls only after owner approval and supports revocation", PairingRequiresApprovalAndRevocation),
            ("protects claim status with a separate claim secret", PairingClaimStatusRequiresSecret),
            ("matches the cross-platform canonical payload vector", MatchesWireVector),
            ("persists replay rejection across fresh database contexts", PersistentReplayRejectsReuse),
            ("rejects persistent nonce reuse for a different command", PersistentNonceRejectsReuse),
            ("checks every persisted approval field", PersistentApprovalMatchesExactCommand),
            ("rejects revoked and expired persisted approvals", PersistentApprovalRejectsRevokedOrExpired),
            ("rejects a modified signed payload", RejectsTampering),
            ("rejects a command for another device", RejectsWrongTarget),
            ("rejects expired commands", RejectsExpiredCommand),
            ("rejects a replayed command", RejectsReplay),
            ("requires an approval reference for power actions", RequiresApprovalReference),
            ("requires a matching server-side approval", RequiresApprovalConfirmation),
            ("does not reuse approval for a different command", DoesNotReuseApproval),
            ("accepts an approved, bounded shutdown request", AcceptsApprovedShutdown),
            ("rejects unknown actions", RejectsUnknownAction),
            ("rejects unsafe power delay parameters", RejectsUnsafeDelay)
        };

        var failures = 0;
        foreach (var test in tests)
        {
            try
            {
                await test.Run();
                Console.WriteLine("PASS " + test.Name);
            }
            catch (Exception ex)
            {
                failures++;
                Console.Error.WriteLine("FAIL " + test.Name + ": " + ex.Message);
            }
        }

        Console.WriteLine($"{tests.Length - failures}/{tests.Length} gateway security checks passed.");
        return failures == 0 ? 0 : 1;
    }

    private static async Task OwnerBootstrapIsOneTime()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var auth = new OwnerAuthenticationService(f.CreateFactory(), new FixedTimeProvider(DateTimeOffset.UtcNow));
        if (!await auth.SetupOwnerAsync("max@example.com", "Max", "A-strong-owner-password-2026!"))
            throw new InvalidOperationException("First owner setup should succeed.");
        if (await auth.SetupOwnerAsync("other@example.com", "Other", "Another-strong-password-2026!"))
            throw new InvalidOperationException("A second owner account was created.");
        await using var db = await f.CreateFactory().CreateDbContextAsync();
        if (await db.OwnerAccounts.CountAsync() != 1)
            throw new InvalidOperationException("The gateway must contain exactly one owner account.");
    }

    private static async Task OwnerLoginIssuesHashedSession()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var auth = new OwnerAuthenticationService(f.CreateFactory(), new FixedTimeProvider(now));
        const string password = "A-strong-owner-password-2026!";
        if (!await auth.SetupOwnerAsync("max@example.com", "Max", password))
            throw new InvalidOperationException("Owner setup failed.");

        var login = await auth.LoginAsync("MAX@example.com", password);
        if (login is null || login.Owner.DisplayName != "Max")
            throw new InvalidOperationException("Valid owner credentials did not create a session.");
        if (login.ExpiresAt != now.AddHours(12))
            throw new InvalidOperationException("Session expiry was not set to 12 hours.");

        var principal = await auth.ValidateSessionAsync(login.AccessToken);
        if (principal is null || principal.OwnerId != login.Owner.Id)
            throw new InvalidOperationException("New bearer session did not validate.");

        await using var db = await f.CreateFactory().CreateDbContextAsync();
        var session = await db.OwnerSessions.SingleAsync();
        if (session.TokenHash == login.AccessToken || session.TokenHash.Length != 64)
            throw new InvalidOperationException("Raw bearer token must never be stored in the database.");
    }

    private static async Task OwnerLoginRejectsInvalidCredentials()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var auth = new OwnerAuthenticationService(f.CreateFactory(), new FixedTimeProvider(DateTimeOffset.UtcNow));
        await auth.SetupOwnerAsync("max@example.com", "Max", "A-strong-owner-password-2026!");
        if (await auth.LoginAsync("max@example.com", "wrong-password") is not null)
            throw new InvalidOperationException("Wrong password was accepted.");
        if (await auth.LoginAsync("unknown@example.com", "A-strong-owner-password-2026!") is not null)
            throw new InvalidOperationException("Unknown owner email was accepted.");
    }

    private static async Task OwnerSessionCanBeRevoked()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var auth = new OwnerAuthenticationService(f.CreateFactory(), new FixedTimeProvider(now));
        const string password = "A-strong-owner-password-2026!";
        await auth.SetupOwnerAsync("max@example.com", "Max", password);
        var login = await auth.LoginAsync("max@example.com", password)
            ?? throw new InvalidOperationException("Owner login failed.");
        if (!await auth.RevokeSessionAsync(login.AccessToken))
            throw new InvalidOperationException("Session revocation did not find the session.");
        if (await auth.ValidateSessionAsync(login.AccessToken) is not null)
            throw new InvalidOperationException("Revoked session remained valid.");

        var later = new OwnerAuthenticationService(f.CreateFactory(), new FixedTimeProvider(now.AddHours(13)));
        if (await later.ValidateSessionAsync(login.AccessToken) is not null)
            throw new InvalidOperationException("Expired session remained valid.");
    }

    private static async Task ExpiredPairingClaimCannotIssueSession()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var pair = await CreateApprovedPairAsync(f, now);
        var laterPairing = new DevicePairingService(f.CreateFactory(), new FixedTimeProvider(now.AddMinutes(11)));
        if (await laterPairing.ExchangeClaimForDeviceSessionAsync(pair.ClaimId, pair.ClaimSecret) is not null)
            throw new InvalidOperationException("Expired pairing claim was exchanged for a device session.");
    }

    private static async Task DeviceSessionRequiresOwnerApproval()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var pair = await CreateApprovedPairAsync(f, now, approve: false);
        if (await pair.Pairing.ExchangeClaimForDeviceSessionAsync(pair.ClaimId, pair.ClaimSecret) is not null)
            throw new InvalidOperationException("Device session was issued before owner approval.");
    }

    private static async Task DeviceSessionIsOneTimeAndHashed()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var pair = await CreateApprovedPairAsync(f, now);
        var session = await pair.Pairing.ExchangeClaimForDeviceSessionAsync(pair.ClaimId, pair.ClaimSecret)
            ?? throw new InvalidOperationException("Approved device could not exchange its claim secret.");
        if (session.ExpiresAt != now.AddHours(12) || session.DeviceId != pair.DeviceId)
            throw new InvalidOperationException("Device session claims or expiry are incorrect.");
        if (await pair.Pairing.ExchangeClaimForDeviceSessionAsync(pair.ClaimId, pair.ClaimSecret) is not null)
            throw new InvalidOperationException("Claim secret was exchanged more than once.");

        await using var db = await f.CreateFactory().CreateDbContextAsync();
        var stored = await db.DeviceSessions.SingleAsync();
        if (stored.TokenHash == session.AccessToken || stored.TokenHash.Length != 64)
            throw new InvalidOperationException("Raw device bearer token must not be stored.");
        if (stored.ClaimId != pair.ClaimId)
            throw new InvalidOperationException("Device session is not bound to its pairing claim.");
    }

    private static async Task DeviceSessionHeartbeatUpdatesLastSeen()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var pair = await CreateApprovedPairAsync(f, now);
        var session = await pair.Pairing.ExchangeClaimForDeviceSessionAsync(pair.ClaimId, pair.ClaimSecret)
            ?? throw new InvalidOperationException("Approved device could not exchange its claim secret.");
        var principal = await pair.Pairing.ValidateDeviceSessionAsync(session.AccessToken);
        if (principal is null || principal.DeviceId != pair.DeviceId || principal.OwnerId != OwnerAccount.SingletonOwnerId)
            throw new InvalidOperationException("Valid device session did not resolve the enrolled device.");

        await using var db = await f.CreateFactory().CreateDbContextAsync();
        var device = await db.DeviceEnrollments.SingleAsync(x => x.DeviceId == pair.DeviceId);
        if (device.LastSeenAtUnixMilliseconds != now.ToUnixTimeMilliseconds())
            throw new InvalidOperationException("Device last-seen time was not updated by heartbeat validation.");
    }

    private static async Task RevokedDeviceInvalidatesSession()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var pair = await CreateApprovedPairAsync(f, now);
        var session = await pair.Pairing.ExchangeClaimForDeviceSessionAsync(pair.ClaimId, pair.ClaimSecret)
            ?? throw new InvalidOperationException("Approved device could not exchange its claim secret.");
        if (!await pair.Pairing.RevokeDeviceAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId))
            throw new InvalidOperationException("Owner device revocation failed.");
        if (await pair.Pairing.ValidateDeviceSessionAsync(session.AccessToken) is not null)
            throw new InvalidOperationException("Revoked device still had a valid session.");
    }

    private static async Task DeviceSessionExpires()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var pair = await CreateApprovedPairAsync(f, now);
        var session = await pair.Pairing.ExchangeClaimForDeviceSessionAsync(pair.ClaimId, pair.ClaimSecret)
            ?? throw new InvalidOperationException("Approved device could not exchange its claim secret.");
        var later = new DevicePairingService(f.CreateFactory(), new FixedTimeProvider(now.AddHours(13)));
        if (await later.ValidateDeviceSessionAsync(session.AccessToken) is not null)
            throw new InvalidOperationException("Expired device bearer session remained valid.");
    }

    private static async Task<(DevicePairingService Pairing, Guid ClaimId, string ClaimSecret, Guid DeviceId)> CreateApprovedPairAsync(
        SqliteStoreFixture f, DateTimeOffset now, bool approve = true)
    {
        var auth = new OwnerAuthenticationService(f.CreateFactory(), new FixedTimeProvider(now));
        await auth.SetupOwnerAsync("max@example.com", "Max", "A-strong-owner-password-2026!");
        var pairing = new DevicePairingService(f.CreateFactory(), new FixedTimeProvider(now));
        var invite = await pairing.CreateInviteAsync(OwnerAccount.SingletonOwnerId);
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var claim = await pairing.StartClaimAsync(
            invite.PairingCode, "Windows laptop", "windows",
            Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()))
            ?? throw new InvalidOperationException("Pairing claim did not start.");
        var proof = key.SignData(Convert.FromBase64String(claim.ProofPayloadBase64),
            HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        if (!await pairing.ProveClaimAsync(claim.ClaimId, Fixture.Encode(proof)))
            throw new InvalidOperationException("Device proof was rejected.");
        if (approve && !await pairing.ApproveClaimAsync(OwnerAccount.SingletonOwnerId, claim.ClaimId))
            throw new InvalidOperationException("Owner approval failed.");
        return (pairing, claim.ClaimId, claim.ClaimSecret, claim.DeviceId);
    }

    private static async Task SharedTaskOwnerAssignment()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var pair = await CreateApprovedPairAsync(f, now);
        var tasks = new SharedTaskService(f.CreateFactory(), new FixedTimeProvider(now));
        var created = await tasks.CreateAsync(
            OwnerAccount.SingletonOwnerId, pair.DeviceId, "Build website", "Create the approved landing page.");
        if (created is null || created.Status != "QUEUED" || created.Version != 1 ||
            created.TargetDeviceId != pair.DeviceId)
            throw new InvalidOperationException("Owner task assignment did not create a queued task.");
        if (await tasks.CreateAsync(
                OwnerAccount.SingletonOwnerId, Guid.NewGuid(), "Invalid target", "Must not be assigned.") is not null)
            throw new InvalidOperationException("Task was assigned to a device outside the owner's registry.");
    }

    private static async Task SharedTaskDeviceIsolation()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var pair = await CreateApprovedPairAsync(f, now);
        var service = new SharedTaskService(f.CreateFactory(), new FixedTimeProvider(now));
        var task = await service.CreateAsync(
            OwnerAccount.SingletonOwnerId, pair.DeviceId, "Phone task", "Only the paired phone should receive this task.")
            ?? throw new InvalidOperationException("Task creation failed.");
        var assigned = await service.GetDeviceInboxAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId);
        var otherDevice = await service.GetDeviceInboxAsync(OwnerAccount.SingletonOwnerId, Guid.NewGuid());
        if (assigned.Count != 1 || assigned[0].Id != task.Id)
            throw new InvalidOperationException("Assigned device did not receive its task.");
        if (otherDevice.Count != 0)
            throw new InvalidOperationException("Another device received a task assigned to a different device.");
    }

    private static async Task SharedTaskProgressVersioning()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var pair = await CreateApprovedPairAsync(f, now);
        var service = new SharedTaskService(f.CreateFactory(), new FixedTimeProvider(now));
        var task = await service.CreateAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId, "Project", "Test state transitions.")
            ?? throw new InvalidOperationException("Task creation failed.");

        if (await service.UpdateFromDeviceAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId, task.Id,
                task.Version, "PLANNING", "Reviewed the request.") != SharedTaskUpdateResult.Updated)
            throw new InvalidOperationException("Queued task did not transition to planning.");
        if (await service.UpdateFromDeviceAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId, task.Id,
                task.Version, "RUNNING", "Stale update.") != SharedTaskUpdateResult.VersionConflict)
            throw new InvalidOperationException("Stale task version was not rejected.");
        if (await service.UpdateFromDeviceAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId, task.Id,
                2, "COMPLETED", "Skipped verification.") != SharedTaskUpdateResult.InvalidTransition)
            throw new InvalidOperationException("Task skipped required verification state.");
        if (await service.UpdateFromDeviceAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId, task.Id,
                2, "RUNNING", "Execution started.") != SharedTaskUpdateResult.Updated)
            throw new InvalidOperationException("Valid planning-to-running transition was rejected.");
    }

    private static async Task SharedTaskApprovalGate()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var pair = await CreateApprovedPairAsync(f, now);
        var service = new SharedTaskService(f.CreateFactory(), new FixedTimeProvider(now));
        var task = await service.CreateAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId, "Sensitive task", "Requires Max's approval.")
            ?? throw new InvalidOperationException("Task creation failed.");
        if (await service.UpdateFromDeviceAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId, task.Id,
                1, "PLANNING", "Plan ready.") != SharedTaskUpdateResult.Updated)
            throw new InvalidOperationException("Planning transition failed.");
        if (await service.UpdateFromDeviceAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId, task.Id,
                2, "WAITING_FOR_APPROVAL", "Max must approve this step.") != SharedTaskUpdateResult.Updated)
            throw new InvalidOperationException("Task did not reach approval state.");
        if (await service.UpdateFromDeviceAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId, task.Id,
                3, "RUNNING", "Attempt without owner approval.") != SharedTaskUpdateResult.InvalidTransition)
            throw new InvalidOperationException("Device bypassed the owner approval gate.");
        if (await service.ApproveRunAsync(OwnerAccount.SingletonOwnerId, task.Id, 3) != SharedTaskUpdateResult.Updated)
            throw new InvalidOperationException("Owner could not approve the waiting task.");
    }

    private static async Task SharedTaskCancellationAndHistory()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var pair = await CreateApprovedPairAsync(f, now);
        var service = new SharedTaskService(f.CreateFactory(), new FixedTimeProvider(now));
        var task = await service.CreateAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId, "Cancellable task", "The owner can stop this.")
            ?? throw new InvalidOperationException("Task creation failed.");
        if (await service.CancelAsync(OwnerAccount.SingletonOwnerId, task.Id, 1) != SharedTaskUpdateResult.Updated)
            throw new InvalidOperationException("Owner could not cancel the task.");
        if (await service.GetDeviceInboxAsync(OwnerAccount.SingletonOwnerId, pair.DeviceId) is { Count: > 0 })
            throw new InvalidOperationException("Cancelled task remained in the active device inbox.");
        var events = await service.GetEventsAsync(OwnerAccount.SingletonOwnerId, task.Id);
        if (events.Count != 2 || events[^1].Status != "CANCELLED" || events[^1].Version != 2)
            throw new InvalidOperationException("Task event history did not record cancellation.");
    }

    private static async Task PairingCreatesOneUseInvite()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var auth = new OwnerAuthenticationService(f.CreateFactory(), new FixedTimeProvider(now));
        await auth.SetupOwnerAsync("max@example.com", "Max", "A-strong-owner-password-2026!");
        var pairing = new DevicePairingService(f.CreateFactory(), new FixedTimeProvider(now));
        var invite = await pairing.CreateInviteAsync(OwnerAccount.SingletonOwnerId);
        if (invite.PairingCode.Length != 8 || invite.PairingCode.Any(c => !char.IsDigit(c)))
            throw new InvalidOperationException("Pairing code must contain eight digits.");
        if (invite.ExpiresAt != now.AddMinutes(10))
            throw new InvalidOperationException("Pairing invite expiry must be ten minutes.");

        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var publicKey = Convert.ToBase64String(key.ExportSubjectPublicKeyInfo());
        var wrongCode = invite.PairingCode == "00000000" ? "99999999" : "00000000";
        if (await pairing.StartClaimAsync(wrongCode, "HP laptop", "windows", publicKey) is not null)
            throw new InvalidOperationException("Incorrect pairing code was accepted.");
        if (await pairing.StartClaimAsync(invite.PairingCode, "HP laptop", "windows", publicKey) is null)
            throw new InvalidOperationException("Valid one-use pairing code was not accepted.");
        if (await pairing.StartClaimAsync(invite.PairingCode, "Second device", "android", publicKey) is not null)
            throw new InvalidOperationException("Pairing code was accepted more than once.");
    }

    private static async Task PairingRequiresPrivateKeyProof()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var auth = new OwnerAuthenticationService(f.CreateFactory(), new FixedTimeProvider(now));
        await auth.SetupOwnerAsync("max@example.com", "Max", "A-strong-owner-password-2026!");
        var pairing = new DevicePairingService(f.CreateFactory(), new FixedTimeProvider(now));
        var invite = await pairing.CreateInviteAsync(OwnerAccount.SingletonOwnerId);
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var claim = await pairing.StartClaimAsync(invite.PairingCode, "Nokia phone", "android",
            Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()))
            ?? throw new InvalidOperationException("Pairing claim did not start.");

        if (await pairing.ProveClaimAsync(claim.ClaimId, Fixture.Encode(RandomNumberGenerator.GetBytes(64))))
            throw new InvalidOperationException("Random signature proved private-key possession.");
        var signature = key.SignData(Convert.FromBase64String(claim.ProofPayloadBase64),
            HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        if (!await pairing.ProveClaimAsync(claim.ClaimId, Fixture.Encode(signature)))
            throw new InvalidOperationException("Valid device private-key proof was rejected.");
    }

    private static async Task PairingRequiresApprovalAndRevocation()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var auth = new OwnerAuthenticationService(f.CreateFactory(), new FixedTimeProvider(now));
        await auth.SetupOwnerAsync("max@example.com", "Max", "A-strong-owner-password-2026!");
        var pairing = new DevicePairingService(f.CreateFactory(), new FixedTimeProvider(now));
        var invite = await pairing.CreateInviteAsync(OwnerAccount.SingletonOwnerId);
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var claim = await pairing.StartClaimAsync(invite.PairingCode, "HP EliteBook", "windows",
            Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()))
            ?? throw new InvalidOperationException("Pairing claim did not start.");

        if (await pairing.ApproveClaimAsync(OwnerAccount.SingletonOwnerId, claim.ClaimId))
            throw new InvalidOperationException("Owner approval succeeded before device proof.");
        var signature = key.SignData(Convert.FromBase64String(claim.ProofPayloadBase64),
            HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        if (!await pairing.ProveClaimAsync(claim.ClaimId, Fixture.Encode(signature)))
            throw new InvalidOperationException("Device proof failed.");
        if (!await pairing.ApproveClaimAsync(OwnerAccount.SingletonOwnerId, claim.ClaimId))
            throw new InvalidOperationException("Owner approval failed after valid proof.");

        var devices = await pairing.ListDevicesAsync(OwnerAccount.SingletonOwnerId);
        if (devices.Count != 1 || devices[0].DeviceName != "HP EliteBook" || devices[0].Revoked)
            throw new InvalidOperationException("Approved device was not listed correctly.");
        if (!await pairing.RevokeDeviceAsync(OwnerAccount.SingletonOwnerId, claim.DeviceId))
            throw new InvalidOperationException("Device revocation failed.");
        devices = await pairing.ListDevicesAsync(OwnerAccount.SingletonOwnerId);
        if (devices.Count != 1 || !devices[0].Revoked)
            throw new InvalidOperationException("Revoked device state was not persisted.");
    }

    private static async Task PairingClaimStatusRequiresSecret()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var auth = new OwnerAuthenticationService(f.CreateFactory(), new FixedTimeProvider(now));
        await auth.SetupOwnerAsync("max@example.com", "Max", "A-strong-owner-password-2026!");
        var pairing = new DevicePairingService(f.CreateFactory(), new FixedTimeProvider(now));
        var invite = await pairing.CreateInviteAsync(OwnerAccount.SingletonOwnerId);
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var claim = await pairing.StartClaimAsync(invite.PairingCode, "Phone", "android",
            Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()))
            ?? throw new InvalidOperationException("Pairing claim did not start.");
        if (await pairing.GetClaimStatusAsync(claim.ClaimId, "wrong-secret") is not null)
            throw new InvalidOperationException("Claim status leaked without the claim secret.");
        if (await pairing.GetClaimStatusAsync(claim.ClaimId, claim.ClaimSecret) != "proof_required")
            throw new InvalidOperationException("Claim status did not report proof_required.");
    }

    private static async Task PersistentReplayRejectsReuse()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var target = Guid.NewGuid();
        var command = Guid.NewGuid();
        var nonce = Fixture.Encode(RandomNumberGenerator.GetBytes(24));
        var firstStore = new EfCommandReplayStore(f.CreateFactory(), new FixedTimeProvider(now));
        var reopenedStore = new EfCommandReplayStore(f.CreateFactory(), new FixedTimeProvider(now));

        if (!await firstStore.TryRegisterAsync(target, command, nonce, now.AddMinutes(1)))
            throw new InvalidOperationException("First command registration should succeed.");
        if (await reopenedStore.TryRegisterAsync(target, command, nonce, now.AddMinutes(1)))
            throw new InvalidOperationException("A new store/context accepted a replayed command.");
    }

    private static async Task PersistentNonceRejectsReuse()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var target = Guid.NewGuid();
        var nonce = Fixture.Encode(RandomNumberGenerator.GetBytes(24));
        var store = new EfCommandReplayStore(f.CreateFactory(), new FixedTimeProvider(now));

        if (!await store.TryRegisterAsync(target, Guid.NewGuid(), nonce, now.AddMinutes(1)))
            throw new InvalidOperationException("First nonce registration should succeed.");
        if (await store.TryRegisterAsync(target, Guid.NewGuid(), nonce, now.AddMinutes(1)))
            throw new InvalidOperationException("A reused nonce was accepted for a different command.");
    }

    private static async Task PersistentApprovalMatchesExactCommand()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var request = new CommandApprovalRequest(
            Guid.NewGuid(), Guid.NewGuid(), Guid.NewGuid(), "power.shutdown", 60,
            now.AddSeconds(-10), now.AddMinutes(1));
        await f.AddApprovalAsync(new OwnerCommandApprovalRecord
        {
            ApprovalId = request.ApprovalId,
            TargetDeviceId = request.TargetDeviceId,
            CommandId = request.CommandId,
            Action = request.Action,
            DelaySeconds = request.DelaySeconds,
            IssuedAtUnixMilliseconds = request.IssuedAt.ToUnixTimeMilliseconds(),
            ExpiresAtUnixMilliseconds = request.ExpiresAt.ToUnixTimeMilliseconds(),
            ApprovedAtUnixMilliseconds = now.ToUnixTimeMilliseconds()
        });

        var verifier = new EfCommandApprovalVerifier(f.CreateFactory(), new FixedTimeProvider(now));
        if (!await verifier.IsApprovedAsync(request))
            throw new InvalidOperationException("Exact stored approval should verify.");

        var alteredRequests = new[]
        {
            request with { ApprovalId = Guid.NewGuid() },
            request with { TargetDeviceId = Guid.NewGuid() },
            request with { CommandId = Guid.NewGuid() },
            request with { Action = "power.restart" },
            request with { DelaySeconds = 90 },
            request with { IssuedAt = request.IssuedAt.AddSeconds(1) },
            request with { ExpiresAt = request.ExpiresAt.AddSeconds(1) }
        };

        foreach (var altered in alteredRequests)
        {
            if (await verifier.IsApprovedAsync(altered))
                throw new InvalidOperationException("Stored approval was accepted after changing a bound field.");
        }
    }

    private static async Task PersistentApprovalRejectsRevokedOrExpired()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var now = DateTimeOffset.UtcNow;
        var revoked = new CommandApprovalRequest(
            Guid.NewGuid(), Guid.NewGuid(), Guid.NewGuid(), "power.shutdown", 60,
            now.AddSeconds(-10), now.AddMinutes(1));
        await f.AddApprovalAsync(new OwnerCommandApprovalRecord
        {
            ApprovalId = revoked.ApprovalId,
            TargetDeviceId = revoked.TargetDeviceId,
            CommandId = revoked.CommandId,
            Action = revoked.Action,
            DelaySeconds = revoked.DelaySeconds,
            IssuedAtUnixMilliseconds = revoked.IssuedAt.ToUnixTimeMilliseconds(),
            ExpiresAtUnixMilliseconds = revoked.ExpiresAt.ToUnixTimeMilliseconds(),
            ApprovedAtUnixMilliseconds = now.AddSeconds(-5).ToUnixTimeMilliseconds(),
            RevokedAtUnixMilliseconds = now.ToUnixTimeMilliseconds()
        });

        var expired = new CommandApprovalRequest(
            Guid.NewGuid(), Guid.NewGuid(), Guid.NewGuid(), "power.restart", 60,
            now.AddMinutes(-2), now.AddMinutes(-1));
        await f.AddApprovalAsync(new OwnerCommandApprovalRecord
        {
            ApprovalId = expired.ApprovalId,
            TargetDeviceId = expired.TargetDeviceId,
            CommandId = expired.CommandId,
            Action = expired.Action,
            DelaySeconds = expired.DelaySeconds,
            IssuedAtUnixMilliseconds = expired.IssuedAt.ToUnixTimeMilliseconds(),
            ExpiresAtUnixMilliseconds = expired.ExpiresAt.ToUnixTimeMilliseconds(),
            ApprovedAtUnixMilliseconds = now.AddMinutes(-2).ToUnixTimeMilliseconds()
        });

        var verifier = new EfCommandApprovalVerifier(f.CreateFactory(), new FixedTimeProvider(now));
        if (await verifier.IsApprovedAsync(revoked))
            throw new InvalidOperationException("Revoked approval was accepted.");
        if (await verifier.IsApprovedAsync(expired))
            throw new InvalidOperationException("Expired approval was accepted.");
    }

    private static Task MatchesWireVector()
    {
        var command = new DeviceCommandEnvelope(
            Guid.Parse("11111111-2222-3333-4444-555555555555"),
            Guid.Parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            DateTimeOffset.FromUnixTimeMilliseconds(1793869200000),
            DateTimeOffset.FromUnixTimeMilliseconds(1793869230000),
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX",
            "power.shutdown",
            60,
            Guid.Parse("99999999-8888-7777-6666-555555555555"),
            string.Empty);

        const string expected =
            "sam-device-command-v1\n" +
            "11111111-2222-3333-4444-555555555555\n" +
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n" +
            "1793869200000\n" +
            "1793869230000\n" +
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX\n" +
            "power.shutdown\n" +
            "60\n" +
            "99999999-8888-7777-6666-555555555555";

        var actual = System.Text.Encoding.UTF8.GetString(
            DeviceCommandCanonicalizer.CreateSigningPayload(command));
        if (!string.Equals(actual, expected, StringComparison.Ordinal))
            throw new InvalidOperationException("Canonical payload does not match the published cross-platform test vector.");

        return Task.CompletedTask;
    }


    private static async Task CompanyCrmIsOwnerScoped()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var service = new CompanyOperationsService(f.CreateFactory(), new FixedTimeProvider(DateTimeOffset.UtcNow));
        var owner = Guid.NewGuid();
        var customer = await service.CreateCustomerAsync(owner, new CreateCompanyCustomerRequest(" Acme ", "Acme Ltd", "HELLO@ACME.KE", "+254 700 000 000", "Client"))
            ?? throw new InvalidOperationException("Valid customer was rejected.");
        if (customer.Name != "Acme" || customer.Email != "hello@acme.ke") throw new InvalidOperationException("Customer normalization failed.");
        if ((await service.CustomersAsync(Guid.NewGuid())).Count != 0 || (await service.CustomersAsync(owner)).Count != 1)
            throw new InvalidOperationException("CRM owner isolation failed.");
        if (await service.CreateCustomerAsync(owner, new CreateCompanyCustomerRequest("Bad", null, null, "abc<script>", null)) is not null)
            throw new InvalidOperationException("Invalid phone was accepted.");
    }

    private static async Task CompanyProjectPipelineIsEnforced()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var service = new CompanyOperationsService(f.CreateFactory(), new FixedTimeProvider(DateTimeOffset.UtcNow));
        var owner = Guid.NewGuid();
        var customer = await service.CreateCustomerAsync(owner, new CreateCompanyCustomerRequest("Client", null, null, null, null))
            ?? throw new InvalidOperationException("Customer setup failed.");
        var project = await service.CreateProjectAsync(owner, new CreateCompanyProjectRequest(customer.Id, "Website", "Build a website.", null))
            ?? throw new InvalidOperationException("Project setup failed.");
        if (await service.ChangeProjectStatusAsync(owner, project.Id, "ACTIVE") != CompanyOperationResult.Conflict)
            throw new InvalidOperationException("Project skipped planning.");
        if (await service.ChangeProjectStatusAsync(owner, project.Id, "PLANNING") != CompanyOperationResult.Updated ||
            await service.ChangeProjectStatusAsync(owner, project.Id, "ACTIVE") != CompanyOperationResult.Updated)
            throw new InvalidOperationException("Valid pipeline transition failed.");
        if (await service.ChangeProjectStatusAsync(Guid.NewGuid(), project.Id, "COMPLETED") != CompanyOperationResult.NotFound)
            throw new InvalidOperationException("Foreign owner changed project.");
    }

    private static async Task CompanyQuoteTotalIsServerCalculated()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var service = new CompanyOperationsService(f.CreateFactory(), new FixedTimeProvider(DateTimeOffset.UtcNow));
        var owner = Guid.NewGuid();
        var customer = await service.CreateCustomerAsync(owner, new CreateCompanyCustomerRequest("Client", null, null, null, null))
            ?? throw new InvalidOperationException("Customer setup failed.");
        var quote = await service.CreateQuoteAsync(owner, new CreateCompanyQuoteRequest(customer.Id, null, "Logo design and brand guide.", "Printing excluded.",
            DateTimeOffset.UtcNow.AddDays(14), new[] { new CompanyQuoteLineInput("Logo design", 1, 15000), new CompanyQuoteLineInput("Brand guide", 2, 2500) }))
            ?? throw new InvalidOperationException("Quote setup failed.");
        if (quote.CurrencyCode != "KES" || quote.TotalKes != 20000 || quote.Status != "DRAFT")
            throw new InvalidOperationException("Quote total or currency incorrect.");
        if (await service.IssueQuoteAsync(owner, quote.Id) != CompanyOperationResult.Updated ||
            await service.IssueQuoteAsync(owner, quote.Id) != CompanyOperationResult.Conflict)
            throw new InvalidOperationException("Quote issue transition incorrect.");
        var issued = await service.QuotesAsync(owner);
        if (issued.Single().Status != "ISSUED")
            throw new InvalidOperationException("Issued quote status must not imply external delivery.");
    }

    private static async Task CompanyDashboardIsOwnerScoped()
    {
        await using var f = await SqliteStoreFixture.CreateAsync();
        var service = new CompanyOperationsService(f.CreateFactory(), new FixedTimeProvider(DateTimeOffset.UtcNow));
        var owner = Guid.NewGuid();
        var customer = await service.CreateCustomerAsync(owner, new CreateCompanyCustomerRequest("Client", null, null, null, null))
            ?? throw new InvalidOperationException("Customer setup failed.");
        await service.CreateProjectAsync(owner, new CreateCompanyProjectRequest(customer.Id, "Brand", "Brand identity work.", null));
        var mine = await service.DashboardAsync(owner);
        var other = await service.DashboardAsync(Guid.NewGuid());
        if (mine.Customers != 1 || mine.Leads != 1 || other.Customers != 0 || other.Leads != 0)
            throw new InvalidOperationException("Dashboard owner isolation or counts failed.");
    }

    private static async Task AcceptsValidStatus()
    {
        using var f = new Fixture();
        AssertValid(await f.ValidateAsync(f.Sign(f.Create("status.read"))));
    }

    private static async Task RejectsTampering()
    {
        using var f = new Fixture();
        var signed = f.Sign(f.Create("status.read"));
        var altered = signed with { Nonce = Fixture.Encode(RandomNumberGenerator.GetBytes(24)) };
        AssertCode(await f.ValidateAsync(altered), "signature_mismatch");
    }

    private static async Task RejectsWrongTarget()
    {
        using var f = new Fixture();
        AssertCode(
            await f.ValidateAsync(f.Sign(f.Create("status.read")), Guid.NewGuid()),
            "wrong_target");
    }

    private static async Task RejectsExpiredCommand()
    {
        using var f = new Fixture();
        var expired = f.Create("status.read") with
        {
            IssuedAt = f.Now.AddMinutes(-2),
            ExpiresAt = f.Now.AddMinutes(-1)
        };
        AssertCode(await f.ValidateAsync(f.Sign(expired)), "expired");
    }

    private static async Task RejectsReplay()
    {
        using var f = new Fixture();
        var command = f.Sign(f.Create("status.read"));
        AssertValid(await f.ValidateAsync(command));
        AssertCode(await f.ValidateAsync(command), "replay_detected");
    }

    private static async Task RequiresApprovalReference()
    {
        using var f = new Fixture();
        var command = f.Create("power.shutdown") with { DelaySeconds = 60 };
        AssertCode(await f.ValidateAsync(f.Sign(command)), "invalid_parameters");
    }

    private static async Task RequiresApprovalConfirmation()
    {
        using var f = new Fixture();
        var command = f.Create("power.restart") with
        {
            DelaySeconds = 60,
            RequiredApprovalId = Guid.NewGuid()
        };
        AssertCode(await f.ValidateAsync(f.Sign(command)), "owner_approval_not_confirmed");
    }

    private static async Task DoesNotReuseApproval()
    {
        using var f = new Fixture(approvalGranted: true);
        var approved = f.Create("power.shutdown") with
        {
            DelaySeconds = 60,
            RequiredApprovalId = Guid.NewGuid()
        };
        f.Approve(approved);

        var differentCommand = f.Sign(approved with { CommandId = Guid.NewGuid() });
        AssertCode(await f.ValidateAsync(differentCommand), "owner_approval_not_confirmed");
    }

    private static async Task AcceptsApprovedShutdown()
    {
        using var f = new Fixture(approvalGranted: true);
        var command = f.Create("power.shutdown") with
        {
            DelaySeconds = 60,
            RequiredApprovalId = Guid.NewGuid()
        };
        f.Approve(command);
        AssertValid(await f.ValidateAsync(f.Sign(command)));
    }

    private static async Task RejectsUnknownAction()
    {
        using var f = new Fixture();
        AssertCode(await f.ValidateAsync(f.Sign(f.Create("shell.execute"))), "unsupported_action");
    }

    private static async Task RejectsUnsafeDelay()
    {
        using var f = new Fixture();
        var command = f.Create("power.shutdown") with
        {
            DelaySeconds = 1,
            RequiredApprovalId = Guid.NewGuid()
        };
        AssertCode(await f.ValidateAsync(f.Sign(command)), "invalid_parameters");
    }

    private static void AssertValid(CommandValidationResult result)
    {
        if (!result.IsValid || result.Code != "ok")
            throw new InvalidOperationException($"Expected valid, got {result.Code}.");
    }

    private static void AssertCode(CommandValidationResult result, string expected)
    {
        if (result.IsValid || result.Code != expected)
            throw new InvalidOperationException($"Expected {expected}, got {result.Code}.");
    }

    private sealed class SqliteStoreFixture(string databasePath, DbContextOptions<SamGatewayDbContext> options) : IAsyncDisposable
    {
        public IDbContextFactory<SamGatewayDbContext> CreateFactory() => new TestDbContextFactory(options);

        public static async Task<SqliteStoreFixture> CreateAsync()
        {
            var path = Path.Combine(Path.GetTempPath(), "sam-gateway-tests-" + Guid.NewGuid().ToString("N") + ".db");
            var options = new DbContextOptionsBuilder<SamGatewayDbContext>()
                .UseSqlite("Data Source=" + path + ";Default Timeout=5")
                .Options;
            await using (var db = new SamGatewayDbContext(options))
                await db.Database.EnsureCreatedAsync();
            return new SqliteStoreFixture(path, options);
        }

        public async Task AddApprovalAsync(OwnerCommandApprovalRecord approval)
        {
            await using var db = await CreateFactory().CreateDbContextAsync();
            db.OwnerCommandApprovals.Add(approval);
            await db.SaveChangesAsync();
        }

        public ValueTask DisposeAsync()
        {
            foreach (var suffix in new[] { "", "-shm", "-wal" })
            {
                try
                {
                    var candidate = databasePath + suffix;
                    if (File.Exists(candidate)) File.Delete(candidate);
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
            return ValueTask.CompletedTask;
        }
    }

    private sealed class TestDbContextFactory(DbContextOptions<SamGatewayDbContext> options)
        : IDbContextFactory<SamGatewayDbContext>
    {
        public SamGatewayDbContext CreateDbContext() => new(options);

        public Task<SamGatewayDbContext> CreateDbContextAsync(CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult(CreateDbContext());
        }
    }

    private sealed class Fixture : IDisposable
    {
        private readonly ECDsa _key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        private readonly MemoryReplayStore _replayStore = new();
        private readonly MemoryApprovalVerifier _approvalVerifier;

        public DateTimeOffset Now { get; } = new(2026, 11, 5, 9, 0, 0, TimeSpan.Zero);
        public Guid DeviceId { get; } = Guid.NewGuid();
        public byte[] PublicKey => _key.ExportSubjectPublicKeyInfo();
        public DeviceCommandValidator Validator { get; }

        public Fixture(bool approvalGranted = false)
        {
            _approvalVerifier = new MemoryApprovalVerifier(approvalGranted);
            Validator = new DeviceCommandValidator(
                _replayStore,
                _approvalVerifier,
                new FixedTimeProvider(Now));
        }

        public DeviceCommandEnvelope Create(string action) => new(
            Guid.NewGuid(),
            DeviceId,
            Now.AddSeconds(-2),
            Now.AddSeconds(30),
            Encode(RandomNumberGenerator.GetBytes(24)),
            action,
            null,
            null,
            string.Empty);

        public void Approve(DeviceCommandEnvelope command) =>
            _approvalVerifier.Add(new CommandApprovalRequest(
                command.RequiredApprovalId!.Value,
                command.TargetDeviceId,
                command.CommandId,
                command.Action,
                command.DelaySeconds!.Value,
                command.IssuedAt,
                command.ExpiresAt));

        public DeviceCommandEnvelope Sign(DeviceCommandEnvelope command)
        {
            var payload = DeviceCommandCanonicalizer.CreateSigningPayload(command);
            var signature = _key.SignData(
                payload,
                HashAlgorithmName.SHA256,
                DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
            return command with { Signature = Encode(signature) };
        }

        public Task<CommandValidationResult> ValidateAsync(
            DeviceCommandEnvelope command,
            Guid? expectedDeviceId = null) =>
            Validator.ValidateAsync(command, expectedDeviceId ?? DeviceId, PublicKey).AsTask();

        public static string Encode(byte[] bytes) =>
            Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

        public void Dispose() => _key.Dispose();
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }

    private sealed class MemoryReplayStore : ICommandReplayStore
    {
        private readonly HashSet<string> _seen = new(StringComparer.Ordinal);

        public ValueTask<bool> TryRegisterAsync(
            Guid targetDeviceId,
            Guid commandId,
            string nonce,
            DateTimeOffset expiresAt,
            CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var key = $"{targetDeviceId:D}:{commandId:D}:{nonce}";
            return ValueTask.FromResult(_seen.Add(key));
        }
    }

    private sealed class MemoryApprovalVerifier(bool initiallyGranted) : ICommandApprovalVerifier
    {
        private readonly HashSet<CommandApprovalRequest> _approved = new();
        private readonly bool _initiallyGranted = initiallyGranted;

        public void Add(CommandApprovalRequest request) => _approved.Add(request);

        public ValueTask<bool> IsApprovedAsync(
            CommandApprovalRequest request,
            CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return ValueTask.FromResult(_initiallyGranted && _approved.Contains(request));
        }
    }
}
