package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.data.study.PlannerRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.CourseBadge
import io.github.binoverfl0w.zencampus.ui.design.CoursePinButton
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.EmptyMoment
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LoadingCards
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ScreenTitle
import io.github.binoverfl0w.zencampus.ui.design.SectionTitle
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.WeatherIcon
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenChip
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenOptionRow
import io.github.binoverfl0w.zencampus.ui.design.ZenSheet
import io.github.binoverfl0w.zencampus.ui.design.ZenTabs
import io.github.binoverfl0w.zencampus.ui.design.ZenTint
import io.github.binoverfl0w.zencampus.ui.group
import io.github.binoverfl0w.zencampus.ui.planner
import io.github.binoverfl0w.zencampus.ui.ui
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.*

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CoursesScreen(snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus, onCourse: (String) -> Unit,
    onOpenUrl: (String) -> Unit = {}) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var pinnedOnly by rememberSaveable { mutableStateOf(false) }
    var managingGroups by rememberSaveable { mutableStateOf(false) }
    var editingGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var showSemesters by remember { mutableStateOf(false) }
    val selectedSemester = snapshot.semesters.firstOrNull { it.id == snapshot.preferences.semesterId }
    val allGroups = remember(snapshot.courses, snapshot.preferences) { courseGroups(snapshot.courses, snapshot.preferences) }
    val groups = allGroups.mapNotNull { group ->
        val groupMatches = query.isBlank() || group.title.contains(query, ignoreCase = true)
        val members = group.courses.filter { course ->
            (!pinnedOnly || course.id in snapshot.preferences.pinnedCourses) &&
                (groupMatches || listOf(course.title, course.number, course.teacher, course.subtitle, course.typeName).any { it.contains(query, ignoreCase = true) })
        }
        group.copy(courses = members).takeIf { members.isNotEmpty() }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("courses-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp)) {
        item { ScreenTitle(words.text("your_courses"), words.text("courses_subtitle")); Spacer(Modifier.height(14.dp)) }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("courses-query"), placeholder = { Text(words.text("search_courses")) },
                leadingIcon = { ZenIcon(Symbol.SEARCH, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = { if (query.isNotEmpty()) IconAction(Symbol.CLOSE, words.text("close"), { query = "" }) },
                singleLine = true)
        }
        item {
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                ZenChip(words.text("pinned"), { pinnedOnly = !pinnedOnly }, Modifier.testTag("courses-pinned-filter"), symbol = Symbol.PIN, selected = pinnedOnly)
                IconAction(Symbol.GROUP, words.group("manage"), { editingGroup = null; managingGroups = true })
                ZenChip(selectedSemester?.title ?: words.text("all_semesters"), { showSemesters = true }, Modifier.widthIn(min = 180.dp).weight(1f), trailing = Symbol.NEXT)
            }
        }
        if (groups.isEmpty()) item {
            if (snapshot.courses.isEmpty() && sync.active.any { it.startsWith("courses:") }) LoadingCards()
            else EmptyMoment(if (query.isNotBlank() || pinnedOnly) words.text("no_results") else words.text("no_courses"),
                if (query.isNotBlank() || pinnedOnly) "" else words.text("no_courses_body"), Symbol.COURSES)
        }
        val isGroup: (CourseGroup) -> Boolean = { group -> group.automatic || snapshot.preferences.courseGroups.any { it.id == group.id } }
        itemsIndexed(groups, key = { _, group -> group.id }) { index, group ->
            if (isGroup(group)) Box(Modifier.padding(top = if (index > 0) 10.dp else 0.dp, bottom = 10.dp)) {
                CourseGroupCard(group, snapshot.preferences, onCourse,
                    { id -> scope.launch { repository.toggleCoursePin(id) } },
                    { editingGroup = group.id; managingGroups = true })
            } else {
                // Consecutive single classes read as one list; groups stand on their own.
                val previous = groups.getOrNull(index - 1)?.let { !isGroup(it) } == true
                val next = groups.getOrNull(index + 1)?.let { !isGroup(it) } == true
                val position = when { previous && next -> GroupPosition.MIDDLE; previous -> GroupPosition.LAST; next -> GroupPosition.FIRST; else -> GroupPosition.SINGLE }
                val course = group.courses.single()
                CourseCard(course, course.id in snapshot.preferences.pinnedCourses,
                    { onCourse(course.id) }, { scope.launch { repository.toggleCoursePin(course.id) } }, position)
            }
        }
        if (query.isBlank() && !pinnedOnly) item(key = "study-groups") { StudyGroupSuggestions(snapshot, repository, onOpenUrl) }
    }
    if (managingGroups) CourseGroupsDialog(snapshot.courses, snapshot.preferences, allGroups, repository, editingGroup,
        onDismiss = { managingGroups = false })
    if (showSemesters) ZenDialog(onDismissRequest = { showSemesters = false },
        title = { Text(words.text("semester")) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                (listOf(Semester("", words.text("all_semesters"), 0, 0)) + snapshot.semesters).forEach { semester ->
                    ZenOptionRow(semester.title, semester.id == snapshot.preferences.semesterId,
                        { showSemesters = false; scope.launch { repository.selectSemester(semester.id) } })
                }
            }
        },
        confirmButton = { DialogButton(words.text("close"), { showSemesters = false }) })
}

