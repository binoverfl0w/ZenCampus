package io.github.binoverfl0w.zencampus.android

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.*
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenNotice
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The signed-in DigiCampus browser: Vips sheets with their answer controls, and any DigiCampus page opened with "Open in
 * DigiCampus". Published files are handed to the app's own download and reader; [CampusLinks] decides every navigation.
 */
class CampusPageActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var browserState: Bundle? = null
    private lateinit var accountScope: String
    private val files = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val container = (application as ZenApplication).container
        val values = if (result.resultCode == RESULT_OK && container.repository.value?.account?.cacheScope == accountScope)
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)?.filter { it.scheme == "content" }?.toTypedArray() else null
        fileCallback?.onReceiveValue(values); fileCallback = null
    }

    private fun back() { if (browser?.canGoBack() == true) browser?.goBack() else finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountScope = intent.getStringExtra("scope") ?: run { finish(); return }
        val course = intent.getStringExtra("course")
        val entry = CampusLinks.entry(intent.getStringExtra("url").orEmpty(), course) ?: run { finish(); return }
        browserState = savedInstanceState?.getBundle("browser")
        enableEdgeToEdge()
        val container = (application as ZenApplication).container
        setContent {
            val repository by container.repository.collectAsState()
            val ready by container.ready.collectAsState()
            val settings by container.settings.collectAsState()
            val sync = repository?.sync?.collectAsState()?.value ?: SyncStatus()
            val snapshot = repository?.snapshot?.collectAsState()?.value
            val preview = repository?.account?.demo == true
            var sessionReady by remember { mutableStateOf(false) }
            var progress by remember { mutableIntStateOf(0) }
            var pageError by remember { mutableStateOf(false) }
            var reconnect by remember { mutableStateOf(false) }
            var unsupported by remember { mutableStateOf(false) }
            var selectedUrl by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedCourse by rememberSaveable { mutableStateOf("") }
            val selected = selectedUrl?.let { VipsLinks.attachment(it, selectedCourse) }
            var currentPage by remember { mutableStateOf(entry) }
            val vipsPage = course != null && VipsLinks.page(currentPage, course)
            val words = Words(settings.language == AppLanguage.GERMAN ||
                settings.language == AppLanguage.SYSTEM && Locale.getDefault().language.startsWith("de"))
            val dark = settings.theme == ThemeMode.DARK || settings.theme == ThemeMode.SYSTEM && androidx.compose.foundation.isSystemInDarkTheme()
            LaunchedEffect(ready, repository) {
                if (!ready) return@LaunchedEffect
                if (repository?.account?.cacheScope != accountScope) { browser?.stopLoading(); finish(); return@LaunchedEffect }
                if (repository?.account?.demo == true && course == null) { finish(); return@LaunchedEffect } // The preview has no university pages.
                sessionReady = container.prepareVipsSession(accountScope)
                if (!sessionReady) finish()
            }
            val openAttachment: (String) -> Boolean = { url ->
                val owner = CampusLinks.attachmentCourse(url, course, currentPage)
                val link = owner?.let { VipsLinks.attachment(url, it) }
                if (owner != null && link != null) {
                    val repo = container.repository.value
                    if (repo?.account?.cacheScope == accountScope) {
                        selectedUrl = link.url; selectedCourse = owner; unsupported = false
                        repo.requestLinkedMaterial(owner, link.url, currentPage)
                    }
                    true
                } else false
            }
            ConfigureSystemBars(window, settings.theme)
            CompositionLocalProvider(LocalWords provides words) { ZenTheme(settings.theme) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                    Column(Modifier.statusBarsPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconAction(Symbol.BACK, words.text("back"), ::back)
                            Column(Modifier.weight(1f)) {
                                Text(intent.getStringExtra("title") ?: "DigiCampus", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium)
                                SmallLabel(words.tool(when { preview -> "reader_preview"; vipsPage -> "reader"; else -> "campus_reader" }))
                            }
                            if (!preview) IconAction(Symbol.EXTERNAL, words.text("open_browser"), { container.openUrl(currentPage) })
                        }
                        if (preview || vipsPage) Text(words.tool(if (preview) "preview_note" else "reader_note"), Modifier.padding(horizontal = 22.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!sessionReady || progress < 100) {
                            if (settings.reduceMotion) Text(words.text("refreshing"), Modifier.padding(horizontal = 22.dp))
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (pageError || reconnect || unsupported) ZenNotice(
                            words.tool(when { reconnect -> "reader_reconnect"; unsupported -> "reader_unsupported"; else -> "reader_error" }), Tone.DANGER,
                            when { reconnect -> words.tool("reconnect"); pageError -> words.text("retry"); !preview -> words.text("open_browser"); else -> null },
                            {
                                if (reconnect) startActivity(Intent(this@CampusPageActivity, LoginActivity::class.java))
                                else if (pageError) { pageError = false; browser?.reload() }
                                else container.openUrl(currentPage)
                            }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        selected?.let { link ->
                            val busy = sync.active.any { it == "linked-material:${link.id}" || it == "download:${link.id}" || it.startsWith("material:OPEN:${link.id}:") }
                            val error = sync.errors["download:${link.id}"]
                            if (busy || error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                val transfer = sync.materialProgress[link.id]
                                Text(if (error != null) words.failure(error) else words.tool("reader_download") +
                                    (transfer?.total?.takeIf { it > 0 }?.let { " ${transfer.bytes * 100 / it}%" } ?: ""),
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                if (error != null) ZenLink(words.text("retry"), { openAttachment(link.url) })
                            }
                            snapshot?.localDocuments?.firstOrNull { it.document.id == link.id }?.let { local ->
                                var menu by remember(link.id) { mutableStateOf(false) }
                                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(local.document.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Box {
                                        IconAction(Symbol.MORE, words.text("files"), { menu = true })
                                        DropdownMenu(menu, { menu = false }) {
                                            fun perform(action: MaterialAction) { menu = false; repository?.requestMaterial(local.document, action) }
                                            DropdownMenuItem(text = { Text(words.text("open_file")) }, onClick = { perform(MaterialAction.OPEN) })
                                            DropdownMenuItem(text = { Text(words.text("open_with")) }, onClick = { perform(MaterialAction.OPEN_WITH) })
                                            DropdownMenuItem(text = { Text(words.text("share_file")) }, onClick = { perform(MaterialAction.SHARE) })
                                            DropdownMenuItem(text = { Text(words.text("save_downloads")) }, onClick = { perform(MaterialAction.SAVE_TO_DOWNLOADS) })
                                            if (!local.pinned) DropdownMenuItem(text = { Text(words.text("keep_offline")) }, onClick = { perform(MaterialAction.KEEP_OFFLINE) })
                                            DropdownMenuItem(text = { Text(words.text("remove_download")) }, onClick = {
                                                menu = false; lifecycleScope.launch { repository?.removeDownload(local.document.id) }
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }) { padding ->
                    if (sessionReady) AndroidView(factory = { context ->
                        @SuppressLint("SetJavaScriptEnabled")
                        val view = WebView(context).apply {
                            // Compose gives views wrap_content; WebView then lays pages out with a zero-height viewport,
                            // so CSS 100vh is 0 and DigiCampus's menus open a few pixels tall.
                            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                            this.settings.javaScriptEnabled = !preview
                            this.settings.domStorageEnabled = !preview
                            this.settings.allowFileAccess = false
                            this.settings.allowContentAccess = false
                            this.settings.setGeolocationEnabled(false)
                            this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            this.settings.setSupportMultipleWindows(false)
                            this.settings.javaScriptCanOpenWindowsAutomatically = false
                            this.settings.builtInZoomControls = true
                            this.settings.displayZoomControls = false
                            this.settings.useWideViewPort = true
                            this.settings.loadWithOverviewMode = true
                            // Exercise pages and submitted answers stay out of WebView's disk cache.
                            this.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                                override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                                    if (preview || container.repository.value?.account?.cacheScope != accountScope) return false
                                    fileCallback?.onReceiveValue(null); fileCallback = callback
                                    return try { files.launch(params.createIntent()); true }
                                    catch (_: Exception) { fileCallback = null; callback.onReceiveValue(null); true }
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    if (!request.isForMainFrame) return request.url.scheme != "https"
                                    val url = request.url.toString()
                                    when (CampusLinks.navigation(url, course, currentPage, request.hasGesture(), preview)) {
                                        CampusRoute.PAGE -> return false
                                        CampusRoute.ATTACHMENT -> openAttachment(url)
                                        CampusRoute.RECONNECT -> { reconnect = true; progress = 100 }
                                        CampusRoute.EXTERNAL -> container.openUrl(url)
                                        CampusRoute.BLOCK -> { pageError = true; progress = 100 }
                                    }
                                    return true
                                }
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    if (!preview) return null
                                    // Preview never sends fabricated assignment IDs to the university.
                                    val html = if (course != null && request.isForMainFrame && request.method == "GET" && VipsLinks.page(request.url.toString(), course))
                                        VipsPreview.page(request.url.toString(), course, dark, words.german) else ""
                                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                                }
                                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                    // Redirects reach here without shouldOverrideUrlLoading; they get the same rule.
                                    val next = CampusLinks.navigation(url, course, currentPage, false, preview)
                                    if (next != CampusRoute.PAGE) { view.stopLoading(); reconnect = next == CampusRoute.RECONNECT; pageError = !reconnect; progress = 100 }
                                    else { currentPage = url; pageError = false; reconnect = false }
                                }
                                override fun onPageFinished(view: WebView, url: String) {
                                    val manager = CookieManager.getInstance()
                                    manager.flush()
                                    if (!preview && CampusLinks.navigation(url, course, currentPage, false, false) == CampusRoute.PAGE) {
                                        val cookie = manager.getCookie("$DIGICAMPUS_API/users/me").orEmpty()
                                        lifecycleScope.launch { if (!container.updateVipsSession(accountScope, cookie)) reconnect = true }
                                    }
                                }
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel(); pageError = true; progress = 100 }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) { pageError = true; progress = 100 } }
                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (request.isForMainFrame) { reconnect = response.statusCode == 401; pageError = !reconnect; progress = 100 }
                                }
                                override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) { dontResend.sendToTarget() }
                            }
                            setDownloadListener { url, _, _, _, _ -> if (!openAttachment(url)) { unsupported = true; progress = 100 } }
                            if (browserState?.let { restoreState(it) } == null) {
                                if (preview) loadDataWithBaseURL(entry, VipsPreview.page(entry, course.orEmpty(), dark, words.german), "text/html", "UTF-8", null)
                                else loadUrl(entry)
                            }
                        }
                        browser = view
                        view
                    }, Modifier.fillMaxSize().padding(padding).imePadding())
                }
            } }
            BackHandler(onBack = ::back)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        browser?.let { view -> outState.putBundle("browser", Bundle().also { view.saveState(it) }) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        fileCallback?.onReceiveValue(null); fileCallback = null
        browser?.stopLoading(); browser?.destroy(); browser = null
        super.onDestroy()
    }
}

