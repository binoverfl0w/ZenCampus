package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.planner
import kotlinx.datetime.LocalDate

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CampusRoomStatus(room: CampusRoom, plan: CampusRoomWeek?, key: String, planner: PlannerSnapshot, sync: SyncStatus,
    date: LocalDate, now: Long, onRefresh: () -> Unit, onClose: () -> Unit, onOpenUrl: (String) -> Unit) {
    val words = LocalWords.current
    val updated = planner.updatedAt[key]
    val fresh = plan != null && isFresh(updated, now, 5 * 60_000L) && key !in sync.errors
    Column(Modifier.fillMaxWidth().testTag("campus-room-status"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(room.name, style = MaterialTheme.typography.titleMedium)
        SmallLabel(words.planner("global_room_scope"))
        if (key in sync.active) LinearProgressIndicator(Modifier.fillMaxWidth())
        sync.errors[key]?.let { SmallLabel(words.failure(it)) }
        if (updated != null) SmallLabel("${words.planner("loaded")} ${words.age(updated, now)}")
        if (plan == null || !fresh) SmallLabel(words.planner("room_status_unknown"))
        else if (now >= plan.from && now < plan.until) {
            val until = roomBusyUntil(plan.entries, now)
            Text(if (until == null) words.planner("room_unbooked_now") else "${words.planner("room_booked_until")} " +
                (if (campusToday(until) != campusToday(now)) words.formatDate(campusToday(until)) + " · " else "") + words.time(until),
                style = MaterialTheme.typography.titleMedium)
        }
        if (fresh && date.atCampusStart() >= plan.from && date.nextCampusStart() <= plan.until) {
            Text(words.planner("room_gaps"), style = MaterialTheme.typography.titleMedium)
            SmallLabel(words.formatDate(date, true))
            val gaps = plannerGaps(plan.entries, date, planner.settings.workStart, planner.settings.workEnd, planner.settings.minimumGap)
            if (gaps.isEmpty()) SmallLabel(words.planner("no_gaps"))
            gaps.forEach { SmallLabel("${words.time(it.from)} – ${words.time(it.until)}") }
        }
        SmallLabel(words.planner("room_notice"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZenLink(words.planner("refresh"), onRefresh)
            ZenLink(words.planner("campus_room_source"), { onOpenUrl(room.sourceUrl) }, external = true)
            ZenLink(words.planner("clear_room"), onClose)
        }
    }
}
