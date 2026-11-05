using Microsoft.EntityFrameworkCore;

namespace SAM.Gateway.Persistence;

public sealed class SamGatewayDbContext(DbContextOptions<SamGatewayDbContext> options) : DbContext(options)
{
    public DbSet<CommandReplayEntry> CommandReplayEntries => Set<CommandReplayEntry>();
    public DbSet<OwnerCommandApprovalRecord> OwnerCommandApprovals => Set<OwnerCommandApprovalRecord>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<CommandReplayEntry>(entity =>
        {
            entity.ToTable("command_replays");
            entity.HasKey(x => x.CommandId);
            entity.Property(x => x.Nonce).HasMaxLength(128).IsRequired();
            entity.HasIndex(x => x.Nonce).IsUnique();
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
