package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.data.services.DemoLibraryProvider
import io.github.binoverfl0w.zencampus.data.services.TouchPointLibraryProvider
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** A verified DigiCampus sign-in: the account it belongs to and the session cookie. */
@Serializable data class SavedSession(val account: Account, val cookie: String, val generation: String = "")

/** A verified library (OPAC) sign-in; [connectionId] scopes its cache. */
@Serializable data class LibrarySession(val connectionId: String, val cookie: String)

/**
 * What a platform does around the shared session lifecycle: where credentials live and what else must change with
 * them (notifications, background work, web cookies, open windows). Every hook runs inside the session's lock.
 */
interface SessionPlatform {
    val savedSignInStore: SavedSignInStore? get() = null
    /** Remembers a verified account; a preview account may be stored differently or not at all. */
    suspend fun saveAccount(saved: SavedSession)
    suspend fun clearAccount()
    suspend fun saveLibrary(session: LibrarySession)
    suspend fun clearLibrary()
    /** Before the signed-in account changes or signs out: close anything showing the old account's content. */
    suspend fun beforeAccountChange() {}
    /** After the repository changed to [new] (null when signed out); [old] was replaced. */
    suspend fun afterAccountChange(old: Account?, new: Account?) {}
    /** After signing out: forget the website's own cookies and storage for DigiCampus. */
    suspend fun afterSignOut(old: Account?) {}
    /** Before library disconnection; default preserves the existing platform reader cleanup. */
    suspend fun beforeLibraryDisconnect() { beforeAccountChange() }
    suspend fun performAccountChange(persist: suspend () -> Unit, prepare: suspend () -> Unit, finish: suspend () -> Unit) {
        prepare(); persist(); finish()
    }
    suspend fun performLibraryDisconnect(persist: suspend () -> Unit, prepare: suspend () -> Unit, finish: suspend () -> Unit) {
        prepare(); persist(); finish()
    }
    /** After disconnecting the library: forget its cookies and reminders. */
    suspend fun afterLibraryDisconnect() {}
}

/**
 * The signed-in DigiCampus account and library connection, shared by Android and desktop. Platforms supply
 * credentials storage and side effects through [SessionPlatform]; everything about verifying, switching and
 * forgetting sessions happens here, so both apps behave the same.
 */
