import SwiftUI
import AppKit
import Security

struct LogEntry: Identifiable {
    let id = UUID()
    let time: Date
    let level: String
    let message: String
    let component: String
    var summary: String {
        if message.first == "{" { return "连接结果与网络恢复详情" }
        return String(message.split(separator: "\n", omittingEmptySubsequences: false).first ?? "")
    }
    var hasDetails: Bool { message.contains("\n") || message.count > 180 }
}

@MainActor final class VPNModel: ObservableObject {
    @Published var username = UserDefaults.standard.string(forKey: "username") ?? ""
    @Published var password = ""
    @Published var remember = UserDefaults.standard.bool(forKey: "remember")
    @Published var status = "未连接"
    @Published var detail = "输入同济 VPN 账号后连接"
    @Published var active = false
    @Published var stopping = false
    @Published var connected = false
    @Published var log = ""
    @Published var entries: [LogEntry] = []
    @Published var logLevel = "all"
    @Published var logSearch = ""
    @Published var followLogs = true
    @Published var uploadBPS: Double = 0
    @Published var downloadBPS: Double = 0
    @Published var uploadBytes: Int64 = 0
    @Published var downloadBytes: Int64 = 0
    @Published var downloadHistory: [Double] = []
    @Published var uploadHistory: [Double] = []
    var filteredEntries: [LogEntry] { entries.filter { (logLevel == "all" || $0.level == logLevel) && (logSearch.isEmpty || $0.message.localizedCaseInsensitiveContains(logSearch)) } }
    func resetTraffic() { uploadBPS = 0; downloadBPS = 0 }
    static func bytes(_ value: Double) -> String { ByteCountFormatter.string(fromByteCount: Int64(max(0, value)), countStyle: .binary).replacingOccurrences(of: "bytes", with: "B").replacingOccurrences(of: "byte", with: "B") }
    static func graphCeiling(_ values: [Double]) -> Double {
        let peak = max(values.max() ?? 0, 16 * 1024)
        return pow(2, ceil(log2(peak)))
    }
    static func rateParts(_ value: Double) -> (String, String) {
        let units = ["B/s", "KB/s", "MB/s", "GB/s"]
        var scaled = max(0, value); var index = 0
        while scaled >= 1024 && index < units.count - 1 { scaled /= 1024; index += 1 }
        return (String(format: index == 0 ? "%.0f" : "%.2f", scaled), units[index])
    }
    @Published var showLog = false
    @Published var expandedLogs: Set<UUID> = []
    @Published var showConnectionSettings = false
    @Published var showPassword = false
    @Published var method = UserDefaults.standard.string(forKey: "authMethod") ?? "LDAP3"
    @Published var connectedAt: Date?
    private var process: Process?
    private var commandInput: FileHandle?
    private var pending = Data()
    private var observers: [NSObjectProtocol] = []
    private var receivedResult = false
    private var sessionID = UUID()
    var onStopped: (() -> Void)?
    private let credentialService = "local.motionvpn.prototype.credentials"

