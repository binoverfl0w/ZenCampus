package io.github.binoverfl0w.zencampus.desktop

import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.service
import io.ktor.http.Url
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.net.URI
import java.nio.file.Files
import javax.swing.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.*
import org.cef.callback.*
import org.cef.handler.*
import org.cef.misc.*
import org.cef.network.*

internal enum class SignInService { DIGICAMPUS, LIBRARY }
internal sealed interface BrowserPurpose {
    data class SignIn(val service: SignInService) : BrowserPurpose
    /** The signed-in DigiCampus browser: a Vips sheet for [course], or any page "Open in DigiCampus" names (no course). */
    data class Campus(val scope: String, val course: String?, val entry: String, val title: String, val preview: Boolean) : BrowserPurpose
}
internal enum class BrowserNavigation { ALLOW, ATTACHMENT, EXTERNAL, RECONNECT, BLOCK }

/** Evaluated before navigation; DigiCampus pages follow [CampusLinks], [page] being the page the window is on. */
internal fun browserNavigation(purpose: BrowserPurpose, url: String, userGesture: Boolean, page: String = ""): BrowserNavigation {
    if (url == "about:blank") return BrowserNavigation.ALLOW
    if (purpose is BrowserPurpose.SignIn) return if (isTrustedSignInUrl(url)) BrowserNavigation.ALLOW
        else if (safeExternalUrl(url) && userGesture) BrowserNavigation.EXTERNAL else BrowserNavigation.BLOCK
    purpose as BrowserPurpose.Campus
    return when (CampusLinks.navigation(url, purpose.course, page, userGesture, purpose.preview)) {
        CampusRoute.PAGE -> BrowserNavigation.ALLOW
        CampusRoute.ATTACHMENT -> BrowserNavigation.ATTACHMENT
        CampusRoute.RECONNECT -> BrowserNavigation.RECONNECT
        CampusRoute.EXTERNAL -> BrowserNavigation.EXTERNAL
        CampusRoute.BLOCK -> BrowserNavigation.BLOCK
    }
}

private const val BROWSER_PROFILE_PREFIX = "browser-session-"

/**
 * Removes the private Chromium profiles earlier runs left behind. Each run deletes its own when it quits, but a crash or
 * a forced quit cannot, and on Windows Chromium's helpers still hold the files at that moment. Only one ZenCampus runs
 * at a time, so none of them is in use.
 */
internal fun clearBrowserProfiles(cache: File) {
    cache.listFiles { file -> file.isDirectory && file.name.startsWith(BROWSER_PROFILE_PREFIX) }?.forEach { it.deleteRecursively() }
}

internal fun safeExternalUrl(value: String): Boolean = runCatching {
    val url = Url(value)
    url.protocol.name == "https" && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.port == 443
}.getOrDefault(false)

