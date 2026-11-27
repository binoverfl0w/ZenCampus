package io.github.binoverfl0w.zencampus.ui.study

import io.github.binoverfl0w.zencampus.ui.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ScreenTitle
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenStatus
import io.github.binoverfl0w.zencampus.ui.design.courseTint
import kotlinx.coroutines.launch

@Composable
fun UpdatesScreen(snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus,
    openAnnouncement: (String) -> Unit, openMessages: () -> Unit, openDiscussion: (String, String, String) -> Unit,
    openCourse: (String) -> Unit, header: (@Composable () -> Unit)? = null) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val now = LocalNow.current
    val updates = remember(snapshot, now) { studyUpdates(snapshot, now) }
    fun relevant(key: String) = key == "activity" || key == "messages:inbox" || key.startsWith("news:") || key.startsWith("course-files:") || key.startsWith("community:") || key.startsWith("tools:")
    val loaded = snapshot.updatedAt.keys.any(::relevant)
    // Progress only before anything has loaded; afterwards updates arrive in place.
    val loading = !loaded && sync.active.any(::relevant)
    // Updates come from the Study sync; this screen only reads them.
    LazyColumn(Modifier.fillMaxSize().testTag("updates-screen"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { if (header != null) header() else ScreenTitle(words.ui("updates_2"), words.ui("messages_courses_and_files")) }
        if (loading) item { ZenStatus(words.ui("checking_for_updates"), loading = true) }
        if (updates.isEmpty() && loaded && !loading) item {
            ZenStatus(words.ui("no_new_items_in_loaded_sources"))
        }
        if (updates.isNotEmpty()) item(key = "updates") { Column {
            updates.forEachIndexed { index, entry ->
                val icon = when (entry.kind) {
                    StudyUpdateKind.MESSAGE -> Symbol.MAIL
                    StudyUpdateKind.ANNOUNCEMENT -> Symbol.BELL
                    StudyUpdateKind.FILE -> Symbol.FILE
                    StudyUpdateKind.FORUM, StudyUpdateKind.BLUBBER -> Symbol.FORUM
                    StudyUpdateKind.ACTIVITY -> Symbol.TODAY
                }
                val kindLabel = when (entry.kind) {
                    StudyUpdateKind.MESSAGE -> words.ui("message")
                    StudyUpdateKind.ANNOUNCEMENT -> words.ui("announcement")
                    StudyUpdateKind.FILE -> words.ui("file")
                    StudyUpdateKind.FORUM -> "Forum"
                    StudyUpdateKind.BLUBBER -> "Blubber"
                    StudyUpdateKind.ACTIVITY -> words.ui("course_activity")
                }
                ZenListItem(GroupPosition.of(index, updates.size), Modifier.testTag("update-${entry.key}"), {
                    when (entry.kind) {
                        StudyUpdateKind.MESSAGE -> openMessages()
                        StudyUpdateKind.ANNOUNCEMENT -> openAnnouncement(entry.id)
                        StudyUpdateKind.FORUM -> openDiscussion(entry.courseId, "forum", entry.id)
                        StudyUpdateKind.BLUBBER -> openDiscussion(entry.courseId, "blubber", entry.id)
                        StudyUpdateKind.ACTIVITY -> openCourse(entry.courseId)
                        StudyUpdateKind.FILE -> snapshot.courseMaterials[entry.courseId]?.documents?.firstOrNull { it.id == entry.id && it.downloadable }
                            ?.let { repository.requestMaterial(it) }
                    }
                }) {
                    ZenIconTile(icon, if (entry.courseId.isNotBlank()) courseTint(entry.courseId) else null)
                    ZenListText(entry.title, entry.subtitle,
                        overline = listOfNotNull(kindLabel, entry.timestamp.takeIf { it > 0 }?.let { words.age(it, now) }).joinToString(" · "),
                        modifier = Modifier.weight(1f))
                    if (entry.unread) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                }
            }
        } }
    }
}