/** A local, read-only demonstration of the same navigation and native attachment handoff. */
internal object VipsPreview {
    fun page(url: String, course: String, dark: Boolean, german: Boolean): String {
        val exercise = Uri.parse(url).path.orEmpty().endsWith("show_exercise")
        val attachment = "$DIGICAMPUS_ORIGIN/sendfile.php?type=0&amp;file_id=preview-vips-pdf&amp;file_name=Graphs%20-%20Exercise%20sheet%203.pdf&amp;cid=$course"
        val overview = "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=3&amp;cid=$course"
        val next = "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_exercise?assignment_id=3&amp;exercise_id=1&amp;cid=$course"
        return """<!doctype html><html lang="${if (german) "de" else "en"}"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
            body{font-family:system-ui,sans-serif;margin:0;padding:24px;color:${if (dark) "#e8e5da" else "#303e37"};background:${if (dark) "#171d19" else "#f8f7f2"};line-height:1.6}h1{font-size:25px;line-height:1.3;font-weight:500}h2{font-size:19px;font-weight:500}a{color:${if (dark) "#b5c9b4" else "#466c53"};display:block;padding:16px 0}p{max-width:50em}small{opacity:.65}hr{border:0;border-top:1px solid #92998b55;margin:24px 0}
            </style></head><body><small>${if (german) "VORSCHAU · BEISPIEL" else "PREVIEW · SAMPLE"}</small>
            <h1>${if (exercise) if (german) "1. Breitensuche" else "1. Breadth-first search" else "Graphs · Exercise sheet 3"}</h1>
            ${if (exercise) "<p>" + (if (german) "Betrachte einen ungerichteten Graphen mit den Kanten A–B, A–C, B–D und C–D. Gib die Reihenfolge der Breitensuche ab A an." else "Consider an undirected graph with edges A–B, A–C, B–D and C–D. List the breadth-first traversal starting at A.") + "</p><p>A → B → C → D</p><small>" + (if (german) "Nur Lesevorschau. Echte Aufgaben verwenden die offiziellen Vips-Eingabefelder." else "Read-only preview. Real exercises use the official Vips answer controls.") + "</small>" else "<p>" + (if (german) "Übe Graphensuche und kürzeste Wege. Aufgaben und Anhänge bleiben beim Lesen in ZenCampus." else "Practice graph traversal and shortest paths. Exercises and attachments stay inside ZenCampus as you read.") + "</p><a href='$next'>" + (if (german) "1. Breitensuche" else "1. Breadth-first search") + " →</a>"}
            <hr><h2>${if (german) "Dateien zur Aufgabe" else "Exercise attachments"}</h2>
            <a href="$attachment" target="_blank">Graphs - Exercise sheet 3.pdf</a>
            ${if (exercise) "<hr><a href='$overview'>← " + (if (german) "Aufgabenblatt" else "Sheet overview") + "</a>" else ""}
            </body></html>""".trimIndent()
    }
}
