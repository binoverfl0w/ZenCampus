package io.github.binoverfl0w.zencampus.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import io.github.binoverfl0w.zencampus.ui.service
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.RequestBody
import okio.BufferedSink

internal class DesktopContainer(
    val paths: DesktopPaths,
    private val vault: SessionVault = when {
        System.getProperty("os.name").startsWith("Mac") -> MacKeychainSessionVault(paths.profile)
        isWindows -> WindowsSessionVault(File(paths.data, "sessions"), paths.profile, windowsSignInDirectory())
        else -> KeyringSessionVault(paths.profile)
    },
    private val allowTestData: Boolean = false,
    val release: DesktopRelease = DesktopRelease.load(),
    private val log: FileLogSink? = null,
    arguments: List<String> = emptyList(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preferences = DesktopPreferencesStore(File(paths.data, "preferences.json"))
    private val driver = openDatabase(File(paths.data, "campus.db"))
    private val store = SqlCampusStore(CampusDatabase(driver))
    val materials = DesktopMaterials(paths)
    private var selectionJob: Job? = null
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _settings = MutableStateFlow(preferences.read().settings)
    val settings = _settings.asStateFlow()
    private val _network = MutableStateFlow(NetworkConditions(metered = true))
    val network = _network.asStateFlow()
    val notice = MutableStateFlow<String?>(null)
    var closePrivateReaders: () -> Unit = {}
    private val client = HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 20_000 }
        engine { config {
            followRedirects(false); followSslRedirects(false); retryOnConnectionFailure(false)
            addInterceptor(Interceptor { chain ->
                val request = chain.request(); val body = request.body
                if (request.method in setOf("GET", "HEAD") || body == null) chain.proceed(request)
                else chain.proceed(request.newBuilder().method(request.method, object : RequestBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun isOneShot() = true
                    override fun isDuplex() = body.isDuplex()
                    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                }).build())
            })
        } }
    }
    val services = CampusServicesRepository(store, AugsburgMensaProvider({ client }), scope,
        conditions = { _network.value }, settings = { _settings.value }, catalogueProvider = TouchPointCatalogueProvider({ client }),
        translator = MyMemoryTranslator({ client }))
    val previewServices = CampusServicesRepository(store, DemoMensaProvider(), scope,
        conditions = { NetworkConditions() }, settings = { _settings.value }, preview = true,
        translator = DemoMealTranslator())
    /** Sign-in, sign-out and the library connection, shared with Android; this container adds the desktop's side effects. */
    private val session = CampusSession(scope, { client }, store, materials, DesktopSessionPlatform(), { _network.value }, { _settings.value },
        services, previewServices)
    val updatePlatform = DesktopUpdatePlatform(paths, scope, arguments)
    val updates = AppUpdates({ client }, release.updateRepo, release.version, updatePlatform, scope,
        automatic = { _settings.value.checkUpdates })
    private val crashes = CrashReports({ client }, CrashDsn.parse(release.crashDsn), FileCrashStore(File(paths.data, "crashes")),
        release.version, enabled = { _settings.value.crashReports })
    /** Updates, crash reports and the diagnostic log, for Settings and the prompts over every board. */
    val maintenance = io.github.binoverfl0w.zencampus.ui.AppMaintenance(scope, updates, crashes, log?.let { sink -> {
        scope.launch {
            runCatching { exportDesktopLog(paths, release, sink) }
                .onSuccess { file -> notice.value = "Diagnostic log saved to ${file.absolutePath}"; runCatching { externalOpen(file.parentFile) } }
                .onFailure { notice.value = "The diagnostic log could not be saved." }
        }
    } })
    val repository: StateFlow<CampusRepository?> get() = session.repository
    val authError get() = session.authError
    val authBusy get() = session.authBusy
    val libraryAuthError get() = session.libraryAuthError
    val libraryAuthBusy get() = session.libraryAuthBusy
    val signInSession get() = session

    private inner class DesktopSessionPlatform : SessionPlatform {
        override val savedSignInStore = SavedSignInStore(vault::read, vault::write, vault::clear)
        override suspend fun saveAccount(saved: SavedSession) {
            if (saved.account.demo) {
                // The sample account is never kept in the keyring; a marker restores it in verification profiles.
                runCatching { vault.clear("digicampus") }
                atomicPrivateWrite(File(paths.data, "digicampus-signed-out"), byteArrayOf())
                atomicPrivateWrite(File(paths.data, "preview"), byteArrayOf())
            } else {
                File(paths.data, "preview").delete()
                rememberSession("digicampus", saved)
            }
        }
        override suspend fun clearAccount() {
            runCatching { vault.clear("digicampus") }.onFailure { notice.value = "The keyring could not be cleared. Unlock it and sign out again." }
            File(paths.data, "preview").delete()
            // A local revocation marker also prevents a locked keyring from resurrecting a signed-out account.
            atomicPrivateWrite(File(paths.data, "digicampus-signed-out"), byteArrayOf())
        }
        override suspend fun saveLibrary(session: LibrarySession) = rememberSession("library", session)
        override suspend fun clearLibrary() {
            runCatching { vault.clear("library") }
            atomicPrivateWrite(File(paths.data, "library-signed-out"), byteArrayOf())
        }
        override suspend fun beforeAccountChange() = withContext(Dispatchers.Main) { closePrivateReaders() }
        override suspend fun afterAccountChange(old: Account?, new: Account?) {
            if (new != null) store.purgeExcept(setOfNotNull(new.cacheScope, services.publicScope, previewServices.publicScope,
                session.library?.let { CampusServicesRepository.libraryScope(it.connectionId) }))
        }
        override suspend fun afterSignOut(old: Account?) { File(paths.data, "ui-${digest(old?.cacheScope.orEmpty())}.json").delete() }
    }

    init {
        materials.onNotice = { notice.value = it }
        scope.launch {
            try {
                val restored = readSession<SavedSession>("digicampus")
                val saved = restored?.takeUnless { it.account.demo && !allowTestData }
                if (restored?.account?.demo == true && !allowTestData) {
                    runCatching { vault.clear("digicampus") }
                    atomicPrivateWrite(File(paths.data, "digicampus-signed-out"), byteArrayOf())
                }
                val library = readSession<LibrarySession>("library")
                val preview = if (allowTestData && File(paths.data, "preview").exists()) SavedSession(DemoProvider().account(), "") else null
                session.restore(saved ?: preview, library)
                if (!allowTestData) File(paths.data, "preview").delete()
                val repo = repository.value
                if (repo != null) {
                    val files = store.read(repo.account.cacheScope, "downloads")?.let {
                        runCatching { desktopJson.decodeFromString<List<LocalDocument>>(it.payload) }.getOrNull()
                    }.orEmpty()
                    materials.cleanup(repo.account.cacheScope, files.map { it.path }.toSet())
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice.value = "Saved accounts could not be restored. You can sign in again." }
            finally { _ready.value = true }
            if (!allowTestData) { crashes.start(); updates.start() }
        }
        scope.launch {
            ready.first { it }
            while (isActive) {
                val next = readNetworkConditions(); val before = _network.value; _network.value = next
                if (!before.connected && next.connected) foreground()
                delay(30_000)
            }
        }
        scope.launch {
            ready.first { it }
            DesktopReminders(this@DesktopContainer).run()
        }
    }

    private inline fun <reified T> readSession(service: String): T? = runCatching {
        if (File(paths.data, "$service-signed-out").exists()) return null
        vault.read(service)?.let { desktopJson.decodeFromString<T>(it) }
    }.getOrNull()

    private inline fun <reified T> rememberSession(service: String, value: T) {
        try {
            vault.write(service, desktopJson.encodeToString(value))
            File(paths.data, "$service-signed-out").delete()
        } catch (_: Exception) {
            atomicPrivateWrite(File(paths.data, "$service-signed-out"), byteArrayOf())
            notice.value = "Signed in for this session. Unlock your keyring to remember this account next time."
        }
    }

    fun setSettings(next: AppSettings) {
        _settings.value = next
        scope.launch { runCatching { preferences.update { it.copy(settings = next) } }
            .onFailure { notice.value = "Settings could not be saved." } }
    }

    fun preview() {
        check(allowTestData) { "Test data is only available in isolated verification profiles" }
        scope.launch { ready.first { it }; session.usePreview() }
    }

    suspend fun signIn(cookie: String, explicit: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (cookie.isNotBlank()) ready.first { it }
        session.signIn(cookie, explicit)
    }



    fun signOut(removeSavedSignIns: Boolean = true) { scope.launch {
        ready.first { it }
        try { session.signOut(removeSavedSignIns) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice.value = desktopWords(settings.value).service("saved_storage_error") }
    } }


    suspend fun connectLibrary(cookie: String, explicit: Boolean): Boolean = withContext(Dispatchers.IO) { session.connectLibrary(cookie, explicit) }

    fun connectPreviewLibrary() { scope.launch { session.connectPreviewLibrary() } }
    fun disconnectLibrary(preview: Boolean, removeSavedSignIns: Boolean = true) { scope.launch {
        try { session.disconnectLibrary(preview, removeSavedSignIns) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice.value = desktopWords(settings.value).service("saved_storage_error") }
    } }
    fun libraryReminders(enabled: Boolean) { scope.launch { services.setPreferences { it.copy(libraryReminders = enabled) } } }
    /** Window focused or network back: the single Study sync decides whether anything needs reading. */
    fun foreground() { scope.launch { ready.first { it }; repository.value?.setForeground(true) } }

    suspend fun vipsSession(expectedScope: String): SavedSession? = session.accountSession(expectedScope)

    suspend fun updateVipsSession(expectedScope: String, cookie: String): Boolean = session.updateAccountCookie(expectedScope, cookie)

    fun selectAssignment(file: File, expectedScope: String, course: String, folder: String) {
        selectionJob?.cancel()
        selectionJob = scope.launch {
            val repo = repository.value?.takeIf { it.account.cacheScope == expectedScope } ?: return@launch
            try {
                val draft = readDesktopAssignment(file, course, folder)
                currentCoroutineContext().ensureActive()
                if (repository.value === repo) repo.selectUpload(draft)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (repository.value === repo) repo.uploadSelectionFailed((e as? CampusException)?.kind ?: FailureKind.STORAGE) }
        }
    }

    suspend fun close() {
        repository.value?.close(purge = false)
        scope.coroutineContext.cancelChildren()
        scope.coroutineContext[Job]?.children?.toList()?.joinAll()
        client.close(); driver.close(); scope.cancel()
    }

    private fun openDatabase(file: File): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0) ?: 0)
        }, 0).value
        if (version == 0L) CampusDatabase.Schema.create(driver)
        else if (version < CampusDatabase.Schema.version) CampusDatabase.Schema.migrate(driver, version, CampusDatabase.Schema.version)
        check(version <= CampusDatabase.Schema.version) { "Database belongs to a newer ZenCampus version" }
        driver.execute(null, "PRAGMA user_version=${CampusDatabase.Schema.version}", 0)
        return driver
    }
}

