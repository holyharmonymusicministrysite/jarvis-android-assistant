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
builder.Services.AddSingleton<DevicePairingService>();
builder.Services.AddSingleton<SharedTaskService>();
builder.Services.AddSingleton<CompanyOperationsService>();
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
    options.AddPolicy("pairing-start", context =>
        RateLimitPartition.GetFixedWindowLimiter(
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions { PermitLimit = 5, Window = TimeSpan.FromMinutes(1), QueueLimit = 0, AutoReplenishment = true }));
    options.AddPolicy("pairing-proof", context =>
        RateLimitPartition.GetFixedWindowLimiter(
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions { PermitLimit = 10, Window = TimeSpan.FromMinutes(1), QueueLimit = 0, AutoReplenishment = true }));
    options.AddPolicy("pairing-status", context =>
        RateLimitPartition.GetFixedWindowLimiter(
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions { PermitLimit = 20, Window = TimeSpan.FromMinutes(1), QueueLimit = 0, AutoReplenishment = true }));
    options.AddPolicy("device-session-exchange", context =>
        RateLimitPartition.GetFixedWindowLimiter(
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions { PermitLimit = 5, Window = TimeSpan.FromMinutes(1), QueueLimit = 0, AutoReplenishment = true }));
    options.AddPolicy("tasks-create", context =>
        RateLimitPartition.GetFixedWindowLimiter(
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions { PermitLimit = 10, Window = TimeSpan.FromMinutes(1), QueueLimit = 0, AutoReplenishment = true }));
    options.AddPolicy("tasks-progress", context =>
        RateLimitPartition.GetFixedWindowLimiter(
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions { PermitLimit = 60, Window = TimeSpan.FromMinutes(1), QueueLimit = 0, AutoReplenishment = true }));
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
            context.Request.Headers["Authorization"].ToString(), out var token))
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
        "verify Android and Windows pairing/session flows on the actual Nokia G21 and HP EliteBook",
        "owner-authenticated approval creation and revocation for high-impact commands",
        "signed device command transport and visible shutdown/restart countdown with cancellation",
        "durable project-building execution worker with isolated tools and verified artifacts",
        "tamper-evident audit events, schema migrations and retention controls",
        "production HTTPS, persistent database backups, monitoring and rate limits for every sensitive route"
    }
}));


app.MapPost("/api/v1/pairing/invites", async (
    HttpContext context, OwnerAuthenticationService authentication, DevicePairingService pairing, CancellationToken cancellationToken) =>
{
    var owner = await ResolveOwnerAsync(context, authentication, cancellationToken);
    if (owner is null) return Results.Unauthorized();
    return Results.Ok(await pairing.CreateInviteAsync(owner.OwnerId, cancellationToken));
});

app.MapPost("/api/v1/pairing/claims/start", async (
    DevicePairingService pairing, PairingClaimStartRequest request, CancellationToken cancellationToken) =>
{
    var claim = await pairing.StartClaimAsync(request.PairingCode, request.DeviceName,
        request.Platform, request.PublicKeySpkiBase64, cancellationToken);
    return claim is null ? Results.Unauthorized() : Results.Ok(claim);
}).RequireRateLimiting("pairing-start");

app.MapPost("/api/v1/pairing/claims/{claimId:guid}/prove", async (
    Guid claimId, DevicePairingService pairing, PairingProofRequest request, CancellationToken cancellationToken) =>
{
    var valid = await pairing.ProveClaimAsync(claimId, request.SignatureBase64Url, cancellationToken);
    return valid ? Results.Ok(new { status = "proof_verified" }) : Results.Unauthorized();
}).RequireRateLimiting("pairing-proof");

app.MapPost("/api/v1/pairing/claims/{claimId:guid}/status", async (
    Guid claimId, DevicePairingService pairing, PairingClaimStatusRequest request, CancellationToken cancellationToken) =>
{
    var status = await pairing.GetClaimStatusAsync(claimId, request.ClaimSecret, cancellationToken);
    return status is null ? Results.Unauthorized() : Results.Ok(new { status });
}).RequireRateLimiting("pairing-status");

