using Microsoft.EntityFrameworkCore;

namespace SAM.Gateway.Persistence;

public sealed class CompanyCustomer
{
    public Guid Id { get; set; } public Guid OwnerId { get; set; }
    public string Name { get; set; } = ""; public string CompanyName { get; set; } = "";
    public string Email { get; set; } = ""; public string Phone { get; set; } = "";
    public string Notes { get; set; } = ""; public bool IsArchived { get; set; }
    public long CreatedAtUnixMilliseconds { get; set; }
}
public sealed class CompanyProject
{
    public Guid Id { get; set; } public Guid OwnerId { get; set; } public Guid CustomerId { get; set; }
    public string ProjectCode { get; set; } = ""; public string Name { get; set; } = "";
    public string Brief { get; set; } = ""; public string Status { get; set; } = "LEAD";
    public long UpdatedAtUnixMilliseconds { get; set; }
}
public sealed class CompanyQuote
{
    public Guid Id { get; set; } public Guid OwnerId { get; set; } public Guid CustomerId { get; set; }
    public Guid? ProjectId { get; set; } public string QuoteNumber { get; set; } = "";
    public string Status { get; set; } = "DRAFT"; public string CurrencyCode { get; set; } = "KES";
    public string Scope { get; set; } = ""; public string Exclusions { get; set; } = "";
    public long ValidUntilUnixMilliseconds { get; set; } public decimal TotalKes { get; set; }
    public long CreatedAtUnixMilliseconds { get; set; } public List<CompanyQuoteLine> Lines { get; set; } = [];
}
public sealed class CompanyQuoteLine
{
    public Guid Id { get; set; } public Guid QuoteId { get; set; } public CompanyQuote? Quote { get; set; }
    public string Description { get; set; } = ""; public decimal Quantity { get; set; }
    public decimal UnitPriceKes { get; set; } public decimal LineTotalKes { get; set; }
}

public sealed record CreateCompanyCustomerRequest(string Name, string? CompanyName, string? Email, string? Phone, string? Notes);
public sealed record CreateCompanyProjectRequest(Guid CustomerId, string Name, string Brief, DateTimeOffset? TargetDate);
public sealed record CompanyQuoteLineInput(string Description, decimal Quantity, decimal UnitPriceKes);
public sealed record CreateCompanyQuoteRequest(Guid CustomerId, Guid? ProjectId, string Scope, string Exclusions, DateTimeOffset ValidUntil, IReadOnlyList<CompanyQuoteLineInput> Lines);
public sealed record CompanyDashboardSummary(int Customers, int Leads, int ActiveProjects, int DraftQuotes, int SentQuotes, decimal SentQuoteValueKes);
public enum CompanyOperationResult { Updated, NotFound, Invalid, Conflict }

public sealed class CompanyOperationsService(IDbContextFactory<SamGatewayDbContext> factory, TimeProvider? clock = null)
{
    private readonly TimeProvider _clock = clock ?? TimeProvider.System;
    private static string Clean(string? s, int max) => (s ?? "").Trim().Length > max ? (s ?? "").Trim()[..(max + 1)] : (s ?? "").Trim();

    public async Task<CompanyCustomer?> CreateCustomerAsync(Guid owner, CreateCompanyCustomerRequest r, CancellationToken ct = default)
    {
        var name=Clean(r.Name,100); var company=Clean(r.CompanyName,120); var email=Clean(r.Email,254).ToLowerInvariant();
        var phone=Clean(r.Phone,32); var notes=Clean(r.Notes,2000);
        if(owner==Guid.Empty || name.Length is <1 or >100 || company.Length>120 || email.Length>254 ||
           email.Length>0 && (!email.Contains('@') || email.StartsWith('@') || email.EndsWith('@')) ||
           phone.Length>32 || phone.Any(c=>!(char.IsDigit(c)||c is '+' or '-' or ' ' or '(' or ')')) ||
           notes.Length>2000 || notes.Any(char.IsControl)) return null;
        var now=_clock.GetUtcNow().ToUnixTimeMilliseconds();
        var x=new CompanyCustomer { Id=Guid.NewGuid(),OwnerId=owner,Name=name,CompanyName=company,Email=email,Phone=phone,Notes=notes,CreatedAtUnixMilliseconds=now };
        await using var db=await factory.CreateDbContextAsync(ct); db.CompanyCustomers.Add(x); await db.SaveChangesAsync(ct); return x;
    }
    public async Task<IReadOnlyList<CompanyCustomer>> CustomersAsync(Guid owner,CancellationToken ct=default)
    { await using var db=await factory.CreateDbContextAsync(ct); return await db.CompanyCustomers.AsNoTracking().Where(x=>x.OwnerId==owner&&!x.IsArchived).OrderBy(x=>x.Name).Take(500).ToListAsync(ct); }

