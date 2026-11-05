namespace SAM.Gateway.Persistence;

public sealed class DeviceEnrollment
{
    public Guid DeviceId { get; set; }
    public Guid OwnerId { get; set; }
    public string DeviceName { get; set; } = string.Empty;
    public string Platform { get; set; } = string.Empty;
    public byte[] PublicKeySpki { get; set; } = Array.Empty<byte>();
    public string FingerprintSha256 { get; set; } = string.Empty;
    public long EnrolledAtUnixMilliseconds { get; set; }
    public long LastSeenAtUnixMilliseconds { get; set; }
    public long? RevokedAtUnixMilliseconds { get; set; }
}
