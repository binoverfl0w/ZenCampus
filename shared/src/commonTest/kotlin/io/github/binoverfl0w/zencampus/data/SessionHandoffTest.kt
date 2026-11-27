package io.github.binoverfl0w.zencampus.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHandoffTest {
    @Test fun rechecksAfterLoginEvenWhenTheCookieDoesNotChange() = runTest {
        var signedIn = false
        var completions = 0
        val cookies = mutableListOf<String>()
        val handoff = SessionHandoff(this, { "session=unchanged" }, { cookie, _ ->
            cookies += cookie
            signedIn
        }, { completions++ })

        handoff.request()
        advanceUntilIdle()
        assertEquals(0, completions)
        signedIn = true
        handoff.request()
        advanceUntilIdle()
        assertEquals(listOf("session=unchanged", "session=unchanged"), cookies)
        assertEquals(1, completions)
    }

    @Test fun navigationDuringAProbeQueuesFreshCookiesAndPreservesAnExplicitRetry() = runTest {
        var cookie = "session=guest"
        var completions = 0
        val requests = mutableListOf<Pair<String, Boolean>>()
        val firstResponse = CompletableDeferred<Boolean>()
        val handoff = SessionHandoff(this, { cookie }, { candidate, requested ->
            requests += candidate to requested
            if (requests.size == 1) firstResponse.await() else true
        }, { completions++ })

        handoff.request()
        runCurrent()
        cookie = "session=member"
        handoff.request()
        handoff.request(userRequested = true)
        runCurrent()
        assertEquals(listOf("session=guest" to false), requests)
        firstResponse.complete(false)
        advanceUntilIdle()
        assertEquals(listOf("session=guest" to false, "session=member" to true), requests)
        assertEquals(1, completions)
    }

    @Test fun successfulHandoffCompletesOnceAndStopsFurtherProbes() = runTest {
        var requests = 0
        var completions = 0
        val handoff = SessionHandoff(this, { "session=member" }, { _, _ -> requests++; true }, { completions++ })
        handoff.request()
        handoff.request(userRequested = true)
        advanceUntilIdle()
        handoff.request()
        advanceUntilIdle()
        assertEquals(1, requests)
        assertEquals(1, completions)
    }

    @Test fun emptyAutomaticChecksAreSkippedButExplicitChecksCanShowAnError() = runTest {
        val requests = mutableListOf<Boolean>()
        val handoff = SessionHandoff(this, { "" }, { _, requested -> requests += requested; false }, {})
        handoff.request()
        advanceUntilIdle()
        assertEquals(emptyList(), requests)
        handoff.request(userRequested = true)
        advanceUntilIdle()
        assertEquals(listOf(true), requests)
    }

    @Test fun closingTheSignInScreenCancelsChecksAndDoesNotCompleteTheHandoff() = runTest {
        val handoffScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val response = CompletableDeferred<Boolean>()
        var completions = 0
        var requests = 0
        val handoff = SessionHandoff(handoffScope, { "session=member" }, { _, _ -> requests++; response.await() }, { completions++ })
        handoff.request()
        runCurrent()
        handoff.request()
        handoffScope.cancel()
        response.complete(true)
        advanceUntilIdle()
        assertEquals(1, requests)
        assertEquals(0, completions)
    }
}