class CampusSession(
    private val scope: CoroutineScope,
    private val client: () -> HttpClient,
    private val store: CampusStore,
    private val materials: MaterialStorage,
    private val platform: SessionPlatform,
    private val network: () -> NetworkConditions,
    private val settings: () -> AppSettings,
    /** The real library and Mensa services, and the sample ones used by preview mode. */
    val services: CampusServicesRepository,
    val previewServices: CampusServicesRepository,
) {
    private val accountLock = Mutex()
    private val libraryLock = Mutex()
    private val _repository = MutableStateFlow<CampusRepository?>(null)
    val repository = _repository.asStateFlow()
    val authBusy = MutableStateFlow(false)
    val authError = MutableStateFlow<FailureKind?>(null)
    val authHttpStatus = MutableStateFlow<Int?>(null)
    val libraryAuthBusy = MutableStateFlow(false)
    val libraryAuthError = MutableStateFlow<FailureKind?>(null)
    private var saved: SavedSession? = null
    private var cookie: MutableStateFlow<String>? = null
    val savedSignIns get() = platform.savedSignInStore
    var library: LibrarySession? = null
        private set

    /** Startup: reattach the remembered library connection and account, without contacting either service. */
    suspend fun restore(account: SavedSession?, librarySession: LibrarySession?, initializeAccount: Boolean = true) {
        libraryLock.withLock {
            library = librarySession
            if (librarySession != null) services.attachLibrary(librarySession.connectionId, libraryProvider(librarySession))
        }
        accountLock.withLock {
            trySavedSignIn { savedSignIns?.load(account?.account?.takeUnless { it.demo }?.cacheScope) }
            if (account != null) activate(account, initializeAccount)
        }
    }

    /** Verifies [sessionCookie] against DigiCampus and switches to its account. [explicit] shows guest-page failures too. */
    suspend fun signIn(sessionCookie: String, explicit: Boolean): Boolean = withContext(Dispatchers.Default) {
        if (sessionCookie.isBlank()) {
            authHttpStatus.value = null
            if (explicit) authError.value = FailureKind.AUTHENTICATION
            return@withContext false
        }
        accountLock.withLock {
            authBusy.value = true; authError.value = null; authHttpStatus.value = null
            try {
                val account = DigiCampusProvider(client, { sessionCookie }).account()
                replace(SavedSession(account, sessionCookie, randomConnectionId()))
                ZenLog.info("session", "signed in to DigiCampus")
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = e as? CampusException
                val kind = failure?.kind ?: FailureKind.OFFLINE
                if (explicit || kind != FailureKind.AUTHENTICATION) ZenLog.warn("session", "DigiCampus sign-in failed", e)
                // A guest page's 401 is expected while the sign-in page loads; other failed checks are shown.
                if (explicit || kind != FailureKind.AUTHENTICATION) { authError.value = kind; authHttpStatus.value = failure?.httpStatus }
                false
            } finally { authBusy.value = false }
        }
    }

    /** Switches to the sample account (verification builds only; the caller checks). */
    suspend fun usePreview() = accountLock.withLock {
        authError.value = null; authHttpStatus.value = null
        replace(SavedSession(DemoProvider().account(), ""))
    }

    suspend fun signOut(removeSavedSignIns: Boolean = true) = accountLock.withLock {
        if (removeSavedSignIns) savedSignIns?.removeAll()
        var old: CampusRepository? = null
        platform.performAccountChange(persist = { platform.clearAccount() }, prepare = {
            platform.beforeAccountChange()
            old = _repository.value
            _repository.value = null; saved = null; cookie = null
        }, finish = { old?.close(purge = true) })
        if (old?.account?.demo == true) previewServices.disconnectLibrary()
        authError.value = null; authHttpStatus.value = null
        platform.afterSignOut(old?.account)
        platform.afterAccountChange(old?.account, null)
        ZenLog.info("session", "signed out")
    }

    /** The current account's session, when it is still [expectedScope] (for embedded official pages). */
    suspend fun accountSession(expectedScope: String): SavedSession? = accountLock.withLock {
        saved?.takeIf { it.account.cacheScope == expectedScope && _repository.value?.account?.cacheScope == expectedScope }
    }

    private fun realScope(expected: String? = null): String? = _repository.value?.account
        ?.takeUnless { it.demo }?.cacheScope?.takeIf { expected == null || it == expected }

    /** A manual visit during an expiry also consumes the automatic prompt for that session. */
    suspend fun claimAutomaticSignIn(expectedScope: String, foreground: () -> Boolean): Boolean = accountLock.withLock {
        realScope(expectedScope) ?: return@withLock false
        val generation = saved?.generation ?: return@withLock false
        val vault = savedSignIns ?: return@withLock false
        fun eligible() = foreground() && network().connected && _repository.value?.sync?.value?.sessionExpired == true
        if (!eligible()) return@withLock false
        trySavedSignIn { vault.claimExpiry(generation, ::eligible) } == true && eligible()
    }

    suspend fun openSavedSignIn(popupScope: CoroutineScope, kind: SignInKind, expired: Boolean, rejection: String, allowAutomaticFill: Boolean = true): SavedSignInPopup? {
        val vault = savedSignIns ?: return null
        if (kind == SignInKind.STUDY) accountLock.withLock {
            if (_repository.value?.sync?.value?.sessionExpired == true) saved?.generation?.let { generation ->
                trySavedSignIn { vault.claimExpiry(generation) { true } }
            }
        }
        trySavedSignIn { vault.load() }
        return SavedSignInPopup(vault, popupScope, kind, expired, rejection, allowAutomaticFill = allowAutomaticFill)
    }

    /**
     * An embedded official page rotated the session cookie: verify it belongs to the same account and use it from now
     * on. A connectivity failure is not proof of an expired login, so it keeps the session.
     */
    suspend fun updateAccountCookie(expectedScope: String, next: String): Boolean = accountLock.withLock {
        val current = saved?.takeIf { it.account.cacheScope == expectedScope } ?: return@withLock false
        if (_repository.value?.account?.cacheScope != expectedScope) return@withLock false
        if (current.account.demo) return@withLock true
        fun parts(value: String) = value.split(';').map(String::trim).filter { '=' in it }.sorted()
        if (parts(current.cookie) == parts(next)) return@withLock true
        if (next.isBlank()) return@withLock false
        try {
            val verified = DigiCampusProvider(client, { next }).account()
            if (verified.cacheScope != expectedScope) return@withLock false
            val updated = current.copy(cookie = next)
            platform.saveAccount(updated)
            saved = updated; cookie?.value = next
            true
        } catch (e: CancellationException) { throw e }
        catch (e: CampusException) { e.kind !in setOf(FailureKind.AUTHENTICATION, FailureKind.PERMISSION) }
        catch (_: Exception) { true }
    }

    /** Holds the account lock during background work so sign-out cannot race cache writes. */
    suspend fun <T> withSignedIn(repo: CampusRepository, action: suspend () -> T): T? = accountLock.withLock {
        if (_repository.value === repo) action() else null
    }

    /** Runs [action] only while [repo] is still the signed-in account (for notifications posted from workers). */
    suspend fun whileSignedIn(repo: CampusRepository, action: () -> Unit) = accountLock.withLock { if (_repository.value === repo) action() }

    private suspend fun replace(next: SavedSession) {
        var old: CampusRepository? = null
        platform.performAccountChange(persist = { platform.saveAccount(next) }, prepare = {
            platform.beforeAccountChange()
            old = _repository.value
            _repository.value = null
            old?.let { it.close(purge = it.account.cacheScope != next.account.cacheScope) }
        }, finish = { activate(next) })
        platform.afterAccountChange(old?.account, next.account)
    }

    private fun activate(next: SavedSession, initialize: Boolean = true) {
        saved = next
        val sessionCookie = MutableStateFlow(next.cookie).also { cookie = it }
        val demo = next.account.demo
        val provider = if (demo) DemoProvider() else DigiCampusProvider(client, { sessionCookie.value })
        val repo = CampusRepository(next.account, provider, store, materials, scope,
            // The sample account works offline, but keeps the real metering and battery state.
            conditions = { if (demo) network().copy(connected = true) else network() },
            settings = settings,
            weatherProvider = if (demo) DemoWeatherProvider() else OpenMeteoWeatherProvider(client))
        _repository.value = repo
        if (initialize) scope.launch { repo.initialize() }
    }

    // --- Library -------------------------------------------------------------------------------------------------

    suspend fun connectLibrary(libraryCookie: String, explicit: Boolean): Boolean = withContext(Dispatchers.Default) {
        if (libraryCookie.isBlank()) { if (explicit) libraryAuthError.value = FailureKind.AUTHENTICATION; return@withContext false }
        libraryAuthBusy.value = true; libraryAuthError.value = null
        try {
            val candidate = LibrarySession(randomConnectionId(), libraryCookie)
            val provider = libraryProvider(candidate)
            val account = provider.account()
            libraryLock.withLock {
                currentCoroutineContext().ensureActive()
                val verified = candidate.copy(cookie = provider.sessionCookie())
                platform.saveLibrary(verified)
                library = verified
                services.attachLibrary(candidate.connectionId, provider, account)
            }
            ZenLog.info("session", "library connected")
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val kind = (e as? CampusException)?.kind ?: FailureKind.OFFLINE
            if (explicit || kind != FailureKind.AUTHENTICATION) ZenLog.warn("session", "library connection failed", e)
            if (explicit || kind != FailureKind.AUTHENTICATION) libraryAuthError.value = kind
            false
        } finally { libraryAuthBusy.value = false }
    }

    suspend fun connectPreviewLibrary() {
        previewServices.attachLibrary("preview", DemoLibraryProvider())
        previewServices.refreshLibrary(true)
    }

    suspend fun disconnectLibrary(preview: Boolean, removeSavedSignIns: Boolean = true) {
        if (preview) { previewServices.disconnectLibrary(); return }
        libraryLock.withLock {
            if (removeSavedSignIns) savedSignIns?.removeAll(SignInKind.LIBRARY)
            platform.performLibraryDisconnect(persist = { platform.clearLibrary() }, prepare = {
                platform.beforeLibraryDisconnect()
                library = null
            }, finish = { services.disconnectLibrary() })
        }
        platform.afterLibraryDisconnect()
    }

    suspend fun <T> withConnectedLibrary(id: String, action: suspend () -> T): T? = libraryLock.withLock {
        if (library?.connectionId == id && services.library.value.connectionId == id) action() else null
    }

    /** Runs [action] only while library connection [id] is still the connected one. */
    suspend fun whileLibraryConnected(id: String?, action: () -> Unit) = libraryLock.withLock {
        if (id != null && library?.connectionId == id && services.library.value.connectionId == id) action()
    }

    private fun libraryProvider(session: LibrarySession) = TouchPointLibraryProvider(client, session.cookie, onCookieChanged = { rotated ->
        libraryLock.withLock {
            val current = library
            if (current?.connectionId == session.connectionId && rotated != current.cookie) {
                val next = current.copy(cookie = rotated)
                platform.saveLibrary(next)
                library = next
            }
        }
    })

    private fun randomConnectionId(): String = (1..4).joinToString("-") { kotlin.random.Random.nextLong().toULong().toString(16) }
}
