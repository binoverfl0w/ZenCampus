package io.github.binoverfl0w.zencampus.ui.study

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
import io.github.binoverfl0w.zencampus.ui.design.CourseDot
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ReadingText
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ToneChip
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenGroup
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenRow
import io.github.binoverfl0w.zencampus.ui.design.courseTint
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

// Shared Study rows: a class in a list and course announcements.

/** A class in a list: time column, course dot, title and room. */
@Composable
fun EventRow(event: CampusEvent, course: Course?, onClick: (() -> Unit)? = null) {
    val words = LocalWords.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Zen.radiusMedium))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.widthIn(min = 48.dp)) {
            if (event.allDay) Text(words.text("all_day"), style = MaterialTheme.typography.labelLarge)
            else {
                Text(words.time(event.start), style = MaterialTheme.typography.titleSmall, color = if (event.cancelled) muted else Color.Unspecified)
                Text(words.time(event.end), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        if (event.cancelled) Box(Modifier.size(10.dp).border(2.dp, courseTint(course?.id ?: event.courseId).ink, CircleShape))
        else CourseDot(course?.id ?: event.courseId, 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(course?.title ?: event.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (event.cancelled) muted else Color.Unspecified,
                textDecoration = if (event.cancelled) TextDecoration.LineThrough else null)
            Text(event.location.ifBlank { words.text("no_room") }, style = MaterialTheme.typography.bodySmall, color = muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (event.cancelled) ToneChip(words.text("cancelled"), Tone.DANGER)
        else if (onClick != null) ZenIcon(Symbol.NEXT, Modifier.size(16.dp), muted)
    }
}

/** An announcement as a list row; place several inside one [ZenGroup]. */
@Composable
fun AnnouncementRow(announcement: Announcement, course: Course?, unread: Boolean, onClick: () -> Unit) {
    val words = LocalWords.current
    ZenRow(Modifier.testTag("announcement-${announcement.id}"), onClick, verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp)) { CourseDot(course?.id ?: announcement.courseId) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(listOfNotNull(course?.title?.takeIf { it.isNotBlank() }, words.age(announcement.published, LocalNow.current)).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(announcement.title, style = MaterialTheme.typography.titleSmall)
            if (announcement.content.isNotBlank()) Text(announcement.content, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (unread) ToneChip(words.text("new"), Tone.PRIMARY)
    }
}

@Composable
fun AnnouncementCard(announcement: Announcement, course: Course?, unread: Boolean, onClick: () -> Unit) {
    ZenGroup { AnnouncementRow(announcement, course, unread, onClick) }
}

@Composable
internal fun AnnouncementDialog(announcement: Announcement, course: Course?, onDismiss: () -> Unit, onRead: () -> Unit,
    extra: @Composable () -> Unit = {}) {
    val words = LocalWords.current
    ZenDialog(onDismissRequest = onDismiss,
        title = { Text(announcement.title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CourseDot(course?.id ?: announcement.courseId)
                    Text(listOfNotNull(course?.title, words.age(announcement.published, LocalNow.current)).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium)
                }
                ReadingText(announcement.content)
                extra()
            }
        },
        confirmButton = { DialogButton(words.text("mark_read"), onRead, primary = true) },
        dismissButton = { DialogButton(words.text("close"), onDismiss) },
    )
}
