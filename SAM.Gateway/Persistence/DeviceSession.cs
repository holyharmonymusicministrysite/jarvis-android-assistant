namespace SAM.Gateway.Persistence;

/// <summary>Opaque bearer session issued only after owner-approved device pairing.</summary>
public sealed class DeviceSession
{
    public Guid Id { get; set; }
    public Guid ClaimId { get; set; }
    public Guid DeviceId { get; set; }
    public string TokenHash { get; set; } = string.Empty;
    public long CreatedAtUnixMilliseconds { get; set; }
    public long ExpiresAtUnixMilliseconds { get; set; }
    public long? RevokedAtUnixMilliseconds { get; set; }
}
