import AppKit
import CoreGraphics

private func color(_ rgb: UInt32) -> NSColor {
    NSColor(srgbRed: CGFloat((rgb >> 16) & 255) / 255,
            green: CGFloat((rgb >> 8) & 255) / 255,
            blue: CGFloat(rgb & 255) / 255, alpha: 1)
}

private struct Palette {
    let page, surface, ink, muted, line, blue, purple, soft, success: NSColor

    init(dark: Bool) {
        page = color(dark ? 0x151b29 : 0xf6f7fa)
        surface = color(dark ? 0x1b2232 : 0xffffff)
        ink = color(dark ? 0xe1e8f6 : 0x34415b)
        muted = color(dark ? 0xa4b1c8 : 0x69778e)
        line = color(dark ? 0x303c53 : 0xe5eaf4)
        blue = color(dark ? 0x6f8af1 : 0x5274e8)
        purple = color(dark ? 0xba9aef : 0x8b5be2)
        soft = color(dark ? 0x253452 : 0xedf2ff)
        success = color(dark ? 0x8bc7b1 : 0x369c81)
    }
}

private enum Stage: Int {
    case closing, preparing, installing, starting

    var message: String {
        ["正在关闭当前客户端…", "正在准备安装新版 RHRemote…",
         "正在安装新版 RHRemote…", "正在启动新版 RHRemote…"][rawValue]
    }
    var step: Int { max(1, rawValue) }
    var base: CGFloat { [0.14, 0.36, 0.68, 0.90][rawValue] }
    var limit: CGFloat { [0.24, 0.62, 0.86, 0.96][rawValue] }
}

private final class SplashView: NSView {
    static let size = NSSize(width: 456, height: 188)
    private let palette: Palette
    private let logo: NSImage?
    private let badge: String
    private let began = ProcessInfo.processInfo.systemUptime
    private let drawn: () -> Void
    private var painted = false
    private var progress: CGFloat = 0.10
    private var target: CGFloat = 0.14
    var stage: Stage = .closing
    override var isFlipped: Bool { true }

    init(logo: NSImage?, badge: String, dark: Bool, drawn: @escaping () -> Void) {
        self.logo = logo
        self.badge = badge
        self.palette = Palette(dark: dark)
        self.drawn = drawn
        super.init(frame: NSRect(origin: .zero, size: Self.size))
    }

    required init?(coder: NSCoder) { nil }

    func animate() {
        target = min(stage.limit, max(stage.base, target + 0.0018))
        progress += (target - progress) * 0.12
        needsDisplay = true
    }

    private func text(_ value: String, size: CGFloat, ink: NSColor, bold: Bool = false) -> NSAttributedString {
        NSAttributedString(string: value, attributes: [
            .font: NSFont.systemFont(ofSize: size, weight: bold ? .bold : .regular),
            .foregroundColor: ink,
        ])
    }

