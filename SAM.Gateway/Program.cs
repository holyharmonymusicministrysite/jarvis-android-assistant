using System.Security.Cryptography;
using System.Text;
using System.Threading.RateLimiting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.AspNetCore.RateLimiting;
using SAM.Gateway.Persistence;
using SAM.Gateway.Security;

var builder = WebApplication.CreateBuilder(args);

var connectionString = builder.Configuration.GetConnectionString("SamGateway")
    ?? "Data Source=sam-gateway.db;Cache=Shared;Default Timeout=5";

builder.Services.AddDbContextFactory<SamGatewayDbContext>(options =>
    options.UseSqlite(connectionString));
builder.Services.AddSingleton<TimeProvider>(TimeProvider.System);
builder.Services.AddSingleton<OwnerAuthenticationService>();
builder.Services.AddSingleton<ICommandReplayStore, EfCommandReplayStore>();
builder.Services.AddSingleton<ICommandApprovalVerifier, EfCommandApprovalVerifier>();
builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.AddPolicy("owner-login", context =>
        RateLimitPartition.GetFixedWindowLimiter(
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = 5,
                Window = TimeSpan.FromMinutes(1),
                QueueLimit = 0,
                AutoReplenishment = true
            }));
    options.AddPolicy("owner-setup", context =>
        RateLimitPartition.GetFixedWindowLimiter(
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = 3,
                Window = TimeSpan.FromMinutes(15),
                QueueLimit = 0,
                AutoReplenishment = true
            }));
});

var app = builder.Build();
app.UseRateLimiter();

// Initialize the schema before startup. Readiness remains false until device
// enrollment/revocation, approval management and audit controls exist.
await using (var scope = app.Services.CreateAsyncScope())
{
    var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<SamGatewayDbContext>>();
    await using var db = await factory.CreateDbContextAsync();
    await db.Database.EnsureCreatedAsync();
}

app.MapPost("/api/v1/setup/owner", async (
    HttpContext context,
    OwnerAuthenticationService authentication,
    IConfiguration configuration,
    OwnerSetupRequest request,
    CancellationToken cancellationToken) =>
{
    var configuredSecret = configuration["SAM_OWNER_SETUP_SECRET"];
    if (string.IsNullOrWhiteSpace(configuredSecret) || Encoding.UTF8.GetByteCount(configuredSecret) < 32)
        return Results.Problem("Owner setup is disabled. Configure a 32-byte-or-longer SAM_OWNER_SETUP_SECRET.", statusCode: 503);

    var suppliedSecret = context.Request.Headers["X-SAM-Setup-Secret"].ToString();
    var expectedHash = SHA256.HashData(Encoding.UTF8.GetBytes(configuredSecret));
    var suppliedHash = SHA256.HashData(Encoding.UTF8.GetBytes(suppliedSecret));
    if (!CryptographicOperations.FixedTimeEquals(expectedHash, suppliedHash))
        return Results.Unauthorized();

    try
    {
        var created = await authentication.SetupOwnerAsync(
            request.Email, request.DisplayName, request.Password, cancellationToken);
        if (!created)
            return Results.Conflict(new { code = "owner_already_configured" });

        var login = await authentication.LoginAsync(request.Email, request.Password, cancellationToken);
        if (login is null)
            return Results.Problem("Owner account was created but a session could not be issued.", statusCode: 500);

        return Results.Created("/api/v1/auth/me", new
        {
            accessToken = login.AccessToken,
            tokenType = "Bearer",
            expiresAt = login.ExpiresAt,
            owner = login.Owner
        });
    }
    catch (ArgumentException ex)
    {
        return Results.BadRequest(new { code = "invalid_owner_details", message = ex.Message });
    }
}).RequireRateLimiting("owner-setup");

app.MapPost("/api/v1/auth/login", async (
    OwnerAuthenticationService authentication,
    OwnerLoginRequest request,
    CancellationToken cancellationToken) =>
{
    var login = await authentication.LoginAsync(request.Email, request.Password, cancellationToken);
    if (login is null) return Results.Unauthorized();

    return Results.Ok(new
    {
        accessToken = login.AccessToken,
        tokenType = "Bearer",
        expiresAt = login.ExpiresAt,
        owner = login.Owner
    });
}).RequireRateLimiting("owner-login");

app.MapGet("/api/v1/auth/me", async (
    HttpContext context,
    OwnerAuthenticationService authentication,
    CancellationToken cancellationToken) =>
{
    if (!OwnerAuthenticationService.TryExtractBearerToken(
            context.Request.Headers.Authorization.ToString(), out var token))
        return Results.Unauthorized();

    var owner = await authentication.ValidateSessionAsync(token, cancellationToken);
    return owner is null
        ? Results.Unauthorized()
        : Results.Ok(new { owner.OwnerId, owner.Email, owner.DisplayName, owner.SessionId });
});

app.MapPost("/api/v1/auth/logout", async (
    HttpContext context,
    OwnerAuthenticationService authentication,
    CancellationToken cancellationToken) =>
{
    if (!OwnerAuthenticationService.TryExtractBearerToken(
            context.Request.Headers.Authorization.ToString(), out var token))
        return Results.Unauthorized();

    var revoked = await authentication.RevokeSessionAsync(token, cancellationToken);
    return revoked ? Results.Ok(new { status = "signed_out" }) : Results.Unauthorized();
});

// Liveness only. Authentication exists, but the service is not ready until
// devices, revocation, approvals, audit and deployment hardening are complete.
app.MapGet("/health", () => Results.Ok(new
{
    status = "ok",
    service = "sam-gateway",
    storage = "sqlite-schema-initialized",
    readiness = "not-ready",
    gatewayReady = false,
    missingRequirements = new[]
    {
        "authenticated device enrollment, public-key ownership and revocation",
        "owner-authenticated approval creation and revocation endpoints",
        "tamper-evident audit event storage and retention controls",
        "rate limits for all sensitive routes, production HTTPS, backups and monitoring",
        "versioned schema migrations and a production persistent database configuration"
    }
}));

app.Run();

public sealed record OwnerSetupRequest(string Email, string DisplayName, string Password);
public sealed record OwnerLoginRequest(string Email, string Password);

public partial class Program { }
