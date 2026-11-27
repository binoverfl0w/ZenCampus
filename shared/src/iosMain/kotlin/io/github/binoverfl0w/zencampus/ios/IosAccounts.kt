package io.github.binoverfl0w.zencampus

import androidx.compose.runtime.*
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.digicampus.*
import io.github.binoverfl0w.zencampus.data.services.*
import io.github.binoverfl0w.zencampus.data.study.*
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.service
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID

/** Implemented by the iOS host: secrets remain in Keychain and authentication in WebKit. */
interface IosPlatform {
    fun widgetsChanged() {}
    fun removeIncomingFile(path: String) {}
    fun readSecret(service: String): String?
    fun writeSecret(service: String, value: String): Boolean
    fun deleteSecret(service: String): Boolean
    fun showLogin(kind: SignInKind, savedSignIn: SavedSignInPopup?, words: Words,
                  verifyCookie: (String, (Boolean) -> Unit) -> Unit, onCookie: (String) -> Unit)
    fun openMaterial(path: String): Boolean
    fun shareMaterial(path: String): Boolean
    fun writeDiagnosticLine(line: String)
    fun shareDiagnosticLog(): Boolean
    fun exportMaterial(path: String, onComplete: (String?) -> Unit)
    fun selectUpload(onFile: (String?, String?, String?) -> Unit)
    fun selectMaterialFolder(onFolder: (String?, String?) -> Unit)
    fun beginMaterialFolder(bookmark: String): String?
    fun endMaterialFolder(lease: String)
    fun commitMaterialFolder(lease: String, stagedPath: String, folders: List<String>, name: String): String?
    /** The signed-in DigiCampus browser. [classify] gets the address and the page it is on; see [CampusLinks.navigation]. */
    fun openVips(url: String, title: String, cookie: String, classify: (String, String) -> String,
                 onAttachment: (String, String) -> Unit, onCookie: (String) -> Unit)
    fun openVipsPreview(url: String, title: String, classify: (String) -> String, onAttachment: (String, String) -> Unit)
    fun scheduleBackground(enabled: Boolean): Boolean
    fun canRunBackground(): Boolean
    fun requestNotifications(onResult: (Boolean) -> Unit)
    fun notificationsEnabled(onResult: (Boolean) -> Unit)
    fun sendNotification(kind: String, title: String, body: String, onResult: (Boolean) -> Unit)
    fun clearNotification(kind: String)
    fun closePrivateReader()
}

@Serializable private data class IosLibrarySession(val id: String, val cookie: String)

