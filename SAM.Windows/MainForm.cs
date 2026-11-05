using System.Security.Cryptography;

namespace SAM.Windows;

internal sealed class MainForm : Form
{
    private readonly Label _status = new()
    {
        AutoSize = true,
        Text = "Device identity: checking..."
    };

    private readonly Label _fingerprint = new()
    {
        AutoSize = true,
        Text = "Key fingerprint: unavailable"
    };

    private readonly TextBox _publicKey = new()
    {
        Multiline = true,
        ReadOnly = true,
        ScrollBars = ScrollBars.Vertical,
        Dock = DockStyle.Fill,
        Height = 100
    };

    private readonly Button _initialize = new()
    {
        Text = "Create secure device identity",
        AutoSize = true
    };

    private readonly Button _copy = new()
    {
        Text = "Copy public key",
        AutoSize = true,
        Enabled = false
    };

    private DeviceIdentity? _identity;
    private Guid? _pendingClaimId;
    private string? _pendingClaimSecret;
    private string? _pendingGatewayUrl;

    private readonly TextBox _gatewayUrl = new() { Width = 250, PlaceholderText = "SAM gateway HTTPS URL" };
    private readonly TextBox _pairingCode = new() { Width = 100, MaxLength = 8, PlaceholderText = "8-digit code" };
    private readonly Button _pair = new() { Text = "Pair with SAM", AutoSize = true };
    private readonly Button _checkPairing = new() { Text = "Check approval", AutoSize = true, Enabled = false };
    private readonly Button _checkDeviceSession = new() { Text = "Check device connection", AutoSize = true, Enabled = false };
    private readonly Button _logoutDeviceSession = new() { Text = "Sign out device", AutoSize = true, Enabled = false };

