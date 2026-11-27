import UIKit
import WebKit
import QuickLook
import UniformTypeIdentifiers
import Security
import BackgroundTasks
import Network
import Darwin
import UserNotifications
import MetricKit
import MachO
import ZenShared
import WidgetKit

typealias SignInCookieVerifier = (String, @escaping (KotlinBoolean) -> KotlinUnit) -> Void

@main
class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    let platform = PlatformBridge()
    private let crashDiagnostics = CrashDiagnostics()
    #if ZEN_UI_FIXTURE
    private let previewEnabled = ProcessInfo.processInfo.arguments.contains("--zen-ui-preview")
    #else
    private let previewEnabled = false
    #endif
    lazy var runtime = IosRuntime(platform: platform, initiallyBackground: UIApplication.shared.applicationState == .background,
                                  previewEnabled: previewEnabled)
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // First, so a crash while starting up is recorded too.
        IosCrashesKt.startCrashCapture(image: CrashDiagnostics.appImage())
        crashDiagnostics.app = self
        MXMetricManager.shared.add(crashDiagnostics)
        UNUserNotificationCenter.current().delegate = self
        platform.onNetworkChange = { [weak self] connected in self?.runtime.networkChanged(connected: connected) }
        runtime.networkChanged(connected: platform.networkConnected)
        BGTaskScheduler.shared.register(forTaskWithIdentifier: PlatformBridge.refreshIdentifier, using: nil) { [weak self] task in
            guard let self = self, let refresh = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { refresh.setTaskCompleted(success: false); return }
                self.runtime.scheduleIfEnabled()
                refresh.expirationHandler = { [weak self] in DispatchQueue.main.async { self?.runtime.cancelBackground() } }
                self.runtime.runBackground { success in refresh.setTaskCompleted(success: success.boolValue) }
            }
        }
        runtime.scheduleIfEnabled()
        return true
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let parts = response.notification.request.identifier.split(separator: ".")
        if parts.count == 4, parts[0] == "campus", parts[1] == "zen" {
            DispatchQueue.main.async { self.runtime.openNotification(kind: String(parts[2])) }
        }
        completionHandler()
    }
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

/// Native crashes, reported by MetricKit on a later launch; Kotlin decides whether and when they are sent.
final class CrashDiagnostics: NSObject, MXMetricManagerSubscriber {
    weak var app: AppDelegate?

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            let begin = payload.timeStampBegin.timeIntervalSince1970, end = payload.timeStampEnd.timeIntervalSince1970
            for crash in payload.crashDiagnostics ?? [] {
                guard let json = String(data: crash.jsonRepresentation(), encoding: .utf8) else { continue }
                DispatchQueue.main.async { [weak self] in self?.app?.runtime.recordNativeCrash(json: json, begin: begin, end: end) }
            }
        }
    }

    /// The app binary as "name uuid load-address text-size", which crash reports need for Sentry to symbolicate them.
    static func appImage() -> String {
        for index in 0..<_dyld_image_count() {
            guard let header = _dyld_get_image_header(index), header.pointee.filetype == UInt32(MH_EXECUTE) else { continue }
            var uuid: UUID?
            var textSize: UInt64 = 0
            var command = UnsafeRawPointer(header).advanced(by: MemoryLayout<mach_header_64>.size)
            for _ in 0..<header.pointee.ncmds {
                let load = command.assumingMemoryBound(to: load_command.self).pointee
                if load.cmd == UInt32(LC_UUID) {
                    uuid = UUID(uuid: command.assumingMemoryBound(to: uuid_command.self).pointee.uuid)
                } else if load.cmd == UInt32(LC_SEGMENT_64) {
                    let segment = command.assumingMemoryBound(to: segment_command_64.self).pointee
                    let name = withUnsafeBytes(of: segment.segname) { String(decoding: $0.prefix(while: { $0 != 0 }), as: UTF8.self) }
                    if name == "__TEXT" { textSize = segment.vmsize }
                }
                command = command.advanced(by: Int(load.cmdsize))
            }
            guard let id = uuid else { return "" }
            let name = URL(fileURLWithPath: String(cString: _dyld_get_image_name(index))).lastPathComponent
            return "\(name) \(id.uuidString) 0x\(String(UInt(bitPattern: UnsafeRawPointer(header)), radix: 16)) \(textSize)"
        }
        return ""
    }
}

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    #if ZEN_UI_FIXTURE
    private var startedSignInFixture = false
    #endif
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene, let app = UIApplication.shared.delegate as? AppDelegate else { return }
        let window = UIWindow(windowScene: windowScene)
        let controller = MainViewControllerKt.MainViewController(platform: app.platform, runtime: app.runtime)
        app.platform.root = controller
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
        if let shortcut = connectionOptions.shortcutItem {
            _ = app.runtime.openRoute(route: shortcut.type)
        }
        receive(connectionOptions.urlContexts)
    }
    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem,
                     completionHandler: @escaping (Bool) -> Void) {
        let runtime = (UIApplication.shared.delegate as? AppDelegate)?.runtime
        completionHandler(runtime?.openRoute(route: shortcutItem.type) ?? false)
    }
    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        receive(URLContexts)
    }
    private func receive(_ contexts: Set<UIOpenURLContext>) {
        guard let url = contexts.first?.url, let app = UIApplication.shared.delegate as? AppDelegate else { return }
        if url.isFileURL {
            app.platform.importSubmission(url, maximumBytes: Int(app.runtime.maximumUploadBytes())) { path, name, mime in
                app.runtime.receiveFile(path: path, name: name, mime: mime)
            }
        } else {
            _ = app.runtime.openWidgetUrl(url: url.absoluteString)
        }
    }
    func sceneWillEnterForeground(_ scene: UIScene) {
        (UIApplication.shared.delegate as? AppDelegate)?.runtime.foreground()
    }
    func sceneDidBecomeActive(_ scene: UIScene) {
        let app = UIApplication.shared.delegate as? AppDelegate
        if let app {
            app.runtime.networkChanged(connected: app.platform.networkConnected)
            app.runtime.signInVisibility(active: true)
            #if ZEN_UI_FIXTURE
            if !startedSignInFixture,
               ProcessInfo.processInfo.arguments.contains("--zen-sign-in-fixture"),
               let root = window?.rootViewController {
                startedSignInFixture = true
                let kind: SignInKind = ProcessInfo.processInfo.arguments.contains("--zen-library-fixture") ? .library : .study
                app.runtime.savedSignInFixture(kind: kind) { popup in
                    guard let popup else { return }
                    let login = LoginViewController(kind: kind, savedSignIn: popup, words: Words(german: false), onCookie: { _ in })
                    login.useSignInFixture = true
                    root.present(UINavigationController(rootViewController: login), animated: false)
                }
            }
            #endif
        }
    }
    func sceneWillResignActive(_ scene: UIScene) {
        (UIApplication.shared.delegate as? AppDelegate)?.runtime.signInVisibility(active: false)
    }
}

