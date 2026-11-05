using System.Security.Cryptography;
using SAM.Gateway.Security;

internal static class Program
{
    private static async Task<int> Main()
    {
        var tests = new (string Name, Func<Task> Run)[]
        {
            ("accepts a valid signed status command", AcceptsValidStatus),
            ("matches the cross-platform canonical payload vector", MatchesWireVector),
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