internal class DesktopBrowser(private val container: DesktopContainer) {
    var onQuit: () -> Unit = { closeWindow() }
    private var app: CefApp? = null
    private var browserData: File? = null
    private val startup = Mutex()
    private var current: BrowserWindow? = null
    private suspend fun engine(): CefApp = startup.withLock {
        app ?: withContext(Dispatchers.IO) {
            CefAppBuilder().apply {
                if (isMac) setAppHandler(object : MavenCefAppHandlerAdapter() {
                    // JCEF intercepts AppKit termination once Chromium is loaded.
                    // Its callback runs on Swing and true leaves shutdown to the app.
                    override fun onBeforeTerminate(): Boolean {
                        onQuit()
                        return true
                    }
                })
                setInstallDir(File(container.paths.cache, "chromium-152"))
                // X11 embedding can leave the sign-in page blank until a manual refresh.
                // The off-screen renderer paints that flow reliably under XWayland.
                // Windows only uses native windows, and JCEF pumps Chromium from the Swing thread whenever off-screen
                // rendering is enabled; that thread never reads the native windows' messages, so no click reached a page.
                cefSettings.windowless_rendering_enabled = !isWindows
                cefSettings.cache_path = ""
                clearBrowserProfiles(container.paths.cache)
                browserData = privateDirectory(Files.createTempDirectory(container.paths.cache.toPath(), BROWSER_PROFILE_PREFIX).toFile())
                cefSettings.root_cache_path = browserData!!.absolutePath
                cefSettings.persist_session_cookies = false
                // Chromium's own log only when diagnosing (CI sets the file); it can hold page addresses.
                val cefLog = System.getenv("ZENCAMPUS_CEF_LOG")?.takeIf { it.isNotBlank() }
                cefSettings.log_severity = if (cefLog != null) org.cef.CefSettings.LogSeverity.LOGSEVERITY_INFO else org.cef.CefSettings.LogSeverity.LOGSEVERITY_DISABLE
                if (cefLog != null) cefSettings.log_file = cefLog
                // Chromium honours one --disable-features switch. Windows: it pauses windows it thinks are covered, which an
                // embedded child window can wrongly be.
                val disabled = listOf("Translate") + if (isWindows) listOf("CalculateNativeWinOcclusion") else emptyList()
                val args = mutableListOf("--no-first-run", "--disable-session-crashed-bubble", "--disable-features=" + disabled.joinToString(","), "--disable-background-networking")
                // AWT embeds an X11 window. Chromium's Wayland auto-selection cannot embed into that parent.
                if (System.getProperty("os.name").startsWith("Linux")) args += "--ozone-platform=x11"
                addJcefArgs(*args.toTypedArray())
            }.build()
        }.also { app = it }
    }
    fun signIn(service: SignInService, expired: Boolean = false) {
        val purpose = BrowserPurpose.SignIn(service)
        if (current?.bringForward(purpose) != true) open(purpose, expired)
    }
    fun vips(course: String, url: String, title: String) { campus(course, url, title) }
    /** False when [url] is not a DigiCampus page the window shows; the caller then uses the system browser. */
    fun campus(course: String?, url: String, title: String): Boolean {
        val repo = container.repository.value ?: return false
        if (repo.account.demo && course == null) return false
        val entry = CampusLinks.entry(url, course) ?: return false
        open(BrowserPurpose.Campus(repo.account.cacheScope, course, entry, title, repo.account.demo))
        return true
    }
    private fun open(purpose: BrowserPurpose, expired: Boolean = false) {
        current?.close()
        current = BrowserWindow(purpose, expired).also { it.start() }
    }
    fun closeWindow() { current?.close(); current = null }
    fun closeVipsWindow() { if (current?.privateReader == true) closeWindow() }
    suspend fun close() {
        withContext(Dispatchers.Main) { closeWindow() }
        withContext(Dispatchers.IO) { app?.dispose(); app = null }
        withTimeoutOrNull(10_000) { while (CefApp.getState() !in setOf(CefApp.CefAppState.NONE, CefApp.CefAppState.TERMINATED)) delay(25) }
        browserData?.deleteRecursively(); browserData = null
    }