app.MapGet("/api/v1/pairing/claims", async (
    HttpContext context, OwnerAuthenticationService authentication, DevicePairingService pairing, CancellationToken cancellationToken) =>
{
    var owner = await ResolveOwnerAsync(context, authentication, cancellationToken);
    if (owner is null) return Results.Unauthorized();
    return Results.Ok(await pairing.ListPendingClaimsAsync(owner.OwnerId, cancellationToken));
});

app.MapPost("/api/v1/pairing/claims/{claimId:guid}/approve", async (
    Guid claimId, HttpContext context, OwnerAuthenticationService authentication,
    DevicePairingService pairing, CancellationToken cancellationToken) =>
{
    var owner = await ResolveOwnerAsync(context, authentication, cancellationToken);
    if (owner is null) return Results.Unauthorized();
    var approved = await pairing.ApproveClaimAsync(owner.OwnerId, claimId, cancellationToken);
    return approved ? Results.Ok(new { status = "device_enrolled" }) :
        Results.Conflict(new { code = "claim_not_approvable" });
});

app.MapPost("/api/v1/pairing/claims/{claimId:guid}/reject", async (
    Guid claimId, HttpContext context, OwnerAuthenticationService authentication,
    DevicePairingService pairing, CancellationToken cancellationToken) =>
{
    var owner = await ResolveOwnerAsync(context, authentication, cancellationToken);
    if (owner is null) return Results.Unauthorized();
    var rejected = await pairing.RejectClaimAsync(owner.OwnerId, claimId, cancellationToken);
    return rejected ? Results.Ok(new { status = "claim_rejected" }) : Results.NotFound();
});

app.MapPost("/api/v1/pairing/claims/{claimId:guid}/session", async (
    Guid claimId, DevicePairingService pairing, PairingClaimStatusRequest request, CancellationToken cancellationToken) =>
{
    var session = await pairing.ExchangeClaimForDeviceSessionAsync(claimId, request.ClaimSecret, cancellationToken);
    return session is null ? Results.Unauthorized() :
        Results.Ok(new { accessToken = session.AccessToken, tokenType = "Bearer", session.ExpiresAt, session.DeviceId, session.DeviceName });
}).RequireRateLimiting("device-session-exchange");

app.MapPost("/api/v1/tasks", async (
    HttpContext context, OwnerAuthenticationService authentication, SharedTaskService tasks,
    CreateSharedTaskRequest request, CancellationToken cancellationToken) =>
{
    var owner = await ResolveOwnerAsync(context, authentication, cancellationToken);
    if (owner is null) return Results.Unauthorized();
    var created = await tasks.CreateAsync(owner.OwnerId, request.TargetDeviceId,
        request.Title, request.Instructions, cancellationToken);
    return created is null
        ? Results.BadRequest(new { code = "invalid_task_or_target_device" })
        : Results.Created("/api/v1/tasks/" + created.Id.ToString("D"), created);
}).RequireRateLimiting("tasks-create");

app.MapGet("/api/v1/tasks", async (
    HttpContext context, OwnerAuthenticationService authentication, SharedTaskService tasks,
    CancellationToken cancellationToken) =>
{
    var owner = await ResolveOwnerAsync(context, authentication, cancellationToken);
    if (owner is null) return Results.Unauthorized();
    return Results.Ok(await tasks.GetOwnerTasksAsync(owner.OwnerId, cancellationToken));
});

app.MapGet("/api/v1/tasks/{taskId:guid}/events", async (
    Guid taskId, HttpContext context, OwnerAuthenticationService authentication,
    SharedTaskService tasks, CancellationToken cancellationToken) =>
{
    var owner = await ResolveOwnerAsync(context, authentication, cancellationToken);
    if (owner is null) return Results.Unauthorized();
    return Results.Ok(await tasks.GetEventsAsync(owner.OwnerId, taskId, cancellationToken));
});

