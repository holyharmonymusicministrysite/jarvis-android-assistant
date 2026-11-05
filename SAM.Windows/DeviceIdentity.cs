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

        var identity = new DeviceIdentity(CngKey.Open(KeyName, Provider, CngKeyOpenOptions.UserKey));
        identity.VerifyKeyPair();
        return identity;
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
        var identity = new DeviceIdentity(key);
        identity.VerifyKeyPair();
        return identity;
    }

    public string ExportPublicKey()
    {
        using var ecdsa = new ECDsaCng(_key);
        return Convert.ToBase64String(ecdsa.ExportSubjectPublicKeyInfo());
    }

    public string GetFingerprint()
    {
        using var ecdsa = new ECDsaCng(_key);
        var hash = SHA256.HashData(ecdsa.ExportSubjectPublicKeyInfo());
        return Convert.ToHexString(hash);
    }

    public byte[] Sign(ReadOnlySpan<byte> data)
    {
        using var ecdsa = new ECDsaCng(_key);
        return ecdsa.SignData(data, HashAlgorithmName.SHA256);
    }

    private void VerifyKeyPair()
    {
        var challenge = RandomNumberGenerator.GetBytes(32);
        var signature = Sign(challenge);
        using var verifier = ECDsa.Create();
        verifier.ImportSubjectPublicKeyInfo(Convert.FromBase64String(ExportPublicKey()), out _);
        if (!verifier.VerifyData(challenge, signature, HashAlgorithmName.SHA256))
        {
            Dispose();
            throw new CryptographicException("SAM device identity failed its signing self-test.");
        }
    }

    public void Dispose() => _key.Dispose();
}