    private inner class BrowserWindow(private val purpose: BrowserPurpose, private val expired: Boolean) {
        private val words = desktopWords(container.settings.value)
        val privateReader get() = purpose is BrowserPurpose.Campus
        private val scope = CoroutineScope(SupervisorJob(container.scope.coroutineContext[Job]) + Dispatchers.Main)
        private val frame = JFrame(when (purpose) {
            is BrowserPurpose.SignIn -> if (purpose.service == SignInService.LIBRARY) "${words.service("library")} · ZenCampus" else "DigiCampus · ZenCampus"
            is BrowserPurpose.Campus -> "${purpose.title} · ZenCampus"
        })
        private val status = JTextArea(words.desktop("preparing"), 2, 0).apply {
            isEditable = false; isFocusable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
        }
        private val address = JLabel(" ")
        private val content = JPanel(BorderLayout())
        private val continueButton = JButton(words.desktop("continue"))
        private var signInPopup: SavedSignInPopup? = null
        private var completedPage: String? = null
        private val backButton = JButton("← ${words.text("back")}")
        private var client: CefClient? = null
        private var browser: CefBrowser? = null
        private var handoff: SessionHandoff? = null
        private var enterForwarder: OffscreenEnterForwarder? = null
        private var wheelForwarder: OffscreenWheelForwarder? = null
        @Volatile private var closed = false
        @Volatile private var loadError: CefLoadHandler.ErrorCode? = null
        private var currentUrl = ""

        fun bringForward(target: BrowserPurpose): Boolean {
            if (closed || target != purpose) return false
            frame.toFront()
            return true
        }

        fun start() {
            val dark = desktopDark
            val background = if (dark) Color(0x1b2228) else Color(0xf5f6f4)
            val foreground = if (dark) Color(0xe1e7e9) else Color(0x33454d)
            val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 12, 10)).apply {
                this.background = background
                add(backButton)
                add(JButton(words.text("refresh")).apply { addActionListener { browser?.reload() } })
                if (purpose is BrowserPurpose.SignIn) add(continueButton)
                else add(JButton(words.desktop("official_page")).apply { addActionListener { openExternalFromWindow(currentUrl) } })
            }
            toolbar.components.filterIsInstance<JButton>().forEach { styleDesktopButton(it, dark) }
            address.foreground = foreground; address.border = BorderFactory.createEmptyBorder(4, 16, 8, 16)
            status.foreground = foreground; status.border = BorderFactory.createEmptyBorder(8, 16, 10, 16)
            val header = JPanel(BorderLayout()).apply {
                this.background = background
                add(toolbar, BorderLayout.NORTH)
                add(address, BorderLayout.CENTER)
            }
            frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            frame.addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) = close() })
            frame.minimumSize = Dimension(560, 480); frame.setSize(1040, 820); frame.setLocationRelativeTo(null)
            frame.contentPane = JPanel(BorderLayout()).apply {
                this.background = background; add(header, BorderLayout.NORTH); add(content, BorderLayout.CENTER); add(status, BorderLayout.SOUTH)
            }
            listOf(backButton, continueButton, address, status).forEach { it.font = desktopFont() }
            backButton.addActionListener { if (browser?.canGoBack() == true) browser?.goBack() else close() }
            continueButton.addActionListener { handoff?.request(true) }
            frame.rootPane.registerKeyboardAction({ if (browser?.canGoBack() == true) browser?.goBack() else close() },
                KeyStroke.getKeyStroke("alt LEFT"), JComponent.WHEN_IN_FOCUSED_WINDOW)
            frame.rootPane.registerKeyboardAction({ backButton.doClick() }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW)
            frame.rootPane.registerKeyboardAction({ onQuit() }, KeyStroke.getKeyStroke("control Q"), JComponent.WHEN_IN_FOCUSED_WINDOW)
            frame.isVisible = true
            scope.launch {
                try {
                    if (purpose is BrowserPurpose.SignIn) {
                        container.ready.first { it }
                        val kind = if (purpose.service == SignInService.LIBRARY) SignInKind.LIBRARY else SignInKind.STUDY
                        container.signInSession.openSavedSignIn(scope, kind, expired, words.service("saved_rejected"))?.let { popup ->
                            signInPopup = popup
                            val bar = DesktopSavedSignInBar(popup, words, frame, { completedPage }, ::fillSavedSignIn)
                            header.add(bar, BorderLayout.SOUTH)
                            header.revalidate()
                            scope.launch {
                                popup.updates.collect {
                                    bar.refresh()
                                    if (popup.selected == null) fillSavedSignIn()
                                }
                            }
                        }
                    }
                    val runtime = engine()
                    if (closed) return@launch
                    // Creating a client starts CEF initialization. Cookie APIs are unavailable until it finishes.
                    client = runtime.createClient()
                    withTimeout(30_000) {
                        while (CefApp.getState() != CefApp.CefAppState.INITIALIZED) {
                            check(CefApp.getState() != CefApp.CefAppState.INITIALIZATION_FAILED)
                            delay(25)
                        }
                    }
                    var seed = ""
                    val entry = when (purpose) {
                        is BrowserPurpose.SignIn -> if (purpose.service == SignInService.LIBRARY) LIBRARY_ACCOUNT else "$DIGICAMPUS_ORIGIN/index.php"
                        is BrowserPurpose.Campus -> {
                            val session = container.vipsSession(purpose.scope) ?: run { close(); return@launch }
                            if (!purpose.preview) seed = session.cookie
                            purpose.entry
                        }
                    }
                    if (closed) return@launch
                    configure(entry, seed)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { showStatus(words.desktop("browser_failed")) }
            }
            scope.launch {
                when (purpose) {
                    is BrowserPurpose.SignIn -> {
                        val busy = if (purpose.service == SignInService.LIBRARY) container.libraryAuthBusy else container.authBusy
                        busy.collect { continueButton.isEnabled = !it; if (it) showStatus(words.desktop("connecting")) }
                    }
                    is BrowserPurpose.Campus -> {
                        container.repository.collect { if (it?.account?.cacheScope != purpose.scope) close() }
                    }
                }
            }
        }

        private fun configure(entry: String, seed: String) {
            val cefClient = checkNotNull(client)
            if (purpose is BrowserPurpose.SignIn) {
                handoff = SessionHandoff(scope,
                    readCookie = { cookieSnapshot },
                    verify = { _, explicit ->
                        val target = if (purpose.service == SignInService.LIBRARY) LIBRARY_BASE else "$DIGICAMPUS_API/users/me"
                        val cookie = readCookies(checkNotNull(browser), target); cookieSnapshot = cookie
                        val ok = if (purpose.service == SignInService.LIBRARY) container.connectLibrary(cookie, explicit) else container.signIn(cookie, explicit)
                        if (!ok && !closed) {
                            val error = if (purpose.service == SignInService.LIBRARY) container.libraryAuthError.value else container.authError.value
                            showStatus(if (error == FailureKind.AUTHENTICATION || error == null) words.desktop("finish_login") else words.failure(error))
                        }
                        ok
                    }, onAuthenticated = { close() })
            }
            cefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
                    if (closed) return true
                    if (!frame.isMain) return !resourceAllowed(request.url)
                    return navigate(request.url, userGesture)
                }
                override fun onOpenURLFromTab(browser: CefBrowser, frame: CefFrame, targetUrl: String, userGesture: Boolean): Boolean {
                    if (!navigate(targetUrl, userGesture)) SwingUtilities.invokeLater { if (!closed) browser.loadURL(targetUrl) }
                    return true
                }
                override fun getResourceRequestHandler(browser: CefBrowser, frame: CefFrame?, request: CefRequest,
                    isNavigation: Boolean, isDownload: Boolean, requestInitiator: String?, disableDefaultHandling: BoolRef): CefResourceRequestHandler {
                    return object : CefResourceRequestHandlerAdapter() {
                        override fun onBeforeResourceLoad(browser: CefBrowser, frame: CefFrame?, request: CefRequest): Boolean {
                            if (closed || (!isPreview() && !resourceAllowed(request.url))) return true
                            return false
                        }
                        override fun getResourceHandler(browser: CefBrowser, frame: CefFrame?, request: CefRequest): CefResourceHandler? {
                            val vips = purpose as? BrowserPurpose.Campus
                            if (vips?.preview != true) return null
                            val course = vips.course.orEmpty()
                            val html = if (VipsLinks.page(request.url, course)) vipsPreviewPage(request.url, course) else ""
                            return HtmlResource(html)
                        }
                    }
                }
                override fun onCertificateError(browser: CefBrowser, certError: CefLoadHandler.ErrorCode, requestUrl: String, callback: CefCallback): Boolean {
                    showStatus(words.desktop("certificate_failed"))
                    return false
                }
            })
            cefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                override fun onAfterCreated(browser: CefBrowser) {
                    scope.launch {
                        try {
                            if (purpose is BrowserPurpose.Campus) prepareVipsBrowser(browser)
                            if (seed.isNotBlank()) seedCookies(browser, DIGICAMPUS_ORIGIN, seed)
                            if (!closed) browser.loadURL(entry)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { showStatus(words.desktop("browser_session_failed")) }
                    }
                }
                override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, targetUrl: String, targetFrameName: String): Boolean {
                    if (!navigate(targetUrl, true)) SwingUtilities.invokeLater { if (!closed) browser.loadURL(targetUrl) }
                    return true
                }
            })
            cefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain || closed) return
                    val url = browser.url
                    if (url == "about:blank") return
                    val completed = frame.getURL().takeIf { loadError == null && httpStatusCode in 200..499 }
                    if (purpose is BrowserPurpose.Campus && httpStatusCode in 200..399) {
                        // Off-screen CEF does not reliably create target=_blank windows. Route genuine
                        // link clicks through the existing, course-bound navigation/download checks.
                        browser.executeJavaScript(VIPS_SAME_WINDOW_LINKS, url, 0)
                    }
                    SwingUtilities.invokeLater {
                        if (!closed) {
                            currentUrl = url; address.text = runCatching { URI(url).host }.getOrNull() ?: "ZenCampus preview"
                            val failure = loadError
                            if (httpStatusCode <= 0 && failure != null) {
                                showStatus("${words.desktop("load_failed")} (${failure.name}). ${words.desktop("reload_back")}")
                                return@invokeLater
                            }
                            loadError = null
                            showStatus(if (httpStatusCode >= 400) "HTTP $httpStatusCode · ${words.desktop("reload_back")}"
                                else if (purpose is BrowserPurpose.SignIn) words.desktop("finish_login")
                                else if (isPreview()) "Sample exercises · no university requests" else "")
                            if (purpose is BrowserPurpose.SignIn) {
                                completedPage = completed
                                fillSavedSignIn()
                                scope.launch {
                                    val target = if (purpose.service == SignInService.LIBRARY) LIBRARY_BASE else "$DIGICAMPUS_API/users/me"
                                    cookieSnapshot = readCookies(browser, target); handoff?.request()
                                }
                            } else if (purpose is BrowserPurpose.Campus && !purpose.preview) scope.launch {
                                if (!container.updateVipsSession(purpose.scope, readCookies(browser, "$DIGICAMPUS_API/users/me")))
                                    showStatus(words.desktop("reconnect"))
                            }
                        }
                    }
                }
                override fun onLoadError(browser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String) {
                    if (frame.isMain && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) {
                        loadError = errorCode
                        showStatus("${words.desktop("load_failed")} (${errorCode.name}). ${words.desktop("reload_back")}")
                    }
                }
            })
            cefClient.addDownloadHandler(object : CefDownloadHandler {
                override fun onBeforeDownload(browser: CefBrowser, item: CefDownloadItem, suggestedName: String, callback: CefBeforeDownloadCallback): Boolean {
                    navigate(item.url, true); return false // The shared, account-owned material transfer handles recognized files.
                }
                override fun onDownloadUpdated(browser: CefBrowser, item: CefDownloadItem, callback: CefDownloadItemCallback) { callback.cancel() }
            })
            cefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(browser: CefBrowser, level: org.cef.CefSettings.LogSeverity, message: String, source: String, line: Int) = true
            })
            cefClient.addKeyboardHandler(object : CefKeyboardHandlerAdapter() {
                override fun onPreKeyEvent(browser: CefBrowser, event: CefKeyboardHandler.CefKeyEvent, shortcut: BoolRef): Boolean {
                    if (event.type != CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN) return false
                    val alt = event.modifiers and org.cef.misc.EventFlags.EVENTFLAG_ALT_DOWN != 0
                    val ctrl = event.modifiers and org.cef.misc.EventFlags.EVENTFLAG_CONTROL_DOWN != 0
                    val goBack = event.windows_key_code == java.awt.event.KeyEvent.VK_ESCAPE ||
                        alt && event.windows_key_code == java.awt.event.KeyEvent.VK_LEFT
                    val quit = ctrl && event.windows_key_code == java.awt.event.KeyEvent.VK_Q
                    if (goBack || quit) SwingUtilities.invokeLater { if (quit) onQuit() else backButton.doClick() }
                    return goBack || quit
                }
            })
            // Each window owns an independent in-memory context, including cookies and web storage.
            // Its native context is disposed by CefBrowser when the window closes.
            val context = CefRequestContext.createContext(null)
            // The Linux native X11 child can render while ignoring Vips clicks under XWayland. Windows embeds the native
            // window well, and its off-screen view needs a Java OpenGL surface that machines without a GPU cannot start.
            val offscreen = !isWindows && (purpose is BrowserPurpose.Campus || purpose is BrowserPurpose.SignIn && purpose.service == SignInService.DIGICAMPUS)
            val view = cefClient.createBrowser("about:blank", offscreen, false, context)
            browser = view
            if (offscreen) enterForwarder = OffscreenEnterForwarder(view, scope)
            if (offscreen && purpose is BrowserPurpose.Campus) wheelForwarder = OffscreenWheelForwarder(view, scope)
            content.add(view.uiComponent, BorderLayout.CENTER)
            frame.validate()
            // Native creation must not depend on a future Swing paint, especially when replacing a window.
            view.createImmediately()
            content.repaint()
            currentUrl = entry
        }

        private fun fillSavedSignIn() {
            val view = browser ?: return
            val page = completedPage ?: return
            if (view.url == page) signInPopup?.scriptForPage(page)?.let { view.executeJavaScript(it, page, 0) }
        }

        @Volatile private var cookieSnapshot = "pending" // A handoff always reads a fresh HttpOnly-inclusive snapshot.
        private fun isPreview() = (purpose as? BrowserPurpose.Campus)?.preview == true
        private fun resourceAllowed(url: String) = url == "about:blank" || url.startsWith("data:") || url.startsWith("blob:") ||
            (safeExternalUrl(url) && (purpose is BrowserPurpose.SignIn || runCatching { Url(url).host }.getOrNull()?.let {
                it == "uni-augsburg.de" || it.endsWith(".uni-augsburg.de")
            } == true))

        private fun navigate(url: String, userGesture: Boolean): Boolean {
            if (closed) return true
            when (browserNavigation(purpose, url, userGesture, currentUrl)) {
                BrowserNavigation.ALLOW -> return false
                BrowserNavigation.ATTACHMENT -> {
                    val campus = purpose as BrowserPurpose.Campus
                    val repo = container.repository.value
                    val course = CampusLinks.attachmentCourse(url, campus.course, currentUrl) ?: return true
                    if (repo?.account?.cacheScope == campus.scope) {
                        showStatus(words.desktop("opening_attachment"))
                        repo.requestLinkedMaterial(course, url, currentUrl)
                        scope.launch {
                            delay(350)
                            val attachment = VipsLinks.attachment(url, course) ?: return@launch
                            repo.sync.collect { sync ->
                                val error = sync.errors["download:${attachment.id}"]
                                if (error != null) showStatus(words.failure(error) + " " + words.desktop("retry_attachment"))
                            }
                        }
                    }
                }
                BrowserNavigation.EXTERNAL -> openExternalFromWindow(url)
                BrowserNavigation.RECONNECT -> showStatus(words.desktop("reconnect"))
                BrowserNavigation.BLOCK -> showStatus(words.desktop("link_blocked"))
            }
            return true
        }
        private fun openExternalFromWindow(url: String) {
            scope.launch { if (!openExternal(url)) showStatus(words.desktop("external_link_failed")) }
        }
        private fun showStatus(message: String) = SwingUtilities.invokeLater {
            if (!closed) { status.text = message; status.isVisible = message.isNotBlank(); frame.revalidate() }
        }
        fun close() {
            if (closed) return
            closed = true; enterForwarder?.close(); enterForwarder = null; wheelForwarder?.close(); wheelForwarder = null; scope.cancel()
            browser?.stopLoad(); browser?.close(true); browser = null
            client?.dispose(); client = null; frame.dispose()
            if (current === this) current = null
        }
    }
}

