using System.Net.Http.Json;
using System.Text.Json;

namespace SAM.Windows;

internal sealed record DeviceSessionCreated(string AccessToken, DateTimeOffset ExpiresAt, Guid DeviceId, string DeviceName);
internal sealed record DeviceSessionInfo(Guid SessionId, Guid OwnerId, Guid DeviceId, string DeviceName, string Platform, string FingerprintSha256, DateTimeOffset ExpiresAt);

internal sealed record PairingClaimStarted(
    Guid ClaimId,
    Guid DeviceId,
    string Challenge,
    string ClaimSecret,
    string ProofPayloadBase64,
    string FingerprintSha256,
    DateTimeOffset ExpiresAt);

/// <summary>HTTPS-only client for the proof step of SAM device pairing.</summary>
internal sealed class SamGatewayClient : IDisposable
{
    private readonly HttpClient _http = new(new HttpClientHandler { AllowAutoRedirect = false });
    private readonly Uri _baseUri;

    public SamGatewayClient(string baseUrl)
    {
        if (!Uri.TryCreate(baseUrl.TrimEnd('/') + "/", UriKind.Absolute, out var uri) ||
            uri.Scheme != Uri.UriSchemeHttps || string.IsNullOrWhiteSpace(uri.Host) ||
            !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) ||
            !string.IsNullOrEmpty(uri.Fragment))
            throw new ArgumentException("Enter a valid SAM gateway HTTPS address.", nameof(baseUrl));
        _baseUri = uri;
        _http.Timeout = TimeSpan.FromSeconds(20);
    }

    public async Task<PairingClaimStarted> StartClaimAsync(
        string pairingCode,
        string deviceName,
        string publicKeySpkiBase64,
        CancellationToken cancellationToken = default)
    {
        using var response = await _http.PostAsJsonAsync(
            new Uri(_baseUri, "api/v1/pairing/claims/start"),
            new { pairingCode, deviceName, platform = "windows", publicKeySpkiBase64 },
            cancellationToken);
        var body = await ReadBodyAsync(response, cancellationToken);
        return JsonSerializer.Deserialize<PairingClaimStarted>(body, JsonOptions)
            ?? throw new InvalidOperationException("SAM gateway returned an empty pairing response.");
    }

    public async Task ProveClaimAsync(
        Guid claimId, string signatureBase64Url, CancellationToken cancellationToken = default)
    {
        using var response = await _http.PostAsJsonAsync(
            new Uri(_baseUri, "api/v1/pairing/claims/" + claimId.ToString("D") + "/prove"),
            new { signatureBase64Url }, cancellationToken);
        _ = await ReadBodyAsync(response, cancellationToken);
    }

    public async Task<string> GetClaimStatusAsync(
        Guid claimId, string claimSecret, CancellationToken cancellationToken = default)
    {
        using var response = await _http.PostAsJsonAsync(
            new Uri(_baseUri, "api/v1/pairing/claims/" + claimId.ToString("D") + "/status"),
            new { claimSecret }, cancellationToken);
        var body = await ReadBodyAsync(response, cancellationToken);
        using var document = JsonDocument.Parse(body);
        return document.RootElement.GetProperty("status").GetString() ?? "unknown";
    }

    public async Task<DeviceSessionCreated> ExchangeDeviceSessionAsync(
        Guid claimId, string claimSecret, CancellationToken cancellationToken = default)
    {
        using var response = await _http.PostAsJsonAsync(
            new Uri(_baseUri, "api/v1/pairing/claims/" + claimId.ToString("D") + "/session"),
            new { claimSecret }, cancellationToken);
        var body = await ReadBodyAsync(response, cancellationToken);
        return JsonSerializer.Deserialize<DeviceSessionCreated>(body, JsonOptions)
            ?? throw new InvalidOperationException("SAM gateway returned an empty device session.");
    }

    public async Task<DeviceSessionInfo> GetDeviceMeAsync(
        string accessToken, CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(_baseUri, "api/v1/devices/me"));
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", accessToken);
        using var response = await _http.SendAsync(request, cancellationToken);
        var body = await ReadBodyAsync(response, cancellationToken);
        return JsonSerializer.Deserialize<DeviceSessionInfo>(body, JsonOptions)
            ?? throw new InvalidOperationException("SAM gateway returned an empty device identity.");
    }

    public async Task HeartbeatAsync(string accessToken, CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(_baseUri, "api/v1/devices/me/heartbeat"));
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", accessToken);
        using var response = await _http.SendAsync(request, cancellationToken);
        _ = await ReadBodyAsync(response, cancellationToken);
    }

    public async Task LogoutDeviceSessionAsync(string accessToken, CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(_baseUri, "api/v1/devices/me/logout"));
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", accessToken);
        using var response = await _http.SendAsync(request, cancellationToken);
        _ = await ReadBodyAsync(response, cancellationToken);
    }

    private static async Task<string> ReadBodyAsync(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            string message;
            try
            {
                using var document = JsonDocument.Parse(body);
                message = document.RootElement.TryGetProperty("detail", out var detail)
                    ? detail.GetString() ?? response.StatusCode.ToString()
                    : document.RootElement.TryGetProperty("code", out var code)
                        ? code.GetString() ?? response.StatusCode.ToString()
                        : response.StatusCode.ToString();
            }
            catch (JsonException) { message = response.StatusCode.ToString(); }
            throw new InvalidOperationException("SAM gateway: " + message);
        }
        return body;
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    public void Dispose() => _http.Dispose();
}
