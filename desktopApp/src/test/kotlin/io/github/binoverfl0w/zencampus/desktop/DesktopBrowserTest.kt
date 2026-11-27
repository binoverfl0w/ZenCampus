package io.github.binoverfl0w.zencampus.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.*
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import io.github.binoverfl0w.zencampus.ui.study.CourseCommunityLinks
import io.github.binoverfl0w.zencampus.ui.study.LocalVipsReader
import io.github.binoverfl0w.zencampus.ui.study.VipsSheetRow
import com.sun.net.httpserver.HttpServer
import java.awt.BorderLayout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.awt.Component
import java.awt.Container
import java.awt.Window
import javax.swing.*
import kotlin.coroutines.resume
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRequestContext
import org.cef.handler.*
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

/** Real Chromium with local fixtures; an optional smoke test visits the public sign-in page without credentials. */
class DesktopBrowserTest {
    private val microsoftFixture = "https://login.microsoftonline.com/__zencampus_offline_fixture/"
    private val signInPages = mapOf(
        "pick" to """
            <button data-test-id="accountTile" data-username="other@example.test">Other account</button>
            <button data-test-id="accountTile" data-username="fixture@example.test" onclick="location.href='password'">Fixture account</button>
        """,
        "email" to """
            <h1>Fixture email prompt</h1>
            <form method="post" onsubmit="event.preventDefault(); if (loginfmt.value === 'fixture@example.test') location.href='password'">
                <input name="loginfmt"><button id="idSIButton9">Next</button>
            </form>
        """,
        "password" to """
            <form method="post" onsubmit="event.preventDefault(); if (passwd.value === 'Local fixture' && passwd.dataset.events === 'ic') location.href='authenticator'">
                <input type="password" name="passwd" data-events="" oninput="this.dataset.events += 'i'" onchange="this.dataset.events += 'c'">
                <button id="idSIButton9">Sign in</button>
            </form>
        """,
        "authenticator" to """<h1>Fixture Authenticator</h1><input name="otc"><input name="numberMatching">""",
        "rejected" to """<form method="post" onsubmit="event.preventDefault(); document.body.textContent='Unexpected resubmission'">
            <input type="password" name="passwd"><button id="idSIButton9">Sign in</button>
            <p id="passwordError">Incorrect password</p>
        </form>""",
    )
    private val libraryFixture = "$LIBRARY_ORIGIN/TouchPoint/userAccount.do?methodToCall=start"
    private val libraryPage = """
        <form id="LoginBean" action="/TouchPoint/login.do" method="post">
            <input name="login" autocomplete="username"><input name="password" type="password" autocomplete="current-password">
            <input name="otp" autocomplete="one-time-code"><button type="submit">Sign in</button>
        </form><p id="status">Library fixture login</p>
        <script>
            const form = document.forms[0];
            const status = document.getElementById('status');
            form.onsubmit = event => {
                event.preventDefault();
                const user = form.elements.login.value;
                const password = form.elements.password.value;
                status.textContent = form.elements.otp.value !== '' ? 'Unexpected second factor' :
                    (user === '12345' && password === 'Library fixture' ? 'Library selected fixture account' :
                    (user === '65432' && password === 'New fixture password' ? 'Library added fixture account' : 'Unexpected fixture sign-in'));
                form.elements.login.value = '';
                form.elements.password.value = '';
            };
        </script>
    """.trimIndent()

    companion object {
        private lateinit var root: File
        private lateinit var container: DesktopContainer
        private lateinit var browser: DesktopBrowser

        @JvmStatic
        @BeforeClass
        fun setUpBrowserEngine() {
            root = Files.createTempDirectory("zen-desktop-browser-").toFile()
            container = DesktopContainer(DesktopPaths(root), MemoryVault(), allowTestData = true)
            browser = DesktopBrowser(container)
        }

        @JvmStatic
        @AfterClass
        fun tearDownBrowserEngine() {
            runBlocking {
                if (::browser.isInitialized) browser.close()
                if (::container.isInitialized) container.close()
                if (::root.isInitialized) root.deleteRecursively()
            }
        }
    }