private const val VIPS_SAME_WINDOW_LINKS = """(() => {
  if (window.__zenVipsLinkRouting) return;
  window.__zenVipsLinkRouting = true;
  document.addEventListener('click', (event) => {
    if (!event.isTrusted || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    const element = event.target instanceof Element ? event.target : event.target?.parentElement;
    const link = element?.closest('a[href][target]');
    if (!link || link.target.toLowerCase() !== '_blank') return;
    link.target = '_self';
    setTimeout(() => { if (link.target === '_self') link.target = '_blank'; }, 0);
  }, true);
})()"""

/** JCEF's Linux off-screen AWT key path can drop Enter's character event. */
internal class OffscreenEnterForwarder(private val browser: CefBrowser, scope: CoroutineScope) : KeyEventDispatcher, AutoCloseable {
    private val events = Channel<Unit>(Channel.UNLIMITED)
    private var pressed = false
    private val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val job = scope.launch {
        for (ignored in events) {
            try {
                browserCommand(browser, "Input.dispatchKeyEvent", buildJsonObject {
                    put("type", "keyDown"); put("key", "Enter"); put("code", "Enter")
                    put("windowsVirtualKeyCode", 13); put("nativeVirtualKeyCode", 13)
                    put("text", "\r"); put("unmodifiedText", "\r")
                })
                browserCommand(browser, "Input.dispatchKeyEvent", buildJsonObject {
                    put("type", "keyUp"); put("key", "Enter"); put("code", "Enter")
                    put("windowsVirtualKeyCode", 13); put("nativeVirtualKeyCode", 13)
                })
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* The window may be closing. */ }
        }
    }
    init { manager.addKeyEventDispatcher(this) }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.component !== browser.uiComponent) return false
        if (event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ENTER && event.modifiersEx == 0) {
            if (events.trySend(Unit).isFailure) return false
            pressed = true
            return true
        }
        if (pressed && event.id == KeyEvent.KEY_TYPED && (event.keyChar == '\n' || event.keyChar == '\r')) return true
        if (pressed && event.id == KeyEvent.KEY_RELEASED && event.keyCode == KeyEvent.VK_ENTER) { pressed = false; return true }
        return false
    }
    override fun close() { manager.removeKeyEventDispatcher(this); events.close(); job.cancel(); pressed = false }
}

