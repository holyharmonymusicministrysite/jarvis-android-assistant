using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using SAM.Gateway.Persistence;
using SAM.Gateway.Security;

var builder = WebApplication.CreateBuilder(args);

var connectionString = builder.Configuration.GetConnectionString("SamGateway")
    ?? "Data Source=sam-gateway.db;Cache=Shared;Default Timeout=5";

builder.Services.AddDbContextFactory<SamGatewayDbContext>(options =>
    options.UseSqlite(connectionString));
builder.Services.AddSingleton<TimeProvider>(TimeProvider.System);
builder.Services.AddSingleton<ICommandReplayStore, EfCommandReplayStore>();
builder.Services.AddSingleton<ICommandApprovalVerifier, EfCommandApprovalVerifier>();

var app = builder.Build();

// Initialize the first schema before startup. Readiness remains false until
// owner authentication, device enrollment/revocation and audit controls exist.
await using (var scope = app.Services.CreateAsyncScope())
{
    var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<SamGatewayDbContext>>();
    await using var db = await factory.CreateDbContextAsync();
    await db.Database.EnsureCreatedAsync();
}

// Liveness only. This is not a production readiness or pairing endpoint.
app.MapGet("/health", () => Results.Ok(new
{
    status = "ok",
    service = "sam-gateway",
    storage = "sqlite-schema-initialized",
    readiness = "not-ready",
    gatewayReady = false,
    missingRequirements = new[]
    {
        "owner authentication and secure session management",
        "authenticated device enrollment, public-key ownership and revocation",
        "owner-authenticated approval creation and revocation endpoints",
        "tamper-evident audit event storage and retention controls",
        "rate limits, production HTTPS configuration, backups and monitoring"
    }
}));

app.Run();

public partial class Program { }
