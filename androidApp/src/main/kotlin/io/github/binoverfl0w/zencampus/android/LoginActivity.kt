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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.binoverfl0w.zencampus.data.SavedSignInPopup
import io.github.binoverfl0w.zencampus.data.SessionHandoff
import io.github.binoverfl0w.zencampus.data.SignInKind
import io.github.binoverfl0w.zencampus.data.isTrustedSignInUrl
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.*
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.services.SavedSignInBar
import java.util.Locale
import kotlinx.coroutines.flow.first

/** Official SSO in WebView, with opt-in filling on Microsoft's top-level sign-in page and no credential bridge. */
class LoginActivity : ComponentActivity() {
    private var browser: WebView? = null
    private lateinit var handoff: SessionHandoff
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val container = (application as ZenApplication).container
        handoff = SessionHandoff(
            scope = lifecycleScope,
            readCookie = { CookieManager.getInstance().getCookie("$DIGICAMPUS_API/users/me").orEmpty() },
            verify = { cookie, userRequested -> container.signIn(cookie, showErrors = userRequested) },
            onAuthenticated = {
                CookieManager.getInstance().flush()
                finish()
            },
        )
        setContent {
            val settings by container.settings.collectAsState()
            val busy by container.authBusy.collectAsState()
            val error by container.authError.collectAsState()
            val httpStatus by container.authHttpStatus.collectAsState()
            val words = Words(settings.language == AppLanguage.GERMAN ||
                (settings.language == AppLanguage.SYSTEM && Locale.getDefault().language.startsWith("de")))
            ConfigureSystemBars(window, settings.theme)
            var browserError by remember { mutableStateOf(false) }
            var pageProgress by remember { mutableIntStateOf(0) }
            var currentHost by remember { mutableStateOf("digicampus.uni-augsburg.de") }

            var savedPopup by remember { mutableStateOf<SavedSignInPopup?>(null) }
            var completedPage by remember { mutableStateOf<String?>(null) }
            fun fillSavedSignIn() {
                val page = completedPage ?: return
                val view = browser ?: return
                if (view.url == page) savedPopup?.scriptForPage(page)?.let { view.evaluateJavascript(it, null) }
            }
            LaunchedEffect(Unit) {
                container.ready.first { it }
                savedPopup = container.signInSession.openSavedSignIn(lifecycleScope, SignInKind.STUDY,
                    intent.getBooleanExtra("expired", false), words.service("saved_rejected"), allowAutomaticFill = savedInstanceState == null)
            }
            LaunchedEffect(savedPopup) { fillSavedSignIn() }
            ZenTheme(settings.theme) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        Column(Modifier.statusBarsPadding()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                IconAction(Symbol.BACK, words.text("back"), { finish() })
                                Text(words.text("campus"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                ZenButton(if (words.german) "Weiter" else "Continue", { handoff.request(userRequested = true) },
                                    Modifier.padding(end = 8.dp), enabled = !busy)
                            }
                            Text(currentHost, Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            savedPopup?.let { popup ->
                                CompositionLocalProvider(LocalWords provides words) {
                                    SavedSignInBar(popup, completedPage, ::fillSavedSignIn, Modifier.padding(horizontal = 22.dp))
                                }
                            }
                            if (busy || pageProgress < 100) {
                                if (settings.reduceMotion) Text(words.text("refreshing"), Modifier.padding(horizontal = 22.dp))
                                else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                else LinearProgressIndicator(progress = { pageProgress / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                            if (error != null || browserError) Text(
                                if (browserError) words.failure(FailureKind.OFFLINE)
                                else if (error == FailureKind.AUTHENTICATION) {
                                    if (words.german) "Melde dich zuerst auf der Universitätsseite an und tippe dann auf Weiter."
                                    else "Finish signing in on the university page, then tap Continue."
                                } else {
                                    val message = if (error == FailureKind.SERVER) {
                                        if (words.german) "Digicampus konnte dein Konto nicht verbinden. Tippe auf Weiter, um es erneut zu versuchen."
                                        else "DigiCampus could not connect your account. Tap Continue to try again."
                                    } else words.failure(error!!)
                                    message + (httpStatus?.let { " (HTTP $it)" } ?: "")
                                },
                                Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }) { padding ->
                    AndroidView(factory = { context ->
                        @SuppressLint("SetJavaScriptEnabled")
                        val webView = WebView(context).apply {
                            // Compose gives views wrap_content; WebView then lays pages out with a zero-height viewport,
                            // so CSS 100vh is 0 and DigiCampus's menus open a few pixels tall.
                            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                            this.settings.javaScriptEnabled = true
                            this.settings.domStorageEnabled = true
                            this.settings.allowFileAccess = false
                            this.settings.allowContentAccess = false
                            this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            this.settings.setGeolocationEnabled(false)
                            this.settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) { pageProgress = newProgress }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val uri = request.url
                                    if (isTrustedSignInUrl(uri.toString())) return false
                                    if (request.isForMainFrame) container.openUrl(uri.toString())
                                    return true
                                }
                                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                    completedPage = null
                                    browserError = false
                                    currentHost = Uri.parse(url).host.orEmpty()
                                }
                                override fun onPageFinished(view: WebView, url: String) {
                                    if (!browserError && url == view.url) {
                                        completedPage = url
                                        fillSavedSignIn()
                                    }
                                    val uri = Uri.parse(url)
                                    if (uri.host == "digicampus.uni-augsburg.de" && !uri.path.orEmpty().contains("/login")) {
                                        handoff.request()
                                    }
                                }
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    handler.cancel()
                                    browserError = true
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) browserError = true
                                }
                            }
                        }
                        browser = webView
                        webView.loadUrl("$DIGICAMPUS_ORIGIN/index.php")
                        webView
                    }, modifier = Modifier.fillMaxSize().padding(padding))
                }
            }
            BackHandler {
                if (browser?.canGoBack() == true) browser?.goBack() else finish()
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::handoff.isInitialized) handoff.request()
    }
    override fun onDestroy() {
        browser?.stopLoading()
        browser?.destroy()
        browser = null
        super.onDestroy()
    }
}