/** Preserve real wheel deltas when the pinned JCEF off-screen AWT adapter reports zero movement. */
internal class OffscreenWheelForwarder(private val browser: CefBrowser, scope: CoroutineScope) : AutoCloseable {
    private val component = browser.uiComponent
    private val original = component.mouseWheelListeners.filter { it.javaClass.name == "org.cef.browser.CefBrowserOsr\$5" }
    private val events = Channel<JsonObject>(Channel.UNLIMITED)
    private val job = scope.launch {
        for (event in events) try { browserCommand(browser, "Input.dispatchMouseEvent", event) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* The window may be closing. */ }
    }
    private val listener = MouseWheelListener { event ->
        val unit = if (event.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL) component.height * .9 else event.scrollAmount.coerceAtLeast(1) * 16.0
        val delta = event.preciseWheelRotation * unit
        val modifiers = (if (event.isAltDown) 1 else 0) or (if (event.isControlDown) 2 else 0) or
            (if (event.isMetaDown) 4 else 0) or (if (event.isShiftDown) 8 else 0)
        events.trySend(buildJsonObject {
            put("type", "mouseWheel"); put("x", event.x); put("y", event.y); put("modifiers", modifiers)
            put("deltaX", if (event.isShiftDown) delta else 0.0); put("deltaY", if (event.isShiftDown) 0.0 else delta)
        })
        event.consume()
    }
    init {
        // Only replace the known pinned adapter; leave other Swing listeners untouched.
        if (original.size == 1) { component.removeMouseWheelListener(original.single()); component.addMouseWheelListener(listener) }
    }
    override fun close() {
        if (original.size == 1) { component.removeMouseWheelListener(listener); component.addMouseWheelListener(original.single()) }
        events.close(); job.cancel()
    }
}

