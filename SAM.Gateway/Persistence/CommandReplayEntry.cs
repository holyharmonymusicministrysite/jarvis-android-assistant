namespace SAM.Gateway.Persistence;

/// <summary>
/// One-time command/nonce registration. CommandId and Nonce are globally unique.
/// Rows remain after expiry so stale IDs/nonces cannot become valid after a restart.
/// </summary>
public sealed class CommandReplayEntry
{
    public Guid CommandId { get; set; }
    public Guid TargetDeviceId { get; set; }
    public string Nonce { get; set; } = string.Empty;
    public long ExpiresAtUnixMilliseconds { get; set; }
    public long RegisteredAtUnixMilliseconds { get; set; }
}