final class PlatformBridge: NSObject, IosPlatform, QLPreviewControllerDataSource {
    static let refreshIdentifier = Bundle.main.bundleIdentifier! + ".refresh"
    private let networkMonitor = NWPathMonitor()
    private let networkQueue = DispatchQueue(label: "io.github.binoverfl0w.zencampus.network")
    var networkConnected: Bool { networkMonitor.currentPath.status == .satisfied }
    var onNetworkChange: ((Bool) -> Void)?
    override init() {
        super.init()
        networkMonitor.pathUpdateHandler = { [weak self] path in
            let connected = path.status == .satisfied
            DispatchQueue.main.async { self?.onNetworkChange?(connected) }
        }
        networkMonitor.start(queue: networkQueue)
        UIDevice.current.isBatteryMonitoringEnabled = true
        inboundQueue.async { try? FileManager.default.removeItem(at: self.inboundDirectory) }
    }
    func widgetsChanged() {
        WidgetCenter.shared.reloadAllTimelines()
    }

    private let inboundQueue = DispatchQueue(label: "io.github.binoverfl0w.zencampus.incoming")
    private let inboundDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("IncomingSubmissions")

    func removeIncomingFile(path: String) {
        let directory = URL(fileURLWithPath: path).deletingLastPathComponent()
        inboundQueue.async { try? FileManager.default.removeItem(at: directory) }
    }

