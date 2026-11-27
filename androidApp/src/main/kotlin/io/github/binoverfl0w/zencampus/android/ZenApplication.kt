package io.github.binoverfl0w.zencampus.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import java.security.MessageDigest
import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.services.AugsburgMensaProvider
import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.data.services.DemoMealTranslator
import io.github.binoverfl0w.zencampus.data.services.DemoMensaProvider
import io.github.binoverfl0w.zencampus.data.services.MyMemoryTranslator
import io.github.binoverfl0w.zencampus.data.services.TouchPointCatalogueProvider
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.db.CampusDatabase
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.AppMaintenance
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.homeWidgets
import io.github.binoverfl0w.zencampus.ui.service
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class ZenApplication : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        val log = startAndroidDiagnostics(this)
        container = AppContainer(this, log)
    }
}

/** The notification that a DigiCampus session ended while the app was closed. */
private const val SESSION_NOTIFICATION = 1049

class AppContainer(private val context: Context, private val log: FileLogSink? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secure = SecureSessionStore(context)
    private val prefs = context.getSharedPreferences("zen_preferences", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _settings = MutableStateFlow(loadSettings())
    val settings = _settings.asStateFlow()
    private val _network = MutableStateFlow(NetworkConditions(connected = false))
    val network = _network.asStateFlow()
    private val client by lazy { HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        engine { config { singleAttemptMutations() } }
    } }
    private val store = SqlCampusStore(CampusDatabase(AndroidSqliteDriver(CampusDatabase.Schema, context, "campus.db")))
    private val materials = AndroidMaterials(context)
    val campusServices = CampusServicesRepository(store, AugsburgMensaProvider({ client }), scope,
        conditions = ::currentNetwork, settings = { _settings.value }, catalogueProvider = TouchPointCatalogueProvider({ client }),
        translator = MyMemoryTranslator({ client }))
    val previewServices = CampusServicesRepository(store, DemoMensaProvider(), scope,
        conditions = { NetworkConditions() }, settings = { _settings.value }, preview = true,
        translator = DemoMealTranslator())
    /** Sign-in, sign-out and the library connection, shared with desktop; this container adds Android's side effects. */
    private val session = CampusSession(scope, { client }, store, materials, AndroidSessionPlatform(), ::currentNetwork, { _settings.value },
        campusServices, previewServices)
    val updates = AppUpdates({ client }, BuildConfig.UPDATE_REPO, BuildConfig.VERSION_NAME,
        AndroidUpdatePlatform(context, ::openUrl), scope, automatic = { _settings.value.checkUpdates })
    private val crashes = CrashReports({ client }, CrashDsn.parse(BuildConfig.CRASH_DSN), FileCrashStore(AndroidUpkeepFiles(context).crashes),
        BuildConfig.VERSION_NAME, enabled = { _settings.value.crashReports })
    /** Updates, crash reports and the shareable log, for Settings and the prompts over every board. */
    val maintenance = AppMaintenance(scope, updates, crashes, log?.let { sink -> { shareAndroidLog(context, sink) } })
    val repository: StateFlow<CampusRepository?> get() = session.repository
    val authError: StateFlow<FailureKind?> get() = session.authError
    val authHttpStatus: StateFlow<Int?> get() = session.authHttpStatus
    val authBusy: StateFlow<Boolean> get() = session.authBusy
    val libraryAuthBusy: StateFlow<Boolean> get() = session.libraryAuthBusy
    val libraryAuthError: StateFlow<FailureKind?> get() = session.libraryAuthError
    val signInSession get() = session

    private inner class AndroidSessionPlatform : SessionPlatform {
        override val savedSignInStore = SavedSignInStore(secure::readSecret, secure::writeSecret, secure::deleteSecret)
        override suspend fun saveAccount(saved: SavedSession) = secure.write(saved)
        override suspend fun clearAccount() = secure.clear()
        override suspend fun saveLibrary(session: LibrarySession) = secure.writeLibrary(session)
        override suspend fun clearLibrary() = secure.clearLibrary()
        override suspend fun afterAccountChange(old: Account?, new: Account?) {
            if (old != null && old.cacheScope != new?.cacheScope) {
                // Notices of the previous account must not survive it.
                val notifications = context.getSystemService(android.app.NotificationManager::class.java)
                notifications.cancel(1043); notifications.cancel(1044)
                context.getSharedPreferences("coursework_reminders", Context.MODE_PRIVATE).edit(commit = true) { clear() }
                context.getSharedPreferences("study_updates", Context.MODE_PRIVATE).edit(commit = true) { clear() }
            }
            updateWorker(); updateCourseworkWorker(); updateUpdatesWorker()
        }
        override suspend fun afterSignOut(old: Account?) = withContext(Dispatchers.Main) {
            clearOriginCookies(DIGICAMPUS_ORIGIN, listOf("/", "/jsonapi.php", "/dispatch.php"))
            WebStorage.getInstance().deleteOrigin(DIGICAMPUS_ORIGIN)
        }
        override suspend fun afterLibraryDisconnect() {
            withContext(Dispatchers.Main) { clearOriginCookies(LIBRARY_ORIGIN, listOf("/", "/TouchPoint", "/TouchPoint/")) }
            WorkManager.getInstance(context).cancelUniqueWork("library-reminders")
            context.getSystemService(android.app.NotificationManager::class.java).cancel(1042)
        }
    }
    private var assignmentSelectionJob: Job? = null
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    init {
        updateNetwork()
        // Sticky: registering delivers the current level at once.
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                _network.update { it.copy(batteryLow = scale > 0 && level * 100 / scale < 15) }
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { updateNetwork() }
            override fun onLost(network: Network) { updateNetwork() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { updateNetwork() }
            // Doze and background restrictions block the app without the network changing; only this reports the unblock.
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) { updateNetwork() }
        })
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { updateNetwork(); foreground() }
            override fun onStop(owner: LifecycleOwner) { repository.value?.setForeground(false) }
        })
        scope.launch {
            val restored = secure.read()
            val saved = restored?.takeUnless { it.account.demo }
            if (restored?.account?.demo == true) secure.clear()
            val library = secure.readLibrary()
            val preserved = mutableSetOf(campusServices.publicScope, previewServices.publicScope)
            saved?.let { preserved.add(it.account.cacheScope) }
            library?.let { preserved.add(CampusServicesRepository.libraryScope(it.connectionId)) }
            store.purgeExcept(preserved)
            if (saved != null) {
                val files = store.read(saved.account.cacheScope, "downloads")?.let {
                    runCatching { json.decodeFromString<List<LocalDocument>>(it.payload) }.getOrNull()
                }.orEmpty()
                materials.cleanup(saved.account.cacheScope, files.map { it.path }.toSet())
            } else {
                materials.clearAll()
            }
            session.restore(saved, library)
            _ready.value = true
            crashes.start()
            updates.start()
            updateWorker(); updateCourseworkWorker(); updateUpdatesWorker()
        }
        scope.launch {
            combine(campusServices.preferences, campusServices.library) { preferences, library ->
                preferences.libraryReminders && library.connected
            }.distinctUntilChanged().collect { updateLibraryWorker() }
        }
        scope.launch {
            campusServices.preferences.map { it.favoriteAlerts && it.favoriteMeals.isNotEmpty() || it.watchedBooks.isNotEmpty() }
                .distinctUntilChanged().collect { updateServicesWorker(it) }
        }
        scope.launch {
            _ready.first { it }
            // The home-screen widgets mirror cached data; they are republished whenever that data or the language changes.
            @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
            repository.flatMapLatest { repo ->
                val services = servicesFor(repo)
                combine(repo?.snapshot ?: flowOf(null), services.mensa, services.library, services.preferences, _settings) { snapshot, mensa, library, preferences, _ ->
                    homeWidgets(snapshot, mensa.menu, library, preferences, nowMillis(), words())
                }
            }.distinctUntilChanged().debounce(750).collect { if (CampusWidgets.installed(context)) CampusWidgets.publish(context, it) }
        }
        scope.launch {
            _ready.first { it }
            // Any background read can find the session ended (hourly sync, update and reminder workers).
            repository.collectLatest { repo ->
                repo?.sync?.map { it.sessionExpired }?.distinctUntilChanged()?.collect { if (it) notifySessionEnded(repo) }
            }
        }
    }

    fun words(): Words = _settings.value.language.let { language ->
        Words(language == AppLanguage.GERMAN || language == AppLanguage.SYSTEM && java.util.Locale.getDefault().language.startsWith("de"))
    }

    private fun servicesFor(repo: CampusRepository?) = if (repo?.account?.demo == true) previewServices else campusServices

    /** Rebuilds the widgets from cache; [fetchMenu] lets a widget update load today's Mensa if it is missing or stale. */
    suspend fun refreshHomeWidget(fetchMenu: Boolean = false) {
        _ready.first { it }
        val repo = repository.value
        val services = servicesFor(repo)
        if (fetchMenu) try { services.refreshMensa(background = true) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        CampusWidgets.publish(context, homeWidgets(repo?.snapshot?.value, services.mensa.value.menu, services.library.value,
            services.preferences.value, nowMillis(), words()))
    }

    private fun updateServicesWorker(enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<CampusServicesWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build()
            manager.enqueueUniquePeriodicWork("zen-campus-services", ExistingPeriodicWorkPolicy.KEEP, request)
        } else manager.cancelUniqueWork("zen-campus-services")
    }

    private fun loadSettings(): AppSettings = runCatching {
        json.decodeFromString<AppSettings>(prefs.getString("settings", null) ?: "{}")
    }.getOrDefault(AppSettings())

    fun setSettings(settings: AppSettings) {
        _settings.value = settings
        scope.launch { prefs.edit().putString("settings", json.encodeToString(_settings.value)).commit(); updateWorker(); updateCourseworkWorker(); updateUpdatesWorker() }
    }

    fun preview() {
        check(BuildConfig.DEBUG) { "Test fixtures are unavailable in release builds" }
        scope.launch { session.usePreview() }
    }

    suspend fun signIn(cookie: String, showErrors: Boolean): Boolean = withContext(Dispatchers.IO) {
        session.signIn(cookie, showErrors).also { if (it) notifications.cancel(SESSION_NOTIFICATION) }
    }

    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    /**
     * An ended DigiCampus session found while the app is closed: the in-app banner cannot say it, so a notification
     * does, once per session. Nothing refreshes until the student signs in again.
     */
    @SuppressLint("MissingPermission")
    suspend fun notifySessionEnded(repo: CampusRepository) {
        if (repo.account.demo || !repo.sync.value.sessionExpired || visible()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val cookie = session.accountSession(repo.account.cacheScope)?.cookie ?: return
        val marker = MessageDigest.getInstance("SHA-256").digest("${repo.account.cacheScope}:$cookie".toByteArray()).joinToString("") { "%02x".format(it) }
        val notices = context.getSharedPreferences("session_notices", Context.MODE_PRIVATE)
        if (notices.getString("ended", null) == marker) return
        session.whileSignedIn(repo) {
            notices.edit(commit = true) { putString("ended", marker) }
            val words = words()
            notifications.createNotificationChannel(NotificationChannel("account", words.service("accounts"), NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(context, MainActivity::class.java).putExtra("openService", "accounts")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, SESSION_NOTIFICATION, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notifications.notify(SESSION_NOTIFICATION, NotificationCompat.Builder(context, "account").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(words.service("account_expired")).setContentText(words.failure(FailureKind.AUTHENTICATION))
                .setStyle(NotificationCompat.BigTextStyle().bigText(words.failure(FailureKind.AUTHENTICATION)))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build())
        }
    }



    fun signOut(removeSavedSignIns: Boolean = true) { scope.launch {
        try { session.signOut(removeSavedSignIns); notifications.cancel(SESSION_NOTIFICATION) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, words().service("saved_storage_error"), android.widget.Toast.LENGTH_LONG).show()
        } }
    } }

    /** App in front or network back: the single Study sync decides whether anything needs reading. */
    fun foreground() { scope.launch { _ready.first { it }; repository.value?.setForeground(true) } }


    suspend fun connectLibrary(cookie: String, showErrors: Boolean): Boolean = withContext(Dispatchers.IO) { session.connectLibrary(cookie, showErrors) }

    fun connectPreviewLibrary() { scope.launch { session.connectPreviewLibrary() } }

    fun disconnectLibrary(preview: Boolean = false, removeSavedSignIns: Boolean = true) { scope.launch {
        try { session.disconnectLibrary(preview, removeSavedSignIns) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, words().service("saved_storage_error"), android.widget.Toast.LENGTH_LONG).show()
        } }
    } }

    fun libraryReminders(enabled: Boolean) { scope.launch {
        campusServices.setPreferences { it.copy(libraryReminders = enabled) }
        if (!enabled) context.getSystemService(android.app.NotificationManager::class.java).cancel(1042)
        updateLibraryWorker()
    } }

    suspend fun withLibraryNotification(id: String?, action: () -> Unit) =
        session.whileLibraryConnected(id) { if (campusServices.preferences.value.libraryReminders) action() }

    private fun updateLibraryWorker() {
        val manager = WorkManager.getInstance(context)
        if (campusServices.library.value.connected && campusServices.preferences.value.libraryReminders) {
            val request = PeriodicWorkRequestBuilder<LibraryReminderWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()).build()
            manager.enqueueUniquePeriodicWork("library-reminders", ExistingPeriodicWorkPolicy.KEEP, request)
        } else manager.cancelUniqueWork("library-reminders")
    }

    private fun clearOriginCookies(origin: String, paths: List<String>) {
        val manager = CookieManager.getInstance()
        val host = android.net.Uri.parse(origin).host.orEmpty()
        val names = paths.flatMap { path -> manager.getCookie(origin + path).orEmpty().split(';') }
            .map { it.trim().substringBefore('=') }.filter { it.matches(Regex("[A-Za-z0-9_-]+")) }.distinct()
        for (name in names) for (path in paths) {
            manager.setCookie(origin + path, "$name=; Max-Age=0; Path=$path; Secure; HttpOnly")
            manager.setCookie(origin + path, "$name=; Max-Age=0; Domain=$host; Path=$path; Secure; HttpOnly")
        }
        manager.flush()
    }
    fun openUrl(url: String) {
        val uri = android.net.Uri.parse(url)
        if (uri.scheme != "https") return
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Opens a DigiCampus page in the signed-in in-app browser: a Vips sheet for [courseId], or any page "Open in
     * DigiCampus" names (no course). False when the address is not one it shows; the caller then uses the system browser.
     */
    fun openCampusPage(courseId: String?, url: String, title: String): Boolean {
        val repo = repository.value ?: return false
        if (repo.account.demo && courseId == null) return false
        val entry = CampusLinks.entry(url, courseId) ?: return false
        context.startActivity(Intent(context, CampusPageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("scope", repo.account.cacheScope).putExtra("course", courseId)
            .putExtra("url", entry).putExtra("title", title))
        return true
    }

    /** Seed only the authenticated DigiCampus origin, while the same account still owns it. */
    suspend fun prepareVipsSession(expectedScope: String): Boolean = withContext(Dispatchers.IO) {
        run {
            val saved = session.accountSession(expectedScope) ?: return@run false
            if (saved.account.demo) return@run true
            withContext(Dispatchers.Main) {
                val manager = CookieManager.getInstance()
                manager.setAcceptCookie(true)
                for (part in saved.cookie.split(';').map(String::trim).filter { it.contains('=') }) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        manager.setCookie(DIGICAMPUS_ORIGIN, "$part; Path=/; Secure; HttpOnly") {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
                manager.flush()
            }
            true
        }
    }

    suspend fun updateVipsSession(expectedScope: String, cookie: String): Boolean = withContext(Dispatchers.IO) { session.updateAccountCookie(expectedScope, cookie) }

    /**
     * What refreshes check before reading. A callback can be missed (the process was frozen, or the app was blocked
     * while the network came back), and a stale "offline" would stop every refresh and retry before it reaches
     * DigiCampus, so an offline state is always asked again before it is believed.
     */
    private fun currentNetwork(): NetworkConditions {
        val cached = _network.value
        // Retries wait for the network in loops; asking at most every two seconds keeps them cheap.
        if (cached.connected || android.os.SystemClock.elapsedRealtime() - lastNetworkCheck < 2_000) return cached
        return updateNetwork()
    }
    @Volatile private var lastNetworkCheck = 0L

    private fun updateNetwork(): NetworkConditions {
        lastNetworkCheck = android.os.SystemClock.elapsedRealtime()
        // Null while there is no default network or the app is blocked from it.
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        // Not "validated": Android's own check fails on some working networks (VPNs, campus Wi-Fi) for long stretches.
        // Trying there is safe: a sign-in portal cannot answer for DigiCampus over HTTPS, so it fails as offline.
        val connected = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        val metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        var previous = _network.value
        val next = _network.updateAndGet { previous = it; it.copy(connected = connected, metered = metered) }
        // Reconnecting syncs only while the app is open; a background worker runs its own sync.
        if (!previous.connected && connected && visible()) foreground()
        return next
    }

    private fun visible() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun prepareAssignmentUpload(uri: android.net.Uri, cacheScope: String, courseId: String, folderId: String) {
        assignmentSelectionJob?.cancel()
        assignmentSelectionJob = scope.launch {
            ready.first { it }
            val repo = repository.value?.takeIf { it.account.cacheScope == cacheScope } ?: return@launch
            try {
                val draft = readAssignmentFile(context, uri, courseId, folderId)
                currentCoroutineContext().ensureActive()
                if (repository.value === repo) repo.selectUpload(draft)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (repository.value === repo) repo.uploadSelectionFailed((e as? CampusException)?.kind ?: FailureKind.STORAGE) }
        }
    }

    suspend fun withCourseworkNotification(repo: CampusRepository, action: () -> Unit) =
        session.whileSignedIn(repo) { if (_settings.value.courseworkReminders && !repo.account.demo) action() }

    suspend fun withUpdatesNotification(repo: CampusRepository, action: () -> Unit) =
        session.whileSignedIn(repo) { if (_settings.value.updateNotifications && !repo.account.demo) action() }

    private fun updateUpdatesWorker() {
        val work = WorkManager.getInstance(context)
        val account = repository.value?.account
        if (!_settings.value.updateNotifications || account == null || account.demo) {
            work.cancelUniqueWork("zen-study-updates")
            context.getSystemService(android.app.NotificationManager::class.java).cancel(1044)
            context.getSharedPreferences("study_updates", Context.MODE_PRIVATE).edit { clear() }
            return
        }
        val request = PeriodicWorkRequestBuilder<StudyUpdatesWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()).build()
        work.enqueueUniquePeriodicWork("zen-study-updates", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun updateCourseworkWorker() {
        val work = WorkManager.getInstance(context)
        val account = repository.value?.account
        if (!_settings.value.courseworkReminders || account == null || account.demo) {
            work.cancelUniqueWork("zen-coursework-reminders")
            context.getSystemService(android.app.NotificationManager::class.java).cancel(1043)
            context.getSharedPreferences("coursework_reminders", Context.MODE_PRIVATE).edit { clear() }
            return
        }
        val request = PeriodicWorkRequestBuilder<CourseworkReminderWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()).build()
        work.enqueueUniquePeriodicWork("zen-coursework-reminders", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun updateWorker() {
        val work = WorkManager.getInstance(context)
        val account = repository.value?.account
        if (!_settings.value.backgroundRefresh || account == null || account.demo) {
            work.cancelUniqueWork("zen-refresh")
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true).build()
        val request = PeriodicWorkRequestBuilder<CampusRefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("zen-account-refresh").build()
        work.enqueueUniquePeriodicWork("zen-refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class CampusRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ZenApplication
        app.container.ready.first { it }
        val repo = app.container.repository.value ?: return Result.success()
        if (repo.sync.value.sessionExpired) return Result.success()
        repo.syncStudy()
        if (repo.sync.value.sessionExpired) { app.container.notifySessionEnded(repo); return Result.success() }
        // Courses with "keep up to date" sync new and changed files; the worker already requires Wi-Fi and battery.
        try { repo.autoSyncMaterials() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        return if (repo.sync.value.errors.values.any { it == FailureKind.OFFLINE || it == FailureKind.SERVER }) Result.retry()
            else Result.success()
    }
}
