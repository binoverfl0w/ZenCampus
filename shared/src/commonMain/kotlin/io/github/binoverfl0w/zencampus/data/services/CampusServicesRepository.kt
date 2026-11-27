package io.github.binoverfl0w.zencampus.data.services

import io.github.binoverfl0w.zencampus.data.CampusStore
import io.github.binoverfl0w.zencampus.data.NetworkConditions
import io.github.binoverfl0w.zencampus.data.withLibraryConnection
import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Public menus and personal loans have separate jobs, state and cache scopes from DigiCampus. */
class CampusServicesRepository(
    private val store: CampusStore, private val mensaProvider: MensaProvider, parentScope: CoroutineScope,
    private val conditions: () -> NetworkConditions = { NetworkConditions() },
    private val settings: () -> AppSettings = { AppSettings() },
    val preview: Boolean = false, private val clock: () -> Long = ::nowMillis,
    private val catalogueProvider: CatalogueProvider = DemoCatalogueProvider(),
    private val translator: MealTranslator? = null,
) {
    val publicScope = if (preview) "campus:augsburg:preview" else "campus:augsburg:public"
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    val catalogue = CatalogueRepository(store, publicScope, catalogueProvider, scope, conditions, clock)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val menuMutex = Mutex()
    private val preferencesMutex = Mutex()
    private val libraryIo = Mutex()
    private val libraryCommit = Mutex()
    private val _mensa = MutableStateFlow(MensaState())
    val mensa = _mensa.asStateFlow()
    private val preferencesLoaded = CompletableDeferred<Unit>()
    suspend fun awaitPreferences() = preferencesLoaded.await()
    private val _preferences = MutableStateFlow(CampusServicePreferences())
    val preferences = _preferences.asStateFlow()
    private val _library = MutableStateFlow(LibraryState())
    val library = _library.asStateFlow()
    private data class Connection(val id: String, val provider: LibraryProvider, val job: Job)
    private var connection: Connection? = null
    private var menuAttempt: Long? = null
    private val translationMutex = Mutex()
    private var translationPause: Long? = null
    /** Text the service answered without a usable translation; not asked again until the app restarts. */
    private val untranslatable = mutableSetOf<String>()

    init {
        scope.launch {
            store.observe(publicScope).collect { rows ->
                val menu = rows.firstOrNull { it.key == "mensa" }?.let { runCatching { json.decodeFromString<MensaMenu>(it.payload) }.getOrNull() }
                _mensa.update { it.copy(menu = menu) }
                _preferences.value = rows.firstOrNull { it.key == "preferences" }?.let {
                    runCatching { json.decodeFromString<CampusServicePreferences>(it.payload) }.getOrNull()
                } ?: CampusServicePreferences()
                preferencesLoaded.complete(Unit)
            }
        }
    }

    suspend fun refreshMensa(force: Boolean = false, background: Boolean = false) = menuMutex.withLock {
        if (!conditions().connected || (background && (!conditions().canPrefetch(settings())))) return@withLock
        val cached = store.read(publicScope, "mensa")
        val location = _preferences.value.mensaLocation
        val valid = cached?.let { runCatching { json.decodeFromString<MensaMenu>(it.payload) }.getOrNull() }?.takeIf { it.location == location }
        if (!force && valid != null && isFresh(cached.updatedAt, clock(), MENU_TTL)) return@withLock
        if (!force && isFresh(menuAttempt, clock(), 60_000)) return@withLock
        menuAttempt = clock()
        _mensa.update { it.copy(loading = true, error = null) }
        try {
            val menu = mensaProvider.menu(location).copy(location = location).translatedWith(translations())
            currentCoroutineContext().ensureActive()
            store.put(publicScope, "mensa", json.encodeToString(menu), clock())
        } catch (e: CancellationException) { menuAttempt = null; throw e }
        catch (e: Exception) { _mensa.update { it.copy(error = (e as? CampusException)?.kind ?: FailureKind.OFFLINE) } }
        finally { _mensa.update { it.copy(loading = false) } }
    }

    /**
     * Translates dish names and descriptions on the loaded menu that are not in the cache yet. The UI calls it only when
     * the app is in English. Stops for a while after a failure, and until the next day once the free quota is used.
     */
    suspend fun translateMenu() = translationMutex.withLock {
        val translator = translator ?: return@withLock
        if (!_preferences.value.translateMeals || !conditions().connected) return@withLock
        if (translationPause?.let { clock() < it } == true) return@withLock
        val known = translations()
        val wanted = _mensa.value.menu?.days.orEmpty().flatMap { it.meals }.flatMap { listOf(it.name, it.description) }
            .map(::menuText).filter { it.isNotEmpty() && it !in known && it !in untranslatable && it.encodeToByteArray().size <= MealTranslator.MAX_BYTES }
            .distinct().take(TRANSLATIONS_PER_RUN)
        val found = mutableMapOf<String, String>()
        try {
            for (batch in wanted.chunked(4)) {
                val results = coroutineScope { batch.map { text -> async { text to runCatching { translator.english(text) } } }.awaitAll() }
                results.forEach { (text, result) -> result.getOrNull()?.let { found[text] = it } }
                results.firstNotNullOfOrNull { (_, result) -> result.exceptionOrNull() as? CancellationException }?.let { throw it }
                results.filter { (_, result) -> (result.exceptionOrNull() as? CampusException)?.kind in UNUSABLE }.forEach { untranslatable += it.first }
                val error = results.firstNotNullOfOrNull { (text, result) -> result.exceptionOrNull()?.takeIf { text !in untranslatable } } ?: continue
                val quota = (error as? CampusException)?.httpStatus == MealTranslator.QUOTA_STATUS
                translationPause = clock() + if (quota) QUOTA_PAUSE else FAILURE_PAUSE
                break
            }
        } finally {
            if (found.isNotEmpty()) withContext(NonCancellable) { saveTranslations(found) }
        }
    }

    private suspend fun translations(): Map<String, String> = store.read(publicScope, "mensa_translations")?.let {
        runCatching { json.decodeFromString<Map<String, String>>(it.payload) }.getOrNull()
    }.orEmpty()

    private suspend fun saveTranslations(found: Map<String, String>) = menuMutex.withLock {
        val all = translations() + found
        store.put(publicScope, "mensa_translations", json.encodeToString(all.entries.toList().takeLast(MAX_TRANSLATIONS).associate { it.key to it.value }), clock())
        val cached = store.read(publicScope, "mensa") ?: return@withLock
        val menu = runCatching { json.decodeFromString<MensaMenu>(cached.payload) }.getOrNull() ?: return@withLock
        // The menu keeps its own age: a translation does not make it fresh.
        store.put(publicScope, "mensa", json.encodeToString(menu.translatedWith(all)), cached.updatedAt)
    }

    suspend fun setPreferences(change: (CampusServicePreferences) -> CampusServicePreferences) = preferencesMutex.withLock {
        val previous = store.read(publicScope, "preferences")?.let {
            runCatching { json.decodeFromString<CampusServicePreferences>(it.payload) }.getOrNull()
        } ?: _preferences.value
        val next = change(previous)
        store.put(publicScope, "preferences", json.encodeToString(next), clock())
        _preferences.value = next
    }

    suspend fun setMensaLocation(key: String) {
        if (MENSA_LOCATIONS.none { it.key == key } || key == _preferences.value.mensaLocation) return
        setPreferences { it.copy(mensaLocation = key) }
        refreshMensa(force = true)
    }

    suspend fun toggleFavorite(meal: MensaMeal) = setPreferences { prefs ->
        val key = favoriteKey(meal.name)
        prefs.copy(favoriteMeals = if (key in prefs.favoriteMeals) prefs.favoriteMeals - key else prefs.favoriteMeals + key)
    }

    suspend fun toggleExcludedLabel(code: String) = setPreferences { prefs ->
        prefs.copy(excludedLabels = if (code in prefs.excludedLabels) prefs.excludedLabels - code else prefs.excludedLabels + code)
    }

    suspend fun setFavoriteAlerts(enabled: Boolean) = setPreferences { it.copy(favoriteAlerts = enabled) }

    suspend fun watchBook(book: CatalogueBook, watch: Boolean) = setPreferences { prefs ->
        prefs.copy(watchedBooks = if (watch) (prefs.watchedBooks.filterNot { it.id == book.id } + book).takeLast(20)
            else prefs.watchedBooks.filterNot { it.id == book.id })
    }

    /**
     * Checks watched books against the catalogue; books with an available copy are returned once and stop being watched.
     * Unreadable records stay watched.
     */
    suspend fun availableWatchedBooks(): List<CatalogueBook> {
        if (!conditions().connected) return emptyList()
        return _preferences.value.watchedBooks.filter { book ->
            try { catalogueProvider.record(book).copies.any { copyAvailable(it.status) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        }
    }
    suspend fun forgetWatchedBooks(books: List<CatalogueBook>) {
        if (books.isNotEmpty()) setPreferences { prefs -> prefs.copy(watchedBooks = prefs.watchedBooks.filterNot { book -> books.any { it.id == book.id } }) }
    }
    suspend fun checkWatchedBooks(): List<CatalogueBook> = availableWatchedBooks().also { forgetWatchedBooks(it) }

    suspend fun attachLibrary(id: String, provider: LibraryProvider, verified: LibraryAccount? = null) = libraryCommit.withLock {
        val old = connection
        old?.job?.cancel()
        if (old != null && old.id != id) store.purge(libraryScope(old.id))
        val job = SupervisorJob(scope.coroutineContext[Job])
        val current = Connection(id, provider, job)
        connection = current
        _library.value = LibraryState(connected = true, connectionId = id)
        if (verified != null) store.put(libraryScope(id), "account", json.encodeToString(verified), clock())
        CoroutineScope(scope.coroutineContext + job).launch {
            store.observe(libraryScope(id)).collect { rows ->
                val account = rows.firstOrNull { it.key == "account" }?.let {
                    runCatching { json.decodeFromString<LibraryAccount>(it.payload) }.getOrNull()
                }
                if (connection === current) _library.update { it.copy(account = account) }
            }
        }
    }

    suspend fun disconnectLibrary() = libraryCommit.withLock {
        val old = connection
        connection = null
        old?.job?.cancel()
        _library.value = LibraryState()
        if (old != null) store.purge(libraryScope(old.id))
        setPreferences { it.copy(libraryReminders = false) }
    }

    suspend fun refreshLibrary(force: Boolean = false) = libraryIo.withLock {
        val current = connection ?: return@withLock
        if (!conditions().connected) return@withLock
        val cached = store.read(libraryScope(current.id), "account")
        if (!force && isFresh(cached?.updatedAt, clock(), LOAN_TTL)) return@withLock
        withLibraryConnection(current.job) {
            if (connection !== current) return@withLibraryConnection
            _library.update { it.copy(loading = true, error = null) }
            try {
                commitAccount(current, current.provider.account())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (connection === current) _library.update {
                it.copy(error = (e as? CampusException)?.kind ?: FailureKind.OFFLINE)
            } }
            finally { if (connection === current) _library.update { it.copy(loading = false) } }
        }
    }

    suspend fun renewLoan(id: String) = libraryIo.withLock {
        val current = connection ?: return@withLock
        if (!conditions().connected) { _library.update { it.copy(error = FailureKind.OFFLINE) }; return@withLock }
        withLibraryConnection(current.job) {
            if (connection !== current) return@withLibraryConnection
            _library.update { it.copy(renewing = id, result = null, error = null) }
            try {
                val result = current.provider.renew(id)
                result.account?.let { commitAccount(current, it) }
                if (result.account == null) libraryCommit.withLock {
                    if (connection === current) {
                        val cached = store.read(libraryScope(current.id), "account")
                        cached?.let { store.put(libraryScope(current.id), "account", it.payload, 0) }
                        _library.update { it.copy(error = FailureKind.OFFLINE) }
                    }
                }
                if (connection === current) _library.update { it.copy(result = result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (connection === current) _library.update {
                it.copy(error = (e as? CampusException)?.kind ?: FailureKind.OFFLINE)
            } }
            finally { if (connection === current) _library.update { it.copy(renewing = null) } }
        }
    }

    fun dismissLibraryResult() { _library.update { it.copy(result = null) } }

    private suspend fun commitAccount(current: Connection, account: LibraryAccount) = libraryCommit.withLock {
        currentCoroutineContext().ensureActive()
        if (connection !== current) return@withLock
        store.put(libraryScope(current.id), "account", json.encodeToString(account), clock())
        _library.update { it.copy(account = account) }
    }

    companion object {
        const val MENU_TTL = 30 * 60_000L
        const val LOAN_TTL = 5 * 60_000L
        const val TRANSLATIONS_PER_RUN = 120
        const val MAX_TRANSLATIONS = 1500
        const val FAILURE_PAUSE = 10 * 60_000L
        const val QUOTA_PAUSE = 6 * 60 * 60_000L
        private val UNUSABLE = setOf(FailureKind.INVALID_RESPONSE, FailureKind.NOT_FOUND)
        fun libraryScope(id: String) = "opac:augsburg:$id"
    }
}