    func prepare() {
        guard observers.isEmpty else { return }
        if remember {
            let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: credentialService, kSecReturnData as String: true,
                kSecMatchLimit as String: kSecMatchLimitOne]
            var item: CFTypeRef?
            if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
               let data = item as? Data,
               let saved = try? JSONDecoder().decode([String: String].self, from: data) {
                username = saved["username"] ?? username
                password = saved["password"] ?? ""
            }
        }
        for (notification, command) in [(NSWorkspace.willSleepNotification, "sleep"), (NSWorkspace.didWakeNotification, "wake")] {
            observers.append(NSWorkspace.shared.notificationCenter.addObserver(forName: notification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.send(command) }
            })
        }
    }
    func saveCredentials() -> Bool {
        UserDefaults.standard.set(username, forKey: "username")
        UserDefaults.standard.set(method, forKey: "authMethod")
        UserDefaults.standard.set(remember, forKey: "remember")
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: credentialService]
        if !remember { SecItemDelete(query as CFDictionary); return true }
        guard let data = try? JSONEncoder().encode(["username": username, "password": password]) else { return false }
        let update = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return true }
        if update == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            if SecItemAdd(item as CFDictionary, nil) == errSecSuccess { return true }
        }
        detail = "无法保存钥匙串凭据，请关闭记住密码后重试"
        return false
    }
    func appendLog(_ text: String, level: String = "info", component: String = "app") {
        let time = Date()
        entries.append(LogEntry(time: time, level: level, message: text, component: component))
        if entries.count > 2000 { entries.removeFirst(entries.count - 2000); expandedLogs.formIntersection(Set(entries.map(\.id))) }
        log += "[\(time.formatted(date: .omitted, time: .standard))] [\(level.uppercased())] \(text)\n"
        if log.count > 64000 { log = String(log.suffix(48000)) }
    }
    func send(_ command: String) {
        guard active else { return }
        do { try commandInput?.write(contentsOf: Data((command + "\n").utf8)) }
        catch { appendLog("控制管道已关闭：\(error.localizedDescription)") }
    }
    func connect() {
        guard !active else { return }
        NSApplication.shared.keyWindow?.makeFirstResponder(nil)
        username = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !username.isEmpty, !password.isEmpty else { return }
        guard saveCredentials(), let resources = Bundle.main.resourceURL else { return }
        let backend = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MotionVPNPrototype/go-backend", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: backend.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: backend.path) { try FileManager.default.removeItem(at: backend) }
            try FileManager.default.createDirectory(at: backend, withIntermediateDirectories: true)
            let executable = backend.appendingPathComponent("motionvpn")
            try FileManager.default.copyItem(at: resources.appendingPathComponent("motionvpn"), to: executable)
            let task = Process(), input = Pipe(), output = Pipe()
            let id = UUID(); sessionID = id
            task.executableURL = executable
            task.arguments = []
            task.currentDirectoryURL = backend
            task.environment = ProcessInfo.processInfo.environment
            task.standardInput = input; task.standardOutput = output; task.standardError = output
            pending = Data(); receivedResult = false
            resetTraffic(); uploadBytes = 0; downloadBytes = 0; uploadHistory = []; downloadHistory = []
            appendLog("开始连接 vpn.tongji.cn · 认证方式 \(method)")
            status = "正在连接"; detail = "正在验证 VPN 账号…"
            active = true; stopping = false; connected = false; connectedAt = nil
            output.fileHandleForReading.readabilityHandler = { [weak self] handle in
                let data = handle.availableData
                guard !data.isEmpty else { return }
                Task { @MainActor in
                    guard self?.sessionID == id else { return }
                    self?.consume(data)
                }
            }
            task.terminationHandler = { [weak self] task in
                let tail = output.fileHandleForReading.readDataToEndOfFile()
                output.fileHandleForReading.readabilityHandler = nil
                Task { @MainActor in
                    guard let self, self.sessionID == id else { return }
                    self.consume(tail)
                    self.active = false; self.stopping = false; self.connected = false; self.connectedAt = nil
                    self.resetTraffic()
                    self.commandInput = nil; self.process = nil
                    if !self.receivedResult {
                        self.status = task.terminationStatus == 0 ? "已断开" : "连接失败"
                        self.detail = task.terminationStatus == 0 ? "连接已结束" : "后端异常退出，请查看连接日志"
                    }
                    self.onStopped?()
                }
            }
            try task.run()
            process = task; commandInput = input.fileHandleForWriting
            let credentials = try JSONEncoder().encode(["username": username, "password": password, "method": method])
            try input.fileHandleForWriting.write(contentsOf: credentials + Data([10]))
        } catch {
            process?.terminate()
            active = false; stopping = false; status = "启动失败"; detail = error.localizedDescription
            appendLog(detail)
        }
    }
    func consume(_ data: Data) {
        pending.append(data)
        while let newline = pending.firstIndex(of: 10) {
            let line = Data(pending[..<newline]); pending.removeSubrange(...newline)
            guard let object = try? JSONSerialization.jsonObject(with: line) as? [String: Any], let event = object["event"] as? String else {
                if let text = String(data: line, encoding: .utf8), !text.isEmpty { appendLog(text) }
                continue
            }
            switch event {
            case "traffic":
                if let traffic = object["traffic"] as? [String: Any] {
                    uploadBPS = traffic["upload_bps"] as? Double ?? 0
                    downloadBPS = traffic["download_bps"] as? Double ?? 0
                    uploadBytes = (traffic["upload_bytes"] as? NSNumber)?.int64Value ?? 0
                    downloadBytes = (traffic["download_bytes"] as? NSNumber)?.int64Value ?? 0
                    uploadHistory.append(uploadBPS); downloadHistory.append(downloadBPS)
                    if uploadHistory.count > 60 { uploadHistory.removeFirst(); downloadHistory.removeFirst() }
                }
            case "log": appendLog(object["message"] as? String ?? "", level: object["level"] as? String ?? "info", component: object["component"] as? String ?? "backend")
            case "connecting":
                receivedResult = false
                if !stopping { status = "正在连接"; detail = "正在认证并读取隧道配置…" }
            case "progress":
                if !stopping && !connected { detail = object["message"] as? String ?? "正在准备网络…" }
                appendLog(object["message"] as? String ?? "")
            case "connected":
                appendLog("隧道已连接 · \(object["interface"] as? String ?? "") · IPv4")
                if !stopping {
                    status = "已连接"; connected = true; connectedAt = Date()
                    detail = "IPv4 流量通过 VPN · \(object["interface"] as? String ?? "")"
                }
            case "reconnecting":
                resetTraffic(); appendLog("连接中断，稍后重试", level: "warning")
                connected = false; connectedAt = nil; receivedResult = false
                if !stopping { status = "等待重连"; detail = "将在 \(object["seconds"] as? Int ?? 2) 秒后重新连接" }
            case "sleeping":
                resetTraffic(); appendLog("系统睡眠，连接已暂停")
                connected = false; connectedAt = nil; receivedResult = false
                if !stopping { status = "已暂停"; detail = "唤醒电脑后自动连接" }
            case "failed":
                receivedResult = true; status = "连接失败"; detail = object["message"] as? String ?? "未知错误"
                appendLog(detail, level: "error")
            case "result":
                resetTraffic()
                guard let result = object["result"] as? [String: Any] else { continue }
                connected = false; connectedAt = nil
                receivedResult = true
                let site = result["site_test"] as? [String: Any] ?? [:]
                let modified = site["host_network_modified"] as? Bool == true
                let clean = site["cleanup_confirmed"] as? Bool == true && site["parent_state_restored"] as? Bool == true && site["outer_route_restored"] as? Bool == true
                let error = result["error"] as? String ?? site["connection_error"] as? String
                status = modified && !clean ? "网络恢复需要检查" : (error == nil ? "已断开" : "连接失败")
                detail = error ?? (clean ? "网络设置已恢复" : "连接已结束")
                if let data = try? JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]), let text = String(data: data, encoding: .utf8) { appendLog(text, level: (modified && !clean) || error != nil ? "error" : "info") }
            default: break
            }
        }
    }
    static func selfTest() {
        let model = VPNModel()
        let traffic = "{\"event\":\"traffic\",\"traffic\":{\"upload_bps\":1024,\"download_bps\":4096,\"upload_bytes\":2048,\"download_bytes\":8192}}\n"
        let data = Data(traffic.utf8)
        model.consume(data.prefix(25)); assert(model.downloadBPS == 0)
        model.consume(data.dropFirst(25)); assert(model.downloadBPS == 4096 && model.uploadBytes == 2048)
        model.consume(Data("{\"event\":\"log\",\"level\":\"warning\",\"message\":\"retry test\"}\n".utf8))
        model.logLevel = "warning"; assert(model.filteredEntries.count == 1)
        model.logSearch = "missing"; assert(model.filteredEntries.isEmpty)
        for _ in 0..<70 { model.consume(data) }; assert(model.downloadHistory.count == 60)
        for _ in 0..<2010 { model.appendLog("bounded") }; assert(model.entries.count == 2000)
        model.consume(Data("{\"event\":\"sleeping\"}\n".utf8)); assert(model.downloadBPS == 0)
        assert(graphCeiling([80, 348]) == 16384)
        assert(graphCeiling([20000]) == 32768)
        let appDelegate = AppDelegate()
        appDelegate.model = model
        model.active = false
        assert(!appDelegate.applicationShouldTerminateAfterLastWindowClosed(NSApplication.shared))
        assert(appDelegate.applicationShouldTerminate(NSApplication.shared) == .terminateNow)
        model.active = true
        assert(appDelegate.applicationShouldTerminate(NSApplication.shared) == .terminateLater)
        assert(model.stopping)
        model.active = false; model.onStopped = nil
        assert(rateParts(80).0 == "80" && rateParts(80).1 == "B/s")
        assert(rateParts(1280).0 == "1.25" && rateParts(1280).1 == "KB/s")
        assert(rateParts(-1).0 == "0")
        let multiline = LogEntry(time: Date(), level: "info", message: "诊断完成\n更多详情", component: "probe")
        assert(multiline.summary == "诊断完成" && multiline.hasDetails)
        let jsonEntry = LogEntry(time: Date(), level: "info", message: "{\n  \"ok\": true\n}", component: "result")
        assert(jsonEntry.summary == "连接结果与网络恢复详情")
        model.expandedLogs.insert(model.entries[0].id)
        for _ in 0..<2001 { model.appendLog("new entry") }
        assert(model.expandedLogs.isEmpty)
        print("Open TJ UI model tests passed: framing, rates, totals, filter, search, history, log bounds, reset, units, summaries, expansion eviction")
    }
    func disconnect() {
        guard active, !stopping else { return }
        stopping = true; status = "正在断开"; detail = "正在关闭隧道并恢复网络设置…"
        appendLog("收到断开请求，等待网络恢复")
        send("stop")
    }
}

