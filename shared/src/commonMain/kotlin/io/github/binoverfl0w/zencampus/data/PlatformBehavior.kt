package io.github.binoverfl0w.zencampus.data

import androidx.compose.ui.Modifier
import kotlinx.coroutines.Job

internal expect suspend fun <T> withLibraryConnection(job: Job, action: suspend () -> T): T
internal expect suspend fun awaitBackgroundSync(job: Job, coordinator: RefreshCoordinator)
internal expect fun cleanCourseTitle(title: String): String
internal expect fun Modifier.platformToggleState(state: String): Modifier