    func importSubmission(_ url: URL, maximumBytes: Int, onFile: @escaping (String, String, String) -> Void) {
        let accessed = url.startAccessingSecurityScopedResource()
        inboundQueue.async {
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            let fm = FileManager.default
            let directory = self.inboundDirectory.appendingPathComponent(UUID().uuidString)
            let destination = directory.appendingPathComponent(url.lastPathComponent)
            var failure: NSError?
            var copied = false
            NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &failure) { source in
                do {
                    let attributes = try source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
                    guard attributes.isRegularFile == true,
                          let size = attributes.fileSize, size > 0, size <= maximumBytes else { return }
                    try fm.createDirectory(at: directory, withIntermediateDirectories: true)
                    try fm.copyItem(at: source, to: destination)
                    copied = true
                } catch {
                    try? fm.removeItem(at: directory)
                }
            }
            guard copied else {
                DispatchQueue.main.async {
                    (UIApplication.shared.delegate as? AppDelegate)?.runtime.incomingFileFailed()
                }
                return
            }
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            DispatchQueue.main.async { onFile(destination.path, url.lastPathComponent, mime) }
        }
    }

    @discardableResult
    func scheduleBackground(enabled: Bool) -> Bool {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.refreshIdentifier)
        guard enabled else { return true }
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60 * 60)
        do { try BGTaskScheduler.shared.submit(request); return true }
        catch { NSLog("ZenCampus background refresh scheduling failed: %@", String(describing: error)); return false }
    }
    func canRunBackground() -> Bool {
        let path = networkMonitor.currentPath
        let battery = UIDevice.current.batteryLevel
        let charging = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
        return path.status == .satisfied && (path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet)) &&
            !path.isExpensive && !path.isConstrained && !ProcessInfo.processInfo.isLowPowerModeEnabled &&
            (battery < 0 || battery >= 0.2 || charging)
    }
    private let notificationCenter = UNUserNotificationCenter.current()
    func requestNotifications(onResult: @escaping (KotlinBoolean) -> Void) {
        notificationCenter.getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral: DispatchQueue.main.async { onResult(KotlinBoolean(bool: true)) }
            case .notDetermined:
                self.notificationCenter.requestAuthorization(options: [.alert, .sound]) { allowed, _ in
                    DispatchQueue.main.async { onResult(KotlinBoolean(bool: allowed)) }
                }
            default: DispatchQueue.main.async { onResult(KotlinBoolean(bool: false)) }
            }
        }
    }
    func notificationsEnabled(onResult: @escaping (KotlinBoolean) -> Void) {
        notificationCenter.getNotificationSettings { settings in
            let enabled = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional || settings.authorizationStatus == .ephemeral
            DispatchQueue.main.async { onResult(KotlinBoolean(bool: enabled)) }
        }
    }
    private let noticeLock = NSLock()
    private var noticeGeneration: [String: UInt64] = [:]
    func sendNotification(kind: String, title: String, body: String, onResult: @escaping (KotlinBoolean) -> Void) {
        clearNotification(kind: kind)
        noticeLock.lock()
        let defaults = UserDefaults.standard
        let sequence = defaults.integer(forKey: "zen-notice-sequence") + 1
        defaults.set(sequence, forKey: "zen-notice-sequence")
        let generation = noticeGeneration[kind, default: 0]
        let id = "io.github.binoverfl0w.zencampus.\(kind).\(sequence)"
        noticeLock.unlock()
        let content = UNMutableNotificationContent(); content.title = title; content.body = body; content.sound = .default
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        notificationCenter.add(request) { error in
            self.noticeLock.lock(); let current = self.noticeGeneration[kind, default: 0]; self.noticeLock.unlock()
            let valid = error == nil && current == generation
            if !valid {
                self.notificationCenter.removePendingNotificationRequests(withIdentifiers: [id])
                self.notificationCenter.removeDeliveredNotifications(withIdentifiers: [id])
            }
            DispatchQueue.main.async { onResult(KotlinBoolean(bool: valid)) }
        }
    }
    func clearNotification(kind: String) {
        noticeLock.lock()
        noticeGeneration[kind, default: 0] += 1
        let cutoff = UserDefaults.standard.integer(forKey: "zen-notice-sequence")
        noticeLock.unlock()
        let prefix = "io.github.binoverfl0w.zencampus.\(kind)."
        func belongs(_ id: String) -> Bool {
            guard id.hasPrefix(prefix), let sequence = Int(id.dropFirst(prefix.count)) else { return false }
            return sequence <= cutoff
        }
        notificationCenter.getPendingNotificationRequests { requests in
            self.notificationCenter.removePendingNotificationRequests(withIdentifiers: requests.map(\.identifier).filter(belongs))
        }
        notificationCenter.getDeliveredNotifications { notices in
            self.notificationCenter.removeDeliveredNotifications(withIdentifiers: notices.map { $0.request.identifier }.filter(belongs))
        }
    }
    private var previewURL: URL?
    private var uploadPicker: UploadPicker?
    private var exportPicker: ExportPicker?
    private var folderPicker: FolderPicker?
    private let folderLock = NSLock()
    private var folderLeases: [String: URL] = [:]
    private weak var vipsReader: UINavigationController?

    func openMaterial(path: String) -> Bool {
        guard let root = root, root.presentedViewController == nil, FileManager.default.fileExists(atPath: path) else { return false }
        previewURL = URL(fileURLWithPath: path)
        let preview = QLPreviewController()
        preview.dataSource = self
        root.present(UINavigationController(rootViewController: preview), animated: true)
        return true
    }

    func openVips(url: String, title: String, cookie: String, classify: @escaping (String, String) -> String,
                  onAttachment: @escaping (String, String) -> Void, onCookie: @escaping (String) -> Void) {
        guard let root = root, root.presentedViewController == nil, let entry = URL(string: url) else { return }
        let reader = VipsViewController(entry: entry, title: title, cookie: cookie,
                                        classify: classify, onAttachment: onAttachment, onCookie: onCookie)
        let navigation = UINavigationController(rootViewController: reader)
        vipsReader = navigation
        root.present(navigation, animated: true)
    }

    func openVipsPreview(url: String, title: String, classify: @escaping (String) -> String, onAttachment: @escaping (String, String) -> Void) {
        guard let root = root, root.presentedViewController == nil, let entry = URL(string: url) else { return }
        let reader = VipsPreviewController(entry: entry, title: title, classify: classify, onAttachment: onAttachment)
        let navigation = UINavigationController(rootViewController: reader)
        vipsReader = navigation
        root.present(navigation, animated: true)
    }

    func closePrivateReader() {
        vipsReader?.dismiss(animated: false)
        vipsReader = nil
    }

    func exportMaterial(path: String, onComplete: @escaping (String?) -> Void) {
        guard let root = root, root.presentedViewController == nil, FileManager.default.fileExists(atPath: path) else {
            onComplete(nil); return
        }
        let delegate = ExportPicker(onComplete: onComplete)
        exportPicker = delegate
        let picker = UIDocumentPickerViewController(forExporting: [URL(fileURLWithPath: path)], asCopy: true)
        picker.delegate = delegate
        root.present(picker, animated: true)
    }

    func selectMaterialFolder(onFolder: @escaping (String?, String?) -> Void) {
        guard let root = root, root.presentedViewController == nil else { onFolder(nil, nil); return }
        let delegate = FolderPicker(onFolder: onFolder)
        folderPicker = delegate
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
        picker.delegate = delegate
        root.present(picker, animated: true)
    }

    func beginMaterialFolder(bookmark: String) -> String? {
        guard let data = Data(base64Encoded: bookmark) else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale),
              !stale, url.isFileURL, url.startAccessingSecurityScopedResource() else { return nil }
        let lease = UUID().uuidString
        folderLock.lock(); folderLeases[lease] = url; folderLock.unlock()
        return lease + "\n" + url.path
    }

    func endMaterialFolder(lease: String) {
        folderLock.lock(); let url = folderLeases.removeValue(forKey: lease); folderLock.unlock()
        url?.stopAccessingSecurityScopedResource()
    }

    // A provider-coordinated short commit keeps the previous file intact during network downloads.
    func commitMaterialFolder(lease: String, stagedPath: String, folders: [String], name: String) -> String? {
        folderLock.lock(); let selected = folderLeases[lease]; folderLock.unlock()
        guard let root = selected, !name.isEmpty,
              ([name] + folders).allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." && !$0.contains("/") }) else { return nil }
        var result: String?
        var coordinationError: NSError?
        let coordinator = NSFileCoordinator(filePresenter: nil)
        coordinator.coordinate(writingItemAt: root, options: .forMerging, error: &coordinationError) { coordinatedRoot in
            let rootPath = coordinatedRoot.standardizedFileURL.resolvingSymlinksInPath().path
            let directory = folders.reduce(coordinatedRoot) { $0.appendingPathComponent($1, isDirectory: true) }
            func isContained() -> Bool {
                let path = directory.standardizedFileURL.resolvingSymlinksInPath().path
                return path == rootPath || path.hasPrefix(rootPath + "/")
            }
            guard isContained() else { return }
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                guard isContained() else { return }
                let temporary = directory.appendingPathComponent(".\(UUID().uuidString).download.part")
                defer { try? FileManager.default.removeItem(at: temporary) }
                try FileManager.default.copyItem(at: URL(fileURLWithPath: stagedPath), to: temporary)
                let handle = try FileHandle(forWritingTo: temporary)
                try handle.synchronize(); try handle.close()
                let destination = directory.appendingPathComponent(name, isDirectory: false)
                guard Darwin.rename(temporary.path, destination.path) == 0 else { return }
                result = destination.path
            } catch { return }
        }
        return coordinationError == nil ? result : nil
    }

    func selectUpload(onFile: @escaping (String?, String?, String?) -> Void) {
        guard let root = root, root.presentedViewController == nil else { return }
        let delegate = UploadPicker(onFile: onFile)
        uploadPicker = delegate
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.allowsMultipleSelection = false
        picker.delegate = delegate
        root.present(picker, animated: true)
    }

    private let diagnosticLock = NSLock()
    private var diagnosticDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ZenCampusDiagnostics", isDirectory: true)
    }
    func writeDiagnosticLine(line: String) {
        diagnosticLock.lock(); defer { diagnosticLock.unlock() }
        let fm = FileManager.default, directory = diagnosticDirectory
        do {
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            let current = directory.appendingPathComponent("zencampus.log")
            let previous = directory.appendingPathComponent("zencampus.1.log")
            if ((try? fm.attributesOfItem(atPath: current.path)[.size] as? NSNumber)?.intValue ?? 0) > 512 * 1024 {
                if fm.fileExists(atPath: previous.path) { try fm.removeItem(at: previous) }
                try fm.moveItem(at: current, to: previous)
            }
            if !fm.fileExists(atPath: current.path) { fm.createFile(atPath: current.path, contents: nil) }
            let handle = try FileHandle(forWritingTo: current)
            defer { try? handle.close() }
            try handle.seekToEnd()
            try handle.write(contentsOf: Data((line + "\n").utf8))
        } catch { /* Logging must not interrupt the app. */ }
    }
    func shareDiagnosticLog() -> Bool {
        diagnosticLock.lock()
        let directory = diagnosticDirectory
        var contents = "ZenCampus iOS diagnostic log\n\n"
        for name in ["zencampus.1.log", "zencampus.log"] {
            if let text = try? String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8) { contents += text }
        }
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("ZenCampus-diagnostics.txt")
        let written: Bool
        do { try contents.write(to: target, atomically: true, encoding: .utf8); written = true } catch { written = false }
        diagnosticLock.unlock()
        return written && shareMaterial(path: target.path)
    }

    func shareMaterial(path: String) -> Bool {
        guard let root = root, root.presentedViewController == nil, FileManager.default.fileExists(atPath: path) else { return false }
        let sheet = UIActivityViewController(activityItems: [URL(fileURLWithPath: path)], applicationActivities: nil)
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = root.view
            popover.sourceRect = CGRect(x: root.view.bounds.midX, y: root.view.bounds.midY, width: 1, height: 1)
        }
        root.present(sheet, animated: true)
        return true
    }

    func numberOfPreviewItems(in controller: QLPreviewController) -> Int { previewURL == nil ? 0 : 1 }
    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { previewURL! as NSURL }
    weak var root: UIViewController?
    private let keychainService = Bundle.main.bundleIdentifier!

    private func query(_ service: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: keychainService,
         kSecAttrAccount as String: service]
    }

    func readSecret(service: String) -> String? {
        var request = query(service)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func writeSecret(service: String, value: String) -> Bool {
        let data = Data(value.utf8)
        let existing = query(service)
        let status = SecItemUpdate(existing as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var item = existing
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    func deleteSecret(service: String) -> Bool {
        let result = SecItemDelete(query(service) as CFDictionary)
        return result == errSecSuccess || result == errSecItemNotFound
    }

    func showLogin(kind: SignInKind, savedSignIn: SavedSignInPopup?, words: Words,
                   verifyCookie: @escaping SignInCookieVerifier, onCookie: @escaping (String) -> Void) {
        guard let root = root, root.view.window?.windowScene?.activationState == .foregroundActive,
              root.presentedViewController == nil else { return }
        let login = LoginViewController(kind: kind, savedSignIn: savedSignIn, words: words, verifyCookie: verifyCookie, onCookie: onCookie)
        root.present(UINavigationController(rootViewController: login), animated: true)
    }
}

final class FolderPicker: NSObject, UIDocumentPickerDelegate {
    private let onFolder: (String?, String?) -> Void
    init(onFolder: @escaping (String?, String?) -> Void) { self.onFolder = onFolder }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { onFolder(nil, nil); return }
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else {
            onFolder(nil, nil); return
        }
        onFolder(data.base64EncodedString(), url.lastPathComponent)
    }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onFolder(nil, nil) }
}

