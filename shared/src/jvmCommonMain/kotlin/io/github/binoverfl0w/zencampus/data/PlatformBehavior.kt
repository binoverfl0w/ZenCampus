package io.github.binoverfl0w.zencampus.data

import androidx.compose.ui.Modifier
import kotlinx.coroutines.*

internal actual suspend fun <T> withLibraryConnection(job: Job, action: suspend () -> T): T = withContext(job) { action() }
internal actual suspend fun awaitBackgroundSync(job: Job, coordinator: RefreshCoordinator) { job.join() }
internal actual fun cleanCourseTitle(title: String): String = title.replace(Regex("[^\\p{L}\\p{N} ]"), " ")
internal actual fun Modifier.platformToggleState(state: String): Modifier = this