@MainActor final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    var model: VPNModel?
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard let model, model.active else { return .terminateNow }
        model.onStopped = { NSApplication.shared.reply(toApplicationShouldTerminate: true) }
        model.disconnect()
        return .terminateLater
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        if !UserDefaults.standard.bool(forKey: "backgroundCloseExplained") {
            let alert = NSAlert()
            alert.messageText = "Open TJ 会继续在菜单栏运行"
            alert.informativeText = "关闭窗口不会断开 VPN。你可以从菜单栏查看状态、断开连接或重新打开窗口。选择“退出 Open TJ”会先断开并恢复网络。"
            alert.addButton(withTitle: "继续在后台运行")
            alert.addButton(withTitle: "取消")
            guard alert.runModal() == .alertFirstButtonReturn else { return false }
            UserDefaults.standard.set(true, forKey: "backgroundCloseExplained")
        }
        return true
    }
}

struct ScrollPauseObserver: NSViewRepresentable {
    var pause: () -> Void
    final class Coordinator {
        var token: Any?
        weak var view: NSView?
        var pause: () -> Void = {}
        deinit { if let token { NSEvent.removeMonitor(token) } }
    }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeNSView(context: Context) -> NSView {
        let view = NSView(); context.coordinator.view = view
        let coordinator = context.coordinator
        coordinator.token = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak coordinator] event in
            guard let coordinator, let view = coordinator.view, event.window === view.window else { return event }
            let point = view.convert(event.locationInWindow, from: nil)
            if view.bounds.contains(point), event.scrollingDeltaY > 0 { coordinator.pause() }
            return event
        }
        return view
    }
    func updateNSView(_ view: NSView, context: Context) { context.coordinator.pause = pause }
}

