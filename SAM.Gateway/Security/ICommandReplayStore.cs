namespace SAM.Gateway.Security;

/// <summary>
/// Production implementations must atomically and durably reject a reused
/// command ID or nonce for a target device until the command has expired.
/// The gateway must fail closed if this store is unavailable.
/// </summary>
public interface ICommandReplayStore
{
    ValueTask<bool> TryRegisterAsync(
        Guid targetDeviceId,
        Guid commandId,
        string nonce,
        DateTimeOffset expiresAt,
        CancellationToken cancellationToken = default);
}
