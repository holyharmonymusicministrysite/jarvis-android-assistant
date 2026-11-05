using System.Security.Cryptography;

namespace SAM.Gateway.Security;

/// <summary>
/// Validates a signed command before a device adapter may consider dispatching it.
/// This class deliberately does not perform power, screen, or input operations.
/// </summary>
public sealed class DeviceCommandValidator
{
    private static readonly TimeSpan MaximumCommandLifetime = TimeSpan.FromMinutes(2);
    private static readonly TimeSpan MaximumCommandAge = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan ClockSkew = TimeSpan.FromSeconds(30);

    private readonly ICommandReplayStore _replayStore;
    private readonly ICommandApprovalVerifier _approvalVerifier;
    private readonly TimeProvider _timeProvider;

    public DeviceCommandValidator(
        ICommandReplayStore replayStore,
        ICommandApprovalVerifier approvalVerifier,
        TimeProvider? timeProvider = null)
    {
        _replayStore = replayStore ?? throw new ArgumentNullException(nameof(replayStore));
        _approvalVerifier = approvalVerifier ?? throw new ArgumentNullException(nameof(approvalVerifier));
        _timeProvider = timeProvider ?? TimeProvider.System;
    }

    public async ValueTask<CommandValidationResult> ValidateAsync(
        DeviceCommandEnvelope? command,
        Guid expectedDeviceId,
        ReadOnlyMemory<byte> subjectPublicKeyInfo,
        CancellationToken cancellationToken = default)
    {
        if (command is null || expectedDeviceId == Guid.Empty ||
            command.CommandId == Guid.Empty || command.TargetDeviceId == Guid.Empty)
            return CommandValidationResult.Invalid("invalid_identity");

        if (command.TargetDeviceId != expectedDeviceId)
            return CommandValidationResult.Invalid("wrong_target");

        if (!IsSupportedShape(command, out var shapeError))
            return CommandValidationResult.Invalid(shapeError);

        var now = _timeProvider.GetUtcNow();
        if (command.ExpiresAt <= now)
            return CommandValidationResult.Invalid("expired");
        if (command.IssuedAt > now + ClockSkew ||
            command.IssuedAt < now - MaximumCommandAge ||
            command.ExpiresAt <= command.IssuedAt ||
            command.ExpiresAt - command.IssuedAt > MaximumCommandLifetime)
            return CommandValidationResult.Invalid("invalid_time_window");

        if (!TryDecodeBase64Url(command.Nonce, out var nonceBytes) ||
            nonceBytes.Length is < 16 or > 64 ||
            !string.Equals(EncodeBase64Url(nonceBytes), command.Nonce, StringComparison.Ordinal))
            return CommandValidationResult.Invalid("invalid_nonce");

        if (!TryDecodeBase64Url(command.Signature, out var signature) || signature.Length != 64)
            return CommandValidationResult.Invalid("invalid_signature_format");

        bool signatureIsValid;
        try
        {
            using var verifier = ECDsa.Create();
            verifier.ImportSubjectPublicKeyInfo(subjectPublicKeyInfo.Span, out var bytesRead);
            if (bytesRead != subjectPublicKeyInfo.Length || verifier.KeySize != 256)
                return CommandValidationResult.Invalid("invalid_public_key");

            var payload = DeviceCommandCanonicalizer.CreateSigningPayload(command);
            signatureIsValid = verifier.VerifyData(
                payload,
                signature,
                HashAlgorithmName.SHA256,
                DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        }
        catch (CryptographicException)
        {
            return CommandValidationResult.Invalid("invalid_public_key_or_signature");
        }
        catch (ArgumentException)
        {
            return CommandValidationResult.Invalid("invalid_public_key_or_signature");
        }

        if (!signatureIsValid)
            return CommandValidationResult.Invalid("signature_mismatch");

        if (RequiresOwnerApproval(command.Action))
        {
            var approval = new CommandApprovalRequest(
                command.RequiredApprovalId!.Value,
                command.TargetDeviceId,
                command.CommandId,
                command.Action,
                command.DelaySeconds!.Value,
                command.IssuedAt,
                command.ExpiresAt);

            // This must consult authoritative server-side approval state and
            // match every field above. Storage failure must fail closed.
            if (!await _approvalVerifier.IsApprovedAsync(approval, cancellationToken))
                return CommandValidationResult.Invalid("owner_approval_not_confirmed");
        }

        // The replay store operation must be atomic and durable in production.
        // If it throws because storage is unavailable, let the error fail closed.
        var firstUse = await _replayStore.TryRegisterAsync(
            command.TargetDeviceId,
            command.CommandId,
            command.Nonce,
            command.ExpiresAt,
            cancellationToken);

        return firstUse
            ? CommandValidationResult.Valid()
            : CommandValidationResult.Invalid("replay_detected");
    }

    private static bool IsSupportedShape(DeviceCommandEnvelope command, out string error)
    {
        error = "unsupported_action";

        switch (command.Action)
        {
            case "status.read":
                if (command.DelaySeconds is not null || command.RequiredApprovalId is not null)
                {
                    error = "invalid_parameters";
                    return false;
                }
                return true;

            case "power.shutdown":
            case "power.restart":
                if (command.DelaySeconds is not (>= 30 and <= 300) ||
                    command.RequiredApprovalId is null ||
                    command.RequiredApprovalId == Guid.Empty)
                {
                    error = "invalid_parameters";
                    return false;
                }
                return true;

            default:
                return false;
        }
    }

    private static bool RequiresOwnerApproval(string action) =>
        action is "power.shutdown" or "power.restart";

    private static bool TryDecodeBase64Url(string? value, out byte[] bytes)
    {
        bytes = Array.Empty<byte>();
        if (string.IsNullOrWhiteSpace(value) ||
            value.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '-' or '_')))
            return false;

        var base64 = value.Replace('-', '+').Replace('_', '/');
        base64 += new string('=', (4 - base64.Length % 4) % 4);
        try
        {
            bytes = Convert.FromBase64String(base64);
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    private static string EncodeBase64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