internal suspend fun prepareVipsBrowser(browser: CefBrowser) {
    // Configure Chromium's cache before navigation. Rewriting CEF request flags/headers here
    // makes its native HTTP loader reject ordinary navigations with ERR_INVALID_ARGUMENT.
    browserCommand(browser, "Network.enable")
    browserCommand(browser, "Network.setCacheDisabled", buildJsonObject { put("cacheDisabled", true) })
}

/**
 * Sends a DevTools command and returns its result. JCEF's client can lose a reply: when it arrives before the request's
 * id, the reply is stored and discarded again, and the request then waits forever. That is rare, and likelier where
 * Chromium runs its own thread (Windows), so a command without a reply is sent again. Every command used here
 * (reading, setting and clearing cookies) can safely run twice.
 */
internal suspend fun browserCommand(browser: CefBrowser, method: String, parameters: JsonObject? = null): JsonObject {
    repeat(3) { withTimeoutOrNull(5_000) { browserCommandOnce(browser, method, parameters) }?.let { return it } }
    throw IllegalStateException("No reply from the browser to $method")
}

internal suspend fun browserCommandOnce(browser: CefBrowser, method: String, parameters: JsonObject?): JsonObject =
    suspendCancellableCoroutine { continuation ->
        val client = browser.devToolsClient
        if (client == null) { continuation.resumeWithException(IllegalStateException("Browser closed")); return@suspendCancellableCoroutine }
        client.executeDevToolsMethod(method, parameters?.toString()).whenComplete { result, error ->
            if (continuation.isActive) {
                if (error != null) continuation.resumeWithException(error)
                else try { continuation.resume(desktopJson.parseToJsonElement(result).jsonObject) }
                catch (e: Exception) { continuation.resumeWithException(e) }
            }
        }
    }