final class ExportPicker: NSObject, UIDocumentPickerDelegate {
    private let onComplete: (String?) -> Void
    init(onComplete: @escaping (String?) -> Void) { self.onComplete = onComplete }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { onComplete(urls.first?.path) }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onComplete(nil) }
}

final class UploadPicker: NSObject, UIDocumentPickerDelegate {
    private let onFile: (String?, String?, String?) -> Void
    init(onFile: @escaping (String?, String?, String?) -> Void) { self.onFile = onFile }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        onFile(url.path, url.lastPathComponent, mime)
    }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onFile(nil, nil, nil) }
}

// All preview pages are generated locally. The navigation delegate never permits a network load.
final class VipsPreviewController: UIViewController, WKNavigationDelegate {
    private let entry: URL
    private let classify: (String) -> String
    private let onAttachment: (String, String) -> Void
    private let webView: WKWebView
    private var previewPage: URL?
    init(entry: URL, title: String, classify: @escaping (String) -> String, onAttachment: @escaping (String, String) -> Void) {
        self.entry = entry; self.classify = classify; self.onAttachment = onAttachment
        let config = WKWebViewConfiguration(); config.websiteDataStore = .nonPersistent()
        config.defaultWebpagePreferences.allowsContentJavaScript = false
        self.webView = WKWebView(frame: .zero, configuration: config)
        super.init(nibName: nil, bundle: nil); self.title = title + " · Preview"
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unsupported") }
    override func viewDidLoad() {
        super.viewDidLoad()
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .done, target: self, action: #selector(close))
        webView.navigationDelegate = self; webView.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(webView)
        NSLayoutConstraint.activate([webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor), webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor)])
        loadPreview(entry)
    }
    @objc private func close() { dismiss(animated: true) }
    private func loadPreview(_ url: URL) {
        previewPage = url
        let course = URLComponents(url: entry, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "cid" })?.value ?? ""
        let exercise = url.path.hasSuffix("show_exercise")
        let next = "https://digicampus.uni-augsburg.de/dispatch.php/vips/sheets/show_exercise?assignment_id=3&amp;exercise_id=1&amp;cid=\(course)"
        let overview = "https://digicampus.uni-augsburg.de/dispatch.php/vips/sheets/show_assignment?assignment_id=3&amp;cid=\(course)"
        let attachment = "https://digicampus.uni-augsburg.de/sendfile.php?type=0&amp;file_id=preview-vips-pdf&amp;file_name=Graphs%20-%20Exercise%20sheet%203.pdf&amp;cid=\(course)"
        let content = exercise ? "<p>Consider the edges A–B, A–C, B–D and C–D. List a breadth-first traversal starting at A.</p><p>A → B → C → D</p><small>Read-only sample.</small><p><a href='\(overview)'>← Sheet overview</a></p>" : "<p>Practice graph traversal and shortest paths.</p><p><a href='\(next)'>1. Breadth-first search →</a></p>"
        let html = "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font:17px system-ui;line-height:1.6;padding:24px;color:#303e37;background:#f8f7f2}a{color:#466c53}</style><small>PREVIEW · SAMPLE</small><h1>\(exercise ? "1. Breadth-first search" : "Graphs · Exercise sheet 3")</h1>\(content)<hr><h2>Exercise attachments</h2><a href='\(attachment)'>Graphs - Exercise sheet 3.pdf</a>"
        webView.loadHTMLString(html, baseURL: nil)
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        if url.absoluteString == "about:blank" { decisionHandler(.allow); return }
        switch classify(url.absoluteString) {
        case "page": decisionHandler(.cancel); loadPreview(url)
        case "attachment": decisionHandler(.cancel); dismiss(animated: true) { self.onAttachment(url.absoluteString, self.previewPage?.absoluteString ?? self.entry.absoluteString) }
        default: decisionHandler(.cancel)
        }
    }
}