app.MapGet("/api/v1/tasks/inbox", async (
    HttpContext context, DevicePairingService pairing, SharedTaskService tasks, CancellationToken cancellationToken) =>
{
    var device = await ResolveDeviceAsync(context, pairing, cancellationToken);
    if (device is null) return Results.Unauthorized();
    return Results.Ok(await tasks.GetDeviceInboxAsync(device.OwnerId, device.DeviceId, cancellationToken));
});

app.MapPost("/api/v1/tasks/{taskId:guid}/progress", async (
    Guid taskId, HttpContext context, DevicePairingService pairing,
    SharedTaskService tasks, TaskProgressRequest request, CancellationToken cancellationToken) =>
{
    var device = await ResolveDeviceAsync(context, pairing, cancellationToken);
    if (device is null) return Results.Unauthorized();
    var result = await tasks.UpdateFromDeviceAsync(
        device.OwnerId, device.DeviceId, taskId, request.ExpectedVersion,
        request.Status, request.Message, cancellationToken);
    return result switch
    {
        SharedTaskUpdateResult.Updated => Results.Ok(new { status = "updated" }),
        SharedTaskUpdateResult.NotFound => Results.NotFound(),
        SharedTaskUpdateResult.VersionConflict => Results.Conflict(new { code = "task_version_conflict" }),
        _ => Results.BadRequest(new { code = "invalid_task_transition" })
    };
}).RequireRateLimiting("tasks-progress");

app.MapPost("/api/v1/tasks/{taskId:guid}/approve-run", async (
    Guid taskId, HttpContext context, OwnerAuthenticationService authentication,
    SharedTaskService tasks, TaskVersionRequest request, CancellationToken cancellationToken) =>
{
    var owner = await ResolveOwnerAsync(context, authentication, cancellationToken);
    if (owner is null) return Results.Unauthorized();
    var result = await tasks.ApproveRunAsync(owner.OwnerId, taskId, request.ExpectedVersion, cancellationToken);
    return result switch
    {
        SharedTaskUpdateResult.Updated => Results.Ok(new { status = "owner_approved" }),
        SharedTaskUpdateResult.NotFound => Results.NotFound(),
        SharedTaskUpdateResult.VersionConflict => Results.Conflict(new { code = "task_version_conflict" }),
        _ => Results.BadRequest(new { code = "task_not_waiting_for_approval" })
    };
});

app.MapPost("/api/v1/tasks/{taskId:guid}/cancel", async (
    Guid taskId, HttpContext context, OwnerAuthenticationService authentication,
    SharedTaskService tasks, TaskVersionRequest request, CancellationToken cancellationToken) =>
{
    var owner = await ResolveOwnerAsync(context, authentication, cancellationToken);
    if (owner is null) return Results.Unauthorized();
    var result = await tasks.CancelAsync(owner.OwnerId, taskId, request.ExpectedVersion, cancellationToken);
    return result switch
    {
        SharedTaskUpdateResult.Updated => Results.Ok(new { status = "cancelled" }),
        SharedTaskUpdateResult.NotFound => Results.NotFound(),
        SharedTaskUpdateResult.VersionConflict => Results.Conflict(new { code = "task_version_conflict" }),
        _ => Results.BadRequest(new { code = "task_already_terminal" })
    };
});

app.MapGet("/api/v1/devices/me", async (
    HttpContext context, DevicePairingService pairing, CancellationToken cancellationToken) =>
{
    var device = await ResolveDeviceAsync(context, pairing, cancellationToken);
    return device is null ? Results.Unauthorized() :
        Results.Ok(new { device.SessionId, device.OwnerId, device.DeviceId, device.DeviceName, device.Platform, device.FingerprintSha256, device.ExpiresAt });
});