    @Test(timeout = 90_000) fun liveAnonymousSignInLoads() = runBlocking {
        org.junit.Assume.assumeTrue("Optional live anonymous sign-in smoke", System.getenv("ZEN_LIVE_SIGNIN_TEST") == "1")
        try {
            container.ready.first { it }
            withContext(Dispatchers.Main) { browser.signIn(SignInService.DIGICAMPUS) }
            val view = withTimeout(60_000) {
                while (view(browser)?.identifier?.let { it > 0 } != true) delay(100)
                checkNotNull(view(browser))
            }
            try { waitForText(view, "Hinweise zum Login") }
            catch (e: Exception) { throw AssertionError("Anonymous sign-in page failed: ${browserStatus(browser)}; url=${view.url}", e) }
            withTimeout(30_000) { while (browserStatus(browser).startsWith("Opening browser")) delay(100) }
            waitForRenderedContent(view)
            val position = devtools(view, "Runtime.evaluate", """{"expression":"(()=>{const a=document.querySelector('#loginbox a[href*=\\\"Shibboleth.sso/Login\\\"]');const r=a.getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]})()","returnByValue":true}""")
            val values = position.getValue("result").jsonObject.getValue("value").jsonArray
            val observed = AtomicReference<String>("none")
            val listener = java.awt.event.AWTEventListener { event ->
                if (event is java.awt.event.MouseEvent && event.id == java.awt.event.MouseEvent.MOUSE_PRESSED)
                    observed.set("target=${event.component.javaClass.name}, x=${event.x}, y=${event.y}, browser=${event.component === view.uiComponent}")
            }
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(listener, java.awt.AWTEvent.MOUSE_EVENT_MASK)
            val origin = withContext(Dispatchers.Main) { val frame = browserFrame(browser); frame.toFront(); frame.requestFocus(); view.uiComponent.locationOnScreen }
            withContext(Dispatchers.IO) {
                val robot = java.awt.Robot(); robot.mouseMove(origin.x + values[0].jsonPrimitive.double.toInt(), origin.y + values[1].jsonPrimitive.double.toInt())
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            }
            val navigated = withTimeoutOrNull(20_000) {
                while (runCatching { java.net.URI(view.url).host }.getOrNull() !in setOf("auth.rz.uni-augsburg.de", "idp.rz.uni-augsburg.de")) delay(100)
                true
            } ?: false
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            check(navigated) { "Login did not reach the identity provider: host=${runCatching { java.net.URI(view.url).host }.getOrNull()}, path=${runCatching { java.net.URI(view.url).path }.getOrNull()}, origin=$origin, click=${values.joinToString()}, event=${observed.get()}, status=${browserStatus(browser)}" }
            println("Anonymous sign-in page rendered and reached ${java.net.URI(view.url).host}; ${browserStatus(browser)}")
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { browser.closeWindow() }
        }
    }
    @Test(timeout = 420_000) fun realBrowserSupportsCookiesExercisesAttachmentsAndAccountRevocation() = runBlocking {
        var fixtureViewer: JFrame? = null
        try {
            container.ready.first { it }; container.preview()
            withTimeout(10_000) { container.repository.filterNotNull().first() }
            var attachment: MaterialPresentation? = null
            val attachmentOpens = AtomicInteger()
            withContext(Dispatchers.Main) {
                container.materials.onPdf = {
                    attachment = it; attachmentOpens.incrementAndGet()
                    if (System.getenv("ZEN_POPUP_FOCUS_TEST") == "1") fixtureViewer = JFrame("PDF popup fixture").apply {
                        defaultCloseOperation = javax.swing.WindowConstants.DISPOSE_ON_CLOSE
                        add(javax.swing.JLabel("Local PDF fixture")); setSize(500, 400); isVisible = true
                    }
                }
                container.closePrivateReaders = browser::closeVipsWindow
                browser.vips("algorithms", "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=3&cid=algorithms", "Browser verification")
            }
            // A fresh CI runner first unpacks Chromium (and Windows scans every file), so the first start gets longer there.
            val started = System.currentTimeMillis()
            var view = withTimeout(if (System.getenv("CI") != null) 240_000 else 60_000) {
                var reported = 0L
                while (view(browser)?.identifier?.let { it > 0 } != true) {
                    val waited = System.currentTimeMillis() - started
                    if (waited - reported >= 10_000) { reported = waited; System.err.println("browser start: ${waited / 1000} s, CEF ${org.cef.CefApp.getState()}, view ${view(browser)}"); System.err.flush() }
                    delay(100)
                }
                checkNotNull(view(browser))
            }
            println("browser view ready after ${(System.currentTimeMillis() - started) / 1000} s")
            waitForText(view, "Practice graph traversal")
            if (System.getenv("ZEN_VIPS_MOUSE_TEST") == "1") {
                focusFixtureWindow(browserFrame(browser))
                // Start away from either edge because the OS can invert the Robot wheel direction.
                val initialOffset = devtools(view, "Runtime.evaluate", """{"expression":"window.__qaWheel=0;window.addEventListener('wheel',()=>window.__qaWheel++);document.body.style.minHeight='2000px';window.scrollTo(0,300);window.scrollY","returnByValue":true}""")
                    .getValue("result").jsonObject.getValue("value").jsonPrimitive.double
                assertEquals(300.0, initialOffset)
                val scrollOrigin = withContext(Dispatchers.Main) { view.uiComponent.locationOnScreen }
                withContext(Dispatchers.IO) { val robot = java.awt.Robot(); robot.mouseMove(scrollOrigin.x + 300, scrollOrigin.y + 200); robot.mouseWheel(3) }
                val scrolled = withTimeoutOrNull(8_000) {
                    while (true) {
                        val offset = devtools(view, "Runtime.evaluate", """{"expression":"window.scrollY","returnByValue":true}""")
                        if (offset.getValue("result").jsonObject["value"]?.jsonPrimitive?.doubleOrNull?.let { it != initialOffset } == true) break
                        delay(100)
                    }
                    true
                } ?: false
                if (!scrolled) {
                    val diag = devtools(view, "Runtime.evaluate", """{"expression":"JSON.stringify([window.scrollY,document.documentElement.scrollHeight,innerHeight,window.__qaWheel])","returnByValue":true}""")
                    error("Vips wheel failed: ${diag}")
                }
                devtools(view, "Runtime.evaluate", """{"expression":"window.scrollTo(0,0)"}""")
                val position = devtools(view, "Runtime.evaluate", """{"expression":"(()=>{window.__qaClicks=[];document.addEventListener('click',e=>window.__qaClicks.push([e.clientX,e.clientY,e.target.tagName]));const a=document.querySelector('a[href*=\\\"show_exercise\\\"]');const r=a.getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]})()","returnByValue":true}""")
                val point = position.getValue("result").jsonObject.getValue("value").jsonArray
                val origin = withContext(Dispatchers.Main) { view.uiComponent.locationOnScreen }
                withContext(Dispatchers.IO) {
                    val robot = java.awt.Robot(); robot.mouseMove(origin.x + point[0].jsonPrimitive.double.toInt(), origin.y + point[1].jsonPrimitive.double.toInt())
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                }
                try { withTimeout(8_000) { waitForText(view, "Consider a graph") } }
                catch (e: Exception) {
                    val diag = devtools(view, "Runtime.evaluate", """{"expression":"JSON.stringify([innerWidth,innerHeight,window.__qaClicks])","returnByValue":true}""")
                    throw AssertionError("Vips mouse click failed: origin=$origin, point=$point, DOM=${diag}; ${browserStatus(browser)}", e)
                }
                waitForRenderedContent(view)
                withContext(Dispatchers.Main) { view.goBack() }
                waitForText(view, "Practice graph traversal")
            }
            verifyRealHttpTransport()
            seedCookies(view, DIGICAMPUS_ORIGIN, "zen_test_session=fixture-only")
            assertTrue(readCookies(view, "$DIGICAMPUS_API/users/me").contains("zen_test_session=fixture-only"))
            assertFalse(readCookies(view, LIBRARY_BASE).contains("fixture-only"))
            val previous = view
            withContext(Dispatchers.Main) {
                browser.vips("algorithms", "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=3&cid=algorithms", "Browser verification")
            }
            view = withTimeoutOrNull(10_000) {
                while (view(browser)?.let { it !== previous && it.identifier > 0 } != true) delay(100)
                checkNotNull(view(browser))
            } ?: error("Replacement browser unavailable: CEF=${CefApp.getState()}, ${browserStatus(browser)}")
            waitForText(view, "Practice graph traversal")
            assertFalse(readCookies(view, "$DIGICAMPUS_API/users/me").contains("fixture-only"))
            clearCookies(view)
            withContext(Dispatchers.Main) { view.loadURL("$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_exercise?assignment_id=3&exercise_id=1&cid=algorithms") }
            waitForText(view, "Consider a graph")
            withTimeout(10_000) {
                while (true) {
                    val result = devtools(view, "Runtime.evaluate", """{"expression":"!!window.__zenVipsLinkRouting","returnByValue":true}""")
                    if (result.getValue("result").jsonObject["value"]?.jsonPrimitive?.booleanOrNull == true) break
                    delay(100)
                }
            }
            if (System.getenv("ZEN_VIPS_MOUSE_TEST") == "1" || System.getenv("ZEN_POPUP_FOCUS_TEST") == "1") focusFixtureWindow(browserFrame(browser))
            val target = devtools(view, "Runtime.evaluate", """{"expression":"(()=>{const a=document.querySelector('a[target=\\\"_blank\\\"]');a.scrollIntoView({block:'center'});const r=a.getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]})()","returnByValue":true}""")
            val point = target.getValue("result").jsonObject.getValue("value").jsonArray
            val x = point[0].jsonPrimitive.double.toInt(); val y = point[1].jsonPrimitive.double.toInt()
            if (System.getenv("ZEN_VIPS_MOUSE_TEST") == "1" || System.getenv("ZEN_POPUP_FOCUS_TEST") == "1") {
                val origin = withContext(Dispatchers.Main) { view.uiComponent.locationOnScreen }
                withContext(Dispatchers.IO) { val robot = java.awt.Robot(); robot.mouseMove(origin.x + x, origin.y + y); robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK) }
            } else for (event in listOf("mousePressed", "mouseReleased")) {
                devtoolsInput(view, "Input.dispatchMouseEvent", """{"type":"$event","x":$x,"y":$y,"button":"left","clickCount":1}""")
            }
            withTimeout(15_000) { while (attachment == null) delay(100) }
            assertEquals("Graphs - Exercise sheet 3.pdf", attachment!!.name)
            assertEquals("application/pdf", attachment!!.mime)
            withTimeout(10_000) {
                while (container.repository.value!!.sync.value.active.any { it.startsWith("linked-material:") || it.startsWith("material:OPEN:") }) delay(50)
            }
            // Closing a viewer must not prevent the same link from opening it again.
            attachment = null
            if (System.getenv("ZEN_POPUP_FOCUS_TEST") == "1") {
                val viewer = withTimeout(5_000) { while (fixtureViewer == null) delay(50); checkNotNull(fixtureViewer) }
                focusFixtureWindow(viewer)
                withContext(Dispatchers.Main) { viewer.dispatchEvent(java.awt.event.WindowEvent(viewer, java.awt.event.WindowEvent.WINDOW_CLOSING)); fixtureViewer = null }
                delay(500)
                val origin = withContext(Dispatchers.Main) { view.uiComponent.locationOnScreen }
                withContext(Dispatchers.IO) { val robot = java.awt.Robot(); robot.mouseMove(origin.x + x, origin.y + y); robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK) }
            } else for (event in listOf("mousePressed", "mouseReleased")) {
                    devtoolsInput(view, "Input.dispatchMouseEvent", """{"type":"$event","x":$x,"y":$y,"button":"left","clickCount":1}""")
            }
            withTimeout(15_000) { while (attachmentOpens.get() < 2) delay(100) }
            assertNotNull(attachment)
            if (System.getenv("ZEN_POPUP_FOCUS_TEST") == "1") repeat(5) { round ->
                withTimeout(10_000) { while (container.repository.value!!.sync.value.active.any { it.startsWith("linked-material:") || it.startsWith("material:OPEN:") }) delay(50) }
                val viewer = withTimeout(5_000) { while (fixtureViewer == null) delay(50); checkNotNull(fixtureViewer) }
                focusFixtureWindow(viewer)
                withContext(Dispatchers.Main) { viewer.dispatchEvent(java.awt.event.WindowEvent(viewer, java.awt.event.WindowEvent.WINDOW_CLOSING)); fixtureViewer = null }
                delay(100)
                val origin = withContext(Dispatchers.Main) { view.uiComponent.locationOnScreen }
                withContext(Dispatchers.IO) { val robot = java.awt.Robot(); robot.mouseMove(origin.x + x, origin.y + y); robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK) }
                withTimeout(15_000) { while (attachmentOpens.get() < round + 3) delay(100) }
            }
            withContext(Dispatchers.Main) { fixtureViewer?.dispose(); fixtureViewer = null }
            waitForText(view, "Consider a graph")
            withContext(Dispatchers.Main) { view.goBack() }
            waitForText(view, "Practice graph traversal")
            val beforeClose = view
            withContext(Dispatchers.Main) { browserFrame(browser).dispatchEvent(java.awt.event.WindowEvent(browserFrame(browser), java.awt.event.WindowEvent.WINDOW_CLOSING)) }
            withTimeout(10_000) { while (view(browser) != null) delay(100) }
            withContext(Dispatchers.Main) { browser.vips("algorithms", "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=3&cid=algorithms", "Browser verification") }
            view = withTimeout(20_000) {
                while (view(browser)?.let { it !== beforeClose && it.identifier > 0 } != true) delay(100)
                checkNotNull(view(browser))
            }
            waitForText(view, "Practice graph traversal")
            repeat(2) {
                val old = view
                withContext(Dispatchers.Main) {
                    browser.closeWindow()
                    browser.vips("algorithms", "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=3&cid=algorithms", "Browser verification")
                    browser.closeWindow() // Close during startup, then immediately try again.
                    browser.vips("algorithms", "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=3&cid=algorithms", "Browser verification")
                }
                view = withTimeout(20_000) {
                    while (view(browser)?.let { it !== old && it.identifier > 0 } != true) delay(100)
                    checkNotNull(view(browser))
                }
                waitForText(view, "Practice graph traversal")
            }
            if (System.getenv("ZEN_POPUP_FOCUS_TEST") == "1") verifyReopeningFromMainWindow(browser, container)
            container.signOut()
            withTimeout(10_000) { while (view(browser) != null || container.repository.value != null) delay(100) }
            assertNull(container.repository.value)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                fixtureViewer?.dispose()
                browser.closeWindow()
            }
        }
    }

    /** A real HTTP loader exercises network validation, which intercepted preview resources bypass. */
    private suspend fun verifyRealHttpTransport() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sheet") { exchange ->
            val body = "<html><body>Network sheet ${requests.incrementAndGet()}</body></html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "public, max-age=3600")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/sheet")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/form") { exchange ->
            val body = """<html><body><button id="tap" style="position:fixed;left:20px;top:20px;width:120px;height:40px" onclick="document.querySelector('#state').textContent='Mouse clicked'">Tap</button><div id="state"></div><form style="margin-top:90px" onsubmit="event.preventDefault();document.body.textContent='Form submitted'"><input id="entry" name="entry"><button type="submit">Submit</button></form></body></html>""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = CefApp.getInstance().createClient()
        var view: CefBrowser? = null
        var frame: JFrame? = null
        val created = CompletableDeferred<CefBrowser>()
        val failure = AtomicReference<CefLoadHandler.ErrorCode?>()
        val savedPopup = AtomicReference<SavedSignInPopup?>()
        try {
            client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                override fun onAfterCreated(browser: CefBrowser) { created.complete(browser) }
            })
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain) return
                    val page = frame.getURL()
                    SwingUtilities.invokeLater {
                        savedPopup.get()?.scriptForPage(page)?.let { browser.executeJavaScript(it, page, 0) }
                    }
                }
                override fun onLoadError(browser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String) {
                    if (frame.isMain && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) failure.set(errorCode)
                }
            })
            client.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun getResourceRequestHandler(browser: CefBrowser, frame: CefFrame?, request: CefRequest,
                    isNavigation: Boolean, isDownload: Boolean, requestInitiator: String?, disableDefaultHandling: BoolRef): CefResourceRequestHandler {
                    // Chromium sees the real origin, but the handler owns the entire origin during this test.
                    if (request.url.startsWith("$STUDY_SIGN_IN_ORIGIN/") || request.url.startsWith("$LIBRARY_ORIGIN/")) disableDefaultHandling.set(true)
                    return object : CefResourceRequestHandlerAdapter() {
                        override fun getResourceHandler(browser: CefBrowser, frame: CefFrame?, request: CefRequest): CefResourceHandler? {
                            val content = when {
                                request.url.startsWith("$STUDY_SIGN_IN_ORIGIN/") -> signInPages[request.url.removePrefix(microsoftFixture)].orEmpty()
                                request.url.startsWith("$LIBRARY_ORIGIN/") -> if (request.url == libraryFixture) libraryPage else ""
                                else -> return null
                            }
                            return HtmlResource("<!doctype html><html><body>$content</body></html>")
                        }
                        override fun onBeforeResourceLoad(browser: CefBrowser, frame: CefFrame?, request: CefRequest): Boolean {
                            return false
                        }
                    }
                }
            })
            withContext(Dispatchers.Main) {
                // Windowed on Windows, like the app: the off-screen view needs an OpenGL surface CI runners cannot provide.
                view = client.createBrowser("about:blank", !isWindows, false, CefRequestContext.createContext(null))
                frame = JFrame("Local HTTP verification").apply {
                    layout = BorderLayout(); add(view!!.uiComponent, BorderLayout.CENTER)
                    setSize(640, 480); isVisible = true
                }
                // Like the app: native creation must not wait for a Swing paint, which a slow Windows runner can delay.
                if (System.getProperty("os.name").startsWith("Mac") || isWindows) view!!.createImmediately()
            }
            val ready = withTimeout(15_000) { created.await() }
            prepareVipsBrowser(ready)
            val address = "http://127.0.0.1:${server.address.port}/redirect"
            withContext(Dispatchers.Main) { ready.loadURL(address) }
            withTimeout(10_000) {
                while (requests.get() == 0) {
                    assertNull(failure.get(), "Network navigation failed before reaching the local HTTP server")
                    delay(50)
                }
            }
            waitForText(ready, "Network sheet 1")
            withContext(Dispatchers.Main) { ready.loadURL("data:text/html,<html><body>Idle fixture</body></html>") }
            waitForText(ready, "Idle fixture")
            withContext(Dispatchers.Main) { ready.loadURL(address) }
            waitForText(ready, "Network sheet 2")
            assertEquals(2, requests.get(), "A cacheable sheet must be fetched again after revisiting it")
            assertNull(failure.get())
            verifySavedSignIns(ready, frame!!, savedPopup)
            withContext(Dispatchers.Main) { ready.loadURL("http://127.0.0.1:${server.address.port}/form") }
            waitForText(ready, "Submit")
            // The rest checks the off-screen view's AWT input workarounds. Windows' windowed view gets input from the
            // system instead, which only arrives while Chromium reads its native windows' messages, so it is clicked and
            // typed into for real.
            if (isWindows) {
                withContext(Dispatchers.Main) { frame!!.toFront(); frame!!.requestFocus() }
                for ((selector, expected) in listOf("#tap" to "Mouse clicked", "#entry" to null)) {
                    val target = devtools(ready, "Runtime.evaluate", """{"expression":"(()=>{const r=document.querySelector('$selector').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]})()","returnByValue":true}""")
                    val point = target.getValue("result").jsonObject.getValue("value").jsonArray
                    val origin = withContext(Dispatchers.Main) { ready.uiComponent.locationOnScreen }
                    withContext(Dispatchers.IO) {
                        val robot = java.awt.Robot(); robot.mouseMove(origin.x + point[0].jsonPrimitive.double.toInt(), origin.y + point[1].jsonPrimitive.double.toInt())
                        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                        if (expected == null) { robot.delay(300); robot.keyPress(java.awt.event.KeyEvent.VK_A); robot.keyRelease(java.awt.event.KeyEvent.VK_A) }
                    }
                    if (expected != null) {
                        try { withTimeout(6_000) { waitForText(ready, expected) } }
                        catch (e: TimeoutCancellationException) { throw AssertionError("A real mouse click did not reach the windowed page", e) }
                    }
                }
                withTimeout(6_000) {
                    while (true) {
                        val value = devtools(ready, "Runtime.evaluate", """{"expression":"document.querySelector('#entry').value","returnByValue":true}""")
                        if (value.getValue("result").jsonObject["value"]?.jsonPrimitive?.content == "a") break
                        delay(100)
                    }
                }
                return
            }
            val testScope = CoroutineScope(currentCoroutineContext())
            val wheelForwarder = withContext(Dispatchers.Main) { OffscreenWheelForwarder(ready, testScope) }
            try {
                devtools(ready, "Runtime.evaluate", """{"expression":"document.body.style.minHeight='2000px'"}""")
                withContext(Dispatchers.Main) {
                    val component = ready.uiComponent
                    component.dispatchEvent(java.awt.event.MouseWheelEvent(component, java.awt.event.MouseEvent.MOUSE_WHEEL,
                        System.currentTimeMillis(), 0, 70, 40, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 2))
                }
                withTimeout(6_000) {
                    while (true) {
                        val position = devtools(ready, "Runtime.evaluate", """{"expression":"window.scrollY","returnByValue":true}""")
                        if ((position.getValue("result").jsonObject["value"]?.jsonPrimitive?.doubleOrNull ?: 0.0) > 0) break
                        delay(100)
                    }
                }
                devtools(ready, "Runtime.evaluate", """{"expression":"window.scrollTo(0,0)"}""")
            } finally { withContext(Dispatchers.Main) { wheelForwarder.close() } }
            withContext(Dispatchers.Main) {
                val component = ready.uiComponent; val t = System.currentTimeMillis()
                component.dispatchEvent(java.awt.event.MouseEvent(component, java.awt.event.MouseEvent.MOUSE_PRESSED, t,
                    java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 70, 40, 1, false, java.awt.event.MouseEvent.BUTTON1))
                component.dispatchEvent(java.awt.event.MouseEvent(component, java.awt.event.MouseEvent.MOUSE_RELEASED, t + 1,
                    0, 70, 40, 1, false, java.awt.event.MouseEvent.BUTTON1))
            }
            withTimeout(6_000) { waitForText(ready, "Mouse clicked") }
            if (System.getenv("ZEN_VIPS_MOUSE_TEST") == "1") {
                focusFixtureWindow(checkNotNull(frame))
                val target = devtools(ready, "Runtime.evaluate", """{"expression":"(()=>{const r=document.querySelector('#entry').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]})()","returnByValue":true}""")
                val point = target.getValue("result").jsonObject.getValue("value").jsonArray
                val origin = withContext(Dispatchers.Main) { ready.uiComponent.locationOnScreen }
                withContext(Dispatchers.IO) {
                    val robot = java.awt.Robot(); robot.mouseMove(origin.x + point[0].jsonPrimitive.double.toInt(), origin.y + point[1].jsonPrimitive.double.toInt())
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                    robot.keyPress(java.awt.event.KeyEvent.VK_A); robot.keyRelease(java.awt.event.KeyEvent.VK_A)
                }
                withTimeout(6_000) {
                    while (true) {
                        val value = devtools(ready, "Runtime.evaluate", """{"expression":"document.querySelector('#entry').value","returnByValue":true}""")
                        if (value.getValue("result").jsonObject["value"]?.jsonPrimitive?.content == "a") break
                        delay(100)
                    }
                }
            }
            devtools(ready, "Runtime.evaluate", """{"expression":"document.querySelector('#entry').focus()"}""")
            val forwarder = withContext(Dispatchers.Main) { OffscreenEnterForwarder(ready, testScope) }
            try {
                withContext(Dispatchers.Main) {
                    ready.setFocus(true)
                    val component = ready.uiComponent; val time = System.currentTimeMillis()
                    val press = java.awt.event.KeyEvent(component, java.awt.event.KeyEvent.KEY_PRESSED, time, 0, java.awt.event.KeyEvent.VK_ENTER, '\n')
                    val typed = java.awt.event.KeyEvent(component, java.awt.event.KeyEvent.KEY_TYPED, time + 1, 0, java.awt.event.KeyEvent.VK_UNDEFINED, '\n')
                    val release = java.awt.event.KeyEvent(component, java.awt.event.KeyEvent.KEY_RELEASED, time + 2, 0, java.awt.event.KeyEvent.VK_ENTER, '\n')
                    val shiftEnter = java.awt.event.KeyEvent(component, java.awt.event.KeyEvent.KEY_PRESSED, time, java.awt.event.InputEvent.SHIFT_DOWN_MASK, java.awt.event.KeyEvent.VK_ENTER, '\n')
                    val letter = java.awt.event.KeyEvent(component, java.awt.event.KeyEvent.KEY_PRESSED, time, 0, java.awt.event.KeyEvent.VK_A, 'a')
                    assertFalse(forwarder.dispatchKeyEvent(shiftEnter)); assertFalse(forwarder.dispatchKeyEvent(letter))
                    assertTrue(forwarder.dispatchKeyEvent(press)); assertTrue(forwarder.dispatchKeyEvent(typed)); assertTrue(forwarder.dispatchKeyEvent(release))
                }
                waitForText(ready, "Form submitted")
            } finally { withContext(Dispatchers.Main) { forwarder.close() } }
        } finally {
            withContext(Dispatchers.Main) { view?.close(true); frame?.dispose(); client.dispose() }
            server.stop(0)
        }
    }

    private suspend fun verifySavedSignIns(view: CefBrowser, frame: JFrame, active: AtomicReference<SavedSignInPopup?>) = coroutineScope {
        container.ready.first { it }
        val session = container.signInSession
        val vault = assertNotNull(session.savedSignIns)
        vault.removeAll()
        vault.save(SignInKind.STUDY, STUDY_SIGN_IN_ORIGIN, "fixture@example.test", "Local fixture", "Fixture account")
        val otherStudy = vault.save(SignInKind.STUDY, STUDY_SIGN_IN_ORIGIN, "other@example.test", "Other fixture", null)
        vault.save(SignInKind.LIBRARY, LIBRARY_ORIGIN, "12345", "Library fixture", null)
        vault.save(SignInKind.LIBRARY, LIBRARY_ORIGIN, "98765", "Other library fixture", null)
        val popupScope = CoroutineScope(SupervisorJob(coroutineContext.job) + Dispatchers.Main)

        fun fill() {
            val page = view.url ?: return
            active.get()?.scriptForPage(page)?.let { view.executeJavaScript(it, page, 0) }
        }

        suspend fun bar(kind: SignInKind) = withContext(Dispatchers.Main) {
            val popup = assertNotNull(session.openSavedSignIn(popupScope, kind, false, "Fixture password rejected"))
            active.set(popup)
            assertNull(popup.selected)
            DesktopSavedSignInBar(popup, Words(false), frame, { view.url }, ::fill).also {
                frame.add(it, BorderLayout.NORTH)
                frame.validate()
            }
        }

        suspend fun pick(panel: DesktopSavedSignInBar, username: String) = withContext(Dispatchers.Main) {
            panel.components.filterIsInstance<JButton>().single { it.text == "Sign in as…" }.doClick()
            val menu = MenuSelectionManager.defaultManager().selectedPath.filterIsInstance<JPopupMenu>().single()
            menu.components.filterIsInstance<JMenuItem>().single { it.text.startsWith("Sign in as $username ·") }.doClick()
            menu.isVisible = false
        }

        try {
            val studyBar = bar(SignInKind.STUDY)
            withContext(Dispatchers.Main) { view.loadURL(microsoftFixture + "pick") }
            waitForText(view, "Fixture account")
            pick(studyBar, "fixture@example.test")
            waitForText(view, "Fixture Authenticator")
            val mfa = devtools(view, "Runtime.evaluate", """{"expression":"[...document.querySelectorAll('input')].every(input => input.value === '')","returnByValue":true}""")
            assertEquals(true, mfa.getValue("result").jsonObject["value"]?.jsonPrimitive?.booleanOrNull)
            withContext(Dispatchers.Main) { view.loadURL(microsoftFixture + "rejected") }
            waitForText(view, "Fixture password rejected")
            withContext(Dispatchers.Main) { view.reload() }
            waitForText(view, "Fixture password rejected")
            withContext(Dispatchers.Main) { frame.remove(studyBar) }

            vault.delete(otherStudy.id)
            active.set(null)
            withContext(Dispatchers.Main) { view.loadURL(microsoftFixture + "email") }
            waitForText(view, "Fixture email prompt")
            withContext(Dispatchers.Main) {
                val single = assertNotNull(session.openSavedSignIn(popupScope, SignInKind.STUDY, false, "Fixture password rejected"))
                assertEquals("fixture@example.test", single.selected?.username)
                active.set(single)
                fill()
            }
            waitForText(view, "Fixture Authenticator")

            val libraryBar = bar(SignInKind.LIBRARY)
            withContext(Dispatchers.Main) { view.loadURL(libraryFixture) }
            waitForText(view, "Library fixture login")
            pick(libraryBar, "12345")
            waitForText(view, "Library selected fixture account")
            withContext(Dispatchers.Main) { view.reload() }
            waitForText(view, "Fixture password rejected")

            val opening = launch(Dispatchers.Main) {
                libraryBar.components.filterIsInstance<JButton>().single { it.text == "Add account" }.doClick()
            }
            try {
                withTimeout(8_000) {
                    while (!withContext(Dispatchers.Main) {
                        val dialog = Window.getWindows().filterIsInstance<JDialog>().firstOrNull { it.isShowing && it.title == "Add account" }
                            ?: return@withContext false
                        val fields = components(dialog).filterIsInstance<JTextField>().toList()
                        fields[0].text = "65432"
                        fields.filterIsInstance<JPasswordField>().single().text = "New fixture password"
                        fields[2].text = "New library account"
                        components(dialog).filterIsInstance<JButton>().single { it.text == "Save" }.doClick()
                        true
                    }) delay(25)
                }
                waitForText(view, "Library added fixture account")
            } finally {
                withContext(Dispatchers.Main) {
                    Window.getWindows().filterIsInstance<JDialog>().filter { it.title == "Add account" }.forEach(Window::dispose)
                }
                opening.join()
            }
            assertTrue(vault.entries.value.any { it.label == "New library account" && it.kind == SignInKind.LIBRARY })
            withContext(Dispatchers.Main) { frame.remove(libraryBar) }
        } finally {
            active.set(null)
            popupScope.coroutineContext.job.cancelAndJoin()
            vault.removeAll()
        }
    }

    private fun components(root: Container): Sequence<Component> = sequence {
        for (component in root.components) {
            yield(component)
            if (component is Container) yieldAll(components(component))
        }
    }

    private fun view(browser: DesktopBrowser): CefBrowser? {
        val current = DesktopBrowser::class.java.getDeclaredField("current").apply { isAccessible = true }.get(browser) ?: return null
        return current.javaClass.getDeclaredField("browser").apply { isAccessible = true }.get(current) as? CefBrowser
    }
    private fun browserStatus(browser: DesktopBrowser): String {
        val current = DesktopBrowser::class.java.getDeclaredField("current").apply { isAccessible = true }.get(browser) ?: return "window closed"
        val status = current.javaClass.getDeclaredField("status").apply { isAccessible = true }.get(current) as javax.swing.JTextArea
        val window = current.javaClass.getDeclaredField("frame").apply { isAccessible = true }.get(current) as JFrame
        val client = current.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(current)
        val view = view(browser)
        return "${status.text}, client=${client != null}, id=${view?.identifier}, shown=${window.isShowing}, size=${view?.uiComponent?.size}, scope=${(current.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(current) as CoroutineScope).isActive}"
    }
    private fun browserFrame(browser: DesktopBrowser): JFrame {
        val current = DesktopBrowser::class.java.getDeclaredField("current").apply { isAccessible = true }.get(browser)
        return current.javaClass.getDeclaredField("frame").apply { isAccessible = true }.get(current) as JFrame
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private suspend fun verifyReopeningFromMainWindow(browser: DesktopBrowser, container: DesktopContainer) {
        val peopleClicks = AtomicInteger()
        val sheetBounds = AtomicReference<androidx.compose.ui.geometry.Rect>()
        val peopleBounds = AtomicReference<androidx.compose.ui.geometry.Rect>()
        val snapshot = checkNotNull(container.repository.value).snapshot.value
        val course = snapshot.courses.first { it.id == "algorithms" }
        val sheet = VipsSheet("focus-fixture", "algorithms", "Exercise sheet", "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=3&cid=algorithms")
        lateinit var panel: ComposePanel
        val main = withContext(Dispatchers.Main) {
            panel = ComposePanel().apply {
                setContent {
                    ZenTheme(ThemeMode.LIGHT) {
                        CompositionLocalProvider(LocalVipsReader provides browser::vips) {
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxWidth().height(170.dp).onGloballyPositioned { sheetBounds.set(it.boundsInRoot()) }, contentAlignment = Alignment.Center) {
                                    VipsSheetRow(sheet) { }
                                }
                                Box(Modifier.fillMaxWidth().height(75.dp).onGloballyPositioned { peopleBounds.set(it.boundsInRoot()) }, contentAlignment = Alignment.CenterStart) {
                                    CourseCommunityLinks(course, snapshot) { _, _ -> peopleClicks.incrementAndGet() }
                                }
                            }
                        }
                    }
                }
            }
            JFrame("ZenCampus reopen fixture").apply { add(panel); setSize(900, 700); isVisible = true }
        }
        try {
            withContext(Dispatchers.Main) { browser.closeWindow() }
            focusFixtureWindow(main)
            withTimeout(10_000) { while (sheetBounds.get() == null || peopleBounds.get() == null) delay(100) }
            val rounds = System.getenv("ZEN_POPUP_STRESS")?.toIntOrNull()?.coerceIn(1, 20) ?: 3
            repeat(rounds) { round ->
                val origin = withContext(Dispatchers.Main) { panel.locationOnScreen }
                val sheetRect = sheetBounds.get()
                withContext(Dispatchers.IO) {
                    val robot = java.awt.Robot(); robot.mouseMove(origin.x + sheetRect.center.x.toInt(), origin.y + sheetRect.center.y.toInt())
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                }
                val child = withTimeout(20_000) {
                    while (view(browser)?.identifier?.let { it > 0 } != true) delay(100)
                    checkNotNull(view(browser))
                }
                try { waitForText(child, "Practice graph traversal") }
                catch (e: Exception) { throw AssertionError("Reopen round $round failed: ${browserStatus(browser)}, url=${child.url}", e) }
                withContext(Dispatchers.Main) { val frame = browserFrame(browser); frame.dispatchEvent(java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING)) }
                withTimeout(10_000) { while (view(browser) != null) delay(100) }
                delay(if (rounds > 3) 100 else 500)
                val peopleRect = peopleBounds.get()
                val nextOrigin = withContext(Dispatchers.Main) { panel.locationOnScreen }
                withContext(Dispatchers.IO) {
                    val robot = java.awt.Robot(); robot.mouseMove(nextOrigin.x + peopleRect.left.toInt() + 35, nextOrigin.y + peopleRect.center.y.toInt())
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                }
                withTimeout(10_000) { while (peopleClicks.get() < round + 1) delay(100) }
            }
        } finally { withContext(Dispatchers.Main) { browser.closeWindow(); main.dispose(); panel.dispose() } }
    }
    private suspend fun focusFixtureWindow(window: JFrame) {
        if (System.getenv("HYPRLAND_INSTANCE_SIGNATURE") != null) {
            val list = withContext(Dispatchers.IO) { ProcessBuilder("hyprctl", "-j", "clients").start().inputStream.bufferedReader().readText() }
            val client = Json.parseToJsonElement(list).jsonArray.map { it.jsonObject }.single {
                it["pid"]?.jsonPrimitive?.longOrNull == ProcessHandle.current().pid() && it["title"]?.jsonPrimitive?.content == window.title
            }
            val address = client.getValue("address").jsonPrimitive.content
            for (code in listOf("hl.dispatch(hl.dsp.window.float({window=\"address:$address\",action=\"enable\"}))", "hl.dispatch(hl.dsp.focus({window=\"address:$address\"}))")) {
                withContext(Dispatchers.IO) { val process = ProcessBuilder("hyprctl", "eval", code).redirectErrorStream(true).start(); val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { "Hyprland fixture focus failed: $output" } }
            }
            withContext(Dispatchers.Main) { window.setSize(900, 700); window.setLocation(80, 80) }
            delay(500)
        } else withContext(Dispatchers.Main) { window.toFront(); window.requestFocus() }
    }
    /** DevTools through the app's sender, which sends a command again when JCEF loses its reply. */
    private suspend fun devtools(view: CefBrowser, method: String, parameters: String): JsonObject =
        withContext(Dispatchers.Main) { browserCommand(view, method, Json.parseToJsonElement(parameters).jsonObject) }
    /** Input is sent once, since sending it again could click twice; the check that follows shows whether it arrived. */
    private suspend fun devtoolsInput(view: CefBrowser, method: String, parameters: String) {
        withContext(Dispatchers.Main) { withTimeoutOrNull(5_000) { browserCommandOnce(view, method, Json.parseToJsonElement(parameters).jsonObject) } }
    }
    private suspend fun waitForText(view: CefBrowser, expected: String) {
        val found = withTimeoutOrNull(20_000) {
            while (true) {
                val text = withTimeoutOrNull(1_000) { suspendCancellableCoroutine<String> { continuation ->
                    view.getText { if (continuation.isActive) continuation.resume(it) }
                } }
                if (text != null && expected in text) break
                delay(100)
            }
            true
        }
        assertTrue(found == true, "No fixture text ‘$expected’ appeared at ${runCatching { java.net.URI(view.url).path }.getOrNull()}")
    }
    private suspend fun waitForRenderedContent(view: CefBrowser) = withTimeout(20_000) {
        // A DOM-only check misses a Linux failure where the embedded native window stays blank.
        while (true) {
            val image = runCatching {
                val future = withContext(Dispatchers.Main) { view.createScreenshot(true) }
                withContext(Dispatchers.IO) { future.get(3, java.util.concurrent.TimeUnit.SECONDS) }
            }.getOrNull()
            if (image != null && image.width > 20 && image.height > 20) {
                val colors = mutableSetOf<Int>()
                for (y in 0 until image.height step 10) for (x in 0 until image.width step 10) colors += image.getRGB(x, y)
                if (colors.size > 8) break
            }
            delay(100)
        }
    }
}
