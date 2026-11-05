namespace SAM.Gateway.Security;

/// <summary>
/// A narrowly typed command. The v1 validator currently accepts status.read,
/// power.shutdown, and power.restart only. It does not execute commands.
/// </summary>
public sealed record DeviceCommandEnvelope(
    Guid CommandId,
    Guid TargetDeviceId,
    DateTimeOffset IssuedAt,
    DateTimeOffset ExpiresAt,
    string Nonce,
    string Action,
    int? DelaySeconds,
    Guid? RequiredApprovalId,
    string Signature);