// Resize the actual NSWindow, not only SwiftUI's minimum layout size.
struct WindowSizing: NSViewRepresentable {
    let expanded: Bool
    var windowDelegate: AppDelegate? = nil
    final class Host: NSView {
        weak var closingDelegate: AppDelegate?
        var expanded = false
        var applied: Bool?
        var expandedSize = NSSize(width: 940, height: 650)
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            DispatchQueue.main.async { [weak self] in self?.apply() }
        }
        func apply() {
            guard let window else { return }
            if let closingDelegate { window.delegate = closingDelegate }
            guard applied != expanded else { return }
            guard !window.styleMask.contains(.fullScreen) else { return }
            if applied == true { expandedSize = window.contentLayoutRect.size }
            let size = expanded ? NSSize(width: max(780, expandedSize.width), height: max(560, expandedSize.height)) : NSSize(width: 420, height: 600)
            var frame = window.frameRect(forContentRect: NSRect(origin: .zero, size: size))
            frame.origin = NSPoint(x: window.frame.minX, y: window.frame.maxY - frame.height)
            if let screen = window.screen {
                frame.size.width = min(frame.width, screen.visibleFrame.width)
                frame.size.height = min(frame.height, screen.visibleFrame.height)
                frame.origin.x = min(max(frame.minX, screen.visibleFrame.minX), screen.visibleFrame.maxX - frame.width)
                frame.origin.y = min(max(frame.minY, screen.visibleFrame.minY), screen.visibleFrame.maxY - frame.height)
            }
            let animate = applied != nil && !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
            applied = expanded
            window.setFrame(frame, display: true, animate: animate)
        }
    }
    func makeNSView(context: Context) -> Host { let view = Host(); view.expanded = expanded; return view }
    func updateNSView(_ view: Host, context: Context) {
        view.closingDelegate = windowDelegate
        view.expanded = expanded
        DispatchQueue.main.async { [weak view] in view?.apply() }
    }
}

