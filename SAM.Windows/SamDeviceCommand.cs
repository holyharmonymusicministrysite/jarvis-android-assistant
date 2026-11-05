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
}
