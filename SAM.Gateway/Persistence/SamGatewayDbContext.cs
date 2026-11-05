using Microsoft.EntityFrameworkCore;

namespace SAM.Gateway.Persistence;

public sealed class SamGatewayDbContext(DbContextOptions<SamGatewayDbContext> options) : DbContext(options)
{
    public DbSet<CommandReplayEntry> CommandReplayEntries => Set<CommandReplayEntry>();
    public DbSet<OwnerCommandApprovalRecord> OwnerCommandApprovals => Set<OwnerCommandApprovalRecord>();
    public DbSet<OwnerAccount> OwnerAccounts => Set<OwnerAccount>();
    public DbSet<OwnerSession> OwnerSessions => Set<OwnerSession>();
    public DbSet<PairingInvite> PairingInvites => Set<PairingInvite>();
    public DbSet<DevicePairingClaim> DevicePairingClaims => Set<DevicePairingClaim>();
    public DbSet<DeviceEnrollment> DeviceEnrollments => Set<DeviceEnrollment>();
    public DbSet<DeviceSession> DeviceSessions => Set<DeviceSession>();
    public DbSet<SharedTask> SharedTasks => Set<SharedTask>();
    public DbSet<SharedTaskEvent> SharedTaskEvents => Set<SharedTaskEvent>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<CommandReplayEntry>(entity =>
        {
            entity.ToTable("command_replays");
            entity.HasKey(x => x.CommandId);
            entity.Property(x => x.Nonce).HasMaxLength(128).IsRequired();
            entity.HasIndex(x => x.Nonce).IsUnique();
        });

        modelBuilder.Entity<OwnerAccount>(entity =>
        {
            entity.ToTable("owner_accounts");
            entity.HasKey(x => x.Id);
            entity.Property(x => x.Email).HasMaxLength(320).IsRequired();
            entity.Property(x => x.NormalizedEmail).HasMaxLength(320).IsRequired();
            entity.HasIndex(x => x.NormalizedEmail).IsUnique();
            entity.Property(x => x.DisplayName).HasMaxLength(64).IsRequired();
            entity.Property(x => x.PasswordSalt).IsRequired();
            entity.Property(x => x.PasswordHash).IsRequired();
        });

        modelBuilder.Entity<OwnerSession>(entity =>
        {
            entity.ToTable("owner_sessions");
            entity.HasKey(x => x.Id);
            entity.Property(x => x.TokenHash).HasMaxLength(64).IsRequired();
            entity.HasIndex(x => x.TokenHash).IsUnique();
            entity.HasOne(x => x.Owner).WithMany().HasForeignKey(x => x.OwnerId).OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<PairingInvite>(entity =>
        {
            entity.ToTable("pairing_invites");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => new { x.OwnerId, x.ExpiresAtUnixMilliseconds });
            entity.Property(x => x.CodeHash).IsRequired();
            entity.Property(x => x.CodeSalt).IsRequired();
        });

        modelBuilder.Entity<DevicePairingClaim>(entity =>
        {
            entity.ToTable("device_pairing_claims");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => x.InviteId).IsUnique();
            entity.HasIndex(x => x.FingerprintSha256);
            entity.Property(x => x.DeviceName).HasMaxLength(64).IsRequired();
            entity.Property(x => x.Platform).HasMaxLength(16).IsRequired();
            entity.Property(x => x.FingerprintSha256).HasMaxLength(64).IsRequired();
            entity.Property(x => x.PublicKeySpki).IsRequired();
            entity.Property(x => x.ProofChallenge).IsRequired();
            entity.Property(x => x.ClaimSecretHash).IsRequired();
        });

        modelBuilder.Entity<DeviceEnrollment>(entity =>
        {
            entity.ToTable("device_enrollments");
            entity.HasKey(x => x.DeviceId);
            entity.HasIndex(x => x.FingerprintSha256).IsUnique();
            entity.HasIndex(x => new { x.OwnerId, x.RevokedAtUnixMilliseconds });
            entity.Property(x => x.DeviceName).HasMaxLength(64).IsRequired();
            entity.Property(x => x.Platform).HasMaxLength(16).IsRequired();
            entity.Property(x => x.FingerprintSha256).HasMaxLength(64).IsRequired();
            entity.Property(x => x.PublicKeySpki).IsRequired();
        });

        modelBuilder.Entity<DeviceSession>(entity =>
        {
            entity.ToTable("device_sessions");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => x.ClaimId).IsUnique();
            entity.HasIndex(x => x.TokenHash).IsUnique();
            entity.Property(x => x.TokenHash).HasMaxLength(64).IsRequired();
        });

        modelBuilder.Entity<SharedTask>(entity =>
        {
            entity.ToTable("shared_tasks");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => new { x.OwnerId, x.UpdatedAtUnixMilliseconds });
            entity.HasIndex(x => new { x.TargetDeviceId, x.Status });
            entity.Property(x => x.Title).HasMaxLength(120).IsRequired();
            entity.Property(x => x.Instructions).HasMaxLength(8000).IsRequired();
            entity.Property(x => x.Status).HasMaxLength(32).IsRequired();
            entity.Property(x => x.LastMessage).HasMaxLength(2000).IsRequired();
        });

        modelBuilder.Entity<SharedTaskEvent>(entity =>
        {
            entity.ToTable("shared_task_events");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => new { x.TaskId, x.Version }).IsUnique();
            entity.Property(x => x.Status).HasMaxLength(32).IsRequired();
            entity.Property(x => x.Message).HasMaxLength(2000).IsRequired();
        });

        modelBuilder.Entity<OwnerCommandApprovalRecord>(entity =>
        {
            entity.ToTable("owner_command_approvals");
            entity.HasKey(x => x.ApprovalId);
            entity.Property(x => x.Action).HasMaxLength(48).IsRequired();
            entity.HasIndex(x => x.CommandId).IsUnique();
        });
    }
}
