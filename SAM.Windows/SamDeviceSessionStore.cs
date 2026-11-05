using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace SAM.Windows;

internal sealed record StoredSamDeviceSession(
    string GatewayUrl,
    string AccessToken,
    Guid DeviceId,
    string DeviceName,
    DateTimeOffset ExpiresAt);

/// <summary>Stores the device bearer token encrypted with Windows DPAPI for the current user.</summary>
internal static class SamDeviceSessionStore
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("SAM.Windows.device-session.v1");
    private static readonly string SessionPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "SAM", "device-session.bin");

    public static void Save(StoredSamDeviceSession session)
    {
        var json = JsonSerializer.SerializeToUtf8Bytes(session);
        var protectedBytes = ProtectedData.Protect(json, Entropy, DataProtectionScope.CurrentUser);
        var directory = Path.GetDirectoryName(SessionPath)
            ?? throw new InvalidOperationException("SAM session directory is unavailable.");
        Directory.CreateDirectory(directory);
        var temporaryPath = SessionPath + ".tmp";
        File.WriteAllBytes(temporaryPath, protectedBytes);
        File.Move(temporaryPath, SessionPath, true);
    }

    public static StoredSamDeviceSession? TryLoad()
    {
        if (!File.Exists(SessionPath)) return null;
        try
        {
            var protectedBytes = File.ReadAllBytes(SessionPath);
            var json = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.CurrentUser);
            return JsonSerializer.Deserialize<StoredSamDeviceSession>(json);
        }
        catch (Exception ex) when (ex is CryptographicException or IOException or JsonException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    public static void Delete()
    {
        try { if (File.Exists(SessionPath)) File.Delete(SessionPath); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