internal fun readDesktopAssignment(file: File, course: String, folder: String): UploadDraft {
    if (!file.isFile || file.length() > MAX_UPLOAD_BYTES) throw CampusException(FailureKind.STORAGE)
    val bytes = file.inputStream().use { it.readNBytes((MAX_UPLOAD_BYTES + 1).toInt()) }
    if (bytes.isEmpty() || bytes.size > MAX_UPLOAD_BYTES) throw CampusException(FailureKind.STORAGE)
    val name = materialFileName(file.name).take(180)
    return UploadDraft(course, folder, name, java.nio.file.Files.probeContentType(file.toPath()) ?: "application/octet-stream", bytes)
}

internal fun readNetworkConditions(): NetworkConditions = runCatching {
    val connected = NetworkInterface.getNetworkInterfaces().toList().any { it.isUp && !it.isLoopback }
    if (isWindows) return@runCatching NetworkConditions(connected, metered = WindowsCost.metered(), batteryLow = windowsBatteryLow())
    val power = File("/sys/class/power_supply").listFiles().orEmpty()
    val batteryLow = power.any { File(it, "type").takeIf(File::isFile)?.readText()?.trim() == "Battery" &&
        File(it, "status").takeIf(File::isFile)?.readText()?.trim() == "Discharging" &&
        (File(it, "capacity").takeIf(File::isFile)?.readText()?.trim()?.toIntOrNull() ?: 100) < 15 }
    val metered = runCatching {
        val process = ProcessBuilder("nmcli", "-t", "-g", "GENERAL.METERED", "device", "show")
            .apply { environment()["LC_ALL"] = "C" }.redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); true }
        else {
            val values = process.inputStream.bufferedReader().use { it.readLines() }.map { it.trim().lowercase() }
            values.any { it.startsWith("yes") || it.startsWith("guess-yes") } ||
                values.none { it.startsWith("no") || it.startsWith("guess-no") }
        }
    }.getOrDefault(true)
    NetworkConditions(connected, metered = metered, batteryLow = batteryLow)
}.getOrDefault(NetworkConditions(metered = true))
