package io.github.binoverfl0w.zencampus.ui.design

import androidx.compose.runtime.Composable
import io.github.binoverfl0w.zencampus.data.digicampus.text
import io.github.binoverfl0w.zencampus.model.*

/**
 * What a screen should show for one cache key, by one rule everywhere:
 * progress only before the first data, saved data quietly when an update failed temporarily,
 * and an error only when there is nothing to show.
 */
data class ContentState(val hasData: Boolean, val loading: Boolean, val error: FailureKind?, val stale: Boolean) {
    /** Nothing to show yet and a read is running (or retrying). */
    val firstLoad: Boolean get() = !hasData && loading
    /** Nothing to show and the read failed for good. */
    val failed: Boolean get() = !hasData && !loading && error != null
    /** Loaded, with no entries, and nothing pending. */
    fun empty(isEmpty: Boolean): Boolean = hasData && isEmpty && !loading && error == null
}

fun SyncStatus.content(key: String, hasData: Boolean): ContentState =
    ContentState(hasData, key in active, errors[key], key in stale)

/**
 * The status part of a list: first-load progress, a definite failure with retry (and an optional official link),
 * or an empty message. Renders nothing while data is on screen.
 */
@Composable
fun ContentStatus(state: ContentState, loadingText: String, onRetry: () -> Unit, emptyText: String? = null, isEmpty: Boolean = false,
    official: String? = null, onOfficial: () -> Unit = {}) {
    val words = LocalWords.current
    when {
        state.firstLoad -> ZenStatus(loadingText, loading = true)
        state.failed -> ZenNotice(words.failure(state.error!!), Tone.DANGER, words.text("retry"), onRetry,
            secondary = official, onSecondary = onOfficial, secondaryExternal = official != null)
        emptyText != null && state.empty(isEmpty) -> ZenStatus(emptyText)
    }
}