struct SplitPersistence: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { NSView() }
    func updateNSView(_ view: NSView, context: Context) {
        DispatchQueue.main.async {
            var ancestor: NSView? = view
            while let current = ancestor {
                if let split = current as? NSSplitView {
                    if split.autosaveName == nil { split.autosaveName = "OpenTJMainSplit" }
                    break
                }
                ancestor = current.superview
            }
        }
    }
}

struct TrafficGraph: View {
    let values: [Double]
    let color: Color
    var body: some View {
        Canvas { context, size in
            let baseline = Path(CGRect(x: 0, y: size.height - 1, width: size.width, height: 1))
            context.fill(baseline, with: .color(.secondary.opacity(0.15)))
            guard values.count > 1 else { return }
            let peak = VPNModel.graphCeiling(values)
            var path = Path()
            for (index, value) in values.enumerated() {
                let point = CGPoint(x: size.width * Double(index) / 59, y: size.height * (1 - value / peak))
                if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
            }
            context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
        }.frame(height: 42).accessibilityHidden(true)
    }
}

struct SpeedColumn: View {
    let title: String
    let symbol: String
    let rate: Double
    let total: Int64
    let history: [Double]
    let color: Color
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: symbol).font(.subheadline).foregroundStyle(color)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(VPNModel.rateParts(rate).0).font(.system(size: 27, weight: .semibold, design: .rounded)).monospacedDigit()
                Text(VPNModel.rateParts(rate).1).font(.caption).foregroundStyle(.secondary)
            }.lineLimit(1).minimumScaleFactor(0.7)
            TrafficGraph(values: history, color: color)
            Text("刻度 " + VPNModel.bytes(VPNModel.graphCeiling(history)) + "/s").font(.caption2).foregroundStyle(.tertiary)
            Text("峰值 " + VPNModel.bytes(history.max() ?? 0) + "/s").font(.caption2).foregroundStyle(.secondary)
            Text("累计 " + VPNModel.bytes(Double(total))).font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct LogPanel: View {
    @ObservedObject var model: VPNModel
    private var warnings: Int { model.entries.filter { $0.level == "warning" }.count }
    private var errors: Int { model.entries.filter { $0.level == "error" }.count }
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Text("日志").font(.headline)
                if warnings > 0 { Label("\(warnings)", systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
                if errors > 0 { Label("\(errors)", systemImage: "xmark.circle").foregroundStyle(.red) }
                Spacer()
                Toggle("跟随", isOn: $model.followLogs).toggleStyle(.checkbox)
                Button { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(model.log, forType: .string) } label: { Image(systemName: "doc.on.doc") }.help("复制日志")
                Button { model.entries.removeAll(); model.expandedLogs.removeAll(); model.log = "" } label: { Image(systemName: "trash") }.help("清空界面日志")
            }.font(.caption).buttonStyle(.borderless).padding(14)
            HStack(spacing: 8) {
                Picker("级别", selection: $model.logLevel) {
                    Text("全部").tag("all"); Text("Info").tag("info"); Text("Warning").tag("warning"); Text("Error").tag("error")
                }.labelsHidden().frame(width: 100)
                TextField("搜索消息", text: $model.logSearch).textFieldStyle(.roundedBorder)
            }.controlSize(.small).padding(.horizontal, 14).padding(.bottom, 12)
            Divider()
            ScrollViewReader { proxy in
                VStack(spacing: 0) {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            if model.filteredEntries.isEmpty { Text("暂无匹配日志").foregroundStyle(.secondary).padding(18) }
                            ForEach(model.filteredEntries) { entry in
                                VStack(alignment: .leading, spacing: 6) {
                                    HStack(alignment: .top, spacing: 8) {
                                        Text(entry.time.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits)))
                                            .foregroundStyle(.secondary).frame(width: 59, alignment: .leading)
                                        Text(entry.level == "warning" ? "WARN" : entry.level.uppercased())
                                            .foregroundStyle(entry.level == "error" ? Color.red : entry.level == "warning" ? Color.orange : Color.secondary).frame(width: 38, alignment: .leading)
                                        Text(entry.component).foregroundStyle(.tertiary).frame(width: 54, alignment: .leading).lineLimit(1)
                                        Text(entry.hasDetails ? entry.summary : entry.message).font(.system(size: 12)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).lineLimit(entry.hasDetails ? 2 : nil)
                                        if entry.hasDetails {
                                            Button {
                                                if !model.expandedLogs.insert(entry.id).inserted { model.expandedLogs.remove(entry.id) }
                                            } label: { Image(systemName: model.expandedLogs.contains(entry.id) ? "chevron.down" : "chevron.right") }
                                                .buttonStyle(.plain).help("展开或收起完整详情")
                                        }
                                    }
                                    if model.expandedLogs.contains(entry.id) {
                                        Text(entry.message).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                                            .padding(10).background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 6))
                                    }
                                }.font(.system(size: 11, design: .monospaced)).padding(.horizontal, 12).padding(.vertical, 9).id(entry.id)
                                Divider().opacity(0.25)
                            }
                        }
                    }.background(ScrollPauseObserver { model.followLogs = false })
                        .onChange(of: model.entries.last?.id) { _, _ in
                            if model.followLogs, let id = model.filteredEntries.last?.id { proxy.scrollTo(id, anchor: .bottom) }
                        }
                        .onChange(of: model.followLogs) { _, follow in
                            if follow, let id = model.filteredEntries.last?.id { proxy.scrollTo(id, anchor: .bottom) }
                        }
                    if !model.followLogs {
                        Divider()
                        Button("回到最新日志") { model.followLogs = true }.buttonStyle(.borderless).padding(10)
                    }
                }
            }
        }.frame(minWidth: 380, maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(nsColor: .textBackgroundColor))
    }
}

