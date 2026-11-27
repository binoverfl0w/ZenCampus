package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.action
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.CourseBadge
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.EmptyMoment
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.LoadingCards
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ReadingText
import io.github.binoverfl0w.zencampus.ui.design.RowChevron
import io.github.binoverfl0w.zencampus.ui.design.ScreenTitle
import io.github.binoverfl0w.zencampus.ui.design.SectionTitle
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ToneChip
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenDivider
import io.github.binoverfl0w.zencampus.ui.design.ZenGroup
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenNotice
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import io.github.binoverfl0w.zencampus.ui.design.ZenRow
import io.github.binoverfl0w.zencampus.ui.design.ZenTabs
import io.github.binoverfl0w.zencampus.ui.study
import io.github.binoverfl0w.zencampus.ui.tool
import io.github.binoverfl0w.zencampus.ui.ui
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus

internal fun Words.taskDue(task: CourseworkTask, now: Long): String {
    val due = task.due ?: return study("no_due")
    val date = campusToday(due)
    val label = when (date) {
        campusToday(now) -> study("due_today")
        campusToday(now).plus(1, DateTimeUnit.DAY) -> study("due_tomorrow")
        else -> "${study("due")} ${formatDate(date)}"
    }
    return "$label · ${time(due)}"
}

@Composable
fun CourseworkRow(task: CourseworkTask, course: Course?, position: GroupPosition = GroupPosition.SINGLE, onClick: () -> Unit) {
    val words = LocalWords.current
    val now = LocalNow.current
    val isOverdue = task.overdue(now)
    ZenListItem(position, Modifier.testTag("task-${task.id}"), onClick) {
        if (course != null) CourseBadge(course, 36.dp) else ZenIconTile(Symbol.TASK)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZenListText(task.title, course?.title.orEmpty(), titleLines = 3)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZenIcon(Symbol.CLOCK, Modifier.size(13.dp), if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(words.taskDue(task, now), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false),
                    color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (task.submitted || isOverdue || task.renewal == "granted") ToneChip(
                    words.study(when { task.submitted -> "submitted"; isOverdue -> "overdue"; else -> "extension" }),
                    when { task.submitted -> Tone.SUCCESS; isOverdue -> Tone.DANGER; else -> Tone.PRIMARY })
            }
        }
        RowChevron()
    }
}

/** Coverage is explicit: an unloaded or rejected course is never represented as an empty list. */
@Composable
private fun CourseworkCoverage(snapshot: CampusSnapshot, sync: SyncStatus, brief: Boolean = false) {
    val words = LocalWords.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val checked = snapshot.courses.count { course ->
        course.id in snapshot.coursework && course.id in snapshot.assignmentFolders &&
            course.id in snapshot.courseTools && (snapshot.courseTools[course.id]?.hasVips != true || course.id in snapshot.vipsSheets)
    }
    val failed = sync.errors.keys.any { it.startsWith("tasks:") || it.startsWith("upload-folders:") || it.startsWith("vips:") || it.startsWith("tools:") }
    if (brief && checked == snapshot.courses.size && !failed) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        SmallLabel("$checked / ${snapshot.courses.size} ${words.study("checked_courses")}")
        if (!snapshot.vipsDatesChecked(sync.errors, sync.active)) SmallLabel(words.tool("dates_unchecked"))
        if (failed) SmallLabel(words.study("unverified"))
        if (!brief) ZenLink(words.study(if (expanded) "hide_sources" else "sources"), { expanded = !expanded }, modifier = Modifier.testTag("coursework-sources"))
        if (expanded) {
            SmallLabel("${snapshot.courses.count { it.id in snapshot.coursework }} / ${snapshot.courses.size} ${words.study("coverage")} · ${words.action("courseware_source")}")
            SmallLabel("${snapshot.courses.count { it.id in snapshot.assignmentFolders }} / ${snapshot.courses.size} ${words.action("folder_coverage")}")
            val vipsCourses = snapshot.courses.filter { snapshot.courseTools[it.id]?.hasVips == true }
            if (vipsCourses.isNotEmpty()) SmallLabel("${vipsCourses.count { it.id in snapshot.vipsSheets }} / ${vipsCourses.size} ${words.tool("coverage")}")
            if (sync.errors.keys.any { it.startsWith("upload-folders:") }) SmallLabel(words.action("folder_unavailable"))
            snapshot.courses.flatMap { listOfNotNull(snapshot.updatedAt[CacheKeys.tasks(it.id)], snapshot.updatedAt[CacheKeys.uploadFolders(it.id)], snapshot.updatedAt[CacheKeys.vips(it.id)]) }.minOrNull()?.let {
                SmallLabel("${words.study("updated")} ${words.age(it, LocalNow.current)}")
            }
            SmallLabel(words.study("scope"))
        }
    }
}
internal data class CourseworkAttention(val due: Long?, val task: CourseworkTask? = null, val folder: CampusFolder? = null, val sheet: VipsSheet? = null)

