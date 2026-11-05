using System.Security.Cryptography;
using System.Globalization;
using System.Text;

namespace SAM.Windows;

/// <summary>
/// Wire command shape mirrored by the Android client and SAM gateway.
/// It is signing data only; it does not imply that the command is authorized or dispatched.
/// </summary>
internal sealed record SamDeviceCommand(
    Guid CommandId,
    Guid TargetDeviceId,
    DateTimeOffset IssuedAt,
    DateTimeOffset ExpiresAt,
    string Nonce,
    string Action,
    int? DelaySeconds = null,
    Guid? RequiredApprovalId = null);

internal static class SamDeviceCommandCanonicalizer
{
    public static byte[] CreateSigningPayload(SamDeviceCommand command)
    {
        ArgumentNullException.ThrowIfNull(command);
        var fields = new[]
        {
            "sam-device-command-v1",
            command.CommandId.ToString("D").ToLowerInvariant(),
            command.TargetDeviceId.ToString("D").ToLowerInvariant(),
            command.IssuedAt.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture),
            command.ExpiresAt.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture),
            command.Nonce,
            command.Action,
            command.DelaySeconds?.ToString(CultureInfo.InvariantCulture) ?? "-",
            command.RequiredApprovalId?.ToString("D").ToLowerInvariant() ?? "-"
        };
        return Encoding.UTF8.GetBytes(string.Join("\n", fields));
    }

    public static SamDeviceCommand CreateKnownVector() => new(
        Guid.Parse("11111111-2222-3333-4444-555555555555"),
        Guid.Parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        DateTimeOffset.FromUnixTimeMilliseconds(1793869200000),
        DateTimeOffset.FromUnixTimeMilliseconds(1793869230000),
        "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX",
        "power.shutdown",
        60,
        Guid.Parse("99999999-8888-7777-6666-555555555555"));

    public static void VerifyKnownVector()
    {
        const string expected =
            "sam-device-command-v1\n" +
            "11111111-2222-3333-4444-555555555555\n" +
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n" +
            "1793869200000\n" +
            "1793869230000\n" +
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX\n" +
            "power.shutdown\n" +
            "60\n" +
            "99999999-8888-7777-6666-555555555555";

        var actual = Encoding.UTF8.GetString(CreateSigningPayload(CreateKnownVector()));
        if (!string.Equals(actual, expected, StringComparison.Ordinal))
            throw new CryptographicException("SAM Windows canonical command format does not match the gateway test vector.");
    }
}
