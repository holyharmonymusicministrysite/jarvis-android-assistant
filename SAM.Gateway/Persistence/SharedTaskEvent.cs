namespace SAM.Gateway.Persistence;

public sealed class SharedTaskEvent
{
    public Guid Id { get; set; }
    public Guid TaskId { get; set; }
    public int Version { get; set; }
    public Guid? ActorDeviceId { get; set; }
    public string Status { get; set; } = string.Empty;
    public string Message { get; set; } = string.Empty;
    public long CreatedAtUnixMilliseconds { get; set; }
}
