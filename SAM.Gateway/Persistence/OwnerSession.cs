namespace SAM.Gateway.Persistence;

/// <summary>Only a hash of the bearer token is stored; the raw token is returned once.</summary>
public sealed class OwnerSession
{
    public Guid Id { get; set; }
    public Guid OwnerId { get; set; }
    public OwnerAccount? Owner { get; set; }
    public string TokenHash { get; set; } = string.Empty;
    public long CreatedAtUnixMilliseconds { get; set; }
    public long ExpiresAtUnixMilliseconds { get; set; }
    public long? RevokedAtUnixMilliseconds { get; set; }
}
