package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.CampusBoard
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.design.EmptyMoment
import io.github.binoverfl0w.zencampus.ui.design.LoadingCards
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWideLayout
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.SectionTitle
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ToneChip
import io.github.binoverfl0w.zencampus.ui.design.WeatherIcon
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenDivider
import io.github.binoverfl0w.zencampus.ui.design.ZenGroup
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.boardTint
import io.github.binoverfl0w.zencampus.ui.design.courseInitials
import io.github.binoverfl0w.zencampus.ui.design.courseTint
import io.github.binoverfl0w.zencampus.ui.services.CampusDailyBrief
import io.github.binoverfl0w.zencampus.ui.ui
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.datetime.*

/**
 * Today: greeting, the next class, the rest of the day, what is due, campus services and course updates.
 * Wide windows split it into a main column and a side column; phones keep one list with the forecast behind a chip.
 */
@Composable
fun TodayScreen(
    snapshot: CampusSnapshot, sync: SyncStatus, onCourse: (String) -> Unit, onWeek: () -> Unit,
    onDay: (LocalDate) -> Unit, onAnnouncement: (String) -> Unit,
    weatherLoading: Boolean, onOpenUrl: (String) -> Unit,
    services: CampusServicesRepository?, onService: (String) -> Unit,
    onCoursework: () -> Unit, onTask: (String, String) -> Unit, onUpdates: () -> Unit,
    onMaterials: (String) -> Unit = onCourse,
) {
    val now = LocalNow.current
    val today = campusToday(now)
    val officeHours = LocalWords.current.ui("office_hours")
    // Booked office hours appear like classes: in the next-up card and today's timeline.
    val events = remember(snapshot.events, snapshot.consultationBookings, officeHours) {
        snapshot.events + snapshot.consultationBookings.map { it.asEvent(officeHours) }
    }
    val upcoming = nextUpcomingEvent(events, now)
    val todayEvents = events.filter { it.occursOn(today) && it.end > now }.sortedBy { it.start }
    val updates = snapshot.announcements.filter { it.expires > now }.take(3)
    val loading = snapshot.updatedAt.keys.none { it.startsWith("personal:") } && sync.refreshing
    var weatherOpen by rememberSaveable { mutableStateOf(false) }

    val hero: @Composable () -> Unit = {
        if (upcoming != null) UpNextCard(upcoming, snapshot, { course -> if (course != null) onCourse(course) else onDay(upcoming.date) },
            onDay, onMaterials)
        else if (loading) LoadingCards()
        else EmptyMoment(LocalWords.current.text("all_clear"), LocalWords.current.text("no_upcoming"))
    }
    val schedule: @Composable () -> Unit = {
        RestOfToday(todayEvents, snapshot, loading, onWeek, services != null, { onService("mensa") }) { event ->
            if (snapshot.courses.any { it.id == event.courseId }) onCourse(event.courseId) else onDay(event.date)
        }
    }
    val coursework: @Composable () -> Unit = { CourseworkBrief(snapshot, sync, onCoursework, onTask) }
    val news: @Composable () -> Unit = { TodayUpdates(updates, snapshot, onAnnouncement, onUpdates) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (LocalWideLayout.current && maxWidth >= 860.dp) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("today-scroll")
                .padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                Greeting(snapshot, null, false) {}
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1.7f), verticalArrangement = Arrangement.spacedBy(28.dp)) { hero(); schedule(); news() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                        CampusWeatherCard(snapshot.weather, weatherLoading, snapshot.account.demo, onOpenUrl)
                        coursework()
                        if (services != null) CampusDailyBrief(services, onService, stacked = true)
                    }
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().testTag("today-scroll"),
            contentPadding = PaddingValues(start = Zen.gutter, end = Zen.gutter, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(Zen.gap)) {
            item { Greeting(snapshot, snapshot.weather, weatherOpen) { weatherOpen = !weatherOpen } }
            if (weatherOpen || (snapshot.weather == null && weatherLoading)) item {
                CampusWeatherCard(snapshot.weather, weatherLoading, snapshot.account.demo, onOpenUrl)
            }
            item { hero() }
            item { schedule() }
            item { coursework() }
            if (services != null) item { CampusDailyBrief(services, onService) }
            item { news() }
        }
    }
}