struct ConnectionInfoRow: View {
    let title: String
    let value: String
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).foregroundStyle(.secondary)
            Spacer(minLength: 16)
            Text(value).foregroundStyle(.primary).textSelection(.enabled)
        }.font(.callout)
    }
}

struct MenuBarPanel: View {
    @ObservedObject var model: VPNModel
    @Environment(\.openWindow) private var openWindow
    func showMain() { openWindow(id: "main"); NSApp.activate(ignoringOtherApps: true) }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Image(systemName: model.connected ? "checkmark.shield.fill" : "network").foregroundStyle(model.connected ? .green : .secondary)
                Text("Open TJ").font(.headline)
                Spacer()
                Text(model.status).font(.caption).foregroundStyle(.secondary)
            }
            if let start = model.connectedAt {
                HStack { Text("连接时长"); Spacer(); Text(start, style: .timer).monospacedDigit() }.font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                Label(VPNModel.bytes(model.downloadBPS) + "/s", systemImage: "arrow.down").foregroundStyle(.blue)
                Spacer()
                Label(VPNModel.bytes(model.uploadBPS) + "/s", systemImage: "arrow.up").foregroundStyle(.teal)
            }.font(.system(.callout, design: .rounded)).monospacedDigit()
            Divider()
            if model.active {
                Button(model.stopping ? "正在恢复网络…" : "断开 VPN") { model.disconnect() }.disabled(model.stopping)
            } else {
                Button("连接 VPN") {
                    if model.username.isEmpty || model.password.isEmpty { showMain() } else { model.connect() }
                }
            }
            Button("打开主窗口") { showMain() }
            Divider()
            Button("退出 Open TJ") { NSApp.terminate(nil) }
        }.buttonStyle(.borderless).padding(18).frame(width: 280)
    }
}

