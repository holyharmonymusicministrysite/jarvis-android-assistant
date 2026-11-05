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

        return CreateVerified(CngKey.Open(KeyName, Provider, CngKeyOpenOptions.UserKey));
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

        return CreateVerified(CngKey.Create(CngAlgorithm.ECDsaP256, KeyName, parameters));
    }

    private static DeviceIdentity CreateVerified(CngKey key)
    {
        var identity = new DeviceIdentity(key);
        try
        {
            identity.VerifyKeyPair();
            return identity;
        }
        catch
        {
            identity.Dispose();
            throw;
        }
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

    public static string CreateNonce()
    {
        var bytes = RandomNumberGenerator.GetBytes(24);
        return Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }

    public string SignCommand(SamDeviceCommand command)
    {
        var payload = SamDeviceCommandCanonicalizer.CreateSigningPayload(command);
        var signature = Sign(payload);
        return Convert.ToBase64String(signature).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }

    public byte[] Sign(ReadOnlySpan<byte> data)
    {
        using var ecdsa = new ECDsaCng(_key);
        return ecdsa.SignData(
            data,
            HashAlgorithmName.SHA256,
            DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
    }

    private void VerifyKeyPair()
    {
        var challenge = RandomNumberGenerator.GetBytes(32);
        var signature = Sign(challenge);
        using var verifier = ECDsa.Create();
        verifier.ImportSubjectPublicKeyInfo(Convert.FromBase64String(ExportPublicKey()), out _);
        if (!verifier.VerifyData(challenge, signature, HashAlgorithmName.SHA256))
            throw new CryptographicException("SAM device identity failed its signing self-test.");

        SamDeviceCommandCanonicalizer.VerifyKnownVector();
        var sampleCommand = SamDeviceCommandCanonicalizer.CreateKnownVector();
        var samplePayload = SamDeviceCommandCanonicalizer.CreateSigningPayload(sampleCommand);
        var wireSignature = SignCommand(sampleCommand);
        var signatureBytes = DecodeBase64Url(wireSignature);
        using var commandVerifier = ECDsa.Create();
        commandVerifier.ImportSubjectPublicKeyInfo(Convert.FromBase64String(ExportPublicKey()), out _);
        if (!commandVerifier.VerifyData(
                samplePayload,
                signatureBytes,
                HashAlgorithmName.SHA256,
                DSASignatureFormat.IeeeP1363FixedFieldConcatenation))
            throw new CryptographicException("SAM Windows command-signature self-test failed.");
    }

    private static byte[] DecodeBase64Url(string value)
    {
        var base64 = value.Replace('-', '+').Replace('_', '/');
        base64 += new string('=', (4 - base64.Length % 4) % 4);
        return Convert.FromBase64String(base64);
    }

    public void Dispose() => _key.Dispose();
}
