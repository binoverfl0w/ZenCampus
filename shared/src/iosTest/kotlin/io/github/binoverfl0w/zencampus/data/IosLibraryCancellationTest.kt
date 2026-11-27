package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.services.*
import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class IosLibraryCancellationTest {
    private val now = nowMillis()
    @Test fun cancellingLibraryRefreshCancelsItsRequestWithoutDisconnecting() = runTest {
        val store = MemoryStore()
        val repo = CampusServicesRepository(store, DemoMensaProvider { now }, backgroundScope, clock = { now })
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val provider = object : LibraryProvider {
            override suspend fun account(): LibraryAccount {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            }
            override suspend fun renew(loanId: String): LibraryRenewal = error("No write expected")
        }
        repo.attachLibrary("alice", provider)
        val refresh = launch { repo.refreshLibrary(force = true) }
        started.await(); refresh.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(repo.library.value.connected)
        assertNull(store.read(CampusServicesRepository.libraryScope("alice"), "account"))
    }

}