internal suspend fun clearCookies(browser: CefBrowser) { browserCommand(browser, "Network.clearBrowserCookies") }
internal suspend fun seedCookies(browser: CefBrowser, origin: String, header: String) {
    for (part in header.split(';').map(String::trim).filter { '=' in it }) {
        val name = part.substringBefore('='); val value = part.substringAfter('=')
        if (name.matches(Regex("[A-Za-z0-9_-]+"))) {
            val result = browserCommand(browser, "Network.setCookie", buildJsonObject {
                put("name", name); put("value", value); put("url", origin); put("path", "/"); put("secure", true); put("httpOnly", true)
            })
            check(result["success"]?.jsonPrimitive?.boolean == true)
        }
    }
}
internal suspend fun readCookies(browser: CefBrowser, url: String): String {
    val result = browserCommand(browser, "Network.getCookies", buildJsonObject { putJsonArray("urls") { add(url) } })
    return result["cookies"]?.jsonArray.orEmpty().map { it.jsonObject }
        .sortedByDescending { it["path"]?.jsonPrimitive?.content?.length ?: 0 }
        .joinToString("; ") { "${it.getValue("name").jsonPrimitive.content}=${it.getValue("value").jsonPrimitive.content}" }
}

internal class HtmlResource(html: String) : CefResourceHandlerAdapter() {
    private val bytes = html.toByteArray(); private var offset = 0
    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean { callback.Continue(); return true }
    override fun getResponseHeaders(response: CefResponse, length: IntRef, redirect: StringRef) {
        response.status = 200; response.mimeType = "text/html"; response.setHeaderByName("Cache-Control", "no-store", true); length.set(bytes.size)
    }
    override fun readResponse(data: ByteArray, toRead: Int, read: IntRef, callback: CefCallback): Boolean {
        val count = minOf(toRead, bytes.size - offset); if (count <= 0) { read.set(0); return false }
        bytes.copyInto(data, 0, offset, offset + count); offset += count; read.set(count); return true
    }
}

