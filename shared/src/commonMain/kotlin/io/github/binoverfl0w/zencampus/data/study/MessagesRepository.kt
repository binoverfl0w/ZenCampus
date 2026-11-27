package io.github.binoverfl0w.zencampus.data.study

import io.github.binoverfl0w.zencampus.model.*
import kotlin.reflect.typeOf
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json

/**
 * DigiCampus messages, people search and contacts.
 *
 * Part of [CampusRepository]: each operation runs with the repository as receiver (`with(repo)`), so the shared
 * cache engine, provider, store and other areas are reached exactly as from the repository itself.
 */
internal class MessagesRepository(private val repo: CampusRepository) {
    suspend fun refreshMessages(sent: Boolean = false, force: Boolean = false) = with(repo) {
        refresh("messages:${if (sent) "sent" else "inbox"}", 5 * 60_000L, force) { provider.messages(account.id, sent) }
    }

    suspend fun findPeople(query: String): List<CampusPerson> { with(repo) {
        checkActive()
        if (!conditions().connected) throw CampusException(FailureKind.OFFLINE)
        val result = provider.findPeople(query)
        currentCoroutineContext().ensureActive(); checkActive()
        return result
    } }

    suspend fun sendMessage(recipient: CampusPerson, subject: String, body: String, requestId: String) { with(repo) {
        if (requestId.isBlank()) return
        studentWrite("write:message", "", recipient.id, "", requestId,
            action = { provider.sendMessage(recipient.id, subject, body) }) { null }
        if (stored<CourseworkWrite>("write:message")?.status == WriteStatus.CONFIRMED) refreshMessages(sent = true, force = true)
    } }

    suspend fun setMessageRead(id: String, read: Boolean) { with(repo) {
        try {
            coordinator.run("message-read:$id") {
                checkActive()
                if (!conditions().connected) throw CampusException(FailureKind.OFFLINE)
                when (val result = provider.setMessageRead(id, read)) {
                    is WriteResult.Rejected -> throw CampusException(result.failure)
                    is WriteResult.Confirmed, WriteResult.Uncertain -> {
                        refreshMessages(force = true)
                        _errors.update { it - "message-read:$id" }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { recordError("message-read:$id", e) }
    } }

    suspend fun refreshContacts(force: Boolean = false) = with(repo) { refresh("contacts", 30 * 60_000L, force) { provider.contacts(account.id) }
    }

    suspend fun setContact(person: CampusPerson, add: Boolean) { with(repo) {
        val key = "write:contact:${person.id}"
        // A contact toggle is idempotent; a new request id lets it be repeated after an earlier result.
        studentWrite(key, "", person.id, person.name, "contact:${clock()}", action = { provider.setContact(account.id, person.id, add) }) { null }
        refreshContacts(force = true)
    } }
}
