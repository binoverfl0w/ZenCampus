package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.study.PlannerRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ScreenTitle
import io.github.binoverfl0w.zencampus.ui.design.SectionTitle
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenChip
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenNotice
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import io.github.binoverfl0w.zencampus.ui.planner
import io.github.binoverfl0w.zencampus.ui.ui
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*

@Composable
internal fun PlannerChoice(label: String, selected: String, options: List<Pair<String, String>>, onChoice: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ZenChip("$label · $selected", { expanded = true }, trailing = Symbol.DOWN)
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (id, name) ->
            DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onChoice(id) })
        } }
    }
}

@Composable
internal fun PlannerFilterSheet(planner: PlannerSnapshot, repository: PlannerRepository, onGlobal: (String, String) -> Unit, onDismiss: () -> Unit) {
    val words = LocalWords.current; val scope = rememberCoroutineScope()
    var room by rememberSaveable { mutableStateOf(planner.settings.filter.room) }
    var teacher by rememberSaveable { mutableStateOf(planner.settings.filter.lecturer) }
    var query by rememberSaveable { mutableStateOf(planner.settings.filter.query) }
    var plans by rememberSaveable { mutableStateOf(planner.settings.filter.includePlans) }
    var name by rememberSaveable { mutableStateOf("") }
    val filter = PlannerFilter(room.trim(), teacher.trim(), query.trim(), plans)
    LazyColumn(Modifier.fillMaxWidth().testTag("planner-filter-sheet"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle(words.planner("filters"), "") }
        item { SmallLabel(words.planner("local_filter_scope")) }
        item { OutlinedTextField(room, { room = it }, Modifier.fillMaxWidth().testTag("planner-room-filter"), label = { Text(words.planner("room_filter")) }, singleLine = true) }
        item { OutlinedTextField(teacher, { teacher = it }, Modifier.fillMaxWidth().testTag("planner-teacher-filter"), label = { Text(words.planner("teacher_filter")) }, singleLine = true) }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(words.planner("course_filter")) }, singleLine = true) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(plans, { plans = it }); Text(words.planner("include_plans")) } }
        item { ZenButton(words.planner("apply"), { scope.launch { repository.settings { it.copy(filter = filter) }; onDismiss() } }, modifier = Modifier.fillMaxWidth().testTag("planner-apply-filter"), style = ButtonStyle.PRIMARY) }
        item { ZenLink(words.planner("global_rooms_action"), { onGlobal("rooms", room.trim()) }, modifier = Modifier.testTag("planner-global-room-search"))
            ZenLink(words.planner("global_lecturer_action"), { onGlobal("lecturer", teacher.trim()) }, modifier = Modifier.testTag("planner-global-lecturer-search")) }
        item { ZenLink(words.planner("clear"), { room = ""; teacher = ""; query = ""; plans = true }) }
        item { SectionTitle(words.planner("saved_filters")) }
        items(planner.savedFilters, key = { it.id }) { saved ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZenLink(saved.name, { scope.launch { repository.settings { it.copy(filter = saved.filter, publishedRooms = saved.publishedRooms,
                    overlays = saved.overlays, exploreOnly = saved.exploreOnly, overlayName = saved.overlayName, overlayCourseId = "", overlaySemesterId = "",
                    campusRoom = saved.campusRoom, lecturerResult = saved.lecturerResult) }; onDismiss() } }, modifier = Modifier.weight(1f))
                ZenLink(words.planner("remove"), { scope.launch { repository.removeFilter(saved.id) } })
            }
        }
        item { OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth().testTag("planner-filter-name"), label = { Text(words.planner("filter_name")) }, singleLine = true) }
        item { ZenLink(words.planner("save_filter"), { scope.launch { repository.saveFilter(name, filter) }; name = "" }, enabled = name.isNotBlank(), modifier = Modifier.testTag("planner-save-filter")) }
        item { ZenLink(words.planner("close"), onDismiss) }
    }
}