internal fun vipsPreviewPage(url: String, course: String): String {
    val exercise = url.substringBefore('?').endsWith("show_exercise")
    val base = "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets"
    val next = "$base/show_exercise?assignment_id=3&amp;exercise_id=1&amp;cid=$course"
    val overview = "$base/show_assignment?assignment_id=3&amp;cid=$course"
    return """<!doctype html><html><head><meta charset="utf-8"><style>body{font:17px/1.7 system-ui,sans-serif;color:#33454d;background:#f5f6f4;padding:36px;max-width:760px;margin:auto}h1{font-weight:500}a{color:#416779;display:block;padding:18px 0}small{opacity:.7}</style></head><body>
        <small>PREVIEW · SAMPLE</small><h1>${if (exercise) "Breadth-first search" else "Graphs · Exercise sheet 3"}</h1>
        ${if (exercise) "<p>Consider a graph with edges A–B, A–C, B–D and C–D. Give a breadth-first traversal starting at A.</p><p>A → B → C → D</p><p>This is a read-only sample. Real sheets use the official Vips answer controls.</p><a href='$overview'>← Sheet overview</a>" else "<p>Practice graph traversal and shortest paths.</p><a href='$next'>1. Breadth-first search →</a>"}
        <hr><h2>Exercise attachments</h2><a href="$DIGICAMPUS_ORIGIN/sendfile.php?type=0&amp;file_id=preview-vips-pdf&amp;file_name=Graphs%20-%20Exercise%20sheet%203.pdf&amp;cid=$course" target="_blank">Graphs - Exercise sheet 3.pdf</a></body></html>""".trimIndent()
}