internal class IosAccounts(
    private val platform: IosPlatform,
    private val store: SqlCampusStore,
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val services: CampusServicesRepository,
    private val settings: () -> AppSettings,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val cancelBackground: () -> Unit = {},
    private val initializeRestoredAccount: Boolean = true,
) {
    private val restored = CompletableDeferred<Unit>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val materials: MaterialStorage = IosMaterials(platform)
    val previewServices = CampusServicesRepository(store, DemoMensaProvider(), scope, preview = true, translator = DemoMealTranslator())
    private val session: CampusSession = CampusSession(scope, { client }, store, materials, object : SessionPlatform {
        override val savedSignInStore = SavedSignInStore(platform::readSecret,
            { key, value -> check(platform.writeSecret(key, value)) { "Keychain unavailable" } },
            { key -> check(platform.deleteSecret(key)) { "Keychain unavailable" } })
        override suspend fun performAccountChange(persist: suspend () -> Unit, prepare: suspend () -> Unit, finish: suspend () -> Unit) {
            persist(); prepare(); finish()
        }
        override suspend fun performLibraryDisconnect(persist: suspend () -> Unit, prepare: suspend () -> Unit, finish: suspend () -> Unit) {
            persist(); prepare(); finish()
        }
        override suspend fun saveAccount(saved: SavedSession) {
            if (saved.account.demo) return
            check(platform.writeSecret("digicampus", json.encodeToString(saved))) { "Keychain unavailable" }
        }
        override suspend fun clearAccount() { if (repository?.account?.demo != true) check(platform.deleteSecret("digicampus")) { "Keychain unavailable" } }
        override suspend fun saveLibrary(session: LibrarySession) {
            check(platform.writeSecret("library", json.encodeToString(IosLibrarySession(session.connectionId, session.cookie)))) { "Keychain unavailable" }
        }
        override suspend fun clearLibrary() { check(platform.deleteSecret("library")) { "Keychain unavailable" } }
        override suspend fun beforeAccountChange() { withContext(uiDispatcher) {
            platform.closePrivateReader()
            platform.clearNotification("coursework"); platform.clearNotification("updates")
        } }
        override suspend fun beforeLibraryDisconnect() { withContext(uiDispatcher) { platform.closePrivateReader() } }
        override suspend fun afterLibraryDisconnect() { withContext(uiDispatcher) {
            platform.clearNotification("library"); platform.clearNotification("holds")
        } }
    }, { NetworkConditions() }, settings, services, previewServices)
    val signInSession get() = session
    val repositoryFlow get() = session.repository
    val repository: CampusRepository? get() = session.repository.value
    var ready by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    init {
        scope.launch {
            try {
                val saved = platform.readSecret("digicampus")?.let { runCatching { json.decodeFromString<SavedSession>(it) }.getOrNull() }
                    ?.takeUnless { it.account.demo }
                val library = platform.readSecret("library")?.let { runCatching { json.decodeFromString<IosLibrarySession>(it) }.getOrNull() }
                    ?.let { LibrarySession(it.id, it.cookie) }
                session.restore(saved, library, initializeRestoredAccount)
            } finally { ready = true; restored.complete(Unit) }
        }
    }

    suspend fun awaitReady() = restored.await()
    suspend fun <T> withCurrentAccount(repo: CampusRepository, action: suspend () -> T): T? = session.withSignedIn(repo, action)

    suspend fun <T> withCurrentLibrary(id: String, action: suspend () -> T): T? = session.withConnectedLibrary(id, action)

    fun openVips(course: String, url: String, title: String) {
        if (!openCampusPage(course, url, title)) message = "This Vips link cannot be opened."
    }

    /**
     * Opens a DigiCampus page in the signed-in in-app browser: a Vips sheet for [course], or any page "Open in
     * DigiCampus" names (no course). False when the address is not one it shows; the caller then uses Safari.
     */
    fun openCampusPage(course: String?, url: String, title: String): Boolean {
        val current = repository ?: return false
        if (current.account.demo && course == null) return false
        val entry = CampusLinks.entry(url, course) ?: return false
        fun classify(target: String, page: String, preview: Boolean) = when (CampusLinks.navigation(target, course, page, true, preview)) {
            CampusRoute.PAGE -> "page"
            CampusRoute.ATTACHMENT -> "attachment"
            CampusRoute.EXTERNAL -> "external"
            CampusRoute.RECONNECT, CampusRoute.BLOCK -> "blocked"
        }
        val attachment = { target: String, page: String ->
            val owner = CampusLinks.attachmentCourse(target, course, page)
            if (repository === current && owner != null) current.requestLinkedMaterial(owner, target, page)
        }
        if (current.account.demo) {
            platform.openVipsPreview(entry, title, classify = { target -> classify(target, entry, true) }, onAttachment = attachment)
            return true
        }
        scope.launch {
            val saved = session.accountSession(current.account.cacheScope) ?: return@launch
            if (repository !== current) return@launch
            platform.openVips(entry, title, saved.cookie,
                classify = { target, page -> classify(target, page, false) },
                onAttachment = attachment,
                onCookie = { cookie -> scope.launch { session.updateAccountCookie(current.account.cacheScope, cookie) } })
        }
        return true
    }

    fun usePreview() { if (repository == null) scope.launch { if (repository == null) session.usePreview() } }
    fun connectPreviewLibrary() { scope.launch { session.connectPreviewLibrary() } }
    fun disconnectPreviewLibrary() { scope.launch { session.disconnectLibrary(preview = true) } }

    fun signIn(words: Words = Words(false), expired: Boolean = false) = openSignIn(SignInKind.STUDY, words, expired)

    fun connectLibrary(words: Words = Words(false)) =
        openSignIn(SignInKind.LIBRARY, words, services.library.value.error == FailureKind.AUTHENTICATION)

    private fun openSignIn(kind: SignInKind, words: Words, expired: Boolean) {
        scope.launch {
            awaitReady()
            val popup = session.openSavedSignIn(this@IosAccounts.scope, kind, expired, words.service("saved_rejected"))
            suspend fun verify(cookie: String, explicit: Boolean): Boolean {
                cancelBackground()
                return if (kind == SignInKind.STUDY) session.signIn(cookie, explicit) else session.connectLibrary(cookie, explicit)
            }
            platform.showLogin(kind, popup, words, { cookie, done ->
                scope.launch { done(verify(cookie, explicit = false)) }
            }) { cookie ->
                scope.launch {
                    if (!verify(cookie, explicit = true)) message = if (kind == SignInKind.STUDY)
                        "Could not connect DigiCampus. Finish signing in and try again. Check network and Keychain access."
                    else "Could not connect the library account. Finish signing in and try again. Check network and Keychain access."
                }
            }
        }
    }

    fun signOut(removeSavedSignIns: Boolean = true) { cancelBackground(); scope.launch {
        try { session.signOut(removeSavedSignIns) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Could not remove the saved DigiCampus session. Unlock the device and try again." }
    } }

    fun disconnectLibrary(removeSavedSignIns: Boolean = true) { cancelBackground(); scope.launch {
        try { session.disconnectLibrary(preview = false, removeSavedSignIns = removeSavedSignIns) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Could not remove the saved library session. Unlock the device and try again." }
    } }
}

internal class IosSettings {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun read(): AppSettings = defaults.stringForKey("appSettings")?.let {
        runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull()
    } ?: AppSettings()
    fun write(value: AppSettings) { defaults.setObject(json.encodeToString(value), "appSettings") }
    fun language(): String = (defaults.arrayForKey("AppleLanguages")?.firstOrNull() as? String) ?: "en"
}