/** Open coursework that needs attention within seven days, earliest first. */
internal fun courseworkAttention(snapshot: CampusSnapshot, now: Long): List<CourseworkAttention> {
    val week = now + 7L * 86_400_000
    val tasks = snapshot.coursework.values.flatten().filter { !it.submitted && (it.due == null || it.due <= week) }
        .map { CourseworkAttention(it.due, task = it) }
    val personal = snapshot.personalDeadlines.values.filter { !it.completed && it.due <= week }.mapNotNull { note ->
        snapshot.assignmentFolders[note.courseId]?.firstOrNull { it.id == note.folderId }?.let { CourseworkAttention(note.due, folder = it) }
    }
    val vips = snapshot.upcomingVipsSheets(now).map { CourseworkAttention(it.attentionAt(now), sheet = it) }
    return (tasks + personal + vips).sortedBy { it.due ?: Long.MAX_VALUE }
}

/** A short due label whose tone reflects urgency. */
@Composable
internal fun DueChip(due: Long?) {
    val words = LocalWords.current
    val now = LocalNow.current
    if (due == null) { ToneChip(words.study("no_due")); return }
    val date = campusToday(due)
    val today = campusToday(now)
    when {
        due < now -> ToneChip(words.ui("overdue"), Tone.DANGER)
        date == today -> ToneChip("${words.ui("today")} ${words.time(due)}", Tone.WARN)
        date == today.plus(1, DateTimeUnit.DAY) -> ToneChip("${words.ui("tomorrow")} ${words.time(due)}", Tone.WARN)
        else -> ToneChip("${words.shortDay(date)} ${words.formatDate(date)} · ${words.time(due)}")
    }
}