@Composable
private fun CourseCard(course: Course, pinned: Boolean, onClick: () -> Unit, onPin: () -> Unit, position: GroupPosition = GroupPosition.SINGLE) {
    ZenListItem(position, Modifier.testTag("course-card-${course.id}"), onClick) {
        CourseBadge(course, 40.dp)
        ZenListText(course.title, listOf(course.subtitle, course.teacher).filter { it.isNotBlank() }.joinToString(" · "),
            modifier = Modifier.weight(1f), overline = course.number)
        CoursePinButton(course.id, pinned, onPin)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus, jump: String,
    onCourse: (String) -> Unit, weatherLoading: Boolean, weatherOnline: Boolean, onOpenUrl: (String) -> Unit) {
    val words = LocalWords.current
    val now = LocalNow.current
    val scope = rememberCoroutineScope()
    val planner by repository.planner.snapshot.collectAsState()
    val plannerSync by repository.planner.sync.collectAsState()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    var exploreMode by rememberSaveable { mutableStateOf("course") }
    var exploreQuery by rememberSaveable { mutableStateOf("") }
    var inspectCourse by rememberSaveable { mutableStateOf("") }
    var inspectSemester by rememberSaveable { mutableStateOf("") }
    var restoredDate by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var selected by rememberSaveable(jump) { mutableStateOf(jump.ifBlank { campusToday(now).toString() }) }
    val date = LocalDate.parse(selected)
    val week = weekDates(date)
    LaunchedEffect(week.first(), snapshot.weather?.observedAt, weatherOnline, now / 300_000L) {
        repository.refreshWeather(visible = true, dates = week)
    }
    val dayStart = date.atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
    val calendarUpdated = snapshot.updatedAt.filter { (key, _) ->
        val from = key.takeIf { it.startsWith("personal:") || it.startsWith("schedule:") }?.substringAfter(':')?.toLongOrNull()
        from != null && dayStart >= from && date < Instant.fromEpochMilliseconds(from).toLocalDateTime(CAMPUS_ZONE).date.plus(CALENDAR_WINDOW_DAYS, DateTimeUnit.DAY)
    }.values.maxOrNull()
    val semester = snapshot.semesters.firstOrNull { dayStart >= it.start && dayStart < it.end }
        ?: snapshot.semesters.firstOrNull { it.id == snapshot.preferences.semesterId }
    val semesterId = semester?.id.orEmpty()
    val ownAndPlanned = plannerEntries(snapshot, planner, semesterId)
    val overlay = planner.schedule(planner.settings.overlayCourseId, planner.settings.overlaySemesterId)
    val overlaySchedules = if (planner.settings.overlays.isEmpty()) listOfNotNull(overlay)
        else planner.settings.overlays.mapNotNull { planner.schedule(it.course.id, it.semesterId) }
    val extra = overlaySchedules.flatMap { schedule -> schedule.events.map { PlannerEntry(it, schedule.course.teacher, tentative = true, summaryOnly = schedule.summaryOnly, explored = true) } }
    LaunchedEffect(planner.settings.overlays, now / 300_000L) {
        kotlinx.coroutines.coroutineScope { planner.settings.overlays.mapNotNull { item ->
            snapshot.semesters.firstOrNull { it.id == item.semesterId }?.let { term -> launch { repository.planner.inspect(item.course, term, speculative = true) } }
        }.forEach { it.join() } }
    }
    val campusRoom = planner.settings.campusRoom
    val campusRoomKey = campusRoom?.let { campusRoomWeekKey(it.id, week.first()) }
    val campusRoomPlan = planner.roomWeeks[campusRoomKey]
    LaunchedEffect(campusRoom, week.first(), now / 300_000L) {
        if (campusRoom != null) repository.planner.roomWeek(campusRoom, week.first())
    }
    val rooms = if (planner.settings.publishedRooms) planner.rooms?.entries.orEmpty() else emptyList()
    val base = if (planner.settings.exploreOnly) ownAndPlanned.filter { entry -> planner.settings.overlays.any { it.course.id == entry.event.courseId } } else ownAndPlanned
    val allEntries = (if (campusRoom != null) campusRoomPlan?.entries.orEmpty() else base + extra + rooms)
        .distinctBy { "${it.event.courseId}:${it.event.start}:${it.event.end}:${it.event.location}:${it.roomId}" }
    val filtered = filterPlanner(allEntries, planner.settings.filter)
    val events = filtered.filter { it.event.occursOn(date) }
    val weekEntries = filtered.filter { entry -> week.any(entry.event::occursOn) }
    val planningEntries = (plannerEntries(snapshot, planner.copy(settings = planner.settings.copy(filter = planner.settings.filter.copy(includePlans = true))), semesterId) + extra)
        .distinctBy { "${it.event.courseId}:${it.event.start}:${it.event.end}" }
    LaunchedEffect(planner.ready) {
        if (planner.ready && !restoredDate) {
            if (jump.isBlank()) runCatching { LocalDate.parse(planner.settings.selectedDate) }.getOrNull()?.let { selected = it.toString() }
            restoredDate = true
        }
    }
    LaunchedEffect(planner.settings.zoom) { zoom = planner.settings.zoom }
    LaunchedEffect(zoom) { kotlinx.coroutines.delay(300); if (zoom != planner.settings.zoom) repository.planner.settings { it.copy(zoom = zoom) } }
    LaunchedEffect(selected, restoredDate) { if (restoredDate) { kotlinx.coroutines.delay(200); repository.planner.settings { it.copy(selectedDate = selected) } } }
    LaunchedEffect(semesterId, planner.plans.map { it.course.id }, planner.settings.filter.lecturer) {
        if (semester != null) {
            val wanted = planner.plans.filter { it.semesterId == semesterId && it.enabled }.map { it.course } +
                if (planner.settings.filter.lecturer.isNotBlank()) snapshot.courses else emptyList()
            kotlinx.coroutines.coroutineScope { wanted.distinctBy { it.id }.map { course -> launch { repository.planner.inspect(course, semester) } }.forEach { it.join() } }
        }
    }
    LaunchedEffect(planner.settings.publishedRooms, week.first()) { if (planner.settings.publishedRooms) repository.planner.refreshRooms() }
    val windowStart = week.first().atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
    val loading = sync.active.any { it == "personal:$windowStart" || it == "schedule:$windowStart" }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(week.first(), now / 300_000L, campusRoom, planner.settings.exploreOnly) {
        if (campusRoom == null && !planner.settings.exploreOnly) repository.preparePlannerWeek(date)
    }
    val calendarTitle = when {
        campusRoom != null -> words.planner("room_timetable_title")
        planner.settings.lecturerResult != null -> words.planner("teaching_week_title")
        else -> words.text("your_week")
    }
    LazyColumn(Modifier.fillMaxSize().testTag("planner-calendar"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { ScreenTitle(calendarTitle, if (planner.settings.view == "day" && campusRoom == null && planner.settings.lecturerResult == null) words.text("calendar_subtitle") else "") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val views = listOf("day", "week")
                ZenTabs(views.map { words.planner(it) }, views.indexOf(planner.settings.view).coerceAtLeast(0),
                    { index -> scope.launch { repository.planner.settings { it.copy(view = views[index]) } } }, tags = views.map { "planner-view-$it" })
                ZenChip(words.planner("explore"), { inspectCourse = ""; inspectSemester = semesterId; exploreMode = "course"; exploreQuery = ""; sheet = "explore" }, Modifier.testTag("planner-explore"), symbol = Symbol.SEARCH)
                ZenChip(words.planner("filters"), { sheet = "filters" }, Modifier.testTag("planner-filters"))
                ZenChip(words.planner("tools"), { sheet = "tools" }, Modifier.testTag("planner-tools"))
                if (planner.settings.view == "week") ZenLink(words.text("this_week"), { selected = campusToday(now).toString() })
            }
            if (planner.settings.filter != PlannerFilter()) SmallLabel(listOf(planner.settings.filter.room, planner.settings.filter.lecturer, planner.settings.filter.query).filter { it.isNotBlank() }.joinToString(" · "))
            if (overlay != null || planner.settings.overlays.isNotEmpty() || planner.settings.lecturerResult != null) {
                SmallLabel("${words.planner(if (planner.settings.exploreOnly) "lecturer_preview" else "overlay")} · ${planner.settings.overlayName.ifBlank { overlay?.course?.title.orEmpty() }}")
                if (planner.settings.overlays.isNotEmpty()) SmallLabel("${words.planner("coverage")} · ${overlaySchedules.size}/${planner.settings.overlays.size}")
                if (planner.settings.overlays.any { plannerScheduleKey(it.course.id, it.semesterId) in plannerSync.active }) LinearProgressIndicator(Modifier.fillMaxWidth())
                planner.settings.lecturerResult?.let { result ->
                    SmallLabel("${words.planner(if (result.complete) "lecturer_complete" else "lecturer_partial")} · ${result.courses.size}${result.total?.let { "/$it" }.orEmpty()}")
                    if (!result.complete) SmallLabel(words.planner("refine"))
                    if (planner.settings.overlays.any { plannerScheduleKey(it.course.id, it.semesterId) in plannerSync.errors }) SmallLabel(words.planner("lecturer_unavailable"))
                    planner.updatedAt[lecturerCoursesKey(result.query, result.semesterId)]?.let { SmallLabel("${words.planner("loaded")} ${words.age(it, now)}") }
                }
                if (overlaySchedules.any { it.summaryOnly }) SmallLabel(words.planner("summary"))
                if (overlaySchedules.any { it.unsupported > 0 }) SmallLabel(words.planner("unsupported"))
                ZenLink(words.planner("clear_overlay"), { scope.launch { repository.planner.settings { it.copy(overlayCourseId = "", overlaySemesterId = "", overlays = emptyList(), exploreOnly = false, overlayName = "", lecturerResult = null) } } })
            }
            if (overlaySchedules.none { it.summaryOnly } && weekEntries.any { it.summaryOnly }) SmallLabel(words.planner("summary"))
            if (planner.settings.filter.room.isNotBlank()) {
                SmallLabel(words.planner(if (planner.settings.publishedRooms) "room_scope" else "own_room_notice"))
                ZenLink(words.planner("clear_room"), { scope.launch { repository.planner.settings { it.copy(filter = it.filter.copy(room = ""), publishedRooms = false) } } })
            }
        }
        if (campusRoom != null) item(key = "campus-room-status") {
            CampusRoomStatus(campusRoom, campusRoomPlan, campusRoomKey.orEmpty(), planner, plannerSync, date, nowMillis(),
                onRefresh = { scope.launch { repository.planner.roomWeek(campusRoom, week.first(), true) } },
                onClose = { scope.launch { repository.planner.settings { it.copy(campusRoom = null, filter = PlannerFilter(), exploreOnly = false) } } },
                onOpenUrl = { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else it) })
        }
        item {
            Row(Modifier.fillMaxWidth().pointerInput(selected) {
                var distance = 0f
                detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
                    if (kotlin.math.abs(distance) > 60.dp.toPx()) selected = date.plus(if (distance < 0) 7 else -7, DateTimeUnit.DAY).toString()
                }) { change, amount -> distance += amount; change.consume() }
            }, verticalAlignment = Alignment.CenterVertically) {
                IconAction(Symbol.BACK, words.text("previous_week"), { selected = date.plus(-7, DateTimeUnit.DAY).toString() })
                Text("${words.formatDate(week.first())} – ${words.formatDate(week.last())}", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconAction(Symbol.NEXT, words.text("next_week"), { selected = date.plus(7, DateTimeUnit.DAY).toString() })
            }
            if (planner.settings.view == "day") {
            Row(Modifier.fillMaxWidth().testTag("calendar-week-days"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    val chosen = day == date
                    val forecast = snapshot.weather?.day(day)
                    val ink = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    val muted = if (chosen) ink.copy(alpha = .8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    Surface(onClick = { selected = day.toString() }, shape = RoundedCornerShape(17.dp),
                        color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.weight(1f).semantics { contentDescription = words.formatDate(day, true); this.selected = chosen }) {
                        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(words.shortDay(day), style = MaterialTheme.typography.labelSmall,
                                color = muted, maxLines = 1)
                            Text(day.day.toString(), style = MaterialTheme.typography.titleMedium, color = ink)
                            Box(Modifier.size(4.dp).background(
                                if (day == campusToday(now)) (if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                                else if (snapshot.events.any { it.occursOn(day) }) MaterialTheme.colorScheme.outline.copy(alpha=.5f)
                                else MaterialTheme.colorScheme.surface.copy(alpha=0f), CircleShape))
                            if (forecast != null) Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.semantics {
                                    contentDescription = "${words.weatherCondition(forecast.weatherCode)}, ${words.text("high")} ${forecast.maxC.roundToInt()}°C, ${words.text("low")} ${forecast.minC.roundToInt()}°C"
                                }) {
                                WeatherIcon(forecast.weatherCode, Modifier.size(18.dp), ink)
                                Text("${forecast.maxC.roundToInt()}°", style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
                                Text("${forecast.minC.roundToInt()}°", style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
                            } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { Text("—", style = MaterialTheme.typography.labelMedium, color = muted) }
                                Text("—", style = MaterialTheme.typography.labelMedium, color = muted)
                                Text("—", style = MaterialTheme.typography.labelSmall, color = muted)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(words.text("daily_weather"), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                ZenLink(words.text("this_week"), { selected = campusToday(now).toString() })
            }
            val forecast = snapshot.weather?.day(date)
            if (forecast != null) Text(words.weatherCondition(forecast.weatherCode) +
                forecast.precipitationProbability?.let { " · ${words.text("precipitation_chance")} $it%" }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CalendarWeatherStatus(week, snapshot.weather, now, weatherLoading) { scope.launch { repository.refreshWeather(force = true) } }
            Spacer(Modifier.height(8.dp))
            WeatherFooter(snapshot.weather, snapshot.account.demo, onOpenUrl)
            Spacer(Modifier.height(8.dp))
            if (calendarUpdated != null && campusRoom == null && !planner.settings.exploreOnly) SmallLabel("${words.text("schedule_updated")} ${words.age(calendarUpdated, now)}")
            }
        }
        item(key = "term-and-holidays") {
            // Semester milestones and holidays in the visible week, from DigiCampus.
            val term = termLine(termStatus(snapshot.semesters, date), countdown = false)
            val holidays = holidaysBetween(snapshot.holidays, week.first(), week.last())
            if (term != null || holidays.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                term?.let { SmallLabel(it) }
                holidays.forEach { holiday -> HolidayLabel(holiday.copy(name = "${words.shortDay(holiday.date)} ${words.formatDate(holiday.date)} · ${holiday.name}")) }
            }
        }
        if (planner.settings.view == "week") item {
            CalendarWeatherStatus(week, snapshot.weather, now, weatherLoading) { scope.launch { repository.refreshWeather(force = true) } }
            PlannerWeekGrid(week, weekEntries, date, zoom, { zoom = it }, { selected = it.toString() }, { detail = it.key }, snapshot.weather,
                (snapshot.courses + overlaySchedules.map { it.course } + planner.plans.map { it.course }).associate { it.id to it.title })
            WeatherFooter(snapshot.weather, snapshot.account.demo, onOpenUrl)
            if (calendarUpdated != null && campusRoom == null && !planner.settings.exploreOnly) SmallLabel("${words.text("schedule_updated")} ${words.age(calendarUpdated, now)}")
        }
        if (planner.settings.publishedRooms) item {
            planner.rooms?.let { roomPlan ->
                val room = roomPlan.rooms.firstOrNull { it.label == planner.settings.filter.room }
                val updated = planner.updatedAt[PlannerRepository.ROOMS]
                if (updated != null) SmallLabel("${words.planner("loaded")} · ${words.age(updated, now)}")
                plannerSync.errors[PlannerRepository.ROOMS]?.let { SmallLabel(words.planner("room_error")); SmallLabel(words.failure(it)) }
                if (dayStart < roomPlan.from || date.nextCampusStart() > roomPlan.until) SmallLabel(words.planner("outside_room_range"))
                else if (room != null && isFresh(updated, nowMillis(), 5 * 60_000L) && plannerSync.errors[PlannerRepository.ROOMS] == null) {
                    Text(words.planner("room_gaps"), style = MaterialTheme.typography.titleMedium)
                    SmallLabel(words.formatDate(date, true))
                    plannerGaps(roomPlan.entries.filter { it.roomId == room.id }, date, planner.settings.workStart, planner.settings.workEnd, planner.settings.minimumGap).forEach {
                        SmallLabel("${words.time(it.from)} – ${words.time(it.until)}")
                    }
                }
                SmallLabel(words.planner("room_notice"))
                ZenLink(words.planner("room_source"), { onOpenUrl(PUBLISHED_ROOMS_URL) }, external = true)
            }
        }
        item {
            SectionTitle(words.formatDate(date, true))
            snapshot.holidays.firstOrNull { it.date == date }?.let { HolidayLabel(it, Modifier.padding(bottom = 6.dp).testTag("calendar-holiday")) }
        }
        val dayBookings = snapshot.consultationBookings.filter { campusToday(it.start) == date }
        if (dayBookings.isNotEmpty()) item(key = "day-bookings") { Column {
            dayBookings.forEachIndexed { index, booking ->
                ZenListItem(GroupPosition.of(index, dayBookings.size), Modifier.testTag("calendar-booking-${booking.id}")) {
                    Column(Modifier.widthIn(min = 44.dp)) {
                        Text(words.time(booking.start), style = MaterialTheme.typography.titleSmall)
                        Text(words.time(booking.end), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ZenIconTile(Symbol.PEOPLE, ZenTint(MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer), 28.dp)
                    ZenListText("${words.ui("office_hours")} · ${booking.rangeName}", listOf(booking.room, booking.reason).filter { it.isNotBlank() }.joinToString(" · "),
                        modifier = Modifier.weight(1f))
                }
            }
        } }
        if (events.isEmpty()) item {
            if (loading) LoadingCards() else EmptyMoment(if (planner.settings.filter == PlannerFilter()) words.text("no_events") else words.planner("no_matching"), "", Symbol.TODAY)
        }
        if (events.isNotEmpty()) item(key = "day-events") { Column {
            events.forEachIndexed { index, entry -> PlannerEntryRow(entry, snapshot, GroupPosition.of(index, events.size)) { detail = entry.key } }
        } }
        item {
            Text(words.text("stale_schedule"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SmallLabel(words.text("source_timezone"))
        }
    }
    allEntries.firstOrNull { it.key == detail }?.let { entry -> PlannerEventDialog(entry, snapshot, { detail = null }, onCourse, onOpenUrl) }
    if (exporting) CalendarExportDialog(repository, sync) { exporting = false }
    if (sheet != null) ZenSheet(onDismissRequest = { sheet = null },
        // List overscroll belongs to the list; it must not move or dismiss the planner.
        sheetGesturesEnabled = false,
        dragHandle = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                IconAction(Symbol.CLOSE, words.planner("close"), { sheet = null })
            }
        }) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding()) {
            when (sheet) {
                "filters" -> PlannerFilterSheet(planner, repository.planner, onGlobal = { mode, query ->
                    exploreMode = mode; exploreQuery = query; inspectCourse = ""; inspectSemester = semesterId; sheet = "explore"
                }) { sheet = null }
                "tools" -> PlannerToolsSheet(snapshot, planner, repository.planner, date, semesterId, planningEntries,
                    { detail = it.key }, { course, term -> inspectCourse = course.id; inspectSemester = term; sheet = "explore" }, { sheet = null },
                    onExport = { sheet = null; exporting = true })
                else -> PlannerExplorer(snapshot, planner, repository.planner, plannerSync, inspectCourse, inspectSemester, exploreMode, exploreQuery,
                    { schedule ->
                        scope.launch { repository.planner.settings { it.copy(view = "week", overlayCourseId = schedule.course.id, overlaySemesterId = schedule.semesterId, overlays = listOf(PlannerOverlay(schedule.course, schedule.semesterId)), exploreOnly = false, overlayName = schedule.course.title, publishedRooms = false, filter = PlannerFilter(), campusRoom = null, lecturerResult = null) } }
                        if (semesterId != schedule.semesterId) schedule.events.firstOrNull()?.let { selected = it.date.toString() }
                        sheet = null
                    }, { result, term ->
                        scope.launch { repository.planner.settings { it.copy(view = "week", overlays = result.courses.map { course -> PlannerOverlay(course, term.id) },
                            exploreOnly = true, overlayName = result.query, overlayCourseId = "", overlaySemesterId = "", publishedRooms = false, filter = PlannerFilter(includePlans = false), campusRoom = null, lecturerResult = result) } }
                        if (semesterId != term.id) selected = campusToday(maxOf(term.start, minOf(now, term.end - 1))).toString()
                        sheet = null
                    }, { room, published ->
                        scope.launch { repository.planner.settings { it.copy(view = "week", filter = PlannerFilter(room = room), publishedRooms = published, overlayCourseId = "", overlaySemesterId = "", overlays = emptyList(), exploreOnly = false, overlayName = "", campusRoom = null, lecturerResult = null) } }
                        sheet = null
                    }, { room ->
                        scope.launch { repository.planner.settings { it.copy(view = "week", campusRoom = room, filter = PlannerFilter(includePlans = false), publishedRooms = false,
                            overlayCourseId = "", overlaySemesterId = "", overlays = emptyList(), exploreOnly = true, overlayName = "", lecturerResult = null) } }
                        sheet = null
                    }, onOpenUrl, { sheet = null })
            }
        }
    }
}

@Composable
private fun CalendarWeatherStatus(week: List<LocalDate>, weather: CampusWeather?, now: Long, loading: Boolean, onRefresh: () -> Unit) {
    val words = LocalWords.current
    if (week.all { weather?.day(it) != null }) return
    val today = campusToday(now)
    val canRefresh = week.any { weather?.day(it) == null && it >= today.plus(-7, DateTimeUnit.DAY) && it <= today.plus(15, DateTimeUnit.DAY) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SmallLabel(words.text(if (loading) "weather_loading" else if (canRefresh) "forecast_some_unavailable" else "forecast_unavailable"))
        if (canRefresh && !loading) ZenLink(words.text("refresh_weather"), onRefresh, modifier = Modifier.testTag("calendar-weather-refresh"))
    }
}
