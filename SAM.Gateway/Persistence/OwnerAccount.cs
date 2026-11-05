namespace SAM.Gateway.Persistence;

public sealed class OwnerAccount
{
    public static readonly Guid SingletonOwnerId = Guid.Parse("00000000-0000-4000-8000-000000000001");

    public Guid Id { get; set; } = SingletonOwnerId;
    public string Email { get; set; } = string.Empty;
    public string NormalizedEmail { get; set; } = string.Empty;
    public string DisplayName { get; set; } = string.Empty;
    public byte[] PasswordSalt { get; set; } = Array.Empty<byte>();
    public byte[] PasswordHash { get; set; } = Array.Empty<byte>();
    public int PasswordIterations { get; set; }
    public long CreatedAtUnixMilliseconds { get; set; }
}