    public async Task<CompanyProject?> CreateProjectAsync(Guid owner,CreateCompanyProjectRequest r,CancellationToken ct=default)
    {
        var name=Clean(r.Name,120); var brief=Clean(r.Brief,4000);
        if(owner==Guid.Empty||r.CustomerId==Guid.Empty||name.Length is <1 or >120||brief.Length is <1 or >4000||brief.Any(char.IsControl))return null;
        await using var db=await factory.CreateDbContextAsync(ct);
        if(!await db.CompanyCustomers.AnyAsync(x=>x.Id==r.CustomerId&&x.OwnerId==owner&&!x.IsArchived,ct))return null;
        var now=_clock.GetUtcNow(); var p=new CompanyProject { Id=Guid.NewGuid(),OwnerId=owner,CustomerId=r.CustomerId,
            ProjectCode="SAM-"+now.ToString("yyMMdd")+"-"+Guid.NewGuid().ToString("N")[..6].ToUpperInvariant(),
            Name=name,Brief=brief,Status="LEAD",UpdatedAtUnixMilliseconds=now.ToUnixTimeMilliseconds() };
        db.CompanyProjects.Add(p); await db.SaveChangesAsync(ct); return p;
    }
    public async Task<CompanyOperationResult> ChangeProjectStatusAsync(Guid owner,Guid id,string status,CancellationToken ct=default)
    {
        var next=Clean(status,24).ToUpperInvariant();
        var allowed=new Dictionary<string,string[]>(StringComparer.Ordinal) {
            ["LEAD"]=["PLANNING","CANCELLED"],["PLANNING"]=["ACTIVE","CANCELLED"],
            ["ACTIVE"]=["ON_HOLD","COMPLETED","CANCELLED"],["ON_HOLD"]=["ACTIVE","CANCELLED"] };
        if(!allowed.ContainsKey(next)&&next is not ("COMPLETED" or "CANCELLED"))return CompanyOperationResult.Invalid;
        await using var db=await factory.CreateDbContextAsync(ct); var p=await db.CompanyProjects.SingleOrDefaultAsync(x=>x.Id==id&&x.OwnerId==owner,ct);
        if(p is null)return CompanyOperationResult.NotFound; if(!allowed.TryGetValue(p.Status,out var choices)||!choices.Contains(next))return CompanyOperationResult.Conflict;
        p.Status=next;p.UpdatedAtUnixMilliseconds=_clock.GetUtcNow().ToUnixTimeMilliseconds();await db.SaveChangesAsync(ct);return CompanyOperationResult.Updated;
    }

