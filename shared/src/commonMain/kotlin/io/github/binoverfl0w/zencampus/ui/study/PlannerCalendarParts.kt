package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.RowChevron
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ToneChip
import io.github.binoverfl0w.zencampus.ui.design.WeatherIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenStepper
import io.github.binoverfl0w.zencampus.ui.design.courseTint
import io.github.binoverfl0w.zencampus.ui.planner
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.datetime.*

@Composable
internal fun PlannerWeekGrid(week: List<LocalDate>, entries: List<PlannerEntry>, selected: LocalDate, zoom: Float,
    onZoom: (Float) -> Unit, onDay: (LocalDate) -> Unit, onEvent: (PlannerEntry) -> Unit, weather: CampusWeather? = null,
    courseTitles: Map<String, String> = emptyMap()) {
    val words = LocalWords.current
    val density = LocalDensity.current
    val viewport = (LocalWindowInfo.current.containerSize.height / density.density - 200f).coerceIn(180f, 460f).dp
    val zoomed = zoom > 1f
    val hasWeather = weather?.daily?.isNotEmpty() == true
    val textScale = density.fontScale.coerceAtLeast(1f)
    val headerHeight = (if (hasWeather) 84f else 48f).times(textScale).dp
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    LaunchedEffect(zoomed) {
        if (!zoomed) { horizontal.scrollTo(0); vertical.scrollTo(0) }
    }
    val latestZoom by rememberUpdatedState(zoom)
    val latestChange by rememberUpdatedState(onZoom)
    val layouts = week.associateWith { plannerGridEvents(entries, it) }
    val startHour = minOf(8, floor((layouts.values.flatten().minOfOrNull { it.startMinute } ?: 480) / 60f).toInt()).coerceAtLeast(0)
    val endHour = maxOf(20, ceil((layouts.values.flatten().maxOfOrNull { it.endMinute } ?: 1200) / 60f).toInt()).coerceAtMost(24)
    val hourHeight = (52f * textScale * zoom).dp
    val height = hourHeight * (endHour - startHour)
    val clashes = plannerClashes(entries.filter { it.roomId.isBlank() },
        week.first().let { it.atCampusStart() }, week.last().let { it.nextCampusStart() })
        .flatMap { listOf(it.first.key, it.second.key) }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(words.planner("week_grid"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            ZenStepper("${(zoom * 100).toInt()}%", { onZoom((zoom - .25f).coerceAtLeast(1f)) }, { onZoom((zoom + .25f).coerceAtMost(2.5f)) },
                words.planner("zoom_out"), words.planner("zoom_in"), decreaseEnabled = zoom > 1f, increaseEnabled = zoom < 2.5f)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(if (zoomed) viewport else headerHeight + height).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), RoundedCornerShape(16.dp))
            .testTag("planner-week-grid").pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } > 1) {
                            val factor = event.calculateZoom()
                            if (factor.isFinite() && factor > 0f) latestChange((latestZoom * factor).coerceIn(1f, 2.5f))
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }) {
            val axis = (38f * density.fontScale.coerceAtMost(1.6f)).dp
            // Whole pixels, so seven columns never overflow the grid and clip the last day.
            val dayWidth = with(density) { ((maxWidth - axis).toPx() / 7f * zoom).toInt().toDp() }
            val horizontalPan = if (zoomed) Modifier.horizontalScroll(horizontal) else Modifier
            Column {
                Row(Modifier.height(headerHeight)) {
                    Spacer(Modifier.width(axis))
                    Row(horizontalPan) {
                        week.forEach { day ->
                            val isSelected = selected == day
                            Box(Modifier.width(dayWidth).fillMaxHeight()
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable { onDay(day) }
                                .testTag("planner-grid-header-$day").semantics {
                                    contentDescription = words.formatDate(day, true); this.selected = isSelected; role = Role.Button
                                }, contentAlignment = Alignment.Center) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(words.shortDay(day), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(day.day.toString(), style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                    if (hasWeather) {
                                        val forecast = weather?.day(day)
                                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                            if (forecast != null) WeatherIcon(forecast.weatherCode,
                                                Modifier.size(12.dp).testTag("planner-grid-weather-$day"), MaterialTheme.colorScheme.onSurfaceVariant)
                                            else Spacer(Modifier.height(12.dp))
                                            Text(forecast?.let { "${it.maxC.roundToInt()}°" } ?: "—", Modifier.testTag("planner-grid-temperature-$day"), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.weight(1f).then(if (zoomed) Modifier.verticalScroll(vertical) else Modifier).testTag("planner-grid-body")) {
                    Column(Modifier.width(axis).height(height)) {
                        for (hour in startHour until endHour) Box(Modifier.height(hourHeight).fillMaxWidth()) {
                            Text("${hour.toString().padStart(2, '0')}:00", Modifier.testTag("planner-grid-hour-$hour"), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalPan) {
                        week.forEach { day ->
                            Box(Modifier.width(dayWidth).height(height).testTag("planner-grid-$day")) {
                                for (hour in startHour until endHour) HorizontalDivider(
                                    Modifier.offset(y = hourHeight * (hour - startHour)), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                                VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                                for (item in layouts[day].orEmpty()) {
                                    val slotWidth = dayWidth / item.columns
                                    val top = hourHeight * ((item.startMinute - startHour * 60) / 60f)
                                    val itemHeight = (hourHeight * ((item.endMinute - item.startMinute) / 60f)).coerceAtLeast(24.dp)
                                    val entry = item.entry
                                    val title = courseTitles[entry.event.courseId].orEmpty().ifBlank { entry.event.title }
                                    val conflict = entry.key in clashes
                                    val ink = if (conflict) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                    Surface(onClick = { onEvent(entry) }, modifier = Modifier
                                        .offset(x = slotWidth * item.column + 1.dp, y = top + 1.dp)
                                        .width((slotWidth - 2.dp).coerceAtLeast(1.dp)).height((itemHeight - 2.dp).coerceAtLeast(22.dp))
                                        .testTag("planner-event-${entry.event.id}-${day}")
                                        .semantics { contentDescription = "$title, ${entry.event.title}, ${words.formatDate(day)}, ${words.time(entry.event.start)}–${words.time(entry.event.end)}, ${entry.event.location}" },
                                        shape = RoundedCornerShape(5.dp),
                                        color = when { conflict -> MaterialTheme.colorScheme.errorContainer
                                            entry.tentative -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .8f)
                                            entry.event.cancelled -> MaterialTheme.colorScheme.surfaceContainerHigh
                                            else -> MaterialTheme.colorScheme.primaryContainer },
                                        border = if (entry.tentative) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .6f)) else null) {
                                        Column(Modifier.padding(horizontal = 3.dp, vertical = 3.dp)) {
                                            if (slotWidth < (54f * textScale).dp) {
                                                val lines = ((itemHeight.value - 6f) / (11f * textScale)).toInt().coerceIn(1, 6)
                                                Text(title, Modifier.fillMaxWidth().testTag("planner-event-title-${entry.event.id}-${day}"),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp,
                                                        textDecoration = if (entry.event.cancelled) TextDecoration.LineThrough else TextDecoration.None),
                                                    color = ink, maxLines = lines, overflow = TextOverflow.Ellipsis)
                                            } else {
                                                Text(words.time(entry.event.start), style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                val lines = ((itemHeight.value - 6f) / (16f * textScale)).toInt().coerceAtLeast(1)
                                                if (lines > 1) Text(title, style = MaterialTheme.typography.labelSmall.copy(textDecoration = if (entry.event.cancelled) TextDecoration.LineThrough else TextDecoration.None),
                                                    color = ink, maxLines = minOf(lines - 1, if (zoom >= 1.5f) 4 else 2), overflow = TextOverflow.Ellipsis)
                                                if (zoom >= 1.5f && lines > 5) Text(entry.event.location, style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SmallLabel(words.planner(if (zoomed) "zoom_hint" else "week_expanded_hint"))
        entries.filter { it.event.allDay && week.any(it.event::occursOn) }.forEach { entry ->
            ZenLink("${words.planner("all_day")} · ${entry.event.title}", { onEvent(entry) })
        }
    }
}

@Composable
internal fun PlannerEntryRow(entry: PlannerEntry, snapshot: CampusSnapshot, position: GroupPosition = GroupPosition.SINGLE, onClick: () -> Unit) {
    val words = LocalWords.current
    val event = entry.event
    val course = snapshot.courses.firstOrNull { it.id == event.courseId }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ZenListItem(position, Modifier.testTag("planner-entry-${entry.key}"), onClick) {
        Column(Modifier.widthIn(min = 44.dp)) {
            if (event.allDay) Text(words.text("all_day"), style = MaterialTheme.typography.labelMedium)
            else {
                Text(words.time(event.start), style = MaterialTheme.typography.titleSmall, color = if (event.cancelled) muted else Color.Unspecified)
                Text(words.time(event.end), style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
        val tint = courseTint(course?.id ?: event.courseId)
        if (event.cancelled) Box(Modifier.size(9.dp).border(2.dp, tint.ink, CircleShape)) else Box(Modifier.size(9.dp).background(tint.ink, CircleShape))
        ZenListText(course?.title ?: event.title,
            listOf(event.location.ifBlank { words.text("no_room") }, entry.teacher).filter { it.isNotBlank() }.joinToString(" · "),
            overline = if (entry.tentative) words.planner(if (entry.explored) "exploring" else "tentative") else "",
            titleColor = if (event.cancelled) muted else Color.Unspecified, modifier = Modifier.weight(1f))
        if (event.cancelled) ToneChip(words.text("cancelled"), Tone.DANGER) else RowChevron()
    }
}

@Composable
internal fun PlannerEventDialog(entry: PlannerEntry, snapshot: CampusSnapshot, onDismiss: () -> Unit,
    onCourse: (String) -> Unit, onOpenUrl: (String) -> Unit) {
    val words = LocalWords.current
    val event = entry.event
    val tint = courseTint(event.courseId.ifBlank { null })
    @Composable fun Meta(symbol: Symbol, text: String) {
        if (text.isBlank()) return
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZenIcon(symbol, Modifier.padding(top = 2.dp).size(17.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
    ZenDialog(onDismiss, title = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (event.courseId.isNotBlank()) Box(Modifier.size(width = 28.dp, height = 4.dp).background(tint.ink, RoundedCornerShape(2.dp)))
            Text(event.title)
        }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (event.cancelled) ToneChip(words.planner("cancelled"), Tone.DANGER)
            Meta(Symbol.CLOCK, "${words.formatDate(event.date, true)} · ${words.time(event.start)} – ${words.time(event.end)}")
            Meta(Symbol.LOCATION, event.location)
            Meta(Symbol.PEOPLE, entry.teacher)
            if (entry.tentative) SmallLabel(words.planner(if (entry.explored) "exploring" else "plans_body"))
            if (entry.summaryOnly) SmallLabel(words.planner("summary"))
            if (event.description.isNotBlank()) Text(event.description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, neutralButton = if (entry.roomId.isBlank()) null else ({
        ZenLink(words.planner(if (entry.roomId.startsWith("campus:")) "campus_room_source" else "room_source"),
            { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else entry.sourceUrl.ifBlank { PUBLISHED_ROOMS_URL }) }, external = true)
    }), confirmButton = {
        if (event.courseId.isNotBlank()) DialogButton(words.planner("open_course"), {
            onDismiss()
            if (snapshot.courses.any { it.id == event.courseId }) onCourse(event.courseId)
            else onOpenUrl("$DIGICAMPUS_ORIGIN/dispatch.php/course/details/?sem_id=${event.courseId}")
        }, primary = true)
        else DialogButton(words.planner("close"), onDismiss)
    }, dismissButton = { if (event.courseId.isNotBlank()) DialogButton(words.planner("close"), onDismiss) })
}

internal fun LocalDate.atCampusStart() = atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
internal fun LocalDate.nextCampusStart() = plus(1, DateTimeUnit.DAY).atCampusStart()