struct ConnectionForm: View {
    @ObservedObject var model: VPNModel
    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            TextField("VPN 用户名", text: $model.username).textContentType(.username)
            HStack {
                Group {
                    if model.showPassword { TextField("VPN 密码", text: $model.password) }
                    else { SecureField("VPN 密码", text: $model.password) }
                }.onSubmit { model.connect() }
                Button { model.showPassword.toggle() } label: { Image(systemName: model.showPassword ? "eye.slash" : "eye") }.buttonStyle(.plain).help("显示或隐藏密码")
            }
            Picker("认证方式", selection: $model.method) {
                ForEach(["LDAP3", "LDAP2", "LDAP", "default_method_localdb"], id: \.self) { Text($0).tag($0) }
            }
            Toggle("在钥匙串中记住密码", isOn: $model.remember).font(.callout)
            if model.active { Text("断开连接后可修改设置").font(.caption).foregroundStyle(.secondary) }
        }.textFieldStyle(.roundedBorder).disabled(model.active)
    }
}

struct Dashboard: View {
    @ObservedObject var model: VPNModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: model.connected ? "checkmark.shield.fill" : "network.badge.shield.half.filled")
                    .font(.system(size: 31, weight: .regular)).foregroundStyle(model.connected ? .green : .secondary)
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 7) {
                        Text(model.status).font(.title3.weight(.semibold))
                        if model.active && !model.connected { ProgressView().controlSize(.mini) }
                    }
                    if let start = model.connectedAt {
                        HStack(spacing: 4) { Text("连接时长"); Text(start, style: .timer).monospacedDigit() }.font(.caption).foregroundStyle(.secondary)
                    } else { Text("同济大学校园网络").font(.caption).foregroundStyle(.secondary) }
                }
                Spacer(minLength: 8)
                if model.active { disconnectButton }
            }.padding(22)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if !model.connected {
                        Text(model.detail).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                    }
                    VStack(alignment: .leading, spacing: 14) {
                        HStack { Text("网络流量").font(.headline); Spacer(); Text("最近 60 秒").font(.caption).foregroundStyle(.secondary) }
                        HStack(spacing: 18) {
                            SpeedColumn(title: "下载", symbol: "arrow.down", rate: model.downloadBPS, total: model.downloadBytes, history: model.downloadHistory, color: .blue)
                            Divider()
                            SpeedColumn(title: "上传", symbol: "arrow.up", rate: model.uploadBPS, total: model.uploadBytes, history: model.uploadHistory, color: .teal)
                        }.fixedSize(horizontal: false, vertical: true)
                    }.padding(18).background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 12))
                    VStack(spacing: 11) {
                        ConnectionInfoRow(title: "服务器", value: "vpn.tongji.cn")
                        ConnectionInfoRow(title: "连接模式", value: "IPv4 全局")
                        if model.active { ConnectionInfoRow(title: "认证方式", value: model.method) }
                    }.font(.callout).foregroundStyle(.secondary)
                    Divider()
                    if !model.active {
                        ConnectionForm(model: model)
                    } else {
                        DisclosureGroup("连接设置", isExpanded: $model.showConnectionSettings) {
                            VStack(spacing: 10) {
                                ConnectionInfoRow(title: "账号", value: model.username)
                                ConnectionInfoRow(title: "认证方式", value: model.method)
                                Text("断开后可编辑账号设置").font(.caption).foregroundStyle(.secondary)
                            }.padding(.top, 14)
                        }.font(.callout)
                    }
                }.padding(22)
            }
            if !model.active || model.stopping {
            Divider()
            VStack(spacing: 10) {
                if !model.active {
                    Button { model.connect() } label: { Label("连接 VPN", systemImage: "power").frame(maxWidth: .infinity).padding(.vertical, 4) }
                        .buttonStyle(.borderedProminent).controlSize(.large)
                        .disabled(model.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.password.isEmpty)
                }
                Text(model.stopping ? "请稍候，正在恢复网络设置" : "断开后自动恢复网络设置")
                    .font(.caption).foregroundStyle(.secondary)
            }.padding(18)
            }
        }.frame(minWidth: 380, idealWidth: 400, maxWidth: 480, maxHeight: .infinity)
            .background(Color(nsColor: .windowBackgroundColor))
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: model.active)
    }
    @ViewBuilder private var disconnectButton: some View {
        if #available(macOS 26.0, *) {
            Button(model.stopping ? "恢复中…" : "断开") { model.disconnect() }.buttonStyle(.glass).disabled(model.stopping)
        } else {
            Button(model.stopping ? "恢复中…" : "断开") { model.disconnect() }.buttonStyle(.bordered).disabled(model.stopping)
        }
    }
}