/** The signed-in DigiCampus browser: Vips sheets, and any DigiCampus page "Open in DigiCampus" names. */
final class VipsViewController: UIViewController, WKNavigationDelegate {
    private let entry: URL
    private let cookie: String
    private let classify: (String, String) -> String
    private let onAttachment: (String, String) -> Void
    private let onCookie: (String) -> Void
    private let webView: WKWebView

    init(entry: URL, title: String, cookie: String, classify: @escaping (String, String) -> String,
         onAttachment: @escaping (String, String) -> Void, onCookie: @escaping (String) -> Void) {
        self.entry = entry; self.cookie = cookie; self.classify = classify
        self.onAttachment = onAttachment; self.onCookie = onCookie
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        self.webView = WKWebView(frame: .zero, configuration: config)
        super.init(nibName: nil, bundle: nil)
        self.title = title
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unsupported") }
    override func viewDidLoad() {
        super.viewDidLoad()
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .done, target: self, action: #selector(close))
        webView.navigationDelegate = self
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor), webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor)])
        let group = DispatchGroup()
        for pair in cookie.split(separator: ";") {
            let parts = pair.trimmingCharacters(in: .whitespaces).split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2, !parts[0].isEmpty,
                  let item = HTTPCookie(properties: [.domain: "digicampus.uni-augsburg.de", .path: "/", .name: String(parts[0]), .value: String(parts[1]), .secure: "TRUE"]) else { continue }
            group.enter(); webView.configuration.websiteDataStore.httpCookieStore.setCookie(item) { group.leave() }
        }
        group.notify(queue: .main) { [weak self] in guard let self = self, self.navigationController?.presentingViewController != nil else { return }; self.webView.load(URLRequest(url: self.entry)) }
    }
    @objc private func close() { webView.stopLoading(); dismiss(animated: true) }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        if action.targetFrame?.isMainFrame == false {
            decisionHandler(url.scheme?.lowercased() == "https" ? .allow : .cancel); return
        }
        let page = webView.url?.absoluteString ?? entry.absoluteString
        switch classify(url.absoluteString, page) {
        case "page": decisionHandler(.allow)
        case "attachment":
            decisionHandler(.cancel)
            let value = url.absoluteString
            dismiss(animated: true) { self.onAttachment(value, page) }
        case "external" where action.navigationType == .linkActivated:
            // Another website: Safari, which has no DigiCampus session to lose.
            decisionHandler(.cancel)
            UIApplication.shared.open(url)
        default:
            decisionHandler(.cancel)
            if presentedViewController == nil {
                let alert = UIAlertController(title: "Page unavailable", message: "This page can't be opened here, or your session has expired. Reconnect DigiCampus if needed.", preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "OK", style: .default))
                present(alert, animated: true)
            }
        }
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let url = webView.url, classify(url.absoluteString, url.absoluteString) == "page" else { return }
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
            guard let self = self, self.navigationController?.presentingViewController != nil else { return }
            let host = "digicampus.uni-augsburg.de"
            let targetPath = "/jsonapi.php/v1/users/me"
            let applicable = cookies.filter { item in
                let domain = item.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
                let path = item.path.isEmpty ? "/" : item.path
                return (host == domain || (item.domain.hasPrefix(".") && host.hasSuffix("." + domain))) &&
                    (targetPath == path || (targetPath.hasPrefix(path) && (path.hasSuffix("/") || targetPath.dropFirst(path.count).first == "/"))) &&
                    (item.expiresDate == nil || item.expiresDate! > Date()) && !item.name.contains(";") && !item.value.contains(";")
            }.sorted { $0.path.count > $1.path.count }
            let header = applicable.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
            if !header.isEmpty { self.onCookie(header) }
        }
    }
}

