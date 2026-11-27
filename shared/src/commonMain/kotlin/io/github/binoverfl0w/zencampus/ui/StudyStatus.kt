package io.github.binoverfl0w.zencampus.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

// The Study status line and the one app-wide banner (an expired session).

/** One line for all of Study, under the board title or in the rail: updating, or when the last complete sync finished. */
@Composable
internal fun studySyncLine(snapshot: CampusSnapshot, study: StudySyncState, network: NetworkConditions): SyncLine? {
    val words = LocalWords.current
    val now = LocalNow.current
    val updated = study.lastCompleted
    return when {
        !network.connected -> SyncLine(words.text("offline") + (updated?.let { " · ${words.age(it, now)}" } ?: ""), SyncState.OFFLINE)
        study.running -> SyncLine(words.text("refreshing"), SyncState.REFRESHING)
        snapshot.account.demo -> SyncLine(words.text("preview_note"))
        updated != null -> SyncLine("${words.text("last_updated")} ${words.age(updated, now)}")
        else -> null
    }
}

/**
 * Only an expired session is announced app-wide: it needs the student. Temporary failures retry on their own and
 * definite ones (no access, removed) are shown where the missing content would be.
 */
@Composable
internal fun SyncErrorBanner(sync: SyncStatus, onSignIn: () -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val words = LocalWords.current
    val error = if (sync.sessionExpired) FailureKind.AUTHENTICATION else null
    if (error == null) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Zen.gutter, vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(Zen.radiusMedium),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(words.failure(error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            if (error == FailureKind.AUTHENTICATION) ZenLink(words.text("sign_in"), onSignIn)
            else {
                IconAction(Symbol.REFRESH, words.text("retry"), onRefresh)
                IconAction(Symbol.CLOSE, words.text("dismiss"), onDismiss)
            }
        }
    }
}