@Composable
internal fun PlannerToolsSheet(snapshot: CampusSnapshot, planner: PlannerSnapshot, repository: PlannerRepository,
    date: LocalDate, semesterId: String, entries: List<PlannerEntry>, onEvent: (PlannerEntry) -> Unit,
    onInspect: (Course, String) -> Unit, onDismiss: () -> Unit, onExport: () -> Unit = {}) {
    val words = LocalWords.current; val scope = rememberCoroutineScope()
    val week = weekDates(date)
    val clashes = plannerClashes(entries, week.first().atCampusStart(), week.last().nextCampusStart())
    val gaps = plannerGaps(entries.filterNot { it.explored }, date, planner.settings.workStart, planner.settings.workEnd, planner.settings.minimumGap)
    LazyColumn(Modifier.fillMaxWidth().testTag("planner-tools-sheet"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle(words.planner("tools"), "") }
        item {
            SectionTitle(words.ui("export_calendar"))
            SmallLabel(words.ui("export_calendar_short"))
            ZenLink(words.ui("export_calendar"), onExport, Modifier.testTag("planner-export-calendar"), symbol = Symbol.CALENDAR)
        }
        item { SectionTitle(words.planner("clashes")) }
        if (clashes.isEmpty()) item { SmallLabel(words.planner("no_clashes")) }
        items(clashes) { clash ->
            ZenPanel(Modifier.fillMaxWidth(), { onEvent(clash.first) }) {
                Text("${words.formatDate(campusToday(clash.from))} · ${words.time(clash.from)}–${words.time(clash.until)}")
                Text("${clash.first.event.title}\n${clash.second.event.title}")
            }
        }
        item { SectionTitle(words.planner("gaps")); SmallLabel(words.formatDate(date, true)); SmallLabel(words.planner("gaps_body")) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PlannerChoice(words.planner("start_hour"), "${planner.settings.workStart}:00", (0..23).map { it.toString() to "$it:00" }) { hour -> scope.launch { repository.settings { it.copy(workStart = hour.toInt()) } } }
            PlannerChoice(words.planner("end_hour"), "${planner.settings.workEnd}:00", (planner.settings.workStart + 1..24).map { it.toString() to "$it:00" }) { hour -> scope.launch { repository.settings { it.copy(workEnd = hour.toInt()) } } }
            PlannerChoice(words.planner("minimum_gap"), "${planner.settings.minimumGap} ${words.planner("minutes")}", listOf(15, 30, 45, 60, 90, 120).map { it.toString() to "$it ${words.planner("minutes")}" }) { minutes -> scope.launch { repository.settings { it.copy(minimumGap = minutes.toInt()) } } }
        } }
        if (gaps.isEmpty()) item { SmallLabel(words.planner("no_gaps")) }
        items(gaps) { gap -> Text("${words.time(gap.from)} – ${words.time(gap.until)} · ${(gap.until - gap.from) / 60_000} ${words.planner("minutes")}") }
        item { SectionTitle(words.planner("my_plans")); SmallLabel(words.planner("plans_body")) }
        val plans = planner.plans.filter { it.semesterId == semesterId }
        if (plans.isEmpty()) item { SmallLabel(words.planner("no_plans")) }
        items(plans, key = { it.course.id }) { plan ->
            ZenPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(plan.enabled, { enabled -> scope.launch { repository.togglePlan(plan.course.id, semesterId, enabled) } })
                    Text(plan.course.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
                SmallLabel("${words.planner("plan_saved_age")} · ${words.age(plan.capturedAt, LocalNow.current)}")
                FlowRow { ZenLink(words.planner("sessions"), { onInspect(plan.course, semesterId) })
                    ZenLink(words.planner("remove_plan"), { scope.launch { repository.removePlan(plan.course.id, semesterId) } }) }
            }
        }
        item { ZenLink(words.planner("close"), onDismiss) }
    }
}

