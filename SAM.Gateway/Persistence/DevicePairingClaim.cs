namespace SAM.Gateway.Persistence;

public sealed class DevicePairingClaim
{
    public Guid Id { get; set; }
    public Guid InviteId { get; set; }
    public Guid OwnerId { get; set; }
    public Guid DeviceId { get; set; }
    public string DeviceName { get; set; } = string.Empty;
    public string Platform { get; set; } = string.Empty;
    public byte[] PublicKeySpki { get; set; } = Array.Empty<byte>();
    public string FingerprintSha256 { get; set; } = string.Empty;
    public byte[] ProofChallenge { get; set; } = Array.Empty<byte>();
    public byte[] ClaimSecretHash { get; set; } = Array.Empty<byte>();
    public long CreatedAtUnixMilliseconds { get; set; }
    public long ExpiresAtUnixMilliseconds { get; set; }
    public bool ProofVerified { get; set; }
    public long? ApprovedAtUnixMilliseconds { get; set; }
    public long? RejectedAtUnixMilliseconds { get; set; }
    public long? DeviceSessionIssuedAtUnixMilliseconds { get; set; }
}
