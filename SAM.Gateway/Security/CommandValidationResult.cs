namespace SAM.Gateway.Security;

public sealed record CommandValidationResult(bool IsValid, string Code)
{
    public static CommandValidationResult Valid() => new(true, "ok");
    public static CommandValidationResult Invalid(string code) => new(false, code);
}
