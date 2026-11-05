namespace SAM.Gateway.Persistence;

/// <summary>
/// Authoritative approval row written by a future owner-authenticated workflow.
/// No public endpoint can create or revoke these records yet.
/// </summary>
public sealed class OwnerCommandApprovalRecord
{
    public Guid ApprovalId { get; set; }
    public Guid TargetDeviceId { get; set; }
    public Guid CommandId { get; set; }
    public string Action { get; set; } = string.Empty;
    public int DelaySeconds { get; set; }
    public long IssuedAtUnixMilliseconds { get; set; }
    public long ExpiresAtUnixMilliseconds { get; set; }
    public long ApprovedAtUnixMilliseconds { get; set; }
    public long? RevokedAtUnixMilliseconds { get; set; }
}
