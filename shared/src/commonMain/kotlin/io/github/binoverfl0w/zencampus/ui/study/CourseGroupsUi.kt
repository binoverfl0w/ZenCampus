package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.classCount
import io.github.binoverfl0w.zencampus.ui.design.CourseBadge
import io.github.binoverfl0w.zencampus.ui.design.CoursePinButton
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.group
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CourseGroupCard(group: CourseGroup, preferences: AccountPreferences, onCourse: (String) -> Unit,
    onPin: (String) -> Unit, onEdit: () -> Unit) {
    val words = LocalWords.current
    Column(Modifier.fillMaxWidth().testTag("course-group-${group.id}")) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZenIcon(Symbol.GROUP, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Text(group.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            SmallLabel(words.classCount(group.courses.size))
            IconAction(Symbol.EDIT, words.group("edit"), onEdit)
        }
        group.courses.forEachIndexed { index, course ->
            ZenListItem(GroupPosition.of(index, group.courses.size), Modifier.testTag("course-member-${course.id}"), { onCourse(course.id) }) {
                BoxWithConstraints(Modifier.weight(1f)) {
                    val roomy = maxWidth >= 300.dp
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (roomy) CourseBadge(course, 36.dp)
                        val kind = course.typeName.takeIf { it.isNotBlank() && !course.title.contains(it, ignoreCase = true) }.orEmpty()
                        ZenListText(course.title, listOf(course.subtitle, course.teacher).filter { it.isNotBlank() }.joinToString(" · "),
                            overline = listOf(kind, course.number).filter { it.isNotBlank() }.joinToString(" · "), modifier = Modifier.weight(1f))
                    }
                }
                CoursePinButton(course.id, course.id in preferences.pinnedCourses) { onPin(course.id) }
            }
        }
    }
}

/** A transient editor: Back closes it without adding navigation history. Only Save persists changes. */
@Composable
fun CourseGroupsDialog(courses: List<Course>, preferences: AccountPreferences, groups: List<CourseGroup>,
    repository: CampusRepository, initialGroupId: String?, onDismiss: () -> Unit) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val initial = groups.firstOrNull { it.id == initialGroupId }
    fun members(group: CourseGroup?): List<String> =
        (preferences.courseGroups.firstOrNull { it.id == group?.id }?.courseIds ?: group?.courses?.map { it.id }?.toSet()).orEmpty().toList()
    var editing by rememberSaveable { mutableStateOf(initialGroupId) }
    var name by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var selected by rememberSaveable { mutableStateOf(members(initial)) }
    var previous by rememberSaveable { mutableStateOf(members(initial)) }
    var query by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun edit(group: CourseGroup?) {
        editing = group?.id ?: "new"
        name = group?.title.orEmpty(); selected = members(group); previous = selected; query = ""; failed = false
    }
    fun change(action: suspend () -> Unit) {
        if (saving) return
        saving = true; failed = false
        scope.launch {
            try { action(); onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { saving = false }
        }
    }
    val visibleGroups = groups.filter { it.courses.size > 1 || preferences.courseGroups.any { saved -> saved.id == it.id } }
    val options = courses.filter { query.isBlank() || listOf(it.title, it.number, it.teacher, it.subtitle, it.typeName).any { field -> field.contains(query, true) } }
        .distinctBy { it.id }.sortedBy { it.title.lowercase() }
    val hiddenMembers = selected.count { id -> courses.none { it.id == id } }
    ZenDialog(onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.testTag("course-groups-dialog"),
        title = { Text(words.group(if (editing == null) "manage" else "edit")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (failed) Text(words.group("failed"), color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("course-groups-editor"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editing == null) {
                    if (visibleGroups.isEmpty()) item {
                        Text(words.group("empty"), style = MaterialTheme.typography.titleSmall)
                        Text(words.group("empty_body"), style = MaterialTheme.typography.bodyMedium)
                    }
                    items(visibleGroups, key = { it.id }) { group ->
                        Surface(onClick = { edit(group) }, modifier = Modifier.fillMaxWidth().testTag("edit-course-group-${group.id}"),
                            shape = RoundedCornerShape(Zen.radiusMedium), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(group.title, style = MaterialTheme.typography.titleSmall)
                                    SmallLabel("${words.classCount(group.courses.size)} · ${words.group(if (group.automatic) "automatic" else "custom")}")
                                }
                                ZenIcon(Symbol.NEXT, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item { SmallLabel(words.group("local")) }
                } else {
                    item {
                        OutlinedTextField(name, { name = it.take(100) }, label = { Text(words.group("name")) },
                            modifier = Modifier.fillMaxWidth().testTag("course-group-name"), enabled = !saving, singleLine = true)
                    }
                    item {
                        OutlinedTextField(query, { query = it }, label = { Text(words.group("search")) },
                            modifier = Modifier.fillMaxWidth().testTag("course-group-search"), singleLine = true)
                    }
                    item {
                        SmallLabel("${selected.size} ${words.group("selected")}")
                        if (selected.size < 2) SmallLabel(words.group("minimum"))
                        if (hiddenMembers > 0) SmallLabel("$hiddenMembers ${words.group("outside")}")
                    }
                    items(options, key = { it.id }) { course ->
                        val checked = course.id in selected
                        Row(Modifier.fillMaxWidth().testTag("course-group-select-${course.id}")
                            .toggleable(checked, enabled = !saving, role = Role.Checkbox) { requested ->
                                selected = if (requested) (selected + course.id).distinct() else selected.filterNot { it == course.id }
                                if (requested && name.isBlank()) name = course.subject().title.take(100)
                            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Checkbox(checked, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                Text(course.title, style = MaterialTheme.typography.bodyMedium)
                                val kind = course.typeName.ifBlank { course.subtitle }.takeIf { it.isNotBlank() && !course.title.contains(it, ignoreCase = true) }.orEmpty()
                                SmallLabel(listOf(kind, course.number, course.teacher).filter { it.isNotBlank() }.joinToString(" · "))
                            }
                        }
                    }
                    if (previous.isNotEmpty()) item {
                        ZenLink(words.group("separate"), { change { repository.separateCourses(previous.toSet()) } }, enabled = !saving,
                            modifier = Modifier.testTag("course-group-separate"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            }
        },
        confirmButton = {
            if (editing == null) DialogButton(words.group("new"), { edit(null) }, enabled = courses.size >= 2, modifier = Modifier.testTag("course-group-new"), primary = true)
            else DialogButton(words.group("save"), { change {
                val storedId = editing?.takeIf { id -> preferences.courseGroups.any { it.id == id } }
                repository.saveCourseGroup(storedId, name, selected.toSet(), previous.toSet())
            } }, enabled = !saving && name.isNotBlank() && selected.size in 2..500, modifier = Modifier.testTag("course-group-save"), primary = true)
        },
        dismissButton = { DialogButton(words.text(if (editing == null) "close" else "cancel"), onDismiss, enabled = !saving) })
}