final class LoginViewController: UIViewController, WKNavigationDelegate {
    private let kind: SignInKind
    private let onCookie: (String) -> Void
    private let webView: WKWebView
    private let savedSignIn: SavedSignInPopup?
    private let words: Words
    private let verifyCookie: SignInCookieVerifier?
    private let bar = UIStackView()
    private let choose = UIButton(type: .system)
    private let add = UIButton(type: .system)
    private let edit = UIButton(type: .system)
    private var completedPage: String?
    private var submitted = false
    private var checkingCookie = false
    private var lastCheckedCookie = ""
    private var openedLibraryAccount = false
    #if ZEN_UI_FIXTURE
    var useSignInFixture = false
    #endif

    init(kind: SignInKind, savedSignIn: SavedSignInPopup?, words: Words,
         verifyCookie: SignInCookieVerifier? = nil, onCookie: @escaping (String) -> Void) {
        self.kind = kind
        self.onCookie = onCookie
        self.savedSignIn = savedSignIn
        self.words = words
        self.verifyCookie = verifyCookie
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        self.webView = WKWebView(frame: .zero, configuration: configuration)
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unsupported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = kind == .library ? words.service(key: "library") : "DigiCampus"
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(cancel))
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: words.service(key: "continue_campus"), style: .done, target: self, action: #selector(continueLogin))