@Composable
private fun DueRow(courseId: String, title: String, source: String, due: Long?, snapshot: CampusSnapshot, tag: String, onClick: () -> Unit) {
    val course = snapshot.courses.firstOrNull { it.id == courseId }
    ZenRow(Modifier.testTag(tag), onClick) {
        if (course != null) CourseBadge(course, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(source.takeIf { it.isNotBlank() }, course?.title).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DueChip(due)
    }
}

@Composable
private fun BriefVipsRow(sheet: VipsSheet, due: Long?, snapshot: CampusSnapshot, onAll: () -> Unit) {
    val words = LocalWords.current
    val reader = LocalVipsReader.current
    val course = snapshot.courses.firstOrNull { it.id == sheet.courseId }
    ZenRow(Modifier.testTag("vips-sheet-${sheet.id}"), { if (reader != null) reader(sheet.courseId, sheet.sourceUrl, sheet.title) else onAll() }) {
        if (course != null) CourseBadge(course, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(sheet.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(vipsEndText(sheet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sheet.startsAt?.let { it > LocalNow.current } == true) SmallLabel("${words.tool(if (sheet.kind == VipsKind.EXAM) "exam_opens" else "starts")} ${sheet.starts}")
            if (!sheet.datesVerified) SmallLabel(words.tool("date_unverified"), MaterialTheme.colorScheme.error)
            if (sheet.kind == VipsKind.EXAM) SmallLabel(words.tool("exam_timer"))
            Text(listOfNotNull("Vips", course?.title).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DueChip(due)
    }
}

/** Today's coursework summary: the next deadlines in one grouped list, with coverage gaps kept visible. */
@Composable
fun CourseworkBrief(snapshot: CampusSnapshot, sync: SyncStatus, onAll: () -> Unit, onTask: (String, String) -> Unit, limit: Int = 3) {
    val words = LocalWords.current
    val now = LocalNow.current
    val all = courseworkAttention(snapshot, now)
    val attention = all.take(limit)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(if (all.isEmpty()) words.ui("due_soon") else "${words.ui("due_soon")} · ${all.size} ${words.ui("open_items")}",
            words.study("all"), onAll)
        ZenGroup(Modifier.testTag("due-soon")) {
            attention.forEachIndexed { index, item ->
                if (index > 0) ZenDivider()
                item.task?.let { task -> DueRow(task.courseId, task.title, words.action("courseware_source"), task.due, snapshot, "task-${task.id}") { onTask(task.courseId, task.id) } }
                item.folder?.let { folder -> DueRow(folder.courseId, folder.name, words.action("personal"), item.due, snapshot, "due-folder-${folder.id}") { onTask(folder.courseId, "folder:${folder.id}") } }
                item.sheet?.let { sheet -> BriefVipsRow(sheet, item.due, snapshot, onAll) }
            }
            if (attention.isEmpty()) Text(words.study(if (sync.active.any { it.startsWith("tasks:") || it.startsWith("upload-folders:") || it.startsWith("tools:") || it.startsWith("vips:") }) "loading"
                else if (snapshot.coursework.size == snapshot.courses.size && snapshot.courses.isNotEmpty() && snapshot.vipsDatesChecked(sync.errors, sync.active)) "week_clear" else "not_loaded"),
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CourseworkCoverage(snapshot, sync, brief = true)
    }
}

@Composable
fun CourseworkScreen(snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus,
    onTask: (String, String) -> Unit, onCourse: (String) -> Unit) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    var submitted by rememberSaveable { mutableStateOf(false) }
    // Coursework for every course comes from the Study sync; this screen only reads it.
    LaunchedEffect(repository, submitted, snapshot.preferences.semesterId, snapshot.courses.map { it.id }) {
        if (submitted && snapshot.courses.isNotEmpty()) repository.refreshSubmittedMaterials()
    }
    val tasks = snapshot.coursework.values.flatten().filter { it.submitted == submitted }.orderedCoursework()
    val vips = if (submitted) emptyList() else snapshot.listedVipsSheets()
    val dated = (tasks.map { CourseworkAttention(it.due, task = it) } + vips.map { CourseworkAttention(it.endsAt, sheet = it) })
        .sortedBy { it.due ?: Long.MAX_VALUE }
    LazyColumn(Modifier.fillMaxSize().testTag("coursework-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ScreenTitle(words.study("coursework"), words.study("body"))
            snapshot.semesters.firstOrNull { it.id == snapshot.preferences.semesterId }?.let { Spacer(Modifier.height(8.dp)); SmallLabel(it.title) }
            Spacer(Modifier.height(14.dp))
            CourseworkFilters(submitted) { submitted = it }
            Spacer(Modifier.height(10.dp))
            CourseworkCoverage(snapshot, sync)
        }
        if (!submitted && dated.isEmpty() && snapshot.coursework.isNotEmpty()) item {
            EmptyMoment(words.study(if (submitted) "empty_submitted_loaded" else "empty_loaded"), words.study("empty_body"), Symbol.CHECK)
        }
        if (dated.isNotEmpty()) item(key = "coursework-dated") { Column {
            dated.forEachIndexed { index, item ->
                val position = GroupPosition.of(index, dated.size)
                item.task?.let { task -> CourseworkRow(task, snapshot.courses.firstOrNull { it.id == task.courseId }, position) { onTask(task.courseId, task.id) } }
                item.sheet?.let { sheet -> VipsSheetRow(sheet, snapshot.courses.firstOrNull { it.id == sheet.courseId }, position) { onCourse(sheet.courseId) } }
            }
        } }
        if (submitted) {
            item {
                val words = LocalWords.current
                SmallLabel(words.ui("only_accessible_files_identified_as"))
                if (snapshot.courses.any { CacheKeys.courseFiles(it.id) in sync.errors })
                    SmallLabel(words.ui("some_file_lists_could_not_be_loaded"), MaterialTheme.colorScheme.error)
                if (snapshot.courses.any { CacheKeys.courseFiles(it.id) in sync.active && it.id !in snapshot.courseMaterials })
                    SmallLabel(words.ui("checking_files"))
            }
            val files = snapshot.courseMaterials.flatMap { (courseId, materials) ->
                materials.ownUploads(snapshot.account.id).map { courseId to it }
            }.sortedByDescending { it.second.activityAt }
            if (files.isNotEmpty()) item { SectionTitle(words.ui("uploaded_files")) }
            if (files.isNotEmpty()) item(key = "submitted-files") { Column {
                files.forEachIndexed { index, (courseId, file) ->
                    ZenListItem(GroupPosition.of(index, files.size), onClick = { repository.requestMaterial(file) }) {
                        ZenIconTile(Symbol.FILE)
                        ZenListText(file.name, if (file.activityAt > 0) words.formatDate(campusToday(file.activityAt)) else "",
                            overline = snapshot.courses.firstOrNull { it.id == courseId }?.title.orEmpty(), modifier = Modifier.weight(1f))
                    }
                }
            } }
            val vipsCourses = snapshot.courses.filter { snapshot.courseTools[it.id]?.hasVips == true }
            if (vipsCourses.isNotEmpty()) item { SectionTitle(words.ui("vips_results")) }
            items(vipsCourses, key = { "submitted-vips:${it.id}" }) { course ->
                VipsResultsPanel(course, snapshot, sync, { _ -> onCourse(course.id) }) { scope.launch { repository.refreshCourseSubmitted(course.id, true) } }
            }
            if (dated.isEmpty() && files.isEmpty() && vipsCourses.isEmpty() && snapshot.coursework.isNotEmpty()) item {
                EmptyMoment(words.study("empty_submitted_loaded"), words.study("empty_body"), Symbol.CHECK)
            }
        }
        if (!submitted && snapshot.assignmentFolders.values.any { it.isNotEmpty() }) {
            item { SectionTitle(words.action("folders")) }
            item(key = "coursework-folders") { Column {
                val folders = snapshot.assignmentFolders.values.flatten()
                folders.forEachIndexed { index, folder ->
                    AssignmentFolderRow(folder, snapshot, GroupPosition.of(index, folders.size)) { onTask(folder.courseId, "folder:${folder.id}") }
                }
            } }
        }
        if (!submitted) snapshot.courses.filter { (snapshot.courseTools[it.id]?.hasVips == true || it.id in snapshot.vipsSheets) &&
            (it.id !in snapshot.vipsSheets || snapshot.vipsSheets[it.id]?.sheets?.isEmpty() == true || CacheKeys.vips(it.id) in sync.errors) }.forEach { course ->
            item(key = CacheKeys.vips(course.id)) {
                ZenPanel(Modifier.fillMaxWidth().testTag("coursework-vips-${course.id}"), { onCourse(course.id) }) {
                    SmallLabel(words.tool("sheets"))
                    Spacer(Modifier.height(6.dp))
                    Text(course.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp)); SmallLabel(words.tool(when {
                        CacheKeys.vips(course.id) in sync.errors -> "sheets_unavailable"
                        course.id !in snapshot.vipsSheets -> "sheets_loading"
                        else -> "sheets_empty"
                    }))
                }
            }
        }
        snapshot.courses.filter { it.id !in snapshot.coursework || sync.errors[CacheKeys.tasks(it.id)] != null }.forEach { course -> item(key = "coverage:${course.id}") {
            ZenPanel(Modifier.fillMaxWidth(), { onCourse(course.id) }) {
                Text(course.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                CourseworkState(course.id, snapshot, sync)
            }
        } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun CourseworkFilters(submitted: Boolean, onSelect: (Boolean) -> Unit) {
    val words = LocalWords.current
    ZenTabs(listOf(words.study("open"), words.study("submitted")), if (submitted) 1 else 0, { onSelect(it == 1) },
        tags = listOf("tasks-open", "tasks-submitted"))
}

@Composable
fun CourseworkState(courseId: String, snapshot: CampusSnapshot, sync: SyncStatus) {
    val words = LocalWords.current
    val error = sync.errors[CacheKeys.tasks(courseId)]
    val key = when {
        courseId !in snapshot.coursework && CacheKeys.tasks(courseId) in sync.active -> "loading"
        error in setOf(FailureKind.NOT_FOUND, FailureKind.PERMISSION) -> "unsupported_body"
        error != null -> "unavailable_body"
        courseId !in snapshot.coursework -> "not_loaded"
        else -> null
    }
    if (key != null) SmallLabel(words.study(key))
    snapshot.updatedAt[CacheKeys.tasks(courseId)]?.let { updated ->
        SmallLabel("${words.study("updated")} ${words.age(updated, LocalNow.current)}")
    }
}

fun LazyListScope.courseworkItems(course: Course, snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus,
    submitted: Boolean, onSubmitted: (Boolean) -> Unit, onTask: (String, String) -> Unit, onOpenUrl: (String) -> Unit,
    onToolsRetry: () -> Unit) {
    item {
        CourseworkFilters(submitted, onSubmitted)
        Spacer(Modifier.height(8.dp))
        CourseworkState(course.id, snapshot, sync)
        Spacer(Modifier.height(8.dp)); SmallLabel(LocalWords.current.study("scope"))
    }
    val tasks = snapshot.coursework[course.id]?.filter { it.submitted == submitted }?.orderedCoursework()
    if (!submitted && tasks?.isEmpty() == true) item {
        val words = LocalWords.current
        EmptyMoment(words.study(if (submitted) "empty_submitted" else "empty"), words.study("empty_body"), Symbol.CHECK)
    }
    if (!tasks.isNullOrEmpty()) item(key = "course-tasks") { Column {
        tasks.forEachIndexed { index, task -> CourseworkRow(task, null, GroupPosition.of(index, tasks.size)) { onTask(course.id, task.id) } }
    } }
    if (submitted) {
        val uploads = snapshot.courseMaterials[course.id]?.ownUploads(snapshot.account.id).orEmpty()
        if (uploads.isNotEmpty()) item { SectionTitle(if (LocalWords.current.german) "Hochgeladene Dateien" else "Uploaded files") }
        if (uploads.isNotEmpty()) item(key = "course-uploads") { Column {
            uploads.forEachIndexed { index, file ->
                ZenListItem(GroupPosition.of(index, uploads.size), onClick = { repository.requestMaterial(file) }) {
                    ZenIconTile(Symbol.FILE); ZenListText(file.name, modifier = Modifier.weight(1f))
                }
            }
        } }
        if (snapshot.courseTools[course.id]?.hasVips == true || course.id in snapshot.vipsSheets || course.id in snapshot.vipsResults) item {
            val scope = rememberCoroutineScope()
            SectionTitle(if (LocalWords.current.german) "Vips-Ergebnisse" else "Vips results")
            Spacer(Modifier.height(8.dp))
            VipsResultsPanel(course, snapshot, sync, onOpenUrl) { scope.launch { repository.refreshCourseSubmitted(course.id, true) } }
        } else item {
            val words = LocalWords.current
            val scope = rememberCoroutineScope()
            val toolsKey = CacheKeys.tools(course.id)
            SectionTitle(words.ui("vips_results"))
            when {
                toolsKey in sync.active -> SmallLabel(words.ui("checking_course_tools"))
                toolsKey in sync.errors -> ZenNotice(words.failure(sync.errors.getValue(toolsKey)), Tone.DANGER, words.text("retry"),
                    { scope.launch { repository.refreshCourseSubmitted(course.id, true) } })
                snapshot.courseTools[course.id] == null -> SmallLabel(words.ui("course_tools_have_not_been_checked_y"))
                else -> SmallLabel(words.ui("no_vips_tool_found_in_this_course"))
            }
        }
    }
    if (!submitted && snapshot.assignmentFolders[course.id]?.isNotEmpty() == true) {
        item { SectionTitle(LocalWords.current.action("folders")) }
        item(key = "course-folders") { Column {
            val folders = snapshot.assignmentFolders[course.id].orEmpty()
            folders.forEachIndexed { index, folder ->
                AssignmentFolderRow(folder, snapshot, GroupPosition.of(index, folders.size)) { onTask(course.id, "folder:${folder.id}") }
            }
        } }
    }
    courseToolsItems(course, snapshot, sync, onOpenUrl, onToolsRetry)
    item {
        val words = LocalWords.current
        ZenLink(words.study("official"), { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else "$DIGICAMPUS_ORIGIN/dispatch.php/course/courseware/tasks?cid=${course.id.encodeURLParameter()}") }, external = true)
    }
}

@Composable
private fun VipsResultsPanel(course: Course, snapshot: CampusSnapshot, sync: SyncStatus, fallback: (String) -> Unit, retry: () -> Unit) {
    val words = LocalWords.current
    val reader = LocalVipsReader.current
    val results = snapshot.vipsResults[course.id]
    val error = sync.errors[CacheKeys.vipsResults(course.id)]
    ZenPanel(Modifier.fillMaxWidth().testTag("vips-results-${course.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CourseBadge(course, 28.dp)
            Text(course.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        if (results == null && error == null) SmallLabel(words.ui("loading"))
        if (error != null) ZenNotice(words.failure(error), Tone.DANGER, words.text("retry"), retry)
        if (results != null && results.isEmpty() && error == null) SmallLabel(words.ui("no_released_results"))
        results.orEmpty().forEach { result ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { if (reader != null) reader(course.id, result.sourceUrl, result.title) else fallback(result.sourceUrl) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(result.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (result.points.isNotBlank()) ToneChip(result.points, Tone.PRIMARY)
                RowChevron()
            }
        }
        ZenLink(words.ui("results_in_digicampus"), { if (reader != null) reader(course.id, vipsResultsUrl(course.id), course.title) else fallback(vipsResultsUrl(course.id)) }, external = true)
    }
}

@Composable
fun CourseworkTaskScreen(courseId: String, taskId: String, snapshot: CampusSnapshot, repository: CampusRepository,
    sync: SyncStatus, onCourse: (String) -> Unit, onOpenUrl: (String) -> Unit) {
    val words = LocalWords.current
    val now = LocalNow.current
    val scope = rememberCoroutineScope()
    var confirming by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(courseId, taskId) {
        repository.refreshCourseworkDetail(courseId, taskId)
        repository.refreshPeerReviews(courseId, taskId)
    }
    val detail = snapshot.courseworkDetails[taskId]?.takeIf { it.task.courseId == courseId }
    val task = detail?.task ?: snapshot.coursework[courseId]?.firstOrNull { it.id == taskId }
    val course = snapshot.courses.firstOrNull { it.id == courseId }
    val key = CacheKeys.task(courseId, taskId)
    val writeKey = "write:task:$taskId"
    val write = snapshot.courseworkWrites[writeKey]
    val writing = writeKey in sync.active
    val unresolved = write?.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN)
    val official = { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else task?.sourceUrl?.takeIf(String::isNotBlank) ?: courseTasksUrl(courseId)) }
    LazyColumn(Modifier.fillMaxSize().testTag("task-detail-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (task == null) item {
            if (key in sync.active) LoadingCards() else EmptyMoment(words.study("unavailable"), words.study("unavailable_body"))
        } else {
            item {
                SmallLabel(words.study(if (snapshot.account.demo) "preview" else "coursework"))
                Spacer(Modifier.height(12.dp))
                Text(task.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                Spacer(Modifier.height(12.dp))
                if (course != null) ZenLink(course.title, { onCourse(courseId) })
            }
            item {
                ZenPanel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f)) {
                    Text(words.taskDue(task, now), style = MaterialTheme.typography.titleMedium)
                    if (task.overdue(now)) { Spacer(Modifier.height(6.dp)); SmallLabel(words.study("overdue"), MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(14.dp))
                    SmallLabel(words.study("status"))
                    Text(words.study(if (task.submitted) "submitted" else "not_submitted"), style = MaterialTheme.typography.bodyLarge)
                    val renewalKey = when (task.renewal) { "granted" -> "extension"; "pending" -> "extension_pending"; "declined" -> "extension_declined"; else -> null }
                    if (renewalKey != null) { Spacer(Modifier.height(12.dp)); SmallLabel(words.study(renewalKey)) }
                    if (task.renewal == "granted" && task.originalDue != null) {
                        SmallLabel("${words.study("original_due")} · ${words.formatDate(campusToday(task.originalDue))} · ${words.time(task.originalDue)}")
                    }
                }
            }
            if (sync.errors[key] != null) item { SmallLabel(words.study("unavailable_body")) }
            if (detail == null && key in sync.active) item { LoadingCards() }
            if (detail?.sections?.isNotEmpty() == true) {
                item { SectionTitle(words.study("instructions")) }
                itemsIndexed(detail.sections) { _, section ->
                    if (section.title.isNotBlank()) Text(section.title, style = MaterialTheme.typography.titleMedium)
                    ReadingText(section.text)
                }
            }
            if (detail?.hasInteractiveContent == true || detail?.sections.isNullOrEmpty()) item {
                SmallLabel(words.study(if (detail?.hasInteractiveContent == true) "interactive" else "no_content"))
            }
            if (!detail?.feedback.isNullOrBlank()) item {
                SectionTitle(words.ui("feedback"))
                Spacer(Modifier.height(8.dp)); ReadingText(detail!!.feedback)
            }
            snapshot.peerReviews[CacheKeys.peerReviews(courseId, taskId)]?.takeIf { it.isNotEmpty() }?.let { reviews ->
                item { SectionTitle(words.ui("peer_feedback")) }
                items(reviews, key = { "peer-review:${it.id}" }) { review ->
                    ZenPanel(Modifier.fillMaxWidth()) {
                        SmallLabel(if (review.reviewer) { words.ui("your_review") }
                            else words.ui("received_review"))
                        if (!review.available) SmallLabel(words.ui("not_available_yet"))
                        if (review.available && review.sections.isEmpty()) SmallLabel(words.ui("no_readable_response_available"))
                        review.sections.forEach { section ->
                            if (section.title.isNotBlank()) Text(section.title, style = MaterialTheme.typography.titleMedium)
                            section.rating?.let { rating -> SmallLabel(when (rating) {
                                1 -> words.ui("good")
                                2 -> "OK"
                                else -> words.ui("weak")
                            }) }
                            if (section.text.isNotBlank()) ReadingText(section.text)
                        }
                        if (review.unsupported || review.reviewer || review.available && review.sections.isEmpty()) ZenLink(words.study("official"), official, external = true)
                    }
                }
            }
            task.progress?.let { progress -> item {
                SectionTitle(words.study("progress"))
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { (progress / 100).toFloat() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp)); SmallLabel("${progress.toInt()}% · ${words.study("progress_body")}")
            } }
            item {
                StudentWriteResult(write, writing, snapshot.account.demo, official, { scope.launch { repository.acknowledgeWrite(writeKey) } })
                sync.errors[writeKey]?.let { SmallLabel(words.failure(it)) }
                if (task.canSolve && !task.submitted && task.due?.let { it >= now } == true && !unresolved) {
                    ZenButton(words.action("submit"), { confirming = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("submit-task"), enabled = !writing, style = ButtonStyle.PRIMARY)
                    Spacer(Modifier.height(10.dp))
                }
                if (unresolved && !writing) ZenLink(words.action("check_status"), { scope.launch { repository.refreshCourseworkDetail(courseId, taskId, true) } })
                SmallLabel(words.study("official_body"))
                Spacer(Modifier.height(10.dp))
                ZenButton(words.study("official"), { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else task.sourceUrl) }, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, trailing = Symbol.EXTERNAL)
                (snapshot.updatedAt[key] ?: snapshot.updatedAt[CacheKeys.tasks(courseId)])?.let { Spacer(Modifier.height(10.dp)); SmallLabel("${words.study("updated")} ${words.age(it, now)}") }
            }
        }
    }
    if (confirming) ZenDialog(onDismissRequest = { confirming = false }, title = { Text(words.action("submit_title")) },
        text = { Column { Text(words.action("submit_body")); if (snapshot.account.demo) { Spacer(Modifier.height(8.dp)); SmallLabel(words.action("submit_preview")) } } },
        confirmButton = { DialogButton(words.action("submit"), { confirming = false; scope.launch { repository.submitTask(courseId, taskId) } }, primary = true) },
        dismissButton = { DialogButton(words.action("cancel"), { confirming = false }) })

}