    private func shape(_ rect: NSRect, radius: CGFloat, fill: NSColor, stroke: NSColor? = nil) {
        let path = NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius)
        fill.setFill()
        path.fill()
        if let stroke {
            stroke.setStroke()
            path.lineWidth = 1
            path.stroke()
        }
    }

    override func draw(_ dirtyRect: NSRect) {
        let p = palette
        shape(bounds.insetBy(dx: 0.5, dy: 0.5), radius: 12, fill: p.page, stroke: p.line)
        shape(bounds.insetBy(dx: 14, dy: 14), radius: 12, fill: p.surface, stroke: p.line)
        logo?.draw(in: NSRect(x: 28, y: 26, width: 46, height: 46), from: .zero,
                   operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil)

        let title = text("RHRemote", size: 18, ink: p.ink, bold: true)
        title.draw(at: NSPoint(x: 86, y: 24))
        let version = text(badge, size: 11.33, ink: p.blue, bold: true)
        let versionBox = NSRect(x: 92 + title.size().width, y: 26, width: version.size().width + 12, height: 20)
        shape(versionBox, radius: 6, fill: p.soft, stroke: p.blue.withAlphaComponent(0.35))
        version.draw(at: NSPoint(x: versionBox.minX + 6, y: versionBox.midY - version.size().height / 2))

        let tag = text("Moonlight 桌面端", size: 10.67, ink: p.muted)
        let tagX = bounds.maxX - 28 - tag.size().width
        shape(NSRect(x: tagX - 10, y: 33, width: 6, height: 6), radius: 3, fill: p.success)
        tag.draw(at: NSPoint(x: tagX, y: 29))
        text(stage.message, size: 12, ink: p.muted).draw(at: NSPoint(x: 86, y: 51))

        var x: CGFloat = 28
        for (index, name) in ["1. 准备安装", "2. 安装更新", "3. 启动客户端"].enumerated() {
            let done = index + 1 < stage.step
            let active = index + 1 == stage.step
            let label = text((done ? "✓ " : "● ") + name, size: 10.67,
                             ink: done ? p.success : (active ? p.blue : p.muted))
            let box = NSRect(x: x, y: 84, width: label.size().width + 10, height: 18)
            if active { shape(box, radius: 5, fill: p.soft) }
            label.draw(at: NSPoint(x: x + 5, y: box.midY - label.size().height / 2))
            x += box.width + 8
        }

        let track = NSRect(x: 28, y: 112, width: bounds.width - 56, height: 6)
        shape(track, radius: 3, fill: p.line)
        let bar = NSRect(x: track.minX, y: track.minY, width: max(12, track.width * progress), height: track.height)
        let barPath = NSBezierPath(roundedRect: bar, xRadius: 3, yRadius: 3)
        NSGradient(starting: p.blue, ending: p.purple)?.draw(in: barPath, angle: 0)
        let elapsed = ProcessInfo.processInfo.systemUptime - began
        let sweep = (elapsed / 0.96).truncatingRemainder(dividingBy: 1.8) - 0.4
        let center = bar.minX + bar.width * sweep
        let reach = max(24, bar.width * 0.28)
        let clear = NSColor.white.withAlphaComponent(0)
        let gloss = NSGradient(colors: [clear, .white.withAlphaComponent(0.43), clear])
        NSGraphicsContext.saveGraphicsState()
        barPath.addClip()
        gloss?.draw(in: NSRect(x: center - reach, y: bar.minY, width: reach * 2, height: 6), angle: 0)
        NSGraphicsContext.restoreGraphicsState()

        let seconds = Int(elapsed)
        text("设备与连接配置已保留", size: 10.67, ink: p.muted).draw(at: NSPoint(x: 28, y: 132))
        let clock = text(String(format: "步骤 %d/3 · %02d:%02d", stage.step, seconds / 60, seconds % 60),
                         size: 10.67, ink: p.muted)
        clock.draw(at: NSPoint(x: bounds.maxX - 28 - clock.size().width, y: 132))
        if !painted {
            painted = true
            drawn()
        }
    }
}

private final class TransitionDriver: NSObject {
    private let directory: URL
    private let installation: URL
    private let staging: URL
    private let owner: NSRunningApplication?
    private let ownerPID: pid_t
    private let version: String
    private let panel: NSPanel
    private let view: SplashView
    private let startedAt = Date()
    private let began = ProcessInfo.processInfo.systemUptime
    private var lastProbe: TimeInterval = 0

