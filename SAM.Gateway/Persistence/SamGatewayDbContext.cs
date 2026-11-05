using Microsoft.EntityFrameworkCore;

namespace SAM.Gateway.Persistence;

public sealed class SamGatewayDbContext(DbContextOptions<SamGatewayDbContext> options) : DbContext(options)
{
    public DbSet<CommandReplayEntry> CommandReplayEntries => Set<CommandReplayEntry>();
    public DbSet<OwnerCommandApprovalRecord> OwnerCommandApprovals => Set<OwnerCommandApprovalRecord>();
    public DbSet<OwnerAccount> OwnerAccounts => Set<OwnerAccount>();
    public DbSet<OwnerSession> OwnerSessions => Set<OwnerSession>();

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

        modelBuilder.Entity<OwnerCommandApprovalRecord>(entity =>
        {
            entity.ToTable("owner_command_approvals");
            entity.HasKey(x => x.ApprovalId);
            entity.Property(x => x.Action).HasMaxLength(48).IsRequired();
            entity.HasIndex(x => x.CommandId).IsUnique();
        });
    }
}
