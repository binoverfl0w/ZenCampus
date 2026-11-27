package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.model.LIBRARY_ORIGIN
import io.github.binoverfl0w.zencampus.model.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val STUDY_SIGN_IN_ORIGIN = "https://login.microsoftonline.com"

@Serializable
enum class SignInKind(val defaultSite: String) {
    STUDY(STUDY_SIGN_IN_ORIGIN),
    LIBRARY(LIBRARY_ORIGIN);

    fun origin(page: String): String? = when (this) {
        STUDY -> microsoftSignInOrigin(page)
        LIBRARY -> librarySignInOrigin(page)
    }
}

/** The password is available only to the shared autofill code. Keep the default object representation. */
@Serializable
class SavedSignIn internal constructor(
    val id: String,
    val kind: SignInKind,
    val site: String,
    val username: String,
    internal val password: String,
    val label: String? = null,
    val lastUsed: Long? = null,
) {
    internal fun usedAt(time: Long) = SavedSignIn(id, kind, site, username, password, label, time)
}

@Serializable
private class SavedSignIns(
    val entries: List<SavedSignIn> = emptyList(),
    val openedFor: String? = null,
    val migratedScopes: Set<String> = emptySet(),
)

@Serializable
private class LegacySignIn(val email: String, val password: String, val autoOpenedFor: String? = null)

/** One protected record per device profile. The mutex serializes migration and read/modify/write operations. */
class SavedSignInStore(
    private val readSecret: (String) -> String?,
    private val writeSecret: (String, String) -> Unit,
    private val deleteSecret: (String) -> Unit,
) {
    private val lock = Mutex()
    private val key = "saved-sign-ins"
    private var current: SavedSignIns? = null
    private var legacyScope: String? = null
    private val _entries = MutableStateFlow<List<SavedSignIn>>(emptyList())
    val entries = _entries.asStateFlow()

    suspend fun load(scope: String? = null) = access {
        if (scope != null) legacyScope = scope
        read().entries
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun save(kind: SignInKind, site: String, username: String, password: String, label: String?, id: String? = null): SavedSignIn = access {
        val data = read()
        val previous = id?.let { checkNotNull(data.entries.find { it.id == id && it.kind == kind && it.site == site }) }
        val secret = password.ifEmpty { previous?.password.orEmpty() }
        require(kind.origin(site) == site && username.isNotBlank() && secret.isNotEmpty())
        val next = SavedSignIn(id ?: Uuid.random().toString(), kind, site, username.trim(), secret,
            label?.trim()?.takeIf { it.isNotEmpty() }, previous?.lastUsed)
        write(SavedSignIns(data.entries.filterNot { it.id == id } + next, data.openedFor, data.migratedScopes))
        next
    }

    suspend fun rename(id: String, label: String?) = access {
        val data = read()
        val previous = checkNotNull(data.entries.find { it.id == id })
        val next = SavedSignIn(id, previous.kind, previous.site, previous.username, previous.password,
            label?.trim()?.takeIf { it.isNotEmpty() }, previous.lastUsed)
        write(SavedSignIns(data.entries.map { if (it.id == id) next else it }, data.openedFor, data.migratedScopes))
    }

    suspend fun delete(id: String) = remove { it.id == id }
    suspend fun removeAll(kind: SignInKind? = null) = remove { kind == null || it.kind == kind }

    private suspend fun remove(matches: (SavedSignIn) -> Boolean) = access {
        val data = read()
        val remaining = data.entries.filterNot(matches)
        if (remaining.size != data.entries.size) write(SavedSignIns(remaining, data.openedFor, data.migratedScopes))
    }

    internal suspend fun markUsed(entry: SavedSignIn) = access {
        val data = read()
        val index = data.entries.indexOfFirst { it.id == entry.id && it.username == entry.username && it.password == entry.password }
        if (index >= 0) {
            val updated = data.entries.toMutableList()
            val time = maxOf(nowMillis(), (updated.maxOfOrNull { it.lastUsed ?: 0 } ?: 0) + 1)
            updated[index] = updated[index].usedAt(time)
            write(SavedSignIns(updated, data.openedFor, data.migratedScopes))
        }
    }

    internal suspend fun claimExpiry(generation: String, eligible: () -> Boolean): Boolean = access {
        val data = read()
        if (data.openedFor == generation || data.entries.none { it.kind == SignInKind.STUDY } || !eligible()) return@access false
        write(SavedSignIns(data.entries, generation, data.migratedScopes))
        true
    }

    private fun read(): SavedSignIns {
        var data = current ?: readSecret(key)?.let { decode<SavedSignIns>(it) } ?: SavedSignIns()
        val scope = legacyScope
        if (scope != null) {
            val legacyKey = "remembered-sign-in:$scope"
            val previous = readSecret(legacyKey)
            if (previous != null) {
                if (scope !in data.migratedScopes) {
                    val old = decode<LegacySignIn>(previous)
                    val migrated = SavedSignIn("legacy:$scope", SignInKind.STUDY, STUDY_SIGN_IN_ORIGIN, old.email, old.password)
                    data = SavedSignIns(data.entries + migrated, data.openedFor ?: old.autoOpenedFor, data.migratedScopes + scope)
                    write(data)
                }
                deleteSecret(legacyKey)
            }
            legacyScope = null
        }
        current = data
        _entries.value = data.entries
        return data
    }

    private inline fun <reified T> decode(value: String): T =
        try { Json.decodeFromString<T>(value) }
        catch (_: IllegalArgumentException) { error("Could not read saved sign-ins") }

    private fun write(data: SavedSignIns) {
        writeSecret(key, Json.encodeToString(data))
        current = data
        _entries.value = data.entries
    }

    private suspend fun <T> access(action: () -> T): T = lock.withLock {
        withContext(NonCancellable + storageDispatcher) { action() }
    }
}

internal suspend fun <T> trySavedSignIn(action: suspend () -> T): T? = try {
    action()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