@Composable
internal fun PlannerExplorer(snapshot: CampusSnapshot, planner: PlannerSnapshot, repository: PlannerRepository, sync: SyncStatus,
    initialCourse: String, initialSemester: String, initialMode: String, initialQuery: String, onPreview: (PlannerSchedule) -> Unit,
    onLecturerPreview: (LecturerCourses, Semester) -> Unit, onRoom: (String, Boolean) -> Unit, onCampusRoom: (CampusRoom) -> Unit,
    onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val words = LocalWords.current; val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var mode by rememberSaveable(initialMode) { mutableStateOf(initialMode) }
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var semesterId by rememberSaveable { mutableStateOf(initialSemester) }
    var selectedId by rememberSaveable { mutableStateOf(initialCourse) }
    var pageOffset by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberSaveable(mode, saver = LazyListState.Saver) { LazyListState() }
    val semester = snapshot.semesters.firstOrNull { it.id == semesterId } ?: currentSemester(snapshot.semesters, LocalNow.current)
    val actualSemester = semester?.id.orEmpty()
    val request = PlannerSearch(query, if (mode == "lecturer") PlannerSearchKind.LECTURER else PlannerSearchKind.COURSE, actualSemester, pageOffset)
    LaunchedEffect(query, mode, actualSemester) {
        pageOffset = 0
        if (mode != "rooms" && query.trim().length >= 3 && actualSemester.isNotBlank()) { delay(450); repository.search(request.copy(offset = 0)) }
        else if (mode == "rooms" && query.trim().length >= 3) { delay(450); repository.searchCampusRooms(query) }
    }
    LaunchedEffect(mode) { if (mode == "rooms") repository.refreshRooms() }
    val pages = (0..pageOffset step 20).mapNotNull { planner.pages[request.copy(offset = it).key] }
    val courses = pages.flatMap { it.courses }.distinctBy { it.id }
    val selectedCourse = (courses + planner.schedules.values.map { it.course } + planner.plans.map { it.course } + snapshot.courses).firstOrNull { it.id == selectedId }
    LaunchedEffect(selectedId, actualSemester, selectedCourse?.id, planner.ready) { if (selectedCourse != null && semester != null) repository.inspect(selectedCourse, semester) }
    if (selectedCourse != null && semester != null) {
        val key = plannerScheduleKey(selectedCourse.id, semester.id)
        val schedule = planner.schedules[key] ?: planner.plans.firstOrNull { it.course.id == selectedCourse.id && it.semesterId == semester.id }?.savedSchedule
        LazyColumn(Modifier.fillMaxWidth().testTag("planner-course-detail"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { ZenLink(words.planner("back"), { selectedId = "" }); ScreenTitle(selectedCourse.title, "") }
            if (key in sync.active && schedule == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            sync.errors[key]?.let { error -> item { SmallLabel(words.failure(error)) } }
            if (schedule == null) item { SmallLabel(words.planner("unavailable")) }
            else item { PlannerCourseOptions(schedule, planner, repository, { onPreview(schedule) }) }
            item { ZenLink(words.planner("refresh"), { scope.launch { repository.inspect(selectedCourse, semester, true) } })
                ZenLink(words.planner("official"), { onOpenUrl(selectedCourse.sourceUrl) }, external = true)
                ZenLink(words.planner("close"), onDismiss) }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("planner-explorer"), state = listState,
        contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("heading") { ScreenTitle(words.planner("explore"), "") }
        item("modes") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("course", "lecturer", "rooms").forEach { option ->
            ZenChip(words.planner(option), { mode = option; query = ""; selectedId = "" }, Modifier.testTag("planner-mode-$option"), selected = mode == option)
        } } }
        if (mode != "rooms") item("semester") {
            PlannerChoice(words.text("semester"), semester?.title ?: words.planner("all_semesters"), snapshot.semesters.map { it.id to it.title }) { semesterId = it; pageOffset = 0 }
        }
        item("search") { OutlinedTextField(query, { query = it.take(120); pageOffset = 0 }, Modifier.fillMaxWidth().testTag("planner-search-query"),
            label = { Text(words.planner(if (mode == "rooms") "room_query" else if (mode == "lecturer") "lecturer_hint" else "search_hint")) }, singleLine = true) }
        if (mode == "rooms") {
            item("room-scope") { SmallLabel(words.planner("global_room_scope")) }
            val roomKey = campusRoomSearchKey(query)
            val global = planner.campusRooms[roomKey]
            item("global-room-heading") { Text(words.planner("campus_rooms"), style = MaterialTheme.typography.titleMedium) }
            if (query.trim().length < 3) item("room-query-hint") { SmallLabel(words.planner("minimum_query")) }
            if (roomKey in sync.active) item("global-room-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            sync.errors[roomKey]?.let { error -> item("global-room-error") {
                ZenNotice(words.failure(error), Tone.DANGER, words.text("retry"), { scope.launch { repository.searchCampusRooms(query, true) } },
                    secondary = words.text("open_official"), secondaryExternal = true,
                    onSecondary = { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else campusRoomDirectoryUrl(query)) })
            } }
            if (global?.rooms?.isEmpty() == true) item("global-room-empty") {
                SmallLabel(words.planner(if (global.version < CAMPUS_ROOM_SEARCH_VERSION) "room_search_saved" else "no_rooms"))
                Text(words.planner("room_directory_gap"), Modifier.testTag("room-directory-gap"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ZenLink(words.text("open_official"), { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else campusRoomDirectoryUrl(query)) }, modifier = Modifier.testTag("room-directory-official"), external = true)
            }
            items(global?.rooms.orEmpty(), key = { "campus-room:${it.id}" }) { room ->
                ZenPanel(Modifier.fillMaxWidth().testTag("campus-room-${room.id}"), {
                    focus.clearFocus()
                    if (room.bookingUrl.isNotBlank()) onCampusRoom(room)
                    else onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else room.sourceUrl)
                }) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium)
                    if (room.description.isNotBlank()) SmallLabel(room.description)
                    room.seats?.let { SmallLabel("$it ${words.planner("seats")}") }
                    SmallLabel(words.planner(if (room.bookingUrl.isBlank()) "room_restricted" else "room_plan"))
                }
            }
            planner.updatedAt[roomKey]?.let { time -> item("global-room-age") { SmallLabel("${words.planner("loaded")} ${words.age(time, LocalNow.current)}") } }
            val known = snapshot.events.map { it.location }.filter { it.isNotBlank() && (query.isBlank() || campusRoomNameMatches(query, it)) }.distinct().sorted()
            if (known.isNotEmpty()) item("known-rooms") { Text(words.planner("known_rooms"), style = MaterialTheme.typography.titleMedium); SmallLabel(words.planner("own_room_notice")) }
            items(known, key = { "known-room:$it" }) { room -> ZenLink(room, { onRoom(room, false) }) }
            item("published-rooms") { Text(words.planner("published_rooms"), style = MaterialTheme.typography.titleMedium) }
            if (PlannerRepository.ROOMS in sync.active) item("rooms-loading") { SmallLabel(words.planner("room_loading")) }
            sync.errors[PlannerRepository.ROOMS]?.let { item("rooms-error") { SmallLabel(words.planner("room_error")); SmallLabel(words.failure(it)) } }
            planner.rooms?.let { rooms ->
                item("rooms-window") { SmallLabel("${words.planner("room_window")} · ${words.formatDate(campusToday(rooms.from))} – ${words.formatDate(campusToday(rooms.until - 1))}")
                    planner.updatedAt[PlannerRepository.ROOMS]?.let { SmallLabel("${words.planner("loaded")} · ${words.age(it, LocalNow.current)}") } }
                items(rooms.rooms.filter { campusRoomNameMatches(query, it.label) }, key = { "published-room:${it.id}" }) { room -> ZenLink(room.label, { onRoom(room.label, true) }) }
            }
            item("rooms-actions") { ZenLink(words.planner("refresh"), { scope.launch { repository.refreshRooms(true) } }); ZenLink(words.planner("room_source"), { onOpenUrl(PUBLISHED_ROOMS_URL) }, external = true) }
        } else {
            if (mode == "lecturer" && courses.isNotEmpty() && semester != null) item {
                val lecturerKey = lecturerCoursesKey(query, actualSemester)
                ZenButton(words.planner("lecturer_week"), {
                    focus.clearFocus()
                    val requestedQuery = query.trim()
                    val requestedSemesterChoice = semesterId
                    scope.launch {
                        val result = repository.prepareLecturer(requestedQuery, semester.id)
                        if (mode == "lecturer" && query.trim() == requestedQuery && semesterId == requestedSemesterChoice && result != null)
                            onLecturerPreview(result, semester)
                    }
                }, modifier = Modifier.fillMaxWidth().testTag("planner-preview-lecturer"), enabled = lecturerKey !in sync.active, style = ButtonStyle.PRIMARY)
                SmallLabel(words.planner("lecturer_scope"))
                if (lecturerKey in sync.active) LinearProgressIndicator(Modifier.fillMaxWidth())
                sync.errors[lecturerKey]?.let { SmallLabel(words.failure(it)) }
            }
            if (query.trim().length < 3) item { SmallLabel(words.planner("minimum_query")) }
            if (sync.active.any { it == request.key || it == request.copy(offset = 0).key }) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            sync.errors[request.key]?.let { error -> item { ZenNotice(words.failure(error), Tone.DANGER, words.text("retry"), { scope.launch { repository.search(request, true) } }) } }
            if (pages.isNotEmpty() && courses.isEmpty()) item { SmallLabel(words.planner("no_results")) }
            items(courses, key = { it.id }) { course ->
                ZenPanel(Modifier.fillMaxWidth().testTag("planner-result-${course.id}"), { focus.clearFocus(); selectedId = course.id }) {
                    if (course.number.isNotBlank()) SmallLabel(course.number)
                    Text(course.title, style = MaterialTheme.typography.titleMedium)
                    if (course.teacher.isNotBlank()) SmallLabel(course.teacher)
                    if (course.location.isNotBlank()) SmallLabel(course.location)
                    SmallLabel(words.planner("inspect"))
                }
            }
            if (pages.lastOrNull()?.hasMore == true) item {
                if (pageOffset < 500) ZenLink(words.planner("more"), { pageOffset += 20; scope.launch { repository.search(request.copy(offset = pageOffset)) } }, enabled = request.key !in sync.active && pages.lastOrNull()?.request?.offset == pageOffset)
                else SmallLabel(words.planner("refine"))
            }
        }
        item("close") { ZenLink(words.planner("close"), onDismiss) }
    }
}