        let content = UIStackView(arrangedSubviews: [bar, webView])
        content.axis = .vertical
        content.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(content)
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            content.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            content.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
        bar.spacing = 8
        bar.alignment = .center
        bar.isLayoutMarginsRelativeArrangement = true
        bar.layoutMargins = UIEdgeInsets(top: 0, left: 12, bottom: 0, right: 12)
        bar.isHidden = savedSignIn == nil
        bar.addArrangedSubview(choose)
        bar.addArrangedSubview(add)
        bar.addArrangedSubview(edit)
        bar.addArrangedSubview(UIView())
        choose.showsMenuAsPrimaryAction = true
        choose.titleLabel?.lineBreakMode = .byTruncatingTail
        add.setContentCompressionResistancePriority(.required, for: .horizontal)
        edit.setContentCompressionResistancePriority(.required, for: .horizontal)
        edit.setImage(UIImage(systemName: "pencil"), for: .normal)
        edit.accessibilityLabel = words.service(key: "saved_edit")
        add.setTitle(words.service(key: "saved_add"), for: .normal)
        for button in [choose, add, edit] {
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        }
        add.addAction(UIAction { [weak self] _ in self?.showSavedSignInForm(nil) }, for: .touchUpInside)
        edit.addAction(UIAction { [weak self] _ in
            guard let self, let entry = self.savedSignIn?.selected else { return }
            self.showSavedSignInForm(entry)
        }, for: .touchUpInside)
        refreshSavedSignIns()
        webView.navigationDelegate = self
        #if ZEN_UI_FIXTURE
        if useSignInFixture {
            webView.loadHTMLString(kind == .library ? Self.librarySignInFixturePage : Self.signInFixturePage,
                                   baseURL: URL(string: "http://127.0.0.1:9081/sign-in"))
            return
        }
        #endif
        let start = kind == .library ? "https://opac.bibliothek.uni-augsburg.de/TouchPoint/start.do?View=uba&Language=de&device=mobile" : "https://digicampus.uni-augsburg.de/index.php"
        webView.load(URLRequest(url: URL(string: start)!))
    }

    private func refreshSavedSignIns() {
        guard let popup = savedSignIn else { return }
        choose.isHidden = popup.entries.isEmpty
        choose.setTitle(popup.selected.map { words.savedSignInAs(username: $0.username) } ?? words.service(key: "saved_choose"), for: .normal)
        edit.isHidden = popup.selected == nil
        choose.menu = UIMenu(children: popup.entries.map { entry in
            let subtitle = [entry.label, entry.site].compactMap { $0 }.joined(separator: " · ")
            return UIAction(title: words.savedSignInAs(username: entry.username), subtitle: subtitle) { [weak self] _ in
                guard let self, popup.pick(id: entry.id) else { return }
                self.refreshSavedSignIns()
                self.fillSavedSignIn()
            }
        })
    }

    private func showSavedSignInForm(_ entry: SavedSignIn?) {
        guard let popup = savedSignIn else { return }
        let site = entry?.site ?? popup.siteForPage(url: webView.url?.absoluteString)
        let form = SavedSignInFormController(kind: kind, site: site, entry: entry, words: words) { [weak self] username, password, label, done in
            popup.save(site: site, username: username, password: password, label: label, id: entry?.id) { result in
                done(result.boolValue)
                if result.boolValue {
                    self?.refreshSavedSignIns()
                    self?.fillSavedSignIn()
                }
            }
        }
        present(UINavigationController(rootViewController: form), animated: true)
    }

    private func fillSavedSignIn() {
        guard !submitted, let url = completedPage, url == webView.url?.absoluteString,
              let script = savedSignIn?.scriptForPage(url: url) else { return }
        webView.evaluateJavaScript(script, in: nil, in: .defaultClient, completionHandler: nil)
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        completedPage = nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if kind == .library, !openedLibraryAccount, webView.url?.host?.lowercased() == "opac.bibliothek.uni-augsburg.de",
           webView.url?.path == "/TouchPoint/start.do" {
            openedLibraryAccount = true
            webView.load(URLRequest(url: URL(string: "https://opac.bibliothek.uni-augsburg.de/TouchPoint/userAccount.do?methodToCall=start")!))
            return
        }
        completedPage = webView.url?.absoluteString
        fillSavedSignIn()
        checkSavedSignInCookie()
    }

    @objc private func cancel() {
        submitted = true
        webView.stopLoading()
        dismiss(animated: true)
    }

    private func readSessionCookie(_ completion: @escaping (String) -> Void) {
        let target = URL(string: kind == .library ? "https://opac.bibliothek.uni-augsburg.de/TouchPoint/" : "https://digicampus.uni-augsburg.de/jsonapi.php/v1/users/me")!
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
            guard let self = self, !self.submitted else { return }
            let host = target.host!.lowercased()
            let path = target.path
            let applicable = cookies.filter { cookie in
                let domain = cookie.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
                let domainMatches = host == domain || (cookie.domain.hasPrefix(".") && host.hasSuffix("." + domain))
                let cookiePath = cookie.path.isEmpty ? "/" : cookie.path
                let pathMatches = path == cookiePath || (path.hasPrefix(cookiePath) && (cookiePath.hasSuffix("/") || path.dropFirst(cookiePath.count).first == "/"))
                return domainMatches && pathMatches && (cookie.expiresDate == nil || cookie.expiresDate! > Date()) &&
                    !cookie.name.contains(";") && !cookie.value.contains(";")
            }.sorted { $0.path.count > $1.path.count }
            completion(applicable.map { "\($0.name)=\($0.value)" }.joined(separator: "; "))
        }
    }

    private func checkSavedSignInCookie() {
        guard !submitted, !checkingCookie, savedSignIn?.selected != nil, let verify = verifyCookie,
              let url = webView.url else { return }
        let targetHost = kind == .library ? "opac.bibliothek.uni-augsburg.de" : "digicampus.uni-augsburg.de"
        guard url.host?.lowercased() == targetHost, !url.path.contains("/login") else { return }
        checkingCookie = true
        readSessionCookie { [weak self] cookie in
            guard let self else { return }
            guard !cookie.isEmpty, cookie != self.lastCheckedCookie else { self.checkingCookie = false; return }
            self.lastCheckedCookie = cookie
            verify(cookie) { [weak self] result in
                guard let self, !self.submitted else { return KotlinUnit.shared }
                self.checkingCookie = false
                if result.boolValue {
                    self.submitted = true
                    self.dismiss(animated: true)
                } else {
                    // The session cookie may have changed while the guest check was in flight.
                    self.checkSavedSignInCookie()
                }
                return KotlinUnit.shared
            }
        }
    }

    @objc private func continueLogin() {
        guard !submitted, !checkingCookie else { return }
        readSessionCookie { [weak self] header in
            guard let self, !self.submitted else { return }
            guard !header.isEmpty else {
                let alert = UIAlertController(title: "Sign in first", message: "Complete sign-in on the university page, then tap Continue.", preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "OK", style: .default))
                self.present(alert, animated: true)
                return
            }
            self.submitted = true
            self.dismiss(animated: true) { self.onCookie(header) }
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        #if ZEN_UI_FIXTURE
        if useSignInFixture {
            decisionHandler(url.absoluteString == "http://127.0.0.1:9081/sign-in" ? .allow : .cancel)
            return
        }
        #endif
        let trusted: Bool
        if kind == .library {
            trusted = url.scheme?.lowercased() == "https" && url.host?.lowercased() == "opac.bibliothek.uni-augsburg.de" &&
                (url.port == nil || url.port == 443) && url.user == nil && url.password == nil
        } else {
            trusted = SignInOriginsKt.isTrustedSignInUrl(value: url.absoluteString)
        }
        decisionHandler(trusted ? .allow : .cancel)
    }

    #if ZEN_UI_FIXTURE
    private static let signInFixturePage = #"""
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body>
        <form method="post">
            <input name="loginfmt"><input type="password" name="passwd" hidden>
            <button id="idSIButton9">Next</button><p id="passwordError" hidden></p>
        </form>
        <input name="otc"><input name="numberMatching"><p id="status">Fixture sign-in page</p>
        <button id="reset">Reset fixture form</button>
        <script>
            const email = document.querySelector('[name=loginfmt]');
            const password = document.querySelector('[name=passwd]');
            const status = document.getElementById('status');
            let events = '';
            let submissions = 0;
            document.getElementById('reset').onclick = () => {
                email.value = '';
                email.hidden = false;
                password.hidden = true;
                document.getElementById('passwordError').hidden = true;
            };
            password.oninput = () => events += 'i';
            password.onchange = () => events += 'c';
            document.forms[0].onsubmit = event => {
                event.preventDefault();
                if (!email.hidden && ['student@example.test', 'new@example.test'].includes(email.value)) {
                    email.hidden = true;
                    password.hidden = false;
                } else if (!password.hidden) {
                    submissions++;
                    const secondFactorUntouched = [...document.querySelectorAll('[name=otc],[name=numberMatching]')]
                        .every(input => input.value === '');
                    const expected = email.value === 'new@example.test' ? 'New fixture password' : 'Grüße " \\';
                    status.textContent = password.value === expected && events.endsWith('ic') && secondFactorUntouched
                        ? (submissions === 1 ? 'Fixture received one password submission' : 'Fixture received added account')
                        : 'Unexpected fixture submission';
                    password.value = '';
                    const error = document.getElementById('passwordError');
                    error.textContent = 'Incorrect password';
                    error.hidden = false;
                }
            };
        </script></body></html>
        """#
    private static let librarySignInFixturePage = #"""
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body>
        <form id="LoginBean" method="post" action="/TouchPoint/login.do">
            <input type="text" name="login" autocomplete="username">
            <input type="password" name="password" autocomplete="current-password">
            <input name="otc" autocomplete="one-time-code"><button type="submit">Sign in</button>
        </form><p id="status">Fixture library page</p>
        <script>
            let submits = 0;
            const form = document.getElementById('LoginBean');
            form.onsubmit = event => {
                event.preventDefault();
                submits++;
                const username = form.elements.login.value;
                const expected = username === '65432' ? 'New fixture password' : 'Grüße " \\';
                document.getElementById('status').textContent = form.elements.password.value === expected &&
                    form.elements.otc.value === '' ? (submits === 1 ? 'Library received selected account' : 'Library received added account')
                    : 'Unexpected fixture submission';
                form.elements.login.value = '';
                form.elements.password.value = '';
            };
        </script></body></html>
        """#
    #endif
}


private final class SavedSignInFormController: UIViewController {
    private let kind: SignInKind
    private let site: String
    private let entry: SavedSignIn?
    private let words: Words
    private let onSave: (String, String, String, @escaping (Bool) -> Void) -> Void
    private let username = UITextField()
    private let password = UITextField()
    private let label = UITextField()
    private let show = UIButton(type: .system)
    private let error = UILabel()

    init(kind: SignInKind, site: String, entry: SavedSignIn?, words: Words,
         onSave: @escaping (String, String, String, @escaping (Bool) -> Void) -> Void) {
        self.kind = kind
        self.site = site
        self.entry = entry
        self.words = words
        self.onSave = onSave
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unsupported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = words.service(key: entry == nil ? "saved_add" : "saved_edit")
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(cancel))
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: words.service(key: "saved_save"), style: .done, target: self, action: #selector(save))

        let scroll = UIScrollView()
        let fields = UIStackView()
        fields.axis = .vertical
        fields.spacing = 10
        scroll.translatesAutoresizingMaskIntoConstraints = false
        fields.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        scroll.addSubview(fields)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            fields.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 16),
            fields.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -16),
            fields.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 16),
            fields.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -16),
            fields.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -32)
        ])

        func note(_ text: String) {
            let note = UILabel()
            note.font = .preferredFont(forTextStyle: .subheadline)
            note.adjustsFontForContentSizeCategory = true
            note.numberOfLines = 0
            note.text = text
            fields.addArrangedSubview(note)
        }
        func field(_ field: UITextField, _ title: String) {
            field.borderStyle = .roundedRect
            field.placeholder = title
            field.accessibilityLabel = title
            field.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            fields.addArrangedSubview(field)
        }
        note(words.service(key: "saved_details"))
        note(site)
        [username, password].forEach {
            $0.autocapitalizationType = .none
            $0.autocorrectionType = .no
            $0.spellCheckingType = .no
        }
        username.textContentType = .username
        username.keyboardType = kind == .study ? .emailAddress : .default
        username.text = entry?.username
        password.textContentType = .password
        password.isSecureTextEntry = true
        label.text = entry?.label
        field(username, words.savedSignInUsername(kind: kind))
        field(password, words.service(key: "saved_password"))
        if entry != nil { note(words.service(key: "saved_keep_password")) }
        show.setTitle(words.service(key: "saved_show_password"), for: .normal)
        show.contentHorizontalAlignment = .leading
        show.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        show.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            let value = self.password.text
            self.password.isSecureTextEntry.toggle()
            self.password.text = value
            self.show.setTitle(self.words.service(key: self.password.isSecureTextEntry ? "saved_show_password" : "saved_hide_password"), for: .normal)
        }, for: .touchUpInside)
        fields.addArrangedSubview(show)
        field(label, words.service(key: "saved_label"))
        error.numberOfLines = 0
        error.text = words.service(key: "saved_storage_error")
        error.textColor = .systemRed
        error.isHidden = true
        fields.addArrangedSubview(error)
    }

    @objc private func save() {
        let name = username.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let secret = password.text ?? ""
        guard !name.isEmpty else { username.becomeFirstResponder(); return }
        guard entry != nil || !secret.isEmpty else { password.becomeFirstResponder(); return }
        view.endEditing(true)
        navigationItem.rightBarButtonItem?.isEnabled = false
        navigationItem.leftBarButtonItem?.isEnabled = false
        error.isHidden = true
        onSave(name, secret, label.text ?? "") { [weak self] ok in
            guard let self else { return }
            if ok { self.password.text = nil; self.dismiss(animated: true) }
            else {
                self.navigationItem.rightBarButtonItem?.isEnabled = true
                self.navigationItem.leftBarButtonItem?.isEnabled = true
                self.error.isHidden = false
            }
        }
    }

    @objc private func cancel() { dismiss(animated: true) }
}
