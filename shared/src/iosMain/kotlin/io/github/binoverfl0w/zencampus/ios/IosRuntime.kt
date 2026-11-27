package io.github.binoverfl0w.zencampus

import androidx.compose.runtime.*
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.services.*
import io.github.binoverfl0w.zencampus.db.CampusDatabase
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.AppMaintenance
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.externalCampusRoute
import io.github.binoverfl0w.zencampus.ui.ui
import io.github.binoverfl0w.zencampus.ui.widgetCampusRoute
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.Foundation.NSBundle
import platform.Foundation.NSUUID

/** One runtime serves both a scene and an iOS background launch. */
class IosRuntime(private val platform: IosPlatform, initiallyBackground: Boolean, internal val previewEnabled: Boolean) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val driver = NativeSqliteDriver(CampusDatabase.Schema, "zencampus-ios.db")
    private val store = SqlCampusStore(CampusDatabase(driver))
    private val client = HttpClient(Darwin) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 15_000 }
    }
    private val settingsStore = IosSettings()
    internal var settings by mutableStateOf(settingsStore.read())
        private set
    internal val services = CampusServicesRepository(store, AugsburgMensaProvider({ client }), scope,
        settings = { settings }, catalogueProvider = TouchPointCatalogueProvider({ client }), translator = MyMemoryTranslator({ client }))
    internal var signInForeground by mutableStateOf(false)
        private set
    internal var signInConnected by mutableStateOf(false)
        private set
    internal val accounts = IosAccounts(platform, store, client, scope, services, { settings },
        cancelBackground = { cancelBackground() }, initializeRestoredAccount = !initiallyBackground)
    private val releaseVersion = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String).orEmpty()
    private val releaseRepo = (NSBundle.mainBundle.objectForInfoDictionaryKey("ZenUpdateRepository") as? String).orEmpty()
    private val updatePlatform = IosUpdatePlatform()
    internal val updates = AppUpdates({ client }, releaseRepo, releaseVersion, updatePlatform, scope, automatic = { settings.checkUpdates })
    private val crashStore = IosCrashStore()
    internal val crashes = CrashReports({ client }, iosCrashDsn(), crashStore, releaseVersion, enabled = { settings.crashReports })
    internal val maintenance = AppMaintenance(scope, updates = updates, crashes = crashes, shareLog = {
        if (!platform.shareDiagnosticLog()) accounts.message = "Could not share the diagnostic log."
    }, iosPreviewUnavailable = true)
    private val logSink = LogSink { line -> platform.writeDiagnosticLine(line.format()) }
    private var updateJob: Job? = null
    private fun startUpdateChecks() {
        if (!previewEnabled && updateJob?.isActive != true) updateJob = updates.start()
    }
    internal fun language() = settingsStore.language()
    internal val widgetRefresh = MutableStateFlow(nowMillis())
    internal fun widgetsChanged() = platform.widgetsChanged()
    internal fun words() = Words(settings.language == AppLanguage.GERMAN ||
        settings.language == AppLanguage.SYSTEM && language().startsWith("de"))

    internal var incomingFile by mutableStateOf<IosIncomingFile?>(null)
        private set
    fun maximumUploadBytes(): Int = MAX_UPLOAD_BYTES
    fun incomingFileFailed() {
        accounts.message = words().text("error_storage")
    }
    fun receiveFile(path: String, name: String, mime: String) {
        dismissIncomingFile()
        val file = IosIncomingFile(path, name, mime)
        incomingFile = file
        scope.launch {
            accounts.awaitReady()
            if (incomingFile === file && accounts.repository == null) {
                dismissIncomingFile(file)
                accounts.message = words().ui("share_signed_out")
            }
        }
    }
    internal fun dismissIncomingFile(file: IosIncomingFile? = incomingFile) {
        if (file == null || incomingFile !== file) return
        incomingFile = null
        platform.removeIncomingFile(file.path)
    }

    internal var serviceRoute by mutableStateOf("")
        private set
    fun openNotification(kind: String) {
        val route = when (kind) {
            "coursework" -> "coursework"
            "updates" -> "updates"
            "library", "holds" -> "library"
            "favorites" -> "mensa"
            "books" -> "catalogue"
            else -> return
        }
        openRoute(route)
    }
    fun openRoute(route: String): Boolean {
        val destination = externalCampusRoute(route) ?: return false
        serviceRoute = "$destination#${NSUUID().UUIDString}"
        return true
    }
    fun openWidgetUrl(url: String): Boolean {
        val scheme = NSBundle.mainBundle.bundleIdentifier ?: return false
        return widgetCampusRoute(url, scheme)?.let(::openRoute) ?: false
    }
    private val background = IosBackgroundCoordinator(scope)
    private val notifications = IosNotifications(platform, store, services, accounts, { settings }, ::language)

    init {
        scope.launch { publishIosWidgets() }
        ZenLog.install(logSink)
        ZenLog.info("app", "iOS preview started")
        if (!initiallyBackground) startUpdateChecks()
        if (!previewEnabled) scope.launch { crashes.start() }
        scope.launch {
            services.awaitPreferences()
            services.preferences.map { prefs -> prefs.libraryReminders || (prefs.favoriteAlerts && prefs.favoriteMeals.isNotEmpty()) || prefs.watchedBooks.isNotEmpty() }
                .distinctUntilChanged().collect { scheduleIfEnabled() }
        }
    }
    /**
     * A crash diagnostic MetricKit delivered (`MXCrashDiagnostic.jsonRepresentation()`), from the payload covering
     * [begin] to [end] (seconds since 1970). It joins the waiting reports, which are sent only with consent.
     */
    fun recordNativeCrash(json: String, begin: Double, end: Double) {
        if (previewEnabled || !crashes.configured) return
        scope.launch(Dispatchers.Default) {
            val report = metricKitCrashReport(json, newCrashId(), (end * 1000).toLong(), IosCrashCapture.appImage) ?: return@launch
            // A Kotlin crash ends in abort(): MetricKit then reports the same crash again, without the Kotlin exception.
            val window = (begin * 1000).toLong() - DUPLICATE_WINDOW..(end * 1000).toLong() + DUPLICATE_WINDOW
            if (report.exceptions.firstOrNull()?.type == "SIGABRT" && crashStore.kotlinCrashTimes().any { it in window }) return@launch
            if (crashStore.save(report)) crashes.start()
        }
    }
    fun scheduleIfEnabled(): Boolean {
        val prefs = services.preferences.value
        return platform.scheduleBackground(settings.backgroundRefresh || settings.courseworkReminders || settings.updateNotifications ||
            prefs.libraryReminders || (prefs.favoriteAlerts && prefs.favoriteMeals.isNotEmpty()) || prefs.watchedBooks.isNotEmpty())
    }
    internal fun updateSettings(next: AppSettings) {
        val previous = settings
        if (previous != next) cancelBackground()
        settings = next; settingsStore.write(next)
        val accountScope = accounts.repository?.account?.cacheScope
        if (!next.courseworkReminders && previous.courseworkReminders) {
            platform.clearNotification("coursework")
            if (accountScope != null) scope.launch { store.remove(accountScope, "ios-notice-coursework") }
        }
        if (!next.updateNotifications && previous.updateNotifications) {
            platform.clearNotification("updates")
            if (accountScope != null) scope.launch { store.remove(accountScope, "ios-notice-updates") }
        }
        val scheduled = scheduleIfEnabled()
        val enabling = (!previous.backgroundRefresh && next.backgroundRefresh) ||
            (!previous.courseworkReminders && next.courseworkReminders) || (!previous.updateNotifications && next.updateNotifications)
        if (enabling && !scheduled) accounts.message = "iOS could not schedule background checks. Check Background App Refresh in iOS Settings."
    }
    internal fun scheduleForAlert() {
        if (!platform.scheduleBackground(true))
            accounts.message = "iOS could not schedule background checks. Check Background App Refresh in iOS Settings."
    }
    fun cancelBackground() { background.cancel() }
    fun signInVisibility(active: Boolean) { signInForeground = active }
    fun networkChanged(connected: Boolean) { signInConnected = connected }
    /** The simulator fixture uses an ephemeral vault and a loopback origin inside WKWebView. */
    fun savedSignInFixture(kind: SignInKind, complete: (SavedSignInPopup?) -> Unit) {
        if (!previewEnabled) { complete(null); return }
        val values = mutableMapOf<String, String>()
        val vault = SavedSignInStore(values::get, { key, value -> values[key] = value }, { values.remove(it) })
        val origin = "http://127.0.0.1:9081"
        scope.launch {
            vault.save(kind, kind.defaultSite, if (kind == SignInKind.STUDY) "student@example.test" else "12345", "Grüße \" \\","Fixture")
            vault.save(kind, kind.defaultSite, "other@example.test", "Other fixture", null)
            complete(SavedSignInPopup(vault, scope, kind, false, "Fixture password was rejected", fixtureOrigin = { url ->
                origin.takeIf { url == "$origin/sign-in" }
            }))
        }
    }
    fun foreground() {
        widgetRefresh.value = nowMillis()
        startUpdateChecks()
        val running = background.cancel()
        scope.launch { running?.join(); accounts.awaitReady(); accounts.repository?.requestStudySync() }
    }

    /** Called by BGTaskScheduler; completion is invoked once, including on cancellation. */
    fun runBackground(completion: (Boolean) -> Unit) {
        widgetRefresh.value = nowMillis()
        background.start(work = {
            accounts.awaitReady()
            services.awaitPreferences()
            if (settings.dataSaver || !awaitBackgroundNetwork(platform::canRunBackground)) true
            else {
                val alertsAllowed = notifications.enabled()
                val repo = accounts.repository
                var okay = true
                if (repo != null && !repo.account.demo && !repo.sync.value.sessionExpired) accounts.withCurrentAccount(repo) {
                    if (settings.backgroundRefresh) {
                        repo.syncStudy()
                        repo.autoSyncMaterials()
                        okay = !repo.sync.value.errors.values.any { it == FailureKind.OFFLINE || it == FailureKind.SERVER }
                    }
                    if (alertsAllowed) notifications.account(repo)
                }
                if (alertsAllowed) {
                    notifications.library()
                    notifications.campusServices()
                }
                okay
            }
        }, completion = completion)
    }

    private companion object {
        /** How far around a MetricKit payload a recorded Kotlin crash counts as the same crash. */
        const val DUPLICATE_WINDOW = 60_000L
    }
}