    public async Task<CompanyQuote?> CreateQuoteAsync(Guid owner,CreateCompanyQuoteRequest r,CancellationToken ct=default)
    {
        var scope=Clean(r.Scope,4000);var exclusions=Clean(r.Exclusions,2000);
        if(owner==Guid.Empty||r.CustomerId==Guid.Empty||scope.Length is <1 or >4000||scope.Any(char.IsControl)||
           exclusions.Length>2000||exclusions.Any(char.IsControl)||r.ValidUntil<=_clock.GetUtcNow()||
           r.ValidUntil>_clock.GetUtcNow().AddDays(90)||r.Lines is null||r.Lines.Count is <1 or >30)return null;
        var lines=new List<CompanyQuoteLine>();decimal total=0;
        foreach(var l in r.Lines){var d=Clean(l.Description,160);if(d.Length is <1 or >160||l.Quantity<=0||l.Quantity>10000||
            l.UnitPriceKes<0||l.UnitPriceKes>100_000_000||decimal.Round(l.Quantity,2)!=l.Quantity||decimal.Round(l.UnitPriceKes,2)!=l.UnitPriceKes)return null;
            var amount=decimal.Round(l.Quantity*l.UnitPriceKes,2,MidpointRounding.AwayFromZero);total+=amount;if(total>1_000_000_000)return null;
            lines.Add(new CompanyQuoteLine{Id=Guid.NewGuid(),Description=d,Quantity=l.Quantity,UnitPriceKes=l.UnitPriceKes,LineTotalKes=amount});}
        await using var db=await factory.CreateDbContextAsync(ct);
        if(!await db.CompanyCustomers.AnyAsync(x=>x.Id==r.CustomerId&&x.OwnerId==owner&&!x.IsArchived,ct))return null;
        if(r.ProjectId is Guid pid&&!await db.CompanyProjects.AnyAsync(x=>x.Id==pid&&x.OwnerId==owner&&x.CustomerId==r.CustomerId,ct))return null;
        var now=_clock.GetUtcNow();var q=new CompanyQuote{Id=Guid.NewGuid(),OwnerId=owner,CustomerId=r.CustomerId,ProjectId=r.ProjectId,
            QuoteNumber="Q-"+now.ToString("yyyyMMdd")+"-"+Guid.NewGuid().ToString("N")[..6].ToUpperInvariant(),
            Status="DRAFT",CurrencyCode="KES",Scope=scope,Exclusions=exclusions,ValidUntilUnixMilliseconds=r.ValidUntil.ToUnixTimeMilliseconds(),
            TotalKes=total,CreatedAtUnixMilliseconds=now.ToUnixTimeMilliseconds(),Lines=lines};
        db.CompanyQuotes.Add(q);await db.SaveChangesAsync(ct);return q;
    }
    public async Task<CompanyOperationResult> IssueQuoteAsync(Guid owner,Guid id,CancellationToken ct=default)
    {await using var db=await factory.CreateDbContextAsync(ct);var q=await db.CompanyQuotes.SingleOrDefaultAsync(x=>x.Id==id&&x.OwnerId==owner,ct);
     if(q is null)return CompanyOperationResult.NotFound;if(q.Status!="DRAFT")return CompanyOperationResult.Conflict;
     if(q.ValidUntilUnixMilliseconds<=_clock.GetUtcNow().ToUnixTimeMilliseconds())return CompanyOperationResult.Invalid;
     q.Status="SENT";await db.SaveChangesAsync(ct);return CompanyOperationResult.Updated;}
    public async Task<IReadOnlyList<CompanyQuote>> QuotesAsync(Guid owner,CancellationToken ct=default)
    {await using var db=await factory.CreateDbContextAsync(ct);return await db.CompanyQuotes.AsNoTracking().Include(x=>x.Lines).Where(x=>x.OwnerId==owner).OrderByDescending(x=>x.CreatedAtUnixMilliseconds).Take(500).ToListAsync(ct);}
    public async Task<CompanyDashboardSummary> DashboardAsync(Guid owner,CancellationToken ct=default)
    {await using var db=await factory.CreateDbContextAsync(ct);var customers=await db.CompanyCustomers.CountAsync(x=>x.OwnerId==owner&&!x.IsArchived,ct);
     var projects=await db.CompanyProjects.Where(x=>x.OwnerId==owner).ToListAsync(ct);var quotes=await db.CompanyQuotes.Where(x=>x.OwnerId==owner).ToListAsync(ct);
     return new(customers,projects.Count(x=>x.Status=="LEAD"),projects.Count(x=>x.Status is "PLANNING" or "ACTIVE" or "ON_HOLD"),
       quotes.Count(x=>x.Status=="DRAFT"),quotes.Count(x=>x.Status=="SENT"),quotes.Where(x=>x.Status=="SENT").Sum(x=>x.TotalKes));}
}
