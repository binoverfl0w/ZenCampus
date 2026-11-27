package io.github.binoverfl0w.zencampus.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Account-owned reads deduplicate; speculative reads yield to foreground consumers. */
class RefreshCoordinator(private val scope: CoroutineScope) {
    private class Entry {
        var waiters = 0
        val promoted = CompletableDeferred<Unit>()
        lateinit var deferred: Deferred<Unit>
    }
    private val mutex = Mutex()
    private val speculativeSlots = Semaphore(2)
    private val inFlight = mutableMapOf<String, Entry>()
    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    suspend fun run(key: String, speculative: Boolean = false, block: suspend () -> Unit) {
        val entry = mutex.withLock {
            val value = inFlight[key]?.takeUnless { it.deferred.isCancelled || it.deferred.isCompleted } ?: Entry().also { fresh ->
                fresh.deferred = scope.async(start = CoroutineStart.LAZY) {
                    try { execute(fresh, block) }
                    finally {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                if (inFlight[key] === fresh) inFlight.remove(key)
                                _active.value = inFlight.keys.toSet()
                            }
                        }
                    }
                }
                inFlight[key] = fresh
                _active.value = inFlight.keys.toSet()
            }
            value.waiters++
            if (!speculative) value.promoted.complete(Unit)
            value
        }
        entry.deferred.start()
        try { entry.deferred.await() }
        finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    entry.waiters--
                    if (entry.waiters == 0 && !entry.promoted.isCompleted && !entry.deferred.isCompleted) {
                        entry.deferred.cancel()
                    }
                }
            }
        }
    }

    /** Cancel work with no remaining consumers after a background task expires. */
    suspend fun cancelUnobserved() {
        val jobs = mutex.withLock {
            inFlight.values.filter { it.waiters == 0 && !it.deferred.isCompleted }.map { it.deferred }.also { entries -> entries.forEach { it.cancel() } }
        }
        jobs.joinAll()
    }

    private suspend fun execute(entry: Entry, block: suspend () -> Unit) = coroutineScope {
        if (entry.promoted.isCompleted) { block(); return@coroutineScope }
        val ready = CompletableDeferred<Unit>()
        val reservation = launch {
            speculativeSlots.withPermit {
                ready.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            val foreground = select {
                entry.promoted.onAwait { true }
                ready.onAwait { false }
            }
            if (foreground) reservation.cancelAndJoin()
            block()
        } finally { reservation.cancelAndJoin() }
    }
}
