using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using SAM.Gateway.Security;

namespace SAM.Gateway.Persistence;

/// <summary>
/// SQLite-backed anti-replay store. Database unique indexes, rather than an
/// in-process lock, share the replay boundary across DbContexts and restarts.
/// Run on a persistent volume and a single gateway node until a shared database
/// provider is configured for multi-node deployments.
/// </summary>
public sealed class EfCommandReplayStore(
    IDbContextFactory<SamGatewayDbContext> contextFactory,
    TimeProvider? timeProvider = null) : ICommandReplayStore
{
    private readonly TimeProvider _timeProvider = timeProvider ?? TimeProvider.System;

    public async ValueTask<bool> TryRegisterAsync(
        Guid targetDeviceId,
        Guid commandId,
        string nonce,
        DateTimeOffset expiresAt,
        CancellationToken cancellationToken = default)
    {
        var now = _timeProvider.GetUtcNow();
        if (targetDeviceId == Guid.Empty || commandId == Guid.Empty ||
            string.IsNullOrWhiteSpace(nonce) || nonce.Length > 128 ||
            expiresAt <= now)
            return false;

        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        db.CommandReplayEntries.Add(new CommandReplayEntry
        {
            CommandId = commandId,
            TargetDeviceId = targetDeviceId,
            Nonce = nonce,
            ExpiresAtUnixMilliseconds = expiresAt.ToUnixTimeMilliseconds(),
            RegisteredAtUnixMilliseconds = now.ToUnixTimeMilliseconds()
        });

        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return true;
        }
        catch (DbUpdateException ex) when (IsUniqueConstraintViolation(ex))
        {
            return false;
        }
    }

    private static bool IsUniqueConstraintViolation(Exception exception)
    {
        for (Exception? current = exception; current is not null; current = current.InnerException)
        {
            if (current is SqliteException sqlite &&
                (sqlite.SqliteExtendedErrorCode == 1555 || sqlite.SqliteExtendedErrorCode == 2067))
                return true;
        }
        return false;
    }
}
