using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;

internal static class Program
{
    [DllImport("user32.dll")]
    private static extern bool SetProcessDpiAwarenessContext(IntPtr dpiContext);
    [DllImport("user32.dll")]
    private static extern bool SetProcessDPIAware();

    [STAThread]
    private static void Main(string[] args)
    {
        try { SetProcessDpiAwarenessContext(new IntPtr(-4)); }
        catch { try { SetProcessDPIAware(); } catch { } }

        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        if (args.Length == 1 && args[0] == "--self-test") { Environment.Exit(RHRemoteSplashForm.SafetyChecks()); return; }
        var config = SplashConfig.Parse(args);
        using (var form = new RHRemoteSplashForm(config))
        {
            if (!string.IsNullOrEmpty(config.ScreenshotPath) && config.DemoMs <= 0) { form.CreateSnapshot(); return; }
            Application.Run(form);
        }
    }
}

internal sealed class SplashConfig
{
    public string Title = "RHRemote";
    public string FromVersion = "";
    public string ToVersion = "";
    public string Theme = "Light"; // Light | Dark (matches client/crates/rhremote-ui/ui/app.slint Palette)
    public string LogoPath = "";
    public int WaitPid;
    public string RootDir = "";
    public string MainExe = "RHRemote.exe";
    public int TimeoutMs = 90000;
    public int DemoMs;
    public int DemoStage = 1;
    public string ScreenshotPath = "";
    public string MonitorName = "";
    public string CancelFile = "";
    public string ReadyFile = "";
    public bool Passive;

    public static SplashConfig Parse(string[] args)
    {
        var c = new SplashConfig();
        for (int i = 0; i < args.Length; i++)
        {
            string k = args[i];
            string v = (i + 1 < args.Length) ? args[i + 1] : "";
            switch (k)
            {
                case "--title": c.Title = v; i++; break;
                case "--from-version": c.FromVersion = v; i++; break;
                case "--to-version": c.ToVersion = v; i++; break;
                case "--theme": c.Theme = v; i++; break;
                case "--logo": c.LogoPath = v; i++; break;
                case "--wait-pid": int.TryParse(v, out c.WaitPid); i++; break;
                case "--root-dir": c.RootDir = v; i++; break;
                case "--main-exe": c.MainExe = v; i++; break;
                case "--timeout-ms": int.TryParse(v, out c.TimeoutMs); i++; break;
                case "--demo-ms": int.TryParse(v, out c.DemoMs); i++; break;
                case "--demo-stage": int.TryParse(v, out c.DemoStage); i++; break;
                case "--screenshot": c.ScreenshotPath = v; i++; break;
                case "--monitor": c.MonitorName = v; i++; break;
                case "--cancel-file": c.CancelFile = v; i++; break;
                case "--ready-file": c.ReadyFile = v; i++; break;
                case "--passive": c.Passive = true; break;
            }
        }
        return c;
    }
}

