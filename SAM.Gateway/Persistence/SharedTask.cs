namespace SAM.Gateway.Persistence;

public sealed class SharedTask
{
    public Guid Id { get; set; }
    public Guid OwnerId { get; set; }
    public Guid TargetDeviceId { get; set; }
    public string Title { get; set; } = string.Empty;
    public string Instructions { get; set; } = string.Empty;
    public string Status { get; set; } = "QUEUED";
    public int Version { get; set; } = 1;
    public string LastMessage { get; set; } = string.Empty;
    public long CreatedAtUnixMilliseconds { get; set; }
    public long UpdatedAtUnixMilliseconds { get; set; }
    public long? CompletedAtUnixMilliseconds { get; set; }
}
