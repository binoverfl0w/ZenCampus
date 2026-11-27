package io.github.binoverfl0w.zencampus

import kotlinx.coroutines.*
import kotlin.test.*

class IosBackgroundCoordinatorTest {
    @Test fun completesSuccessfulWorkExactlyOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val result = CompletableDeferred<Boolean>()
            IosBackgroundCoordinator(scope).start({ true }) { result.complete(it) }
            assertTrue(withTimeout(5_000) { result.await() })
        } finally { scope.cancel() }
    }
    @Test fun cancellationCompletesTaskAndStopsWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val started = CompletableDeferred<Unit>()
            val result = CompletableDeferred<Boolean>()
            val coordinator = IosBackgroundCoordinator(scope)
            coordinator.start({ started.complete(Unit); awaitCancellation() }) { result.complete(it) }
            withTimeout(5_000) { started.await() }
            coordinator.cancel()
            assertFalse(withTimeout(5_000) { result.await() })
        } finally { scope.cancel() }
    }
    @Test fun replacingTaskCancelsOldTaskButCompletesBoth() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val started = CompletableDeferred<Unit>()
            val first = CompletableDeferred<Boolean>()
            val second = CompletableDeferred<Boolean>()
            val coordinator = IosBackgroundCoordinator(scope)
            coordinator.start({ started.complete(Unit); awaitCancellation() }) { first.complete(it) }
            withTimeout(5_000) { started.await() }
            coordinator.start({ true }) { second.complete(it) }
            assertFalse(withTimeout(5_000) { first.await() })
            assertTrue(withTimeout(5_000) { second.await() })
        } finally { scope.cancel() }
    }
    @Test fun failureCompletesWithoutEscapingTheWorker() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val result = CompletableDeferred<Boolean>()
            IosBackgroundCoordinator(scope).start({ error("fixture failure") }) { result.complete(it) }
            assertFalse(withTimeout(5_000) { result.await() })
        } finally { scope.cancel() }
    }

    @Test fun waitsBrieflyForTheNetworkMonitorAndNeverRunsWhenItStaysUnavailable() = runBlocking {
        var checks = 0
        assertTrue(awaitBackgroundNetwork({ ++checks == 3 }, attempts = 4, delayMillis = 1))
        assertEquals(3, checks)
        checks = 0
        assertFalse(awaitBackgroundNetwork({ checks++; false }, attempts = 4, delayMillis = 1))
        assertEquals(4, checks)
    }
    @Test fun alreadyCancelledScopeStillCompletesItsTask() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.cancel()
        val result = CompletableDeferred<Boolean>()
        IosBackgroundCoordinator(scope).start({ true }) { result.complete(it) }
        assertFalse(withTimeout(5_000) { result.await() })
    }

}
