var builder = WebApplication.CreateBuilder(args);
var app = builder.Build();

// This endpoint reports process liveness only. The gateway intentionally has
// no pairing, task, authentication, or remote-command routes yet.
app.MapGet("/health", () => Results.Ok(new
{
    status = "ok",
    service = "sam-gateway",
    readiness = "not-ready",
    gatewayReady = false,
    missingRequirements = new[]
    {
        "owner authentication and session management",
        "durable device registry and revocation",
        "persistent replay protection and audit storage",
        "rate limits and production HTTPS configuration"
    }
}));

app.Run();

public partial class Program { }
