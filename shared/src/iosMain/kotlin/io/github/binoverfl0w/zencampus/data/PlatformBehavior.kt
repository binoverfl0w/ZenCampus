package io.github.binoverfl0w.zencampus.data

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.*

internal actual suspend fun <T> withLibraryConnection(job: Job, action: suspend () -> T): T = coroutineScope {
    val child = currentCoroutineContext().job
    val registration = job.invokeOnCompletion { cause ->
        if (cause != null) child.cancel(CancellationException("Library connection closed"))
    }
    try { action() } finally { registration.dispose() }
}
internal actual suspend fun awaitBackgroundSync(job: Job, coordinator: RefreshCoordinator) {
    try { job.join() } catch (e: CancellationException) {
        withContext(NonCancellable) { job.cancelAndJoin(); coordinator.cancelUnobserved() }
        throw e
    }
}
internal actual fun cleanCourseTitle(title: String): String = title.map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("")
internal actual fun Modifier.platformToggleState(state: String): Modifier = semantics { stateDescription = state }