internal sealed class RHRemoteSplashForm : Form
{
    protected override bool ShowWithoutActivation { get { return true; } }
    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);
    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    private static extern bool ReleaseCapture();
    [DllImport("user32.dll")]
    private static extern IntPtr SendMessage(IntPtr hWnd, int msg, int wParam, int lParam);

    private readonly SplashConfig config;
    private readonly Stopwatch elapsed = Stopwatch.StartNew();
    private readonly DateTime startedAtUtc = DateTime.UtcNow;
    private Process waitProcess;
    private readonly Timer animTimer;
    private readonly Timer probeTimer;
    private Image logoImage;
    private float scale = 1f;
    private bool dwmRounded;
    private bool isDark;

    private int stage; // 0: closing, 1: extracting, 2: replacing, 3: starting
    private float targetProgress = 0.16f;
    private float displayProgress = 0.10f;
    private float shimmerPhase;
    private bool sawTempDir;
    private bool sawSwapOrMultipleTemp;
    private DateTime? updateExeExitedAt;
    private bool screenshotSaved;

    // Exact Slint Palette from client/crates/rhremote-ui/ui/app.slint
    private Color pageColor;
    private Color surfaceColor;
    private Color inkColor;
    private Color mutedColor;
    private Color lineColor;
    private Color blueColor;
    private Color purpleColor;
    private Color softColor;
    private Color successColor;

    public RHRemoteSplashForm(SplashConfig config)
    {
        this.config = config;
        if (config.WaitPid > 0) { try { waitProcess = Process.GetProcessById(config.WaitPid); var boundHandle = waitProcess.Handle; } catch { if (waitProcess != null) waitProcess.Dispose(); waitProcess = null; } }
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        ShowInTaskbar = true;
        TopMost = true;
        DoubleBuffered = true;
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);

        Text = config.Title + " · 正在安装更新";
        ApplyTheme();
        LoadLogo();

        if (!string.IsNullOrEmpty(config.ScreenshotPath))
        {
            stage = Math.Max(0, Math.Min(3, config.DemoStage));
            targetProgress = StageBaseProgress(stage) + 0.12f;
            displayProgress = targetProgress;
            shimmerPhase = 0.48f;
        }
        else if (config.DemoMs > 0)
        {
            stage = 1;
            targetProgress = StageBaseProgress(1);
            displayProgress = 0.14f;
        }

        animTimer = new Timer { Interval = 16 };
        animTimer.Tick += OnAnimateTick;
        probeTimer = new Timer { Interval = 100 };
        probeTimer.Tick += OnProbeTick;
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        using (Graphics g = CreateGraphics())
        {
            scale = g.DpiX / 96f;
            if (scale <= 0f) scale = 1f;
        }
        if (!string.IsNullOrEmpty(config.ScreenshotPath) && scale < 1.25f)
            scale = 1.25f;
        ClientSize = new Size(S(456), S(188));
        TryEnableDwmCornerAndTheme();
        if (!dwmRounded)
            Region = new Region(RoundedRect(new RectangleF(0, 0, Width, Height), S(12)));
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        Screen target = null;
        if (!string.IsNullOrEmpty(config.MonitorName))
        {
            target = Screen.AllScreens.FirstOrDefault(s => string.Equals(s.DeviceName, config.MonitorName, StringComparison.OrdinalIgnoreCase));
            if (target == null) { Close(); return; }
        }
        if (target == null && waitProcess != null) { try { if (!waitProcess.HasExited && waitProcess.MainWindowHandle != IntPtr.Zero) target = Screen.FromHandle(waitProcess.MainWindowHandle); } catch { } }
        if (target == null) target = Screen.PrimaryScreen;
        var area = target.WorkingArea;
        Location = new Point(area.Left + (area.Width - Width) / 2, area.Top + (area.Height - Height) / 2);
        if (!string.IsNullOrEmpty(config.ReadyFile)) { try { File.WriteAllText(config.ReadyFile, Process.GetCurrentProcess().Id.ToString()); } catch { } }
        animTimer.Start();
        probeTimer.Start();
    }

    private void ApplyTheme()
    {
        isDark = string.Equals((config.Theme ?? "").Trim(), "Dark", StringComparison.OrdinalIgnoreCase);
        if (isDark)
        {
            // Slint Palette (dark: true)
            pageColor = Color.FromArgb(21, 27, 41);       // #151b29
            surfaceColor = Color.FromArgb(27, 34, 50);    // #1b2232
            inkColor = Color.FromArgb(225, 232, 246);     // #e1e8f6
            mutedColor = Color.FromArgb(164, 177, 200);   // #a4b1c8
            lineColor = Color.FromArgb(48, 60, 83);       // #303c53
            blueColor = Color.FromArgb(111, 138, 241);    // #6f8af1
            purpleColor = Color.FromArgb(186, 154, 239);  // #ba9aef
            softColor = Color.FromArgb(37, 52, 82);       // #253452
            successColor = Color.FromArgb(139, 199, 177); // #8bc7b1
        }
        else
        {
            // Slint Palette (dark: false)
            pageColor = Color.FromArgb(246, 247, 250);    // #f6f7fa
            surfaceColor = Color.FromArgb(255, 255, 255); // #ffffff
            inkColor = Color.FromArgb(52, 65, 91);        // #34415b
            mutedColor = Color.FromArgb(105, 119, 142);   // #69778e
            lineColor = Color.FromArgb(229, 234, 244);    // #e5eaf4
            blueColor = Color.FromArgb(82, 116, 232);     // #5274e8
            purpleColor = Color.FromArgb(139, 91, 226);   // #8b5be2
            softColor = Color.FromArgb(237, 242, 255);    // #edf2ff
            successColor = Color.FromArgb(54, 156, 129);  // #369c81
        }
        BackColor = pageColor;
    }

    private void LoadLogo()
    {
        try
        {
            if (!string.IsNullOrEmpty(config.LogoPath) && File.Exists(config.LogoPath))
            {
                if (config.LogoPath.EndsWith(".ico", StringComparison.OrdinalIgnoreCase))
                {
                    using (var ico = new Icon(config.LogoPath, new Size(256, 256)))
                    {
                        Icon = (Icon)ico.Clone();
                        logoImage = ico.ToBitmap();
                    }
                }
                else
                {
                    using (var fs = new FileStream(config.LogoPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                        logoImage = new Bitmap(fs);
                }
            }
        }
        catch { }
    }

    private void TryEnableDwmCornerAndTheme()
    {
        try
        {
            int dark = isDark ? 1 : 0;
            DwmSetWindowAttribute(Handle, 20, ref dark, 4);
            int corner = 2;
            int hr = DwmSetWindowAttribute(Handle, 33, ref corner, 4);
            dwmRounded = (hr == 0);
        }
        catch { dwmRounded = false; }
    }

    private static float StageBaseProgress(int s)
    {
        switch (s)
        {
            case 0: return 0.14f;
            case 1: return 0.36f;
            case 2: return 0.68f;
            default: return 0.90f;
        }
    }

    private static float StageMaxProgress(int s)
    {
        switch (s)
        {
            case 0: return 0.24f;
            case 1: return 0.62f;
            case 2: return 0.86f;
            default: return 0.96f;
        }
    }

    private string StageLabel()
    {
        switch (stage)
        {
            case 0: return "正在关闭当前客户端…";
            case 1: return "正在准备安装新版 RHRemote…";
            case 2: return "正在安装新版 RHRemote…";
            default: return "正在启动新版 RHRemote…";
        }
    }

    private void OnAnimateTick(object sender, EventArgs e)
    {
        shimmerPhase += 0.028f;
        if (shimmerPhase > 1.4f) shimmerPhase = -0.4f;

        if (config.DemoMs > 0 && string.IsNullOrEmpty(config.ScreenshotPath))
        {
            float ratio = Math.Min(1f, (float)elapsed.ElapsedMilliseconds / Math.Max(1000, config.DemoMs));
            stage = ratio < 0.42f ? 1 : (ratio < 0.78f ? 2 : 3);
            targetProgress = Math.Min(1f, 0.14f + ratio * 0.86f);
            displayProgress += (targetProgress - displayProgress) * 0.12f;
        }
        else if (config.DemoMs == 0 && string.IsNullOrEmpty(config.ScreenshotPath))
        {
            float maxForStage = StageMaxProgress(stage);
            if (targetProgress < maxForStage)
                targetProgress = Math.Min(maxForStage, targetProgress + 0.0018f);
            displayProgress += (targetProgress - displayProgress) * 0.12f;
        }

        Invalidate();

        if (!screenshotSaved && !string.IsNullOrEmpty(config.ScreenshotPath) && elapsed.ElapsedMilliseconds >= 120)
        {
            screenshotSaved = true;
            SaveSnapshot(config.ScreenshotPath);
            if (config.DemoMs <= 0) { Close(); return; }
        }
        if (config.DemoMs > 0 && elapsed.ElapsedMilliseconds >= config.DemoMs)
            Close();
    }

    private void OnProbeTick(object sender, EventArgs e)
    {
        if (config.DemoMs > 0 || !string.IsNullOrEmpty(config.ScreenshotPath)) return;
        if ((!string.IsNullOrEmpty(config.CancelFile) && File.Exists(config.CancelFile)) || elapsed.ElapsedMilliseconds > config.TimeoutMs) { Close(); return; }

        if (IsOwnerAlive()) { stage = 0; return; }
        if (stage < 1) { stage = 1; targetProgress = Math.Max(targetProgress, StageBaseProgress(1)); }

        if (!string.IsNullOrEmpty(config.RootDir))
        {
            try
            {
                string veloTemp = Path.Combine(config.RootDir, "packages", "VelopackTemp");
                if (Directory.Exists(veloTemp))
                {
                    int subDirs = Directory.GetDirectories(veloTemp).Length;
                    if (subDirs >= 1) sawTempDir = true;
                    if (subDirs >= 2 || (sawTempDir && subDirs == 0)) sawSwapOrMultipleTemp = true;
                }
                else if (sawTempDir) sawSwapOrMultipleTemp = true;
            }
            catch { }
        }

        if (sawSwapOrMultipleTemp && stage < 2)
        {
            stage = 2;
            targetProgress = Math.Max(targetProgress, StageBaseProgress(2));
        }

        if (!IsUpdateExeRunning(config.RootDir) && stage >= 1)
        {
            if (stage < 3) { stage = 3; targetProgress = Math.Max(targetProgress, StageBaseProgress(3)); }
            if (updateExeExitedAt == null) updateExeExitedAt = DateTime.UtcNow;
        }

        IntPtr newMainWindow = FindNewMainWindow();
        if (newMainWindow != IntPtr.Zero)
        {
            displayProgress = 1f;
            Invalidate(); Update();
            if (!config.Passive) { try { SetForegroundWindow(newMainWindow); } catch { } }
            Close();
            return;
        }

        // Keep the transition visible until the new window is actually visible, or the bounded overall timeout.
    }

    private bool IsOwnerAlive()
    {
        if (waitProcess == null) return false;
        try { return !waitProcess.HasExited; } catch { return false; }
    }

    private static bool IsUpdateExeRunning(string rootDir)
    {
        try
        {
            var procs = Process.GetProcessesByName("Update");
            try
            {
                foreach (var p in procs)
                {
                    try
                    {
                        if (p.HasExited) continue;
                        if (string.IsNullOrEmpty(rootDir)) continue;
                        string path = p.MainModule != null ? p.MainModule.FileName : null;
                        if (!string.IsNullOrEmpty(path) && string.Equals(Path.GetFullPath(path), Path.GetFullPath(Path.Combine(rootDir, "Update.exe")), StringComparison.OrdinalIgnoreCase))
                            return true;
                    }
                    catch { continue; }
                }
                return false;
            }
            finally { foreach (var p in procs) p.Dispose(); }
        }
        catch { return false; }
    }

    private string TargetProcessName()
    {
        string name = config.MainExe ?? "RHRemote";
        return name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? name.Substring(0, name.Length - 4) : name;
    }

    private bool IsMainExeRunning()
    {
        try
        {
            var procs = Process.GetProcessesByName(TargetProcessName());
            try { return procs.Any(p => IsExpectedMain(p)); }
            finally { foreach (var p in procs) p.Dispose(); }
        }
        catch { return false; }
    }

    private IntPtr FindNewMainWindow()
    {
        try
        {
            int selfPid = Process.GetCurrentProcess().Id;
            var procs = Process.GetProcessesByName(TargetProcessName());
            try
            {
                foreach (var p in procs)
                {
                    try
                    {
                        if (p.Id == selfPid || !IsExpectedMain(p)) continue;
                        IntPtr hwnd = p.MainWindowHandle;
                        if (hwnd != IntPtr.Zero && IsWindowVisible(hwnd)) return hwnd;
                    }
                    catch { }
                }
            }
            finally { foreach (var p in procs) p.Dispose(); }
        }
        catch { }
        return IntPtr.Zero;
    }

    internal static bool ExpectedMainPath(string path, string root, string executable)
    {
        if (string.IsNullOrEmpty(path) || string.IsNullOrEmpty(root) || string.IsNullOrEmpty(executable)) return false;
        try { return string.Equals(Path.GetFullPath(path), Path.GetFullPath(Path.Combine(root, "current", Path.GetFileName(executable))), StringComparison.OrdinalIgnoreCase); } catch { return false; }
    }
    private bool IsExpectedMain(Process process)
    {
        try { return process.Id != config.WaitPid && !process.HasExited && process.StartTime.ToUniversalTime() >= startedAtUtc && process.MainModule != null && ExpectedMainPath(process.MainModule.FileName, config.RootDir, config.MainExe); } catch { return false; }
    }
    internal static int SafetyChecks()
    {
        string root = Path.Combine(Path.GetTempPath(), "RHRemote-splash-boundary");
        if (!ExpectedMainPath(Path.Combine(root, "current", "RHRemote.exe"), root, "RHRemote.exe")) return 1;
        foreach (string wrong in new[] { Path.Combine(root, "current", "engine", "RHRemote.exe"), Path.Combine(root + "-other", "current", "RHRemote.exe"), Path.Combine(root, "old", "RHRemote.exe") })
            if (ExpectedMainPath(wrong, root, "RHRemote.exe")) return 2;
        if (ExpectedMainPath(Path.Combine(root, "current", "RHRemote.exe"), "", "RHRemote.exe")) return 3;
        return 0;
    }
    internal void CreateSnapshot()
    {
        var hiddenHandle = Handle;
        SaveSnapshot(config.ScreenshotPath);
    }
    protected override void Dispose(bool disposing)
    {
        if (disposing) { if (animTimer != null) animTimer.Dispose(); if (probeTimer != null) probeTimer.Dispose(); if (waitProcess != null) waitProcess.Dispose(); if (logoImage != null) logoImage.Dispose(); }
        base.Dispose(disposing);
    }

    private void SaveSnapshot(string path)
    {
        try
        {
            string dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            using (var bmp = new Bitmap(ClientSize.Width, ClientSize.Height, PixelFormat.Format32bppArgb))
            {
                using (var g = Graphics.FromImage(bmp))
                    RenderSurface(g, new Rectangle(0, 0, bmp.Width, bmp.Height), true);
                bmp.Save(path, ImageFormat.Png);
            }
        }
        catch { }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        RenderSurface(e.Graphics, ClientRectangle, false);
    }

    private void RenderSurface(Graphics g, Rectangle bounds, bool forSnapshot)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;

        g.Clear(pageColor);

        // Slint surface card
        var cardRect = new RectangleF(S(14), S(14), bounds.Width - S(28), bounds.Height - S(28));
        using (var cardPath = RoundedRect(cardRect, S(12)))
        using (var cardBrush = new SolidBrush(surfaceColor))
        using (var cardPen = new Pen(lineColor, 1f))
        {
            g.FillPath(cardBrush, cardPath);
            g.DrawPath(cardPen, cardPath);
        }

        // Top-left icon
        var logoRect = new RectangleF(S(28), S(26), S(46), S(46));
        DrawLogo(g, logoRect);

        float textLeft = S(86);
        using (var titleFont = CreateFont(13.5f, FontStyle.Bold))
        using (var badgeFont = CreateFont(8.5f, FontStyle.Bold))
        using (var tagFont = CreateFont(8f, FontStyle.Regular))
        using (var statusFont = CreateFont(9f, FontStyle.Regular))
        using (var stepFont = CreateFont(8f, FontStyle.Regular))
        using (var footerFont = CreateFont(8f, FontStyle.Regular))
        using (var inkBrush = new SolidBrush(inkColor))
        using (var mutedBrush = new SolidBrush(mutedColor))
        using (var successBrush = new SolidBrush(successColor))
        {
            g.DrawString(config.Title, titleFont, inkBrush, new PointF(textLeft, S(24)));
            SizeF titleSize = g.MeasureString(config.Title, titleFont);

            string badgeText = BuildVersionBadge();
            if (!string.IsNullOrEmpty(badgeText))
            {
                SizeF badgeSize = g.MeasureString(badgeText, badgeFont);
                var badgeRect = new RectangleF(textLeft + titleSize.Width + S(6), S(26), badgeSize.Width + S(12), S(20));
                using (var badgePath = RoundedRect(badgeRect, S(6)))
                using (var bFill = new SolidBrush(softColor))
                using (var bPen = new Pen(Color.FromArgb(90, blueColor), 1f))
                using (var bText = new SolidBrush(blueColor))
                {
                    g.FillPath(bFill, badgePath);
                    g.DrawPath(bPen, badgePath);
                    var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
                    g.DrawString(badgeText, badgeFont, bText, badgeRect, sf);
                }
            }

            // Right-aligned Moonlight client tag
            string clientTag = "Moonlight 桌面端";
            SizeF tagSize = g.MeasureString(clientTag, tagFont);
            float tagX = bounds.Width - S(28) - tagSize.Width;
            g.FillEllipse(successBrush, tagX - S(10), S(33), S(6), S(6));
            g.DrawString(clientTag, tagFont, mutedBrush, new PointF(tagX, S(29)));

            // Current stage subtitle
            g.DrawString(StageLabel(), statusFont, mutedBrush, new PointF(textLeft, S(51)));

            // 3-step pills
            int currentStep = stage <= 1 ? 1 : (stage == 2 ? 2 : 3);
            string[] stepNames = new[] { "1. 准备安装", "2. 安装更新", "3. 启动客户端" };
            float stripY = S(84);
            float stripX = S(28);
            for (int i = 0; i < 3; i++)
            {
                int idx = i + 1;
                bool done = idx < currentStep;
                bool active = idx == currentStep;
                string label = (done ? "✓ " : "● ") + stepNames[i];
                SizeF sz = g.MeasureString(label, stepFont);
                var pillRect = new RectangleF(stripX, stripY, sz.Width + S(10), S(18));
                if (active)
                {
                    using (var pPath = RoundedRect(pillRect, S(5)))
                    using (var pFill = new SolidBrush(softColor))
                        g.FillPath(pFill, pPath);
                }
                using (var sBrush = new SolidBrush(done ? successColor : (active ? blueColor : mutedColor)))
                {
                    var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
                    g.DrawString(label, stepFont, sBrush, pillRect, sf);
                }
                stripX += pillRect.Width + S(8);
            }

            // Progress bar track
            float barX = S(28);
            float barY = S(112);
            float barW = bounds.Width - S(56);
            float barH = S(6);
            var trackRect = new RectangleF(barX, barY, barW, barH);
            using (var trackPath = RoundedRect(trackRect, barH / 2f))
            using (var trackBrush = new SolidBrush(lineColor))
            {
                g.FillPath(trackBrush, trackPath);
            }

            // Signature RHRemote Blue -> Purple gradient bar + shimmer
            float fillW = Math.Max(barH * 2f, barW * Math.Max(0.08f, Math.Min(1f, displayProgress)));
            var fillRect = new RectangleF(barX, barY, fillW, barH);
            using (var fillPath = RoundedRect(fillRect, barH / 2f))
            using (var fillBrush = new LinearGradientBrush(new PointF(barX, barY), new PointF(barX + fillW, barY), blueColor, purpleColor))
            {
                g.FillPath(fillBrush, fillPath);
                float shimmerCenter = barX + fillW * Math.Max(0f, Math.Min(1f, shimmerPhase));
                float shimmerHalf = Math.Max(S(24), fillW * 0.28f);
                var oldClip = g.Clip;
                g.SetClip(fillPath);
                var shimmerRect = new RectangleF(shimmerCenter - shimmerHalf, barY - 1, shimmerHalf * 2f, barH + 2);
                using (var shimmerBrush = new LinearGradientBrush(shimmerRect, Color.FromArgb(0, 255, 255, 255), Color.FromArgb(115, 255, 255, 255), LinearGradientMode.Horizontal))
                {
                    shimmerBrush.InterpolationColors = new ColorBlend(3)
                    {
                        Colors = new[] { Color.FromArgb(0, 255, 255, 255), Color.FromArgb(110, 255, 255, 255), Color.FromArgb(0, 255, 255, 255) },
                        Positions = new[] { 0f, 0.5f, 1f }
                    };
                    g.FillRectangle(shimmerBrush, shimmerRect);
                }
                g.Clip = oldClip;
            }

            // Footer row
            float footerY = S(132);
            g.DrawString("设备与连接配置已保留", footerFont, mutedBrush, new PointF(S(28), footerY));

            int secs = (int)elapsed.Elapsed.TotalSeconds;
            if (!string.IsNullOrEmpty(config.ScreenshotPath)) secs = stage <= 1 ? 1 : (stage == 2 ? 3 : 5);
            string rightText = string.Format("步骤 {0}/3 · {1:00}:{2:00}", currentStep, secs / 60, secs % 60);
            var rightFormat = new StringFormat { Alignment = StringAlignment.Far };
            g.DrawString(rightText, footerFont, mutedBrush, new PointF(bounds.Width - S(28), footerY), rightFormat);
        }

        // Outer border
        var outerRect = new RectangleF(0.5f, 0.5f, bounds.Width - 1f, bounds.Height - 1f);
        using (var borderPen = new Pen(lineColor, 1f))
        {
            if (dwmRounded && !forSnapshot)
                g.DrawRectangle(borderPen, 0, 0, bounds.Width - 1, bounds.Height - 1);
            else
            {
                using (var borderPath = RoundedRect(outerRect, S(12)))
                    g.DrawPath(borderPen, borderPath);
            }
        }
    }

    private void DrawLogo(Graphics g, RectangleF rect)
    {
        if (logoImage != null)
        {
            g.DrawImage(logoImage, rect);
            return;
        }
        // Fallback vector RHRemote icon (blue->purple gradient squircle with dual monitors)
        using (var bgPath = RoundedRect(rect, rect.Width * 0.23f))
        using (var bgBrush = new LinearGradientBrush(rect, Color.FromArgb(82, 116, 232), Color.FromArgb(139, 91, 226), LinearGradientMode.ForwardDiagonal))
            g.FillPath(bgBrush, bgPath);

        using (var backPen = new Pen(Color.FromArgb(165, 255, 255, 255), Math.Max(1.8f, rect.Width * 0.055f)))
        using (var frontPen = new Pen(Color.White, Math.Max(2f, rect.Width * 0.06f)))
        {
            var backMonitor = new RectangleF(rect.X + rect.Width * 0.16f, rect.Y + rect.Height * 0.20f, rect.Width * 0.50f, rect.Height * 0.36f);
            using (var bp = RoundedRect(backMonitor, rect.Width * 0.06f))
                g.DrawPath(backPen, bp);

            var frontMonitor = new RectangleF(rect.X + rect.Width * 0.34f, rect.Y + rect.Height * 0.40f, rect.Width * 0.48f, rect.Height * 0.36f);
            using (var fp = RoundedRect(frontMonitor, rect.Width * 0.06f))
                g.DrawPath(frontPen, fp);
        }
    }

    private string BuildVersionBadge()
    {
        if (!string.IsNullOrEmpty(config.FromVersion) && !string.IsNullOrEmpty(config.ToVersion) && config.FromVersion != config.ToVersion)
            return "v" + config.FromVersion + " → v" + config.ToVersion;
        if (!string.IsNullOrEmpty(config.ToVersion))
            return "v" + config.ToVersion;
        return "版本升级";
    }

    private static Font CreateFont(float sizePt, FontStyle style)
    {
        try { return new Font("Microsoft YaHei UI", sizePt, style, GraphicsUnit.Point); }
        catch { return new Font(SystemFonts.MessageBoxFont.FontFamily, sizePt, style, GraphicsUnit.Point); }
    }

    private int S(float logical)
    {
        return (int)Math.Round(logical * scale);
    }

    private static GraphicsPath RoundedRect(RectangleF r, float radius)
    {
        float d = Math.Min(Math.Min(r.Width, r.Height), radius * 2f);
        var path = new GraphicsPath();
        if (d <= 0.5f) { path.AddRectangle(r); return path; }
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e);
        if (e.Button == MouseButtons.Left)
        {
            ReleaseCapture();
            SendMessage(Handle, 0xA1, 0x2, 0);
        }
    }
}
