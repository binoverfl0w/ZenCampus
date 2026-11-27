package io.github.binoverfl0w.zencampus.data.services

import io.github.binoverfl0w.zencampus.data.CampusStore
import io.github.binoverfl0w.zencampus.data.NetworkConditions
import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class CatalogueRepository(private val store: CampusStore, private val cacheScope: String,
    private val provider: CatalogueProvider, private val scope: CoroutineScope,
    private val conditions: () -> NetworkConditions = { NetworkConditions() },
    private val clock: () -> Long = ::nowMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val commits = Mutex()
    private val saves = Mutex()
    private val reads = Mutex()
    private val readScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val searches = mutableMapOf<String, Deferred<CatalogueResults>>()
    private val records = mutableMapOf<String, Deferred<CatalogueRecord>>()
    private var searchGeneration = 0L
    private var recordGeneration = 0L
    private val _search = MutableStateFlow(CatalogueSearchState())
    val search = _search.asStateFlow()
    private val _record = MutableStateFlow(CatalogueRecordState())
    val record = _record.asStateFlow()
    private val _saved = MutableStateFlow<List<CatalogueBook>>(emptyList())
    val saved = _saved.asStateFlow()
    private val _recent = MutableStateFlow<List<CatalogueQuery>>(emptyList())
    val recent = _recent.asStateFlow()

    init { scope.launch { store.observe(cacheScope).collect { rows ->
        _saved.value = rows.firstOrNull { it.key == "catalogue:saved" }?.let {
            runCatching { json.decodeFromString<List<CatalogueBook>>(it.payload) }.getOrNull()
        }.orEmpty()
        _recent.value = rows.firstOrNull { it.key == "catalogue:recent" }?.let {
            runCatching { json.decodeFromString<List<CatalogueQuery>>(it.payload) }.getOrNull()
        }.orEmpty()
    } } }

    suspend fun search(query: CatalogueQuery, page: Int = 1, force: Boolean = false) {
        val q = query.copy(text = query.text.trim().take(180))
        if (q.text.isBlank()) return
        val generation = commits.withLock { ++searchGeneration }
        val key = "catalogue:search:${q.field}:${q.text.lowercase()}:$page"
        val cached = store.read(cacheScope, key)
        val data = cached?.let { runCatching { json.decodeFromString<CatalogueResults>(it.payload) }.getOrNull() }?.copy(query = q)
        commits.withLock { if (generation == searchGeneration) _search.value = CatalogueSearchState(q, data) }
        if (page == 1) saveRecent(q)
        if (!force && data != null && isFresh(cached.updatedAt, clock(), TTL)) return
        if (!conditions().connected) {
            commits.withLock { if (generation == searchGeneration) _search.update { it.copy(error = FailureKind.OFFLINE) } }; return
        }
        commits.withLock { if (generation == searchGeneration) _search.update { it.copy(loading = true) } }
        try {
            val pending = reads.withLock { searches.getOrPut(key) { readScope.async { provider.search(q, page) } } }
            val result = try { pending.await() } finally { reads.withLock { if (pending.isCompleted && searches[key] === pending) searches.remove(key) } }
            commits.withLock {
                currentCoroutineContext().ensureActive()
                if (generation == searchGeneration) {
                    store.put(cacheScope, key, json.encodeToString(result), clock())
                    prune("catalogue:search:", 20)
                    _search.value = CatalogueSearchState(q, result)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { commits.withLock { if (generation == searchGeneration) _search.update {
            it.copy(error = (e as? CampusException)?.kind ?: FailureKind.OFFLINE)
        } } }
        finally { commits.withLock { if (generation == searchGeneration) _search.update { it.copy(loading = false) } } }
    }

    suspend fun openRecord(id: String, force: Boolean = false) {
        val generation = commits.withLock { ++recordGeneration }
        val cached = store.read(cacheScope, "catalogue:book:$id")
        val data = cached?.let { runCatching { json.decodeFromString<CatalogueRecord>(it.payload) }.getOrNull() }
        val book = data?.book ?: _saved.value.firstOrNull { it.id == id } ?: _search.value.results?.books?.firstOrNull { it.id == id }
            ?: store.observe(cacheScope).first().filter { it.key.startsWith("catalogue:search:") }.firstNotNullOfOrNull { row ->
                runCatching { json.decodeFromString<CatalogueResults>(row.payload) }.getOrNull()?.books?.firstOrNull { it.id == id }
            }
        commits.withLock { if (generation == recordGeneration) _record.value = CatalogueRecordState(id, data) }
        if (book == null) { commits.withLock { if (generation == recordGeneration) _record.update { it.copy(error = FailureKind.NOT_FOUND) } }; return }
        if (!force && data != null && !data.holdingsUnavailable && isFresh(cached.updatedAt, clock(), TTL)) return
        if (!conditions().connected) {
            commits.withLock { if (generation == recordGeneration) _record.update { it.copy(error = FailureKind.OFFLINE) } }; return
        }
        commits.withLock { if (generation == recordGeneration) _record.update { it.copy(loading = true) } }
        try {
            val pending = reads.withLock { records.getOrPut(id) { readScope.async { provider.record(book) } } }
            var value = try { pending.await() } finally { reads.withLock { if (pending.isCompleted && records[id] === pending) records.remove(id) } }
            if (value.holdingsUnavailable && data != null) value = value.copy(copies = data.copies,
                holdingsNote = data.holdingsNote, holdingsFetchedAt = data.holdingsFetchedAt ?: data.fetchedAt)
            commits.withLock {
                currentCoroutineContext().ensureActive()
                if (generation == recordGeneration) {
                    store.put(cacheScope, "catalogue:book:$id", json.encodeToString(value), clock())
                    prune("catalogue:book:", 40)
                    _record.value = CatalogueRecordState(id, value)
                }
            }
            saves.withLock {
                val previous = savedBooks()
                if (previous.any { it.id == id }) {
                    val next = previous.map { if (it.id == id) value.book else it }
                    store.put(cacheScope, "catalogue:saved", json.encodeToString(next), clock())
                    _saved.value = next
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { commits.withLock { if (generation == recordGeneration) _record.update {
            it.copy(error = (e as? CampusException)?.kind ?: FailureKind.OFFLINE)
        } } }
        finally { commits.withLock { if (generation == recordGeneration) _record.update { it.copy(loading = false) } } }
    }

    suspend fun toggleSaved(book: CatalogueBook) = saves.withLock {
        val previous = savedBooks()
        val next = if (previous.any { it.id == book.id }) previous.filterNot { it.id == book.id } else listOf(book) + previous
        store.put(cacheScope, "catalogue:saved", json.encodeToString(next), clock()); _saved.value = next
    }
    private suspend fun savedBooks() = store.read(cacheScope, "catalogue:saved")?.let {
        runCatching { json.decodeFromString<List<CatalogueBook>>(it.payload) }.getOrNull()
    }.orEmpty()
    private suspend fun saveRecent(query: CatalogueQuery) = saves.withLock {
        val previous = store.read(cacheScope, "catalogue:recent")?.let {
            runCatching { json.decodeFromString<List<CatalogueQuery>>(it.payload) }.getOrNull()
        }.orEmpty()
        val next = (listOf(query) + previous.filterNot { it == query }).take(8)
        store.put(cacheScope, "catalogue:recent", json.encodeToString(next), clock()); _recent.value = next
    }
    private suspend fun prune(prefix: String, limit: Int) {
        val rows = store.observe(cacheScope).first()
        val pinned = if (prefix == "catalogue:book:") _saved.value.map { "$prefix${it.id}" }.toSet() else emptySet()
        rows.filter { it.key.startsWith(prefix) && it.key !in pinned }.sortedByDescending { it.updatedAt }.drop(limit)
            .forEach { store.remove(cacheScope, it.key) }
    }
    companion object { const val TTL = 5 * 60_000L }
}
