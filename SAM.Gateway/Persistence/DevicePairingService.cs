using System.Security.Cryptography;
using System.Text;
using Microsoft.EntityFrameworkCore;

namespace SAM.Gateway.Persistence;

public sealed record PairingInviteCreated(Guid InviteId, string PairingCode, DateTimeOffset ExpiresAt);
public sealed record PairingClaimStarted(Guid ClaimId, Guid DeviceId, string Challenge, string ClaimSecret, string ProofPayloadBase64, string FingerprintSha256, DateTimeOffset ExpiresAt);
public sealed record PairingClaimSummary(Guid ClaimId, Guid DeviceId, string DeviceName, string Platform, string FingerprintSha256, bool ProofVerified, DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt);
public sealed record EnrolledDeviceSummary(Guid DeviceId, string DeviceName, string Platform, string FingerprintSha256, DateTimeOffset EnrolledAt, DateTimeOffset LastSeenAt, bool Revoked);

/// <summary>
/// Owner-approved one-use device pairing. A short-lived code identifies an invite
/// only; the claimant must also prove possession of its P-256 private key.
/// Enrollment occurs only after the owner approves the proof-verified claim.
/// </summary>
public sealed class DevicePairingService(
    IDbContextFactory<SamGatewayDbContext> contextFactory,
    TimeProvider? timeProvider = null)
{
    private static readonly TimeSpan InviteLifetime = TimeSpan.FromMinutes(10);
    private readonly TimeProvider _timeProvider = timeProvider ?? TimeProvider.System;

    public async Task<PairingInviteCreated> CreateInviteAsync(Guid ownerId, CancellationToken cancellationToken = default)
    {
        var now = _timeProvider.GetUtcNow();
        var code = RandomNumberGenerator.GetInt32(0, 100_000_000).ToString("D8");
        var salt = RandomNumberGenerator.GetBytes(16);
        var invite = new PairingInvite
        {
            Id = Guid.NewGuid(),
            OwnerId = ownerId,
            CodeSalt = salt,
            CodeHash = HashPairingCode(salt, code),
            CreatedAtUnixMilliseconds = now.ToUnixTimeMilliseconds(),
            ExpiresAtUnixMilliseconds = now.Add(InviteLifetime).ToUnixTimeMilliseconds()
        };

        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        if (!await db.OwnerAccounts.AnyAsync(x => x.Id == ownerId, cancellationToken))
            throw new InvalidOperationException("Owner account does not exist.");
        db.PairingInvites.Add(invite);
        await db.SaveChangesAsync(cancellationToken);
        return new PairingInviteCreated(invite.Id, code, now.Add(InviteLifetime));
    }

    public async Task<PairingClaimStarted?> StartClaimAsync(
        string pairingCode, string deviceName, string platform, string publicKeySpkiBase64,
        CancellationToken cancellationToken = default)
    {
        if (pairingCode.Length != 8 || pairingCode.Any(c => c is < '0' or > '9'))
            return null;
        if (!IsSafeLabel(deviceName, 64) || platform is not ("android" or "windows") ||
            !TryImportPublicKey(publicKeySpkiBase64, out var publicKeySpki))
            return null;

        using var key = ECDsa.Create();
        try
        {
            key.ImportSubjectPublicKeyInfo(publicKeySpki, out var bytesRead);
            if (bytesRead != publicKeySpki.Length || key.KeySize != 256)
                return null;
            publicKeySpki = key.ExportSubjectPublicKeyInfo();
        }
        catch (CryptographicException) { return null; }
        catch (ArgumentException) { return null; }

        var fingerprint = Convert.ToHexString(SHA256.HashData(publicKeySpki));
        var now = _timeProvider.GetUtcNow();
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);

        if (await db.DeviceEnrollments.AnyAsync(
                x => x.FingerprintSha256 == fingerprint && x.RevokedAtUnixMilliseconds == null,
                cancellationToken))
            return null;

        var invites = await db.PairingInvites
            .Where(x => x.ExpiresAtUnixMilliseconds > now.ToUnixTimeMilliseconds() &&
                        x.ClaimedAtUnixMilliseconds == null && x.RevokedAtUnixMilliseconds == null)
            .OrderByDescending(x => x.CreatedAtUnixMilliseconds)
            .Take(20)
            .ToListAsync(cancellationToken);

        PairingInvite? matchedInvite = null;
        foreach (var invite in invites)
        {
            var candidate = HashPairingCode(invite.CodeSalt, pairingCode);
            if (CryptographicOperations.FixedTimeEquals(candidate, invite.CodeHash))
            {
                matchedInvite = invite;
                break;
            }
        }
        if (matchedInvite is null) return null;

        var challengeBytes = RandomNumberGenerator.GetBytes(32);
        var secretBytes = RandomNumberGenerator.GetBytes(32);
        var claimId = Guid.NewGuid();
        var deviceId = Guid.NewGuid();
        var challenge = EncodeBase64Url(challengeBytes);
        var claimSecret = EncodeBase64Url(secretBytes);
        var safeDeviceName = deviceName.Trim();
        var payload = CreateProofPayload(
            matchedInvite.Id, claimId, deviceId, challenge, platform, safeDeviceName, fingerprint);

        db.DevicePairingClaims.Add(new DevicePairingClaim
        {
            Id = claimId,
            InviteId = matchedInvite.Id,
            OwnerId = matchedInvite.OwnerId,
            DeviceId = deviceId,
            DeviceName = safeDeviceName,
            Platform = platform,
            PublicKeySpki = publicKeySpki,
            FingerprintSha256 = fingerprint,
            ProofChallenge = challengeBytes,
            ClaimSecretHash = SHA256.HashData(secretBytes),
            CreatedAtUnixMilliseconds = now.ToUnixTimeMilliseconds(),
            ExpiresAtUnixMilliseconds = matchedInvite.ExpiresAtUnixMilliseconds
        });
        matchedInvite.ClaimedAtUnixMilliseconds = now.ToUnixTimeMilliseconds();
        await db.SaveChangesAsync(cancellationToken);

        return new PairingClaimStarted(
            claimId, deviceId, challenge, claimSecret,
            Convert.ToBase64String(payload), fingerprint,
            DateTimeOffset.FromUnixTimeMilliseconds(matchedInvite.ExpiresAtUnixMilliseconds));
    }

    public async Task<bool> ProveClaimAsync(Guid claimId, string signatureBase64Url, CancellationToken cancellationToken = default)
    {
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var claim = await db.DevicePairingClaims.SingleOrDefaultAsync(x => x.Id == claimId, cancellationToken);
        var now = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        if (claim is null || claim.ProofVerified || claim.ApprovedAtUnixMilliseconds is not null ||
            claim.RejectedAtUnixMilliseconds is not null || claim.ExpiresAtUnixMilliseconds <= now)
            return false;
        if (!TryDecodeBase64Url(signatureBase64Url, out var signature) || signature.Length != 64)
            return false;

        bool valid;
        try
        {
            using var verifier = ECDsa.Create();
            verifier.ImportSubjectPublicKeyInfo(claim.PublicKeySpki, out var bytesRead);
            if (bytesRead != claim.PublicKeySpki.Length || verifier.KeySize != 256)
                return false;
            var payload = CreateProofPayload(
                claim.InviteId, claim.Id, claim.DeviceId,
                EncodeBase64Url(claim.ProofChallenge), claim.Platform,
                claim.DeviceName, claim.FingerprintSha256);
            valid = verifier.VerifyData(
                payload, signature, HashAlgorithmName.SHA256,
                DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        }
        catch (CryptographicException) { return false; }
        catch (ArgumentException) { return false; }

        if (!valid) return false;
        claim.ProofVerified = true;
        await db.SaveChangesAsync(cancellationToken);
        return true;
    }

    public async Task<IReadOnlyList<PairingClaimSummary>> ListPendingClaimsAsync(Guid ownerId, CancellationToken cancellationToken = default)
    {
        var now = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var claims = await db.DevicePairingClaims.AsNoTracking()
            .Where(x => x.OwnerId == ownerId && x.ApprovedAtUnixMilliseconds == null &&
                        x.RejectedAtUnixMilliseconds == null && x.ExpiresAtUnixMilliseconds > now)
            .OrderBy(x => x.CreatedAtUnixMilliseconds)
            .ToListAsync(cancellationToken);
        return claims.Select(x => new PairingClaimSummary(
            x.Id, x.DeviceId, x.DeviceName, x.Platform, x.FingerprintSha256,
            x.ProofVerified,
            DateTimeOffset.FromUnixTimeMilliseconds(x.CreatedAtUnixMilliseconds),
            DateTimeOffset.FromUnixTimeMilliseconds(x.ExpiresAtUnixMilliseconds))).ToArray();
    }

    public async Task<bool> ApproveClaimAsync(Guid ownerId, Guid claimId, CancellationToken cancellationToken = default)
    {
        var now = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var claim = await db.DevicePairingClaims.SingleOrDefaultAsync(
            x => x.Id == claimId && x.OwnerId == ownerId, cancellationToken);
        if (claim is null || !claim.ProofVerified || claim.ApprovedAtUnixMilliseconds is not null ||
            claim.RejectedAtUnixMilliseconds is not null || claim.ExpiresAtUnixMilliseconds <= now)
            return false;

        if (await db.DeviceEnrollments.AnyAsync(
                x => x.FingerprintSha256 == claim.FingerprintSha256 || x.DeviceId == claim.DeviceId,
                cancellationToken))
            return false;

        db.DeviceEnrollments.Add(new DeviceEnrollment
        {
            DeviceId = claim.DeviceId,
            OwnerId = claim.OwnerId,
            DeviceName = claim.DeviceName,
            Platform = claim.Platform,
            PublicKeySpki = claim.PublicKeySpki,
            FingerprintSha256 = claim.FingerprintSha256,
            EnrolledAtUnixMilliseconds = now,
            LastSeenAtUnixMilliseconds = now
        });
        claim.ApprovedAtUnixMilliseconds = now;
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return true;
        }
        catch (DbUpdateException) { return false; }
    }

    public async Task<bool> RejectClaimAsync(Guid ownerId, Guid claimId, CancellationToken cancellationToken = default)
    {
        var now = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var claim = await db.DevicePairingClaims.SingleOrDefaultAsync(
            x => x.Id == claimId && x.OwnerId == ownerId, cancellationToken);
        if (claim is null || claim.ApprovedAtUnixMilliseconds is not null ||
            claim.RejectedAtUnixMilliseconds is not null)
            return false;
        claim.RejectedAtUnixMilliseconds = now;
        var invite = await db.PairingInvites.SingleOrDefaultAsync(x => x.Id == claim.InviteId, cancellationToken);
        if (invite is not null && invite.RevokedAtUnixMilliseconds is null)
            invite.RevokedAtUnixMilliseconds = now;
        await db.SaveChangesAsync(cancellationToken);
        return true;
    }

    public async Task<string?> GetClaimStatusAsync(Guid claimId, string claimSecret, CancellationToken cancellationToken = default)
    {
        if (!TryDecodeBase64Url(claimSecret, out var secretBytes) || secretBytes.Length != 32)
            return null;
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var claim = await db.DevicePairingClaims.AsNoTracking()
            .SingleOrDefaultAsync(x => x.Id == claimId, cancellationToken);
        if (claim is null) return null;
        if (!CryptographicOperations.FixedTimeEquals(SHA256.HashData(secretBytes), claim.ClaimSecretHash))
            return null;
        if (claim.RejectedAtUnixMilliseconds is not null) return "rejected";
        if (claim.ApprovedAtUnixMilliseconds is not null) return "approved";
        if (claim.ExpiresAtUnixMilliseconds <= _timeProvider.GetUtcNow().ToUnixTimeMilliseconds())
            return "expired";
        return claim.ProofVerified ? "awaiting_owner_approval" : "proof_required";
    }

    public async Task<IReadOnlyList<EnrolledDeviceSummary>> ListDevicesAsync(Guid ownerId, CancellationToken cancellationToken = default)
    {
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var devices = await db.DeviceEnrollments.AsNoTracking()
            .Where(x => x.OwnerId == ownerId)
            .OrderBy(x => x.EnrolledAtUnixMilliseconds)
            .ToListAsync(cancellationToken);
        return devices.Select(x => new EnrolledDeviceSummary(
            x.DeviceId, x.DeviceName, x.Platform, x.FingerprintSha256,
            DateTimeOffset.FromUnixTimeMilliseconds(x.EnrolledAtUnixMilliseconds),
            DateTimeOffset.FromUnixTimeMilliseconds(x.LastSeenAtUnixMilliseconds),
            x.RevokedAtUnixMilliseconds is not null)).ToArray();
    }

    public async Task<bool> RevokeDeviceAsync(Guid ownerId, Guid deviceId, CancellationToken cancellationToken = default)
    {
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var device = await db.DeviceEnrollments.SingleOrDefaultAsync(
            x => x.DeviceId == deviceId && x.OwnerId == ownerId, cancellationToken);
        if (device is null || device.RevokedAtUnixMilliseconds is not null) return false;
        device.RevokedAtUnixMilliseconds = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        await db.SaveChangesAsync(cancellationToken);
        return true;
    }

    public static byte[] CreateProofPayload(Guid inviteId, Guid claimId, Guid deviceId,
        string challenge, string platform, string deviceName, string fingerprintSha256)
    {
        var fields = new[]
        {
            "sam-device-pair-proof-v1", inviteId.ToString("D").ToLowerInvariant(),
            claimId.ToString("D").ToLowerInvariant(), deviceId.ToString("D").ToLowerInvariant(),
            challenge, platform, deviceName, fingerprintSha256
        };
        return Encoding.UTF8.GetBytes(string.Join("\n", fields));
    }

    private static bool IsSafeLabel(string? value, int maximumLength) =>
        !string.IsNullOrWhiteSpace(value) && value.Trim().Length <= maximumLength && !value.Any(char.IsControl);

    private static bool TryImportPublicKey(string? encoded, out byte[] subjectPublicKeyInfo)
    {
        subjectPublicKeyInfo = Array.Empty<byte>();
        if (string.IsNullOrWhiteSpace(encoded) || encoded.Length > 4096) return false;
        try
        {
            subjectPublicKeyInfo = Convert.FromBase64String(encoded);
            return subjectPublicKeyInfo.Length is > 0 and <= 2048;
        }
        catch (FormatException) { return false; }
    }

    private static byte[] HashPairingCode(byte[] salt, string code)
    {
        var input = new byte[salt.Length + code.Length];
        Buffer.BlockCopy(salt, 0, input, 0, salt.Length);
        Encoding.ASCII.GetBytes(code, input.AsSpan(salt.Length));
        return SHA256.HashData(input);
    }

    private static string EncodeBase64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static bool TryDecodeBase64Url(string? value, out byte[] bytes)
    {
        bytes = Array.Empty<byte>();
        if (string.IsNullOrWhiteSpace(value) || value.Length > 128 ||
            value.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '-' or '_'))) return false;
        var base64 = value.Replace('-', '+').Replace('_', '/');
        base64 += new string('=', (4 - base64.Length % 4) % 4);
        try { bytes = Convert.FromBase64String(base64); return true; }
        catch (FormatException) { return false; }
    }
}
