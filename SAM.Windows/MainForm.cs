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
            RowCount = 8,
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
        Controls.Add(layout);

        _initialize.Click += (_, _) => InitializeIdentity();
        _copy.Click += (_, _) =>
        {
            if (!string.IsNullOrWhiteSpace(_publicKey.Text))
                Clipboard.SetText(_publicKey.Text);
        };

        LoadExistingIdentity();
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
