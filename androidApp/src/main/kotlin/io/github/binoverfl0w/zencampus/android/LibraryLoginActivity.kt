package io.github.binoverfl0w.zencampus.android

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.binoverfl0w.zencampus.data.SavedSignInPopup
import io.github.binoverfl0w.zencampus.data.SessionHandoff
import io.github.binoverfl0w.zencampus.data.SignInKind
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.*
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.services.SavedSignInBar
import java.util.Locale
import kotlinx.coroutines.flow.first

class LibraryLoginActivity : ComponentActivity() {
    private var browser: WebView? = null
    private lateinit var handoff: SessionHandoff
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val container = (application as ZenApplication).container
        handoff = SessionHandoff(lifecycleScope,
            readCookie = { CookieManager.getInstance().getCookie(LIBRARY_BASE).orEmpty() },
            verify = { cookie, explicit -> container.connectLibrary(cookie, explicit) },
            onAuthenticated = { CookieManager.getInstance().flush(); finish() })
        setContent {
            val settings by container.settings.collectAsState()
            val busy by container.libraryAuthBusy.collectAsState()
            val error by container.libraryAuthError.collectAsState()
            val words = Words(settings.language == AppLanguage.GERMAN || settings.language == AppLanguage.SYSTEM && Locale.getDefault().language.startsWith("de"))
            var pageError by remember { mutableStateOf(false) }
            var progress by remember { mutableIntStateOf(0) }
            ConfigureSystemBars(window, settings.theme)
            var savedPopup by remember { mutableStateOf<SavedSignInPopup?>(null) }
            var completedPage by remember { mutableStateOf<String?>(null) }
            fun fillSavedSignIn() {
                val page = completedPage ?: return
                val view = browser ?: return
                if (view.url == page) savedPopup?.scriptForPage(page)?.let { view.evaluateJavascript(it, null) }
            }
            LaunchedEffect(Unit) {
                container.ready.first { it }
                savedPopup = container.signInSession.openSavedSignIn(lifecycleScope, SignInKind.LIBRARY,
                    container.campusServices.library.value.error == FailureKind.AUTHENTICATION, words.service("saved_rejected"), allowAutomaticFill = savedInstanceState == null)
            }
            LaunchedEffect(savedPopup) { fillSavedSignIn() }
            ZenTheme(settings.theme) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                    Column(Modifier.statusBarsPadding()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconAction(Symbol.BACK, words.text("back"), { finish() })
                            Text(words.service("library"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            ZenButton(if (words.german) "Weiter" else "Continue", { handoff.request(userRequested = true) },
                                Modifier.padding(end = 8.dp), enabled = !busy)
                        }
                        Box(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) { SmallLabel("opac.bibliothek.uni-augsburg.de") }
                        savedPopup?.let { popup ->
                            CompositionLocalProvider(LocalWords provides words) {
                                SavedSignInBar(popup, completedPage, ::fillSavedSignIn, Modifier.padding(horizontal = 22.dp))
                            }
                        }
                        if (busy || progress < 100) {
                            if (settings.reduceMotion) Text(words.text("refreshing"))
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (pageError || error != null) Text(if (pageError) words.failure(FailureKind.OFFLINE)
                            else if (error == FailureKind.AUTHENTICATION) words.service("library_login_note") else words.service("library_error"),
                            Modifier.padding(horizontal = 18.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }) { padding ->
                    AndroidView(factory = { context ->
                        @SuppressLint("SetJavaScriptEnabled")
                        val view = WebView(context).apply {
                            // Compose gives views wrap_content; WebView then lays pages out with a zero-height viewport,
                            // so CSS 100vh is 0 and DigiCampus's menus open a few pixels tall.
                            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                            this.settings.javaScriptEnabled = true
                            this.settings.domStorageEnabled = true
                            this.settings.allowFileAccess = false
                            this.settings.allowContentAccess = false
                            this.settings.setGeolocationEnabled(false)
                            this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webChromeClient = object : WebChromeClient() { override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress } }
                            var accountOpened = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val uri = request.url
                                    if (uri.scheme == "https" && uri.host == "opac.bibliothek.uni-augsburg.de" && uri.port in setOf(-1, 443) && uri.userInfo == null) return false
                                    if (request.isForMainFrame) container.openUrl(uri.toString())
                                    return true
                                }
                                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                    completedPage = null
                                    pageError = false
                                }
                                override fun onPageFinished(view: WebView, url: String) {
                                    if (pageError || url != view.url) return
                                    if (!accountOpened && Uri.parse(url).path?.startsWith("/TouchPoint/start.do") == true) {
                                        accountOpened = true
                                        view.loadUrl("${LIBRARY_BASE}userAccount.do?methodToCall=start")
                                    } else {
                                        completedPage = url
                                        fillSavedSignIn()
                                        handoff.request()
                                    }
                                }
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel(); pageError = true }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) pageError = true }
                            }
                        }
                        browser = view
                        view.loadUrl("$LIBRARY_START&device=mobile")
                        view
                    }, Modifier.fillMaxSize().padding(padding))
                }
            }
            BackHandler { if (browser?.canGoBack() == true) browser?.goBack() else finish() }
        }
    }
    override fun onResume() { super.onResume(); if (::handoff.isInitialized) handoff.request() }
    override fun onDestroy() { browser?.stopLoading(); browser?.destroy(); browser = null; super.onDestroy() }
}
