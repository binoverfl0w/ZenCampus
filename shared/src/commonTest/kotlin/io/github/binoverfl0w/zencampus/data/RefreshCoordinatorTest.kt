package io.github.binoverfl0w.zencampus.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoordinatorTest {
    @Test fun limitsSpeculativeReadsToTwoConcurrentRequests() = runTest {
        val coordinator = RefreshCoordinator(backgroundScope)
        var running = 0
        var peak = 0
        coroutineScope {
            (0..5).map { index -> launch {
                coordinator.run("resource:$index", speculative = true) {
                    running++
                    peak = maxOf(peak, running)
                    delay(100)
                    running--
                }
            } }.joinAll()
        }
        assertEquals(2, peak)
        assertTrue(coordinator.active.value.isEmpty())
    }

    @Test fun foregroundReaderPromotesQueuedPrefetchWithoutDuplicatingIt() = runTest {
        val coordinator = RefreshCoordinator(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val first = launch { coordinator.run("first", true) { started += "first"; gate.await() } }
        val second = launch { coordinator.run("second", true) { started += "second"; gate.await() } }
        runCurrent()
        val third = launch { coordinator.run("third", true) { started += "third" } }
        runCurrent()
        assertFalse("third" in started)
        val foreground = launch { coordinator.run("third") { fail("A promoted read must reuse the original request") } }
        runCurrent()
        assertEquals(1, started.count { it == "third" })
        foreground.join()
        gate.complete(Unit)
        first.join(); second.join(); third.join()
        assertTrue(coordinator.active.value.isEmpty())
    }

    @Test fun leavingASpeculativeScreenCancelsItsUnneededRead() = runTest {
        val coordinator = RefreshCoordinator(backgroundScope)
        var cancelled = false
        val pending = launch { coordinator.run("folder", true) {
            try { awaitCancellation() } finally { cancelled = true }
        } }
        runCurrent()
        pending.cancelAndJoin()
        runCurrent()
        assertTrue(cancelled)
        assertTrue(coordinator.active.value.isEmpty())
    }

    @Test fun foregroundNavigationCanRestartAPrefetchThatWasJustCancelled() = runTest {
        val coordinator = RefreshCoordinator(backgroundScope)
        val background = launch { coordinator.run("folder", true) { awaitCancellation() } }
        runCurrent()
        background.cancel()
        var fetched = false
        val foreground = launch { coordinator.run("folder") { fetched = true } }
        foreground.join()
        background.join()
        assertTrue(fetched)
        assertTrue(coordinator.active.value.isEmpty())
    }
    @Test fun expirationCancelsOnlyRefreshesWithoutWaitersAndWaitsForCleanup() = runTest {
        val coordinator = RefreshCoordinator(backgroundScope)
        val abandonedStarted = CompletableDeferred<Unit>()
        val sharedStarted = CompletableDeferred<Unit>()
        val sharedRelease = CompletableDeferred<Unit>()
        var abandonedCleaned = false
        val abandoned = launch { coordinator.run("abandoned") {
            abandonedStarted.complete(Unit)
            try { awaitCancellation() } finally { abandonedCleaned = true }
        } }
        val retained = launch { coordinator.run("retained") { sharedStarted.complete(Unit); sharedRelease.await() } }
        abandonedStarted.await(); sharedStarted.await()
        abandoned.cancelAndJoin()
        assertFalse(abandonedCleaned, "A promoted read normally survives its waiter")
        coordinator.cancelUnobserved()
        assertTrue(abandonedCleaned)
        assertTrue(retained.isActive, "A read with a consumer must continue")
        sharedRelease.complete(Unit); retained.join()
        assertTrue(coordinator.active.value.isEmpty())
    }

}
