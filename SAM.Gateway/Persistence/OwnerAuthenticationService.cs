using System.Net.Mail;
using System.Security.Cryptography;
using System.Text;
using Microsoft.EntityFrameworkCore;

namespace SAM.Gateway.Persistence;

public sealed record OwnerProfile(Guid Id, string Email, string DisplayName);
public sealed record OwnerLoginResult(string AccessToken, DateTimeOffset ExpiresAt, OwnerProfile Owner);
public sealed record OwnerSessionPrincipal(Guid OwnerId, string Email, string DisplayName, Guid SessionId);

/// <summary>
/// Single-owner bootstrap and opaque bearer sessions. Passwords use PBKDF2-SHA256;
/// sessions use random 256-bit tokens and only SHA-256 token hashes are persisted.
/// There is intentionally no public owner-registration operation.
/// </summary>
public sealed class OwnerAuthenticationService(
    IDbContextFactory<SamGatewayDbContext> contextFactory,
    TimeProvider? timeProvider = null)
{
    private const int PasswordIterations = 600_000;
    private const int PasswordHashBytes = 32;
    private static readonly TimeSpan SessionLifetime = TimeSpan.FromHours(12);
    private static readonly byte[] DummySalt = Encoding.UTF8.GetBytes("SAM-auth-dummy-salt-v1-32bytes!!");
    private readonly TimeProvider _timeProvider = timeProvider ?? TimeProvider.System;

    public async Task<bool> IsConfiguredAsync(CancellationToken cancellationToken = default)
    {
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        return await db.OwnerAccounts.AnyAsync(x => x.Id == OwnerAccount.SingletonOwnerId, cancellationToken);
    }

    public async Task<bool> SetupOwnerAsync(
        string email,
        string displayName,
        string password,
        CancellationToken cancellationToken = default)
    {
        var normalizedEmail = NormalizeEmail(email);
        ValidateDisplayName(displayName);
        ValidatePassword(password);

        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        if (await db.OwnerAccounts.AnyAsync(x => x.Id == OwnerAccount.SingletonOwnerId, cancellationToken))
            return false;

        var salt = RandomNumberGenerator.GetBytes(16);
        var hash = HashPassword(password, salt, PasswordIterations);
        db.OwnerAccounts.Add(new OwnerAccount
        {
            Id = OwnerAccount.SingletonOwnerId,
            Email = email.Trim(),
            NormalizedEmail = normalizedEmail,
            DisplayName = displayName.Trim(),
            PasswordSalt = salt,
            PasswordHash = hash,
            PasswordIterations = PasswordIterations,
            CreatedAtUnixMilliseconds = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds()
        });

        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return true;
        }
        catch (DbUpdateException)
        {
            // A concurrent setup request can only win once because the owner ID
            // is a fixed singleton primary key and email is also unique.
            return false;
        }
    }

    public async Task<OwnerLoginResult?> LoginAsync(
        string? email,
        string? password,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrEmpty(password) || password.Length > 128)
        {
            _ = HashPassword("invalid-login-placeholder", DummySalt, PasswordIterations);
            return null;
        }

        string normalizedEmail;
        try { normalizedEmail = NormalizeEmail(email ?? string.Empty); }
        catch (ArgumentException)
        {
            _ = HashPassword(password, DummySalt, PasswordIterations);
            return null;
        }

        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var account = await db.OwnerAccounts
            .SingleOrDefaultAsync(x => x.Id == OwnerAccount.SingletonOwnerId &&
                                       x.NormalizedEmail == normalizedEmail, cancellationToken);

        if (account is null)
        {
            _ = HashPassword(password, DummySalt, PasswordIterations);
            return null;
        }

        var candidate = HashPassword(password, account.PasswordSalt, account.PasswordIterations);
        if (!CryptographicOperations.FixedTimeEquals(candidate, account.PasswordHash))
            return null;

        var tokenBytes = RandomNumberGenerator.GetBytes(32);
        var token = EncodeBase64Url(tokenBytes);
        var now = _timeProvider.GetUtcNow();
        var expiresAt = now.Add(SessionLifetime);
        db.OwnerSessions.Add(new OwnerSession
        {
            Id = Guid.NewGuid(),
            OwnerId = account.Id,
            TokenHash = Convert.ToHexString(SHA256.HashData(tokenBytes)),
            CreatedAtUnixMilliseconds = now.ToUnixTimeMilliseconds(),
            ExpiresAtUnixMilliseconds = expiresAt.ToUnixTimeMilliseconds()
        });
        await db.SaveChangesAsync(cancellationToken);

        return new OwnerLoginResult(
            token,
            expiresAt,
            new OwnerProfile(account.Id, account.Email, account.DisplayName));
    }

    public async Task<OwnerSessionPrincipal?> ValidateSessionAsync(
        string? accessToken,
        CancellationToken cancellationToken = default)
    {
        if (!TryDecodeBase64Url(accessToken, out var tokenBytes) || tokenBytes.Length != 32)
            return null;

        var tokenHash = Convert.ToHexString(SHA256.HashData(tokenBytes));
        var now = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        return await (
            from session in db.OwnerSessions.AsNoTracking()
            join owner in db.OwnerAccounts.AsNoTracking() on session.OwnerId equals owner.Id
            where session.TokenHash == tokenHash &&
                  session.RevokedAtUnixMilliseconds == null &&
                  session.ExpiresAtUnixMilliseconds > now
            select new OwnerSessionPrincipal(owner.Id, owner.Email, owner.DisplayName, session.Id)
        ).SingleOrDefaultAsync(cancellationToken);
    }

    public async Task<bool> RevokeSessionAsync(
        string? accessToken,
        CancellationToken cancellationToken = default)
    {
        if (!TryDecodeBase64Url(accessToken, out var tokenBytes) || tokenBytes.Length != 32)
            return false;

        var tokenHash = Convert.ToHexString(SHA256.HashData(tokenBytes));
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var session = await db.OwnerSessions.SingleOrDefaultAsync(
            x => x.TokenHash == tokenHash && x.RevokedAtUnixMilliseconds == null,
            cancellationToken);
        if (session is null) return false;

        session.RevokedAtUnixMilliseconds = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        await db.SaveChangesAsync(cancellationToken);
        return true;
    }

    public static bool TryExtractBearerToken(string? authorizationHeader, out string token)
    {
        token = string.Empty;
        if (string.IsNullOrWhiteSpace(authorizationHeader)) return false;
        const string prefix = "Bearer ";
        if (!authorizationHeader.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return false;
        token = authorizationHeader[prefix.Length..].Trim();
        return token.Length is > 0 and <= 128;
    }

    private static string NormalizeEmail(string email)
    {
        var value = email.Trim();
        if (value.Length is < 3 or > 320 || !MailAddress.TryCreate(value, out var parsed) ||
            !string.Equals(parsed.Address, value, StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Enter a valid email address.", nameof(email));
        return value.ToUpperInvariant();
    }

    private static void ValidateDisplayName(string displayName)
    {
        var value = displayName.Trim();
        if (value.Length is < 1 or > 64 || value.Any(char.IsControl))
            throw new ArgumentException("Display name must be between 1 and 64 printable characters.", nameof(displayName));
    }

    private static void ValidatePassword(string password)
    {
        if (password.Length is < 14 or > 128 || Encoding.UTF8.GetByteCount(password) > 1024)
            throw new ArgumentException("Password must be 14–128 characters.", nameof(password));
    }

    private static byte[] HashPassword(string password, byte[] salt, int iterations) =>
        Rfc2898DeriveBytes.Pbkdf2(password, salt, iterations, HashAlgorithmName.SHA256, PasswordHashBytes);

    private static string EncodeBase64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static bool TryDecodeBase64Url(string? value, out byte[] bytes)
    {
        bytes = Array.Empty<byte>();
        if (string.IsNullOrWhiteSpace(value) || value.Length > 128 ||
            value.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '-' or '_')))
            return false;
        var base64 = value.Replace('-', '+').Replace('_', '/');
        base64 += new string('=', (4 - base64.Length % 4) % 4);
        try
        {
            bytes = Convert.FromBase64String(base64);
            return true;
        }
        catch (FormatException) { return false; }
    }
}
