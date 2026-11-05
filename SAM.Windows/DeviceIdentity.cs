using System.Security.Cryptography;

namespace SAM.Windows;

/// <summary>
/// Creates a per-Windows-user ECDSA P-256 signing identity in the Windows
/// Software Key Storage Provider. The private key is non-exportable.
/// This is local identity bootstrap only; it does not enroll with a server.
/// </summary>
internal sealed class DeviceIdentity : IDisposable
{
    private const string KeyName = "SAM.DeviceIdentity.v1";
    private static readonly CngProvider Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider;
    private readonly CngKey _key;

    private DeviceIdentity(CngKey key) => _key = key;

    public static DeviceIdentity? TryOpen()
    {
        if (!CngKey.Exists(KeyName, Provider, CngKeyOpenOptions.UserKey))
            return null;

        return new DeviceIdentity(CngKey.Open(KeyName, Provider, CngKeyOpenOptions.UserKey));
    }

    public static DeviceIdentity Create()
    {
        if (CngKey.Exists(KeyName, Provider, CngKeyOpenOptions.UserKey))
            throw new InvalidOperationException("This Windows user already has a SAM device identity.");

        var parameters = new CngKeyCreationParameters
        {
            Provider = Provider,
            KeyUsage = CngKeyUsages.Signing,
            ExportPolicy = CngExportPolicies.None,
            KeyCreationOptions = CngKeyCreationOptions.None
        };

        var key = CngKey.Create(CngAlgorithm.ECDsaP256, KeyName, parameters);
        return new DeviceIdentity(key);
    }

    public string ExportPublicKey()
    {
        using var ecdsa = new ECDsaCng(_key);
        return Convert.ToBase64String(ecdsa.ExportSubjectPublicKeyInfo());
    }

    public byte[] Sign(ReadOnlySpan<byte> data)
    {
        using var ecdsa = new ECDsaCng(_key);
        return ecdsa.SignData(data, HashAlgorithmName.SHA256);
    }

    public void Dispose() => _key.Dispose();
}
