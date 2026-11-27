package io.github.binoverfl0w.zencampus

import kotlinx.coroutines.*

/** A task always calls its own completion, including when replaced or cancelled before it starts. */
internal class IosBackgroundCoordinator(private val scope: CoroutineScope) {
    private var active: Job? = null
    fun cancel(): Job? = active?.also { it.cancel() }
    fun start(work: suspend () -> Boolean, completion: (Boolean) -> Unit) {
        active?.cancel()
        var result = false
        val job = scope.launch {
            try { result = work() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { result = false }
        }
        active = job
        job.invokeOnCompletion { error -> completion(error == null && result) }
    }
}

/** NWPathMonitor can still be initializing when iOS cold-launches the background task. */
internal suspend fun awaitBackgroundNetwork(check: () -> Boolean, attempts: Int = 4, delayMillis: Long = 500): Boolean {
    repeat(attempts) { index ->
        if (check()) return true
        if (index + 1 < attempts) delay(delayMillis)
    }
    return false
}
