namespace SAM.Gateway.Persistence;

public sealed class PairingInvite
{
    public Guid Id { get; set; }
    public Guid OwnerId { get; set; }
    public byte[] CodeSalt { get; set; } = Array.Empty<byte>();
    public byte[] CodeHash { get; set; } = Array.Empty<byte>();
    public long CreatedAtUnixMilliseconds { get; set; }
    public long ExpiresAtUnixMilliseconds { get; set; }
    public long? ClaimedAtUnixMilliseconds { get; set; }
    public long? RevokedAtUnixMilliseconds { get; set; }
}