    public MainForm()
    {
        Text = "SAM — Windows companion (Built by Max)";
        Width = 720;
        Height = 420;
        MinimumSize = new Size(560, 340);
        StartPosition = FormStartPosition.CenterScreen;

        var heading = new Label
        {
            Text = "SAM for Windows 11 — Built by " + SamAgentIdentity.BuilderName,
            AutoSize = true,
            Font = new Font(Font.FontFamily, 18, FontStyle.Bold)
        };
        var explanation = new Label
        {
            Text = "Secure device identity foundation. Built by Max. Pairing and remote commands are not connected yet.",
            AutoSize = true,
            MaximumSize = new Size(650, 0)
        };
        var pairing = new Label
        {
            Text = "Account status: Not paired",
            AutoSize = true
        };
        var keyLabel = new Label { Text = "Public device key (safe to share during a future pairing flow):", AutoSize = true };
        var buttons = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill };
        buttons.Controls.Add(_initialize);
        buttons.Controls.Add(_copy);

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(20),
            ColumnCount = 1,
            RowCount = 9,
            AutoSize = false
        };
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.Controls.Add(heading, 0, 0);
        layout.Controls.Add(explanation, 0, 1);
        layout.Controls.Add(pairing, 0, 2);
        layout.Controls.Add(_status, 0, 3);
        layout.Controls.Add(_fingerprint, 0, 4);
        layout.Controls.Add(keyLabel, 0, 5);
        layout.Controls.Add(_publicKey, 0, 6);
        layout.Controls.Add(buttons, 0, 7);
        var pairingPanel = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = true };
        pairingPanel.Controls.Add(_gatewayUrl);
        pairingPanel.Controls.Add(_pairingCode);
        pairingPanel.Controls.Add(_pair);
        pairingPanel.Controls.Add(_checkPairing);
        pairingPanel.Controls.Add(_checkDeviceSession);
        pairingPanel.Controls.Add(_logoutDeviceSession);
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.Controls.Add(pairingPanel, 0, 8);
        Controls.Add(layout);

        _initialize.Click += (_, _) => InitializeIdentity();
        _pair.Click += async (_, _) => await BeginPairingAsync();
        _checkPairing.Click += async (_, _) => await CheckPairingApprovalAsync();
        _checkDeviceSession.Click += async (_, _) => await CheckStoredDeviceSessionAsync();
        _logoutDeviceSession.Click += async (_, _) => await LogoutStoredDeviceSessionAsync();
        _copy.Click += (_, _) =>
        {
            if (!string.IsNullOrWhiteSpace(_publicKey.Text))
                Clipboard.SetText(_publicKey.Text);
        };

        LoadExistingIdentity();
        LoadStoredDeviceSession();
    }

    private async Task BeginPairingAsync()
    {
        if (_identity is null)
        {
            MessageBox.Show(this, "Create the secure device identity first.", "SAM pairing",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        _pair.Enabled = false;
        try
        {
            using var gateway = new SamGatewayClient(_gatewayUrl.Text);
            var claim = await gateway.StartClaimAsync(
                _pairingCode.Text.Trim(),
                Environment.MachineName,
                _identity.ExportPublicKey());
            var proofPayload = Convert.FromBase64String(claim.ProofPayloadBase64);
            var signature = _identity.Sign(proofPayload);
            var signatureBase64Url = Convert.ToBase64String(signature)
                .TrimEnd('=').Replace('+', '-').Replace('/', '_');
            await gateway.ProveClaimAsync(claim.ClaimId, signatureBase64Url);

            _pendingClaimId = claim.ClaimId;
            _pendingClaimSecret = claim.ClaimSecret;
            _pendingGatewayUrl = _gatewayUrl.Text;
            _checkPairing.Enabled = true;
            _status.Text = "Private-key proof verified. Waiting for Max to approve this device.";
        }
        catch (Exception ex)
        {
            _status.Text = "Pairing failed: " + ex.Message;
            MessageBox.Show(this, ex.Message, "SAM pairing", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally
        {
            _pair.Enabled = true;
        }
    }

    private async Task CheckPairingApprovalAsync()
    {
        if (_pendingClaimId is null || string.IsNullOrWhiteSpace(_pendingClaimSecret) ||
            string.IsNullOrWhiteSpace(_pendingGatewayUrl))
            return;

        _checkPairing.Enabled = false;
        try
        {
            using var gateway = new SamGatewayClient(_pendingGatewayUrl);
            var state = await gateway.GetClaimStatusAsync(_pendingClaimId.Value, _pendingClaimSecret);
            if (state == "approved")
            {
                var session = await gateway.ExchangeDeviceSessionAsync(_pendingClaimId.Value, _pendingClaimSecret);
                var info = await gateway.GetDeviceMeAsync(session.AccessToken);
                await gateway.HeartbeatAsync(session.AccessToken);
                if (info.DeviceId != session.DeviceId || info.DeviceId == Guid.Empty)
                    throw new InvalidOperationException("Device session resolved to an unexpected device identity.");

                SamDeviceSessionStore.Save(new StoredSamDeviceSession(
                    _pendingGatewayUrl, session.AccessToken, session.DeviceId, session.DeviceName, session.ExpiresAt));
                _checkDeviceSession.Enabled = true;
                _logoutDeviceSession.Enabled = true;
                _status.Text = "Paired and online as " + info.DeviceName + ".";
                _pendingClaimId = null;
                _pendingClaimSecret = null;
                _pendingGatewayUrl = null;
                _checkPairing.Enabled = false;
                return;
            }

            _status.Text = state switch
            {
                "awaiting_owner_approval" => "Proof verified. Waiting for Max to approve.",
                "proof_required" => "Pairing proof is still required.",
                "rejected" => "Max rejected this device request.",
                "expired" => "Pairing request expired. Generate a new code.",
                _ => "Pairing status: " + state
            };

            if (state is "rejected" or "expired")
            {
                _pendingClaimId = null;
                _pendingClaimSecret = null;
                _pendingGatewayUrl = null;
                _checkPairing.Enabled = false;
            }
            else
            {
                _checkPairing.Enabled = true;
            }
        }
        catch (Exception ex)
        {
            _status.Text = "Could not check pairing: " + ex.Message;
            _checkPairing.Enabled = true;
        }
    }

    private void LoadStoredDeviceSession()
    {
        var stored = SamDeviceSessionStore.TryLoad();
        if (stored is null) return;
        _gatewayUrl.Text = stored.GatewayUrl;
        _checkDeviceSession.Enabled = true;
        _logoutDeviceSession.Enabled = true;
        _status.Text = "Saved SAM device session found. Checking connection…";
        Shown += async (_, _) => await CheckStoredDeviceSessionAsync();
    }

    private async Task CheckStoredDeviceSessionAsync()
    {
        var stored = SamDeviceSessionStore.TryLoad();
        if (stored is null)
        {
            _status.Text = "No saved SAM device session.";
            _checkDeviceSession.Enabled = false;
            _logoutDeviceSession.Enabled = false;
            return;
        }

        _checkDeviceSession.Enabled = false;
        try
        {
            using var gateway = new SamGatewayClient(stored.GatewayUrl);
            var device = await gateway.GetDeviceMeAsync(stored.AccessToken);
            await gateway.HeartbeatAsync(stored.AccessToken);
            _status.Text = "SAM device online: " + device.DeviceName + ".";
        }
        catch (Exception ex)
        {
            _status.Text = "SAM device connection unavailable: " + ex.Message;
        }
        finally
        {
            _checkDeviceSession.Enabled = true;
        }
    }

    private async Task LogoutStoredDeviceSessionAsync()
    {
        var stored = SamDeviceSessionStore.TryLoad();
        if (stored is null) return;
        _logoutDeviceSession.Enabled = false;
        try
        {
            using var gateway = new SamGatewayClient(stored.GatewayUrl);
            await gateway.LogoutDeviceSessionAsync(stored.AccessToken);
            SamDeviceSessionStore.Delete();
            _checkDeviceSession.Enabled = false;
            _logoutDeviceSession.Enabled = false;
            _status.Text = "This Windows device session has been signed out.";
        }
        catch (Exception ex)
        {
            _status.Text = "Device sign-out failed: " + ex.Message;
            _logoutDeviceSession.Enabled = true;
        }
    }

    private void LoadExistingIdentity()
    {
        try
        {
            _identity = DeviceIdentity.TryOpen();
            if (_identity is null)
            {
                _status.Text = "Device identity: not created on this Windows user profile.";
                return;
            }

            ShowIdentity();
        }
        catch (CryptographicException ex)
        {
            _status.Text = "Device identity could not be opened: " + ex.Message;
        }
    }

    private void InitializeIdentity()
    {
        try
        {
            _identity ??= DeviceIdentity.Create();
            ShowIdentity();
        }
        catch (Exception ex) when (ex is CryptographicException or InvalidOperationException)
        {
            MessageBox.Show(this, ex.Message, "SAM device identity", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void ShowIdentity()
    {
        _publicKey.Text = _identity!.ExportPublicKey();
        _fingerprint.Text = "SHA-256 fingerprint: " + _identity.GetFingerprint();
        _status.Text = "Device identity: ready — private signing key remains in Windows key storage.";
        _initialize.Enabled = false;
        _copy.Enabled = true;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
            _identity?.Dispose();
        base.Dispose(disposing);
    }
}
