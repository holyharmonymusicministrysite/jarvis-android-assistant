namespace SAM.Gateway.Security;

/// <summary>
/// Verifies a server-side owner approval bound to one exact target device,
/// command ID, action, parameters and validity window. Implementations must
/// read authoritative approval state; never trust a client-supplied boolean.
/// </summary>
public interface ICommandApprovalVerifier
{
    ValueTask<bool> IsApprovedAsync(
        CommandApprovalRequest request,
        CancellationToken cancellationToken = default);
}

/// <summary>The exact fields an owner approval must authorize.</summary>
public sealed record CommandApprovalRequest(
    Guid ApprovalId,
    Guid TargetDeviceId,
    Guid CommandId,
    string Action,
    int DelaySeconds,
    DateTimeOffset IssuedAt,
    DateTimeOffset ExpiresAt);