@Composable
private fun Greeting(snapshot: CampusSnapshot, weather: CampusWeather?, weatherOpen: Boolean, onWeather: () -> Unit) {
    val words = LocalWords.current
    val now = LocalNow.current
    val hour = Instant.fromEpochMilliseconds(now).toLocalDateTime(CAMPUS_ZONE).hour
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            val today = campusToday(now)
            val term = termLine(termStatus(snapshot.semesters, today))
            Text(listOfNotNull(words.formatDate(today, full = true), term).joinToString(" · "), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("today-date"))
            snapshot.holidays.firstOrNull { it.date == today }?.let { HolidayLabel(it, Modifier.padding(top = 4.dp).testTag("today-holiday")) }
            Spacer(Modifier.height(2.dp))
            Text("${words.text(if (hour < 12) "greeting_morning" else if (hour < 18) "greeting_day" else "greeting_evening")} ${snapshot.account.firstName}",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-.7).sp),
                modifier = Modifier.semantics { heading() })
        }
        if (weather != null) WeatherChip(weather, weatherOpen, onWeather)
    }
}

@Composable
private fun WeatherChip(weather: CampusWeather, open: Boolean, onClick: () -> Unit) {
    val words = LocalWords.current
    val now = LocalNow.current
    val hour = weather.hoursOn(campusToday(now)).lastOrNull { it.start <= now }
    val rain = hour?.precipitationProbability
    val temperature = "${weather.temperatureC.roundToInt()}°"
    Surface(onClick = onClick, modifier = Modifier.testTag("weather-chip").semantics {
        contentDescription = "${words.ui("weather")}: $temperature C, ${words.weatherCondition(weather.weatherCode)}" +
            (rain?.let { ", ${words.ui("rain")} $it%" } ?: "")
        stateDescription = words.ui(if (open) "hide_forecast" else "show_forecast")
    }, shape = RoundedCornerShape(16.dp), color = if (open) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (open) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.heightIn(min = 48.dp).padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeatherIcon(weather.weatherCode, Modifier.size(22.dp),
                if (open) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, hour?.isDay ?: true)
            Column {
                Text(temperature, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (rain != null) Text("${words.ui("rain")} $rain%", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun Words.duration(minutes: Long): String = when {
    minutes < 60 -> "${minutes.coerceAtLeast(1)} min"
    minutes % 60 == 0L -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/** The next class, tinted with its course colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpNextCard(event: CampusEvent, snapshot: CampusSnapshot, onOpen: (String?) -> Unit, onDay: (LocalDate) -> Unit,
    onMaterials: (String) -> Unit) {
    val words = LocalWords.current
    val now = LocalNow.current
    val wide = LocalWideLayout.current
    val course = snapshot.courses.firstOrNull { it.id == event.courseId }
    val tint = courseTint(course?.id ?: event.courseId)
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ongoing = event.start <= now && event.end > now
    val sameDay = event.date == campusToday(now)
    val newFiles = course?.let { snapshot.courseMaterials[it.id]?.recentFiles(now)?.size } ?: 0
    Surface(onClick = { onOpen(course?.id) }, modifier = Modifier.fillMaxWidth().testTag("up-next"),
        shape = RoundedCornerShape(Zen.radiusHero), color = tint.soft) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.background(tint.ink.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(6.dp).background(tint.ink, CircleShape))
                        Text(if (ongoing) words.text("happening_now") else "${words.ui("up_next")} · ${words.relativeEvent(event, now)}",
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tint.ink)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                        Text(courseInitials(course?.title ?: event.title), color = tint.ink, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(course?.title ?: event.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-.3).sp), color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (course != null && event.title.isNotBlank() && event.title != course.title)
                            Text(event.title, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Meta(Symbol.CLOCK, if (event.allDay) words.text("all_day") else "${words.time(event.start)} – ${words.time(event.end)}")
                    Meta(Symbol.LOCATION, event.location.ifBlank { words.text("no_room") })
                    if (course != null && course.teacher.isNotBlank()) Meta(Symbol.PEOPLE, course.teacher)
                }
                if (ongoing && event.end > event.start) LinearProgressIndicator(
                    progress = { ((now - event.start).toFloat() / (event.end - event.start)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp), color = tint.ink, trackColor = tint.ink.copy(alpha = .15f),
                    drawStopIndicator = {})
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (course != null) Surface(onClick = { onMaterials(course.id) }, shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ZenIcon(Symbol.FILE, Modifier.size(16.dp), tint.ink)
                            Text(words.ui("materials"), style = MaterialTheme.typography.labelLarge, color = ink)
                            if (newFiles > 0) ToneChip("$newFiles ${words.ui("new_files")}", Tone.PRIMARY)
                        }
                    }
                    Surface(onClick = { onDay(event.date) }, shape = RoundedCornerShape(12.dp), color = tint.ink.copy(alpha = .12f)) {
                        Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(words.text("see_in_calendar"), style = MaterialTheme.typography.labelLarge, color = tint.ink)
                            ZenIcon(Symbol.NEXT, Modifier.size(14.dp), tint.ink)
                        }
                    }
                }
            }
            if (wide && !event.allDay) Column(Modifier.align(Alignment.CenterVertically).widthIn(min = 120.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val (label, value) = when {
                    ongoing -> words.ui("ends_in") to words.duration((event.end - now) / 60_000)
                    sameDay -> words.ui("starts_in") to words.duration((event.start - now) / 60_000)
                    else -> words.formatDate(event.date) to words.time(event.start)
                }
                Text(label, style = MaterialTheme.typography.labelLarge, color = muted)
                Text(value, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp), color = ink)
                Text("${words.time(event.start)} – ${words.time(event.end)}", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }
    }
}

@Composable
private fun Meta(symbol: Symbol, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ZenIcon(symbol, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private sealed interface DayEntry
private data class ClassEntry(val event: CampusEvent) : DayEntry
private data class GapEntry(val from: Long, val until: Long, val lunch: Boolean) : DayEntry

/** The rest of today as a timeline, showing free gaps and cancelled classes. */
@Composable
private fun RestOfToday(events: List<CampusEvent>, snapshot: CampusSnapshot, loading: Boolean, onWeek: () -> Unit,
    mensa: Boolean, onMensa: () -> Unit, onEvent: (CampusEvent) -> Unit) {
    val words = LocalWords.current
    val now = LocalNow.current
    val entries = remember(events, now / 60_000) {
        buildList<DayEntry> {
            var previousEnd: Long? = null
            events.filterNot { it.allDay }.forEach { event ->
                val from = previousEnd?.let { maxOf(it, now) }
                if (from != null && event.start - from >= 45 * 60_000L) {
                    val start = Instant.fromEpochMilliseconds(from).toLocalDateTime(CAMPUS_ZONE).hour
                    val end = Instant.fromEpochMilliseconds(event.start).toLocalDateTime(CAMPUS_ZONE).hour
                    add(GapEntry(from, event.start, start < 14 && end >= 11))
                }
                add(ClassEntry(event))
                if (!event.cancelled) previousEnd = maxOf(previousEnd ?: 0L, event.end)
            }
            addAll(0, events.filter { it.allDay }.map { ClassEntry(it) })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(words.ui("rest_of_today"), words.ui("week_view"), onWeek)
        if (events.isEmpty() && loading) LoadingCards()
        else ZenGroup(Modifier.testTag("today-timeline")) {
            if (entries.isEmpty()) Text(words.ui("no_more_classes"), Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.padding(vertical = if (entries.isEmpty()) 0.dp else 6.dp)) {
                entries.forEachIndexed { index, entry ->
                    val first = index == 0
                    val last = index == entries.lastIndex
                    when (entry) {
                        is ClassEntry -> TimelineClass(entry.event, snapshot.courses.firstOrNull { it.id == entry.event.courseId }, first, last) { onEvent(entry.event) }
                        is GapEntry -> TimelineGap(entry, first, last, if (mensa && entry.lunch) onMensa else null)
                    }
                }
            }
        }
    }
}

/** The timeline beside a row: its dot in the vertical middle, joined to the rows above and below. */
@Composable
private fun TimelineRail(first: Boolean, last: Boolean, dot: @Composable () -> Unit) {
    Column(Modifier.width(12.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        RailLine(!first, Modifier.weight(1f).padding(bottom = 4.dp))
        dot()
        RailLine(!last, Modifier.weight(1f).padding(top = 4.dp))
    }
}

@Composable
private fun RailLine(visible: Boolean, modifier: Modifier) {
    Box(modifier.width(2.dp).then(if (visible) Modifier.background(MaterialTheme.colorScheme.outlineVariant) else Modifier))
}

@Composable
private fun TimelineClass(event: CampusEvent, course: Course?, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val words = LocalWords.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tint = courseTint(course?.id ?: event.courseId)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick).testTag("today-event-${event.id}")
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(48.dp)) {
            if (event.allDay) Text(words.text("all_day"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            else {
                Text(words.time(event.start), style = MaterialTheme.typography.titleSmall, color = if (event.cancelled) muted else Color.Unspecified)
                Text(words.time(event.end), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        TimelineRail(first, last) {
            if (event.cancelled) Box(Modifier.size(10.dp).border(2.dp, tint.ink, CircleShape))
            else Box(Modifier.size(10.dp).background(tint.ink, CircleShape))
        }
        Row(Modifier.weight(1f).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(course?.title ?: event.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (event.cancelled) muted else Color.Unspecified,
                    textDecoration = if (event.cancelled) TextDecoration.LineThrough else null)
                Text(listOfNotNull(event.title.takeIf { course != null && it.isNotBlank() && it != course.title },
                    event.location.ifBlank { words.text("no_room") }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (event.cancelled) ToneChip(words.text("cancelled"), Tone.DANGER)
        }
    }
}

@Composable
private fun TimelineGap(gap: GapEntry, first: Boolean, last: Boolean, onLunch: (() -> Unit)?) {
    val words = LocalWords.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(words.time(gap.from), Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge, color = muted)
        TimelineRail(first, last) {
            Box(Modifier.size(10.dp).border(1.5.dp, muted, CircleShape))
        }
        Row(Modifier.weight(1f).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${words.ui("free_until")} ${words.time(gap.until)}", Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, color = muted)
            if (onLunch != null) {
                val tint = boardTint(CampusBoard.MENSA)
                Surface(onClick = onLunch, shape = RoundedCornerShape(10.dp), color = tint.soft, modifier = Modifier.testTag("today-lunch")) {
                    Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ZenIcon(Symbol.DINING, Modifier.size(14.dp), tint.ink)
                        Text(words.ui("lunch_menu"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tint.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayUpdates(updates: List<Announcement>, snapshot: CampusSnapshot, onAnnouncement: (String) -> Unit, onAll: () -> Unit) {
    val words = LocalWords.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(words.text("updates"), words.ui("see_all"), onAll)
        if (updates.isEmpty()) EmptyMoment(words.text("no_updates"), words.text("no_updates_body"), Symbol.CHECK)
        else ZenGroup {
            updates.forEachIndexed { index, announcement ->
                if (index > 0) ZenDivider()
                AnnouncementRow(announcement, snapshot.courses.firstOrNull { it.id == announcement.courseId },
                    announcement.id !in snapshot.preferences.readAnnouncements) { onAnnouncement(announcement.id) }
            }
        }
    }
}