app.MapPost("/api/v1/devices/me/heartbeat", async (
    HttpContext context, DevicePairingService pairing, CancellationToken cancellationToken) =>
{
    var device = await ResolveDeviceAsync(context, pairing, cancellationToken);
    return device is null ? Results.Unauthorized() :
        Results.Ok(new { status = "online", device.DeviceId, serverTime = DateTimeOffset.UtcNow });
});

app.MapPost("/api/v1/devices/me/logout", async (
    HttpContext context, DevicePairingService pairing, CancellationToken cancellationToken) =>
{
    if (!OwnerAuthenticationService.TryExtractBearerToken(context.Request.Headers["Authorization"].ToString(), out var token))
        return Results.Unauthorized();
    var revoked = await pairing.RevokeDeviceSessionAsync(token, cancellationToken);
    return revoked ? Results.Ok(new { status = "device_session_revoked" }) : Results.Unauthorized();
});

app.MapGet("/api/v1/devices", async (
    HttpContext context, OwnerAuthenticationService authentication, DevicePairingService pairing, CancellationToken cancellationToken) =>
{
    var owner = await ResolveOwnerAsync(context, authentication, cancellationToken);
    if (owner is null) return Results.Unauthorized();
    return Results.Ok(await pairing.ListDevicesAsync(owner.OwnerId, cancellationToken));
});

app.MapPost("/api/v1/devices/{deviceId:guid}/revoke", async (
    Guid deviceId, HttpContext context, OwnerAuthenticationService authentication,
    DevicePairingService pairing, CancellationToken cancellationToken) =>
{
    var owner = await ResolveOwnerAsync(context, authentication, cancellationToken);
    if (owner is null) return Results.Unauthorized();
    var revoked = await pairing.RevokeDeviceAsync(owner.OwnerId, deviceId, cancellationToken);
    return revoked ? Results.Ok(new { status = "device_revoked" }) : Results.NotFound();
});


