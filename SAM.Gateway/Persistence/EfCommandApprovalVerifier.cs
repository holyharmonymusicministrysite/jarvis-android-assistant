using Microsoft.EntityFrameworkCore;
using SAM.Gateway.Security;

namespace SAM.Gateway.Persistence;

/// <summary>
/// Reads one authoritative approval row and compares every signed command field.
/// Missing, expired or revoked approvals fail closed.
/// </summary>
public sealed class EfCommandApprovalVerifier(
    IDbContextFactory<SamGatewayDbContext> contextFactory,
    TimeProvider? timeProvider = null) : ICommandApprovalVerifier
{
    private readonly TimeProvider _timeProvider = timeProvider ?? TimeProvider.System;

    public async ValueTask<bool> IsApprovedAsync(
        CommandApprovalRequest request,
        CancellationToken cancellationToken = default)
    {
        if (request.ApprovalId == Guid.Empty ||
            request.TargetDeviceId == Guid.Empty ||
            request.CommandId == Guid.Empty ||
            string.IsNullOrWhiteSpace(request.Action))
            return false;

        await using var db = await contextFactory.CreateDbContextAsync(cancellationToken);
        var approval = await db.OwnerCommandApprovals
            .AsNoTracking()
            .SingleOrDefaultAsync(x => x.ApprovalId == request.ApprovalId, cancellationToken);

        if (approval is null)
            return false;

        var now = _timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
        return approval.RevokedAtUnixMilliseconds is null &&
               approval.ApprovedAtUnixMilliseconds <= now &&
               approval.ExpiresAtUnixMilliseconds > now &&
               approval.TargetDeviceId == request.TargetDeviceId &&
               approval.CommandId == request.CommandId &&
               string.Equals(approval.Action, request.Action, StringComparison.Ordinal) &&
               approval.DelaySeconds == request.DelaySeconds &&
               approval.IssuedAtUnixMilliseconds == request.IssuedAt.ToUnixTimeMilliseconds() &&
               approval.ExpiresAtUnixMilliseconds == request.ExpiresAt.ToUnixTimeMilliseconds();
    }
}