@Composable
private fun PlannerCourseOptions(schedule: PlannerSchedule, planner: PlannerSnapshot, repository: PlannerRepository, onPreview: () -> Unit) {
    val words = LocalWords.current; val scope = rememberCoroutineScope()
    val groups = schedule.events.filterNot { it.cancelled }.groupBy(::plannerGroup).toList().sortedBy { it.second.first().start }
    val plan = planner.plans.firstOrNull { it.course.id == schedule.course.id && it.semesterId == schedule.semesterId }
    var selected by rememberSaveable(schedule.course.id, schedule.semesterId, groups.map { it.first }.joinToString()) { mutableStateOf(plan?.groups?.toList() ?: groups.map { it.first }) }
    var saved by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (schedule.course.teacher.isNotBlank()) Text(schedule.course.teacher)
        if (schedule.summaryOnly) SmallLabel(words.planner("summary"))
        if (schedule.unsupported > 0) SmallLabel(words.planner("unsupported"))
        planner.updatedAt[plannerScheduleKey(schedule.course.id, schedule.semesterId)]?.let { SmallLabel("${words.planner("loaded")} · ${words.age(it, LocalNow.current)}") }
        ZenButton(words.planner("preview"), onPreview, modifier = Modifier.fillMaxWidth().testTag("planner-preview-course"), enabled = schedule.events.isNotEmpty(), style = ButtonStyle.PRIMARY)
        Text(words.planner("sessions"), style = MaterialTheme.typography.titleMedium)
        SmallLabel(words.planner("sessions_body"))
        groups.forEach { (id, events) ->
            val event = events.first()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in selected, { checked -> selected = if (checked) selected + id else selected - id; saved = false })
                Column(Modifier.weight(1f)) {
                    Text("${words.shortDay(event.date)} · ${words.time(event.start)}–${words.time(event.end)}")
                    SmallLabel(event.location)
                    if (event.description.isNotBlank()) SmallLabel(event.description.lineSequence().first().take(120))
                }
            }
        }
        if (saved) Text(words.planner("saved_plan"), Modifier.testTag("planner-plan-saved"))
        ZenButton(words.planner("add_plan"), { scope.launch { repository.savePlan(schedule.course, schedule.semesterId, selected.toSet()); saved = true } }, modifier = Modifier.fillMaxWidth().testTag("planner-save-plan"), enabled = selected.isNotEmpty(), style = ButtonStyle.PRIMARY)
        if (plan != null) ZenLink(words.planner("remove_plan"), { scope.launch { repository.removePlan(plan.course.id, plan.semesterId); saved = false } })
    }
}
