package io.github.binoverfl0w.zencampus.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.binoverfl0w.zencampus.db.CampusDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class CacheRecord(val key: String, val payload: String, val updatedAt: Long)

internal expect val storageDispatcher: CoroutineDispatcher

interface CampusStore {
    fun observe(scope: String): Flow<List<CacheRecord>>
    suspend fun read(scope: String, key: String): CacheRecord?
    suspend fun put(scope: String, key: String, payload: String, updatedAt: Long)
    suspend fun remove(scope: String, key: String)
    suspend fun purge(scope: String)
}

class SqlCampusStore(
    private val database: CampusDatabase,
    private val dispatcher: CoroutineDispatcher = storageDispatcher,
) : CampusStore {
    override fun observe(scope: String): Flow<List<CacheRecord>> =
        database.cacheQueries.selectAccount(scope).asFlow().mapToList(dispatcher)
            .map { entries -> entries.map { CacheRecord(it.resource, it.payload, it.updated_at) } }
    override suspend fun read(scope: String, key: String): CacheRecord? = withContext(dispatcher) {
        database.cacheQueries.selectResource(scope, key).executeAsOneOrNull()
            ?.let { CacheRecord(it.resource, it.payload, it.updated_at) }
    }
    override suspend fun put(scope: String, key: String, payload: String, updatedAt: Long) = withContext(dispatcher) {
        database.transaction { database.cacheQueries.upsert(scope, key, payload, updatedAt) }
    }
    override suspend fun remove(scope: String, key: String) = withContext(dispatcher) {
        database.cacheQueries.deleteResource(scope, key)
        Unit
    }
    override suspend fun purge(scope: String) = withContext(dispatcher) {
        database.cacheQueries.deleteAccount(scope)
        Unit
    }
    suspend fun purgeAll() = withContext(dispatcher) { database.cacheQueries.deleteAll(); Unit }
    suspend fun purgeExcept(scopes: Set<String>) = withContext(dispatcher) {
        database.cacheQueries.selectScopes().executeAsList().filterNot { it in scopes }.forEach {
            database.cacheQueries.deleteAccount(it)
        }
    }
}
