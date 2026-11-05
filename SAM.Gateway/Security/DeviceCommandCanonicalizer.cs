using System.Globalization;
using System.Text;

namespace SAM.Gateway.Security;

/// <summary>
/// Shared wire-format definition for signatures. All timestamps are represented
/// as Unix milliseconds; signature is excluded from the payload.
/// </summary>
public static class DeviceCommandCanonicalizer
{
    public static byte[] CreateSigningPayload(DeviceCommandEnvelope command)
    {
        ArgumentNullException.ThrowIfNull(command);

        var fields = new[]
        {
            "sam-device-command-v1",
            command.CommandId.ToString("D"),
            command.TargetDeviceId.ToString("D"),
            command.IssuedAt.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture),
            command.ExpiresAt.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture),
            command.Nonce,
            command.Action,
            command.DelaySeconds?.ToString(CultureInfo.InvariantCulture) ?? "-",
            command.RequiredApprovalId?.ToString("D") ?? "-"
        };

        return Encoding.UTF8.GetBytes(string.Join("\n", fields));
    }
}