@main struct OpenTJApp: App {
    init() {
        if CommandLine.arguments.contains("--self-test") { VPNModel.selfTest(); exit(0) }
    }
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var model = VPNModel()
    var body: some Scene {
        Window("Open TJ", id: "main") {
            Group {
                if model.showLog {
                    HSplitView {
                        Dashboard(model: model).background(SplitPersistence())
                        LogPanel(model: model)
                    }
                } else {
                    Dashboard(model: model)
                }
            }.frame(minWidth: model.showLog ? 780 : 380, maxWidth: model.showLog ? nil : 480, minHeight: 560)
                .background(WindowSizing(expanded: model.showLog, windowDelegate: delegate))
                .toolbar {
                    ToolbarItem { Button { model.showLog.toggle() } label: { Label(model.showLog ? "隐藏日志" : "显示日志", systemImage: "sidebar.right") }.tint(model.showLog ? .accentColor : .secondary).help(model.showLog ? "隐藏日志（⌘L）" : "显示日志（⌘L）") }
                }
                .onAppear { delegate.model = model; model.prepare() }
        }.defaultSize(width: 420, height: 600)
            .windowResizability(.contentSize)
        .commands {
            CommandGroup(replacing: .newItem) {}
            CommandGroup(after: .sidebar) {
                Button(model.showLog ? "隐藏日志" : "显示日志") { model.showLog.toggle() }.keyboardShortcut("l", modifiers: .command)
            }
        }
        MenuBarExtra("Open TJ", systemImage: model.connected ? "checkmark.shield.fill" : model.active ? "arrow.triangle.2.circlepath" : "network") {
            MenuBarPanel(model: model)
                .onAppear { delegate.model = model; model.prepare() }
        }.menuBarExtraStyle(.window)
    }
}
