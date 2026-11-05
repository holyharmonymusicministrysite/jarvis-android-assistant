using Microsoft.EntityFrameworkCore;

namespace SAM.Gateway.Persistence;

public sealed record SharedTaskSummary(
    Guid Id, Guid OwnerId, Guid TargetDeviceId, string Title, string Instructions,
    string Status, int Version, string LastMessage,
    DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt, DateTimeOffset? CompletedAt);

public sealed record SharedTaskEventSummary(
    Guid Id, Guid TaskId, int Version, Guid? ActorDeviceId,
    string Status, string Message, DateTimeOffset CreatedAt);

public enum SharedTaskUpdateResult
{
    Updated,
    NotFound,
    VersionConflict,
    InvalidTransition
}

/// <summary>
/// Durable account-scoped task inbox. It synchronizes tasks and progress only;
/// it does not execute code, run tools, or claim that a project has been built.
/// </summary>
public sealed class SharedTaskService(
    IDbContextFactory<SamGatewayDbContext> contextFactory,
    TimeProvider? timeProvider = null)
{
    private readonly TimeProvider _timeProvider = timeProvider ?? TimeProvider.System;

    private static readonly HashSet<string> TerminalStates = new(StringComparer.Ordinal)
    {
        "COMPLETED", "FAILED", "CANCELLED"
    };

    private static readonly Dictionary<string, HashSet<string>> AllowedTransitions = new(StringComparer.Ordinal)
    {
        ["QUEUED"] = new(StringComparer.Ordinal) { "PLANNING", "FAILED", "CANCELLED" },
        ["PLANNING"] = new(StringComparer.Ordinal) { "RUNNING", "WAITING_FOR_APPROVAL", "FAILED", "CANCELLED" },
        ["WAITING_FOR_APPROVAL"] = new(StringComparer.Ordinal) { "FAILED", "CANCELLED" },
        ["RUNNING"] = new(StringComparer.Ordinal) { "VERIFYING", "WAITING_FOR_APPROVAL", "FAILED", "CANCELLED" },
        ["VERIFYING"] = new(StringComparer.Ordinal) { "RUNNING", "COMPLETED", "FAILED", "CANCELLED" }
    };

    public async Task<SharedTaskSummary?> CreateAsync(
        Guid ownerId, Guid targetDeviceId, string title, string instructions,
        CancellationToken cancellationToken = default)
    {
        var safeTitle = title?.Trim() ?? string.Empty;
        var safeInstructions = instructions?.Trim() ?? string.Empty;
        if (ownerId == Guid.Empty || targetDeviceId == Guid.Empty ||
            safeTitle.Length is < 1 or > 120 || safeInstructions.Length is < 1 or > 8000 ||
            safeTitle.Any(char.IsControl))
            return null;

        var now = _timeProvider.GetUtcNow();
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var targetExists = await db.DeviceEnrollments.AnyAsync(
            x => x.OwnerId == ownerId && x.DeviceId == targetDeviceId &&
                 x.RevokedAtUnixMilliseconds == null,
            cancellationToken);
        if (!targetExists) return null;

        var task = new SharedTask
        {
            Id = Guid.NewGuid(),
            OwnerId = ownerId,
            TargetDeviceId = targetDeviceId,
            Title = safeTitle,
            Instructions = safeInstructions,
            Status = "QUEUED",
            Version = 1,
            LastMessage = "Task created by the owner.",
            CreatedAtUnixMilliseconds = now.ToUnixTimeMilliseconds(),
            UpdatedAtUnixMilliseconds = now.ToUnixTimeMilliseconds()
        };
        db.SharedTasks.Add(task);
        db.SharedTaskEvents.Add(new SharedTaskEvent
        {
            Id = Guid.NewGuid(),
            TaskId = task.Id,
            Version = 1,
            Status = "QUEUED",
            Message = task.LastMessage,
            CreatedAtUnixMilliseconds = now.ToUnixTimeMilliseconds()
        });
        await db.SaveChangesAsync(cancellationToken);
        return ToSummary(task);
    }

    public async Task<IReadOnlyList<SharedTaskSummary>> GetOwnerTasksAsync(
        Guid ownerId, CancellationToken cancellationToken = default)
    {
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var tasks = await db.SharedTasks.AsNoTracking()
            .Where(x => x.OwnerId == ownerId)
            .OrderByDescending(x => x.UpdatedAtUnixMilliseconds)
            .Take(200)
            .ToListAsync(cancellationToken);
        return tasks.Select(ToSummary).ToArray();
    }

    public async Task<IReadOnlyList<SharedTaskSummary>> GetDeviceInboxAsync(
        Guid ownerId, Guid deviceId, CancellationToken cancellationToken = default)
    {
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var tasks = await db.SharedTasks.AsNoTracking()
            .Where(x => x.OwnerId == ownerId && x.TargetDeviceId == deviceId &&
                        !TerminalStates.Contains(x.Status))
            .OrderBy(x => x.CreatedAtUnixMilliseconds)
            .Take(100)
            .ToListAsync(cancellationToken);
        return tasks.Select(ToSummary).ToArray();
    }

    public async Task<IReadOnlyList<SharedTaskEventSummary>> GetEventsAsync(
        Guid ownerId, Guid taskId, CancellationToken cancellationToken = default)
    {
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var ownsTask = await db.SharedTasks.AnyAsync(
            x => x.Id == taskId && x.OwnerId == ownerId, cancellationToken);
        if (!ownsTask) return Array.Empty<SharedTaskEventSummary>();

        var events = await db.SharedTaskEvents.AsNoTracking()
            .Where(x => x.TaskId == taskId)
            .OrderBy(x => x.Version)
            .ToListAsync(cancellationToken);
        return events.Select(x => new SharedTaskEventSummary(
            x.Id, x.TaskId, x.Version, x.ActorDeviceId, x.Status, x.Message,
            DateTimeOffset.FromUnixTimeMilliseconds(x.CreatedAtUnixMilliseconds))).ToArray();
    }

    public async Task<SharedTaskUpdateResult> UpdateFromDeviceAsync(
        Guid deviceOwnerId, Guid deviceId, Guid taskId,
        int expectedVersion, string nextStatus, string message,
        CancellationToken cancellationToken = default)
    {
        var normalizedStatus = nextStatus?.Trim().ToUpperInvariant() ?? string.Empty;
        var safeMessage = message?.Trim() ?? string.Empty;
        if (safeMessage.Length > 2000 || safeMessage.Any(char.IsControl) ||
            !AllowedTransitions.ContainsKey(normalizedStatus) && !TerminalStates.Contains(normalizedStatus))
            return SharedTaskUpdateResult.InvalidTransition;

        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var task = await db.SharedTasks.SingleOrDefaultAsync(
            x => x.Id == taskId && x.OwnerId == deviceOwnerId && x.TargetDeviceId == deviceId,
            cancellationToken);
        if (task is null) return SharedTaskUpdateResult.NotFound;
        if (task.Version != expectedVersion) return SharedTaskUpdateResult.VersionConflict;
        if (TerminalStates.Contains(task.Status) ||
            !AllowedTransitions.TryGetValue(task.Status, out var allowed) ||
            !allowed.Contains(normalizedStatus))
            return SharedTaskUpdateResult.InvalidTransition;

        var now = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        task.Status = normalizedStatus;
        task.Version++;
        task.LastMessage = safeMessage;
        task.UpdatedAtUnixMilliseconds = now;
        if (TerminalStates.Contains(normalizedStatus))
            task.CompletedAtUnixMilliseconds = now;

        db.SharedTaskEvents.Add(new SharedTaskEvent
        {
            Id = Guid.NewGuid(),
            TaskId = task.Id,
            Version = task.Version,
            ActorDeviceId = deviceId,
            Status = normalizedStatus,
            Message = safeMessage,
            CreatedAtUnixMilliseconds = now
        });
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return SharedTaskUpdateResult.Updated;
        }
        catch (DbUpdateConcurrencyException)
        {
            return SharedTaskUpdateResult.VersionConflict;
        }
    }

    public async Task<SharedTaskUpdateResult> ApproveRunAsync(
        Guid ownerId, Guid taskId, int expectedVersion,
        CancellationToken cancellationToken = default)
    {
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var task = await db.SharedTasks.SingleOrDefaultAsync(
            x => x.Id == taskId && x.OwnerId == ownerId, cancellationToken);
        if (task is null) return SharedTaskUpdateResult.NotFound;
        if (task.Version != expectedVersion) return SharedTaskUpdateResult.VersionConflict;
        if (task.Status != "WAITING_FOR_APPROVAL") return SharedTaskUpdateResult.InvalidTransition;

        var now = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        task.Status = "RUNNING";
        task.Version++;
        task.LastMessage = "Owner approved the task to continue.";
        task.UpdatedAtUnixMilliseconds = now;
        db.SharedTaskEvents.Add(new SharedTaskEvent
        {
            Id = Guid.NewGuid(),
            TaskId = task.Id,
            Version = task.Version,
            Status = "RUNNING",
            Message = task.LastMessage,
            CreatedAtUnixMilliseconds = now
        });
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return SharedTaskUpdateResult.Updated;
        }
        catch (DbUpdateConcurrencyException)
        {
            return SharedTaskUpdateResult.VersionConflict;
        }
    }

    public async Task<SharedTaskUpdateResult> CancelAsync(
        Guid ownerId, Guid taskId, int expectedVersion,
        CancellationToken cancellationToken = default)
    {
        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var task = await db.SharedTasks.SingleOrDefaultAsync(
            x => x.Id == taskId && x.OwnerId == ownerId, cancellationToken);
        if (task is null) return SharedTaskUpdateResult.NotFound;
        if (task.Version != expectedVersion) return SharedTaskUpdateResult.VersionConflict;
        if (TerminalStates.Contains(task.Status)) return SharedTaskUpdateResult.InvalidTransition;

        var now = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        task.Status = "CANCELLED";
        task.Version++;
        task.LastMessage = "Task cancelled by the owner.";
        task.UpdatedAtUnixMilliseconds = now;
        task.CompletedAtUnixMilliseconds = now;
        db.SharedTaskEvents.Add(new SharedTaskEvent
        {
            Id = Guid.NewGuid(),
            TaskId = task.Id,
            Version = task.Version,
            Status = "CANCELLED",
            Message = task.LastMessage,
            CreatedAtUnixMilliseconds = now
        });
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return SharedTaskUpdateResult.Updated;
        }
        catch (DbUpdateConcurrencyException)
        {
            return SharedTaskUpdateResult.VersionConflict;
        }
    }

    private static SharedTaskSummary ToSummary(SharedTask task) => new(
        task.Id, task.OwnerId, task.TargetDeviceId, task.Title, task.Instructions,
        task.Status, task.Version, task.LastMessage,
        DateTimeOffset.FromUnixTimeMilliseconds(task.CreatedAtUnixMilliseconds),
        DateTimeOffset.FromUnixTimeMilliseconds(task.UpdatedAtUnixMilliseconds),
        task.CompletedAtUnixMilliseconds is null
            ? null
            : DateTimeOffset.FromUnixTimeMilliseconds(task.CompletedAtUnixMilliseconds.Value));
}
