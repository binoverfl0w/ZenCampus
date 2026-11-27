package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RequestResilienceTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
    private val messages = """{"data":[]}"""

    private fun provider(vararg replies: () -> Pair<HttpStatusCode, Headers>): Pair<DigiCampusProvider, () -> Int> {
        var calls = 0
        val client = HttpClient(MockEngine {
            val reply = replies[minOf(calls, replies.lastIndex)]; calls++
            val (status, headers) = reply()
            respond(if (status.isSuccess()) messages else "", status, headers)
        })
        return DigiCampusProvider(client, { "fixture" }) to { calls }
    }

    @Test fun busyAndBrokenResponsesAreRetriedUntilTheyWork() = runTest {
        val (provider, calls) = provider(
            { HttpStatusCode.ServiceUnavailable to headersOf() },
            { HttpStatusCode.TooManyRequests to headersOf(HttpHeaders.RetryAfter, "2") },
            { throw kotlinx.io.IOException("connection reset") },
            { HttpStatusCode.OK to json },
        )
        assertEquals(emptyList(), provider.messages("me", false))
        assertEquals(4, calls())
    }

    @Test fun aLongRetryAfterOrADefiniteAnswerIsNotRetried() = runTest {
        val (busy, busyCalls) = provider({ HttpStatusCode.ServiceUnavailable to headersOf(HttpHeaders.RetryAfter, "120") })
        assertEquals(FailureKind.SERVER, assertFailsWith<CampusException> { busy.messages("me", false) }.kind)
        assertEquals(1, busyCalls())
        val (missing, missingCalls) = provider({ HttpStatusCode.NotFound to headersOf() })
        assertEquals(FailureKind.NOT_FOUND, assertFailsWith<CampusException> { missing.messages("me", false) }.kind)
        assertEquals(1, missingCalls())
    }

    @Test fun aServerThatStaysDownFailsAsTemporaryAfterFourAttempts() = runTest {
        val (provider, calls) = provider({ HttpStatusCode.BadGateway to headersOf() })
        val failure = assertFailsWith<CampusException> { provider.messages("me", false) }
        assertTrue(failure.transient); assertEquals(4, calls())
    }

    /** Messages fail on cue; everything else comes from the ordinary test provider. */
    private class Flaky(val base: CampusProvider = TestProvider()) : CampusProvider by base {
        val failures = ArrayDeque<FailureKind>()
        var calls = 0
        var inbox = listOf(CampusMessage("m1", "Hello", "", 1L, false))
        override suspend fun messages(userId: String, sent: Boolean): List<CampusMessage> {
            calls++
            failures.removeFirstOrNull()?.let { throw CampusException(it) }
            return inbox
        }
    }

    @Test fun aTemporaryFailureWithCachedDataStaysQuietAndRetriesByItself() = runTest {
        val provider = Flaky()
        val repo = CampusRepository(Account("alice", "Alice", "alice"), provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { 1_000L })
        repo.refreshMessages(); runCurrent()
        provider.failures += FailureKind.SERVER
        provider.inbox = provider.inbox + CampusMessage("m2", "New", "", 2L, false)
        repo.refreshMessages(force = true); runCurrent()
        assertTrue(repo.sync.value.errors.isEmpty(), "no error while cached messages exist")
        assertFalse("messages:inbox" in repo.sync.value.active, "cached data is not shown as loading")
        assertEquals(listOf("m1"), repo.snapshot.value.messages["inbox"]?.map { it.id })
        advanceTimeBy(CampusRepository.RETRY_DELAYS.first() + 1_000); runCurrent()
        assertEquals(listOf("m1", "m2"), repo.snapshot.value.messages["inbox"]?.map { it.id })
        repo.close(false)
    }

    @Test fun withNothingCachedATemporaryFailureReadsAsLoadingUntilRetriesRunOut() = runTest {
        val provider = Flaky().apply { repeat(CampusRepository.RETRY_DELAYS.size + 1) { failures += FailureKind.OFFLINE } }
        val repo = CampusRepository(Account("alice", "Alice", "alice"), provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { 1_000L })
        repo.refreshMessages(); runCurrent()
        assertTrue("messages:inbox" in repo.sync.value.active); assertTrue(repo.sync.value.errors.isEmpty())
        advanceTimeBy(CampusRepository.RETRY_DELAYS.sum() + 10_000); runCurrent()
        assertEquals(FailureKind.OFFLINE, repo.sync.value.errors["messages:inbox"])
        assertFalse("messages:inbox" in repo.sync.value.active)
        assertEquals(CampusRepository.RETRY_DELAYS.size + 1, provider.calls)
        repo.close(false)
    }

    @Test fun aDefiniteFailureIsShownAtOnceAndNotRetried() = runTest {
        val provider = Flaky().apply { failures += FailureKind.PERMISSION }
        val repo = CampusRepository(Account("alice", "Alice", "alice"), provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { 1_000L })
        repo.refreshMessages(); runCurrent()
        assertEquals(FailureKind.PERMISSION, repo.sync.value.errors["messages:inbox"])
        advanceTimeBy(300_000); runCurrent()
        assertEquals(1, provider.calls)
        repo.close(false)
    }

    @Test fun theStudySyncRunsOnceAtATimeAndSkipsRepeatsSoonAfterwards() = runTest {
        var now = 1_000L
        val provider = Flaky()
        val repo = CampusRepository(Account("alice", "Alice", "alice"), provider, MemoryStore(), MemoryMaterials(), backgroundScope,
            clock = { now })
        repo.requestStudySync(); repo.requestStudySync(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        val first = repo.studySync.value.lastCompleted
        assertNotNull(first); assertFalse(repo.studySync.value.running)
        val calls = provider.calls
        now += 30_000
        repo.requestStudySync(); advanceTimeBy(10_000); runCurrent()
        assertEquals(first, repo.studySync.value.lastCompleted, "a sync shortly after the last one is skipped")
        repo.requestStudySync(force = true); advanceTimeBy(10_000); runCurrent()
        assertTrue(repo.studySync.value.lastCompleted!! > first)
        assertTrue(provider.calls > calls, "pull to refresh rereads")
        repo.close(false)
    }
}