app.MapGet("/api/v1/company/dashboard", async (HttpContext context, OwnerAuthenticationService auth, CompanyOperationsService company, CancellationToken ct) =>
{
    var owner = await ResolveOwnerAsync(context, auth, ct);
    return owner is null ? Results.Unauthorized() : Results.Ok(await company.DashboardAsync(owner.OwnerId, ct));
});
app.MapPost("/api/v1/company/customers", async (HttpContext context, OwnerAuthenticationService auth, CompanyOperationsService company, CreateCompanyCustomerRequest request, CancellationToken ct) =>
{
    var owner = await ResolveOwnerAsync(context, auth, ct);
    if (owner is null) return Results.Unauthorized();
    var customer = await company.CreateCustomerAsync(owner.OwnerId, request, ct);
    return customer is null ? Results.BadRequest(new { code = "invalid_customer_details" }) : Results.Created("/api/v1/company/customers/" + customer.Id, customer);
});
app.MapGet("/api/v1/company/customers", async (HttpContext context, OwnerAuthenticationService auth, CompanyOperationsService company, CancellationToken ct) =>
{
    var owner = await ResolveOwnerAsync(context, auth, ct);
    return owner is null ? Results.Unauthorized() : Results.Ok(await company.CustomersAsync(owner.OwnerId, ct));
});
app.MapPost("/api/v1/company/projects", async (HttpContext context, OwnerAuthenticationService auth, CompanyOperationsService company, CreateCompanyProjectRequest request, CancellationToken ct) =>
{
    var owner = await ResolveOwnerAsync(context, auth, ct);
    if (owner is null) return Results.Unauthorized();
    var project = await company.CreateProjectAsync(owner.OwnerId, request, ct);
    return project is null ? Results.BadRequest(new { code = "invalid_project_or_customer" }) : Results.Created("/api/v1/company/projects/" + project.Id, project);
});
app.MapPost("/api/v1/company/projects/{id:guid}/status", async (Guid id, HttpContext context, OwnerAuthenticationService auth, CompanyOperationsService company, CompanyProjectStatusRequest request, CancellationToken ct) =>
{
    var owner = await ResolveOwnerAsync(context, auth, ct);
    if (owner is null) return Results.Unauthorized();
    var result = await company.ChangeProjectStatusAsync(owner.OwnerId, id, request.Status, ct);
    return result switch { CompanyOperationResult.Updated => Results.Ok(new { status = "updated" }), CompanyOperationResult.NotFound => Results.NotFound(), CompanyOperationResult.Conflict => Results.Conflict(new { code = "invalid_project_transition" }), _ => Results.BadRequest(new { code = "invalid_project_status" }) };
});
app.MapPost("/api/v1/company/quotes", async (HttpContext context, OwnerAuthenticationService auth, CompanyOperationsService company, CreateCompanyQuoteRequest request, CancellationToken ct) =>
{
    var owner = await ResolveOwnerAsync(context, auth, ct);
    if (owner is null) return Results.Unauthorized();
    var quote = await company.CreateQuoteAsync(owner.OwnerId, request, ct);
    return quote is null ? Results.BadRequest(new { code = "invalid_quote_or_customer" }) : Results.Created("/api/v1/company/quotes/" + quote.Id, quote);
});
app.MapGet("/api/v1/company/quotes", async (HttpContext context, OwnerAuthenticationService auth, CompanyOperationsService company, CancellationToken ct) =>
{
    var owner = await ResolveOwnerAsync(context, auth, ct);
    return owner is null ? Results.Unauthorized() : Results.Ok(await company.QuotesAsync(owner.OwnerId, ct));
});
app.MapPost("/api/v1/company/quotes/{id:guid}/issue", async (Guid id, HttpContext context, OwnerAuthenticationService auth, CompanyOperationsService company, CancellationToken ct) =>
{
    var owner = await ResolveOwnerAsync(context, auth, ct);
    if (owner is null) return Results.Unauthorized();
    var result = await company.IssueQuoteAsync(owner.OwnerId, id, ct);
    return result switch { CompanyOperationResult.Updated => Results.Ok(new { status = "issued" }), CompanyOperationResult.NotFound => Results.NotFound(), CompanyOperationResult.Conflict => Results.Conflict(new { code = "quote_not_draft" }), _ => Results.BadRequest(new { code = "quote_expired" }) };
});

static async Task<DeviceSessionPrincipal?> ResolveDeviceAsync(
    HttpContext context, DevicePairingService pairing, CancellationToken cancellationToken)
{
    if (!OwnerAuthenticationService.TryExtractBearerToken(context.Request.Headers["Authorization"].ToString(), out var token))
        return null;
    return await pairing.ValidateDeviceSessionAsync(token, cancellationToken);
}

static async Task<OwnerSessionPrincipal?> ResolveOwnerAsync(
    HttpContext context, OwnerAuthenticationService authentication, CancellationToken cancellationToken)
{
    if (!OwnerAuthenticationService.TryExtractBearerToken(context.Request.Headers["Authorization"].ToString(), out var token))
        return null;
    return await authentication.ValidateSessionAsync(token, cancellationToken);
}

app.Run();

public sealed record OwnerSetupRequest(string Email, string DisplayName, string Password);
public sealed record OwnerLoginRequest(string Email, string Password);
public sealed record PairingClaimStartRequest(string PairingCode, string DeviceName, string Platform, string PublicKeySpkiBase64);
public sealed record PairingProofRequest(string SignatureBase64Url);
public sealed record PairingClaimStatusRequest(string ClaimSecret);
public sealed record CreateSharedTaskRequest(Guid TargetDeviceId, string Title, string Instructions);
public sealed record TaskProgressRequest(int ExpectedVersion, string Status, string Message);
public sealed record TaskVersionRequest(int ExpectedVersion);
public sealed record CompanyProjectStatusRequest(string Status);

public partial class Program { }