    init(directory: URL, installation: URL, staging: URL, ownerPID: pid_t, from: String, to: String, dark: Bool) {
        self.directory = directory
        self.installation = installation
        self.staging = staging
        self.ownerPID = ownerPID
        self.owner = NSRunningApplication(processIdentifier: ownerPID)
        self.version = to
        self.view = SplashView(logo: NSImage(contentsOf: directory.appendingPathComponent("logo.png")),
                               badge: "v\(from) → v\(to)", dark: dark) {
            try? Data().write(to: directory.appendingPathComponent("ready"))
        }
        self.panel = NSPanel(contentRect: view.frame, styleMask: [.borderless, .nonactivatingPanel],
                             backing: .buffered, defer: false)
        super.init()
        panel.contentView = view
        panel.title = "RHRemote · 正在安装更新"
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true
        panel.isMovableByWindowBackground = true
        panel.hidesOnDeactivate = false
        panel.level = .floating
        panel.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary, .ignoresCycle]
    }

    func run() {
        let cursor = NSEvent.mouseLocation
        if let screen = NSScreen.screens.first(where: { $0.frame.contains(cursor) }) ?? NSScreen.main {
            let visible = screen.visibleFrame
            panel.setFrameOrigin(NSPoint(x: visible.midX - view.frame.width / 2,
                                         y: visible.midY - view.frame.height / 2))
        }
        panel.orderFrontRegardless()
        let timer = Timer(timeInterval: 1 / 60, target: self, selector: #selector(tick(_:)),
                          userInfo: nil, repeats: true)
        RunLoop.main.add(timer, forMode: .common)
        NSApplication.shared.run()
    }

    @objc private func tick(_ timer: Timer) {
        view.animate()
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastProbe >= 0.10 else { return }
        lastProbe = now
        let files = FileManager.default
        if now - began >= 90 || files.fileExists(atPath: directory.appendingPathComponent("cancel").path) {
            finish(timer)
            return
        }
        if let owner, !owner.isTerminated { return }
        if owner == nil && kill(ownerPID, 0) == 0 { return }

        let contents = installation.appendingPathComponent("Contents")
        let metadata = NSDictionary(contentsOf: contents.appendingPathComponent("Info.plist"))
        guard metadata?["CFBundleShortVersionString"] as? String == version else {
            if let pending = try? files.contentsOfDirectory(atPath: staging.path), !pending.isEmpty {
                view.stage = .installing
            } else if view.stage == .closing {
                view.stage = .preparing
            }
            return
        }
        view.stage = .starting
        let executable = contents.appendingPathComponent("MacOS/RHRemote").path
        // Only use window metadata. This requires no Screen Recording permission.
        let options: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
        guard let windows = CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]] else { return }
        for app in NSWorkspace.shared.runningApplications {
            guard app.processIdentifier != ownerPID, app.isFinishedLaunching,
                  let launched = app.launchDate, launched >= startedAt,
                  app.executableURL?.resolvingSymlinksInPath().path == executable else { continue }
            if windows.contains(where: {
                $0[kCGWindowOwnerPID as String] as? pid_t == app.processIdentifier &&
                $0[kCGWindowLayer as String] as? Int == 0 &&
                ($0[kCGWindowAlpha as String] as? Double ?? 0) > 0
            }) {
                finish(timer)
                return
            }
        }
    }

    private func finish(_ timer: Timer) {
        timer.invalidate()
        panel.close()
        // Remove only the four files in the helper's own private directory.
        for name in ["ready", "cancel", "logo.png", "RHRemote.UpdateSplash"] {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
        }
        _ = rmdir(directory.path)
        NSApplication.shared.terminate(nil)
    }
}

let args = CommandLine.arguments
guard args.count == 7, let ownerPID = pid_t(args[3]), ownerPID > 0 else { exit(2) }
let directory = URL(fileURLWithPath: args[0]).deletingLastPathComponent()
guard directory.lastPathComponent.hasPrefix("rhremote-update-") else { exit(2) }
NSApplication.shared.setActivationPolicy(.accessory)
TransitionDriver(directory: directory, installation: URL(fileURLWithPath: args[1]),
                 staging: URL(fileURLWithPath: args[2]), ownerPID: ownerPID,
                 from: args[4], to: args[5], dark: args[6] == "dark").run()
