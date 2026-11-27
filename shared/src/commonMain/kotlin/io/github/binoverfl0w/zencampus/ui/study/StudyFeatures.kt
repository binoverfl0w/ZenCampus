package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.action
import io.github.binoverfl0w.zencampus.ui.design.ContentStatus
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.LocalKeyboardShortcuts
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
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenNotice
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import io.github.binoverfl0w.zencampus.ui.design.ZenStatus
import io.github.binoverfl0w.zencampus.ui.design.ZenTabs
import io.github.binoverfl0w.zencampus.ui.design.ZenTint
import io.github.binoverfl0w.zencampus.ui.design.content
import io.github.binoverfl0w.zencampus.ui.design.courseInitials
import io.github.binoverfl0w.zencampus.ui.design.toneColors
import io.github.binoverfl0w.zencampus.ui.ui
import kotlinx.coroutines.launch

/** Messages and the updates feed share one bottom section; the view switch replaces a second tab. */
@Composable
fun InboxScreen(snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus, onOpenUrl: (String) -> Unit,
    view: String, onView: (String) -> Unit, openAnnouncement: (String) -> Unit,
    openDiscussion: (String, String, String) -> Unit, openCourse: (String) -> Unit) {
    val words = LocalWords.current
    val now = LocalNow.current
    val unreadMessages = snapshot.messages["inbox"].orEmpty().count { !it.read }
    val unreadUpdates = remember(snapshot, now) { studyUpdates(snapshot, now).count { it.unread && it.kind != StudyUpdateKind.MESSAGE } }
    val header: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle(words.ui("inbox"), "")
            ZenTabs(listOf(
                words.ui("messages") + if (unreadMessages > 0) " · $unreadMessages" else "",
                words.text("updates") + if (unreadUpdates > 0) " · $unreadUpdates" else "",
            ), if (view == "updates") 1 else 0, { onView(if (it == 1) "updates" else "") }, fill = true, tags = listOf("inbox-messages", "inbox-updates"))
        }
    }
    if (view == "updates") UpdatesScreen(snapshot, repository, sync, openAnnouncement, { onView("") }, openDiscussion, openCourse, header)
    else MessagesScreen(snapshot, repository, sync, onOpenUrl, header)
}

private data class StaffMember(val personId: String, val name: String, val role: String, val courses: List<Course>)

/** Lecturers and tutors of the loaded courses, deduplicated by person. */
private fun courseStaff(snapshot: CampusSnapshot, courseFilter: String): List<StaffMember> {
    val courses = snapshot.courses.filter { courseFilter.isBlank() || it.id == courseFilter }
    return courses.flatMap { course ->
        snapshot.community["community:${course.id}:people:"].orEmpty()
            .filter { it.personId.isNotBlank() && it.personId != snapshot.account.id && it.secondary in setOf("dozent", "tutor") }
            .map { Triple(it, course, it.secondary) }
    }.groupBy { it.first.personId }.map { (id, entries) ->
        StaffMember(id, entries.first().first.title, if (entries.any { it.third == "dozent" }) "dozent" else "tutor", entries.map { it.second }.distinctBy { it.id })
    }.sortedWith(compareBy<StaffMember> { it.role != "dozent" }.thenBy { it.name.lowercase() })
}

@Composable
fun OfficeHoursScreen(snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus, courseFilter: String, connected: Boolean) {
    val words = LocalWords.current
    val now = LocalNow.current
    val scope = rememberCoroutineScope()
    val staff = courseStaff(snapshot, courseFilter)
    val course = snapshot.courses.firstOrNull { it.id == courseFilter }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var booking by remember { mutableStateOf<Pair<ConsultationBlock, ConsultationSlot>?>(null) }
    var cancelling by remember { mutableStateOf<ConsultationBooking?>(null) }
    val bookings = upcomingBookings(snapshot.consultationBookings, now)
    LaunchedEffect(repository, courseFilter, snapshot.courses.map { it.id }) {
        if (courseFilter.isBlank()) repository.refreshCourseStaff()
        else {
            repository.refreshCommunity(courseFilter, "people")
            course?.let { repository.refreshConsultations("courses", it.id, it.title) }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("office-hours"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle(words.ui("office_hours"), course?.title ?: words.ui("office_hours_body")) }
        item {
            SectionTitle(words.ui("your_bookings"))
            if (bookings.isEmpty()) ZenStatus(words.ui("no_bookings"))
            else Column(Modifier.testTag("consultation-bookings")) {
                bookings.forEachIndexed { index, item ->
                    val cancelKey = "write:consultation-cancel:${item.id}"
                    ZenListItem(GroupPosition.of(index, bookings.size), Modifier.testTag("booking-${item.id}")) {
                        ZenIconTile(Symbol.PEOPLE, ZenTint(MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer))
                        ZenListText(item.rangeName.ifBlank { words.ui("office_hours") },
                            "${words.formatDate(campusToday(item.start), true)} · ${words.time(item.start)} – ${words.time(item.end)}",
                            meta = listOf(item.room, item.reason).filter { it.isNotBlank() }.joinToString(" · "), modifier = Modifier.weight(1f))
                        DialogButton(words.ui("cancel_booking"), { cancelling = item }, enabled = connected && cancelKey !in sync.active, destructive = true)
                    }
                    WriteNotice(snapshot.courseworkWrites[cancelKey], cancelKey in sync.active, repository, cancelKey)
                }
            }
        }
        if (course != null) item(key = "course-range") {
            val blocks = snapshot.consultations[CampusRepository.consultationKey("courses", course.id)]
            if (!blocks.isNullOrEmpty()) {
                SectionTitle(words.ui("course_office_hours"))
                blocks.forEach { block -> ConsultationBlockCard(block, snapshot, sync, connected) { slot -> booking = block to slot } }
            }
        }
        item {
            SectionTitle(words.ui("lecturers"))
            val loadingStaff = snapshot.courses.any { "community:${it.id}:people:" in sync.active }
            when {
                staff.isEmpty() && loadingStaff -> ZenStatus(words.ui("loading_people"), loading = true)
                staff.isEmpty() -> ZenStatus(words.ui("no_lecturers"))
            }
        }
        items(staff.size, key = { "staff:" + staff[it].personId }) { index ->
            val member = staff[index]
            val open = member.personId in expanded
            val key = CampusRepository.consultationKey("users", member.personId)
            val blocks = snapshot.consultations[key]
            LaunchedEffect(open) { if (open) repository.refreshConsultations("users", member.personId, member.name) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZenListItem(modifier = Modifier.testTag("staff-${member.personId}"), onClick = {
                    expanded = if (open) expanded - member.personId else expanded + member.personId
                }) {
                    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        Text(courseInitials(member.name), style = MaterialTheme.typography.labelMedium)
                    }
                    ZenListText(member.name, member.courses.joinToString(" · ") { it.title },
                        overline = words.ui(if (member.role == "dozent") "lecturer" else "tutor"), modifier = Modifier.weight(1f))
                    ZenIcon(if (open) Symbol.CLOSE else Symbol.DOWN, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (open) {
                    val state = sync.content(CacheKeys.consultations("users", member.personId), blocks != null)
                    ContentStatus(state, words.ui("loading_office_hours"),
                        { scope.launch { repository.refreshConsultations("users", member.personId, member.name, force = true) } },
                        words.ui("no_office_hours"), blocks.isNullOrEmpty())
                    when {
                        blocks.isNullOrEmpty() -> {}
                        else -> blocks.forEach { block -> ConsultationBlockCard(block, snapshot, sync, connected) { slot -> booking = block to slot } }
                    }
                }
            }
        }
    }
    booking?.let { (block, slot) -> BookingDialog(block, slot, snapshot, repository, sync) { booking = null } }
    cancelling?.let { item ->
        var reason by remember(item.id) { mutableStateOf("") }
        ZenDialog(onDismissRequest = { cancelling = null }, title = { Text(words.ui("cancel_booking_title")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${item.rangeName} · ${words.formatDate(campusToday(item.start), true)} · ${words.time(item.start)}")
                OutlinedTextField(reason, { reason = it.take(500) }, label = { Text(words.ui("cancel_reason")) }, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { DialogButton(words.ui("cancel_booking"), { cancelling = null; scope.launch { repository.cancelConsultation(item, reason) } },
                primary = true, destructive = true) },
            dismissButton = { DialogButton(words.ui("keep_booking"), { cancelling = null }) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConsultationBlockCard(block: ConsultationBlock, snapshot: CampusSnapshot, sync: SyncStatus, connected: Boolean,
    onBook: (ConsultationSlot) -> Unit) {
    val words = LocalWords.current
    ZenPanel(Modifier.fillMaxWidth().testTag("consultation-block-${block.id}"), padding = 14.dp) {
        Text("${words.formatDate(campusToday(block.start), true)} · ${words.time(block.start)} – ${words.time(block.end)}",
            style = MaterialTheme.typography.titleSmall)
        if (block.room.isNotBlank()) SmallLabel("${words.ui("room")} ${block.room}")
        if (block.note.isNotBlank()) Text(block.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.slots.forEach { slot ->
                val mine = slot.bookingId.isNotBlank() || snapshot.consultationBookings.any { it.slotId == slot.id }
                val writing = "write:consultation:${slot.id}" in sync.active
                ZenChip(if (mine) "${words.time(slot.start)} · ${words.ui("booked")}" else words.time(slot.start),
                    { onBook(slot) }, Modifier.testTag("slot-${slot.id}"), selected = mine,
                    symbol = if (mine) Symbol.CHECK else null,
                    enabled = connected && !mine && slot.bookable && !writing)
            }
        }
        if (block.slots.none { it.bookable } && block.slots.isNotEmpty()) {
            Spacer(Modifier.height(6.dp)); SmallLabel(words.ui("no_free_slots"))
        }
        block.slots.forEach { slot ->
            val key = "write:consultation:${slot.id}"
            snapshot.courseworkWrites[key]?.takeIf { it.status != WriteStatus.CONFIRMED }?.let {
                Spacer(Modifier.height(8.dp)); WriteNotice(it, key in sync.active, null, key)
            }
        }
    }
}

@Composable
private fun BookingDialog(block: ConsultationBlock, slot: ConsultationSlot, snapshot: CampusSnapshot, repository: CampusRepository,
    sync: SyncStatus, onDismiss: () -> Unit) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    var reason by rememberSaveable(slot.id) { mutableStateOf("") }
    val requestId = rememberSaveable(slot.id) { "booking:${slot.id}:${nowMillis()}" }
    val key = "write:consultation:${slot.id}"
    val record = snapshot.courseworkWrites[key]
    val busy = key in sync.active
    LaunchedEffect(record?.status, record?.requestId) { if (record?.status == WriteStatus.CONFIRMED && record.requestId == requestId) onDismiss() }
    ZenDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(words.ui("book_office_hour")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(block.rangeName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text("${words.formatDate(campusToday(slot.start), true)} · ${words.time(slot.start)} – ${words.time(slot.end)}")
            if (block.room.isNotBlank()) Text("${words.ui("room")} ${block.room}")
            if (slot.note.isNotBlank()) Text(slot.note)
            if (block.requireReason != "no") OutlinedTextField(reason, { reason = it.take(2_000) }, modifier = Modifier.fillMaxWidth().testTag("booking-reason"),
                label = { Text(words.ui(if (block.reasonRequired) "booking_reason_required" else "booking_reason")) }, minLines = 2)
            if (block.confirmationText.isNotBlank()) ZenNotice(block.confirmationText, Tone.NEUTRAL)
            if (record?.requestId == requestId && record.status == WriteStatus.REJECTED)
                Text(record.failure?.let(words::failure).orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { DialogButton(words.ui("book"), { scope.launch { repository.bookConsultation(block, slot, reason, requestId) } },
            Modifier.testTag("confirm-booking"), enabled = !busy && (!block.reasonRequired || reason.isNotBlank()), primary = true) },
        dismissButton = { DialogButton(words.text("cancel"), onDismiss, enabled = !busy) })
}

/** Shared state for confirmed writes: sending, uncertain (check the official page) and rejected results. */
@Composable
internal fun WriteNotice(record: CourseworkWrite?, busy: Boolean, repository: CampusRepository?, key: String) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    when {
        busy -> ZenStatus(words.action("sending"), loading = true)
        record == null -> Unit
        record.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN) -> ZenNotice(words.ui("write_uncertain"), Tone.WARN,
            repository?.let { words.ui("checked") }, { scope.launch { repository?.acknowledgeWrite(key) } })
        record.status == WriteStatus.REJECTED -> ZenNotice(record.failure?.let(words::failure) ?: words.ui("write_rejected"), Tone.DANGER)
    }
}

/** Exports the loaded timetable and booked office hours as an .ics file. */
@Composable
fun CalendarExportDialog(repository: CampusRepository, sync: SyncStatus, onDismiss: () -> Unit) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val busy = "calendar-export" in sync.active
    val desktop = LocalKeyboardShortcuts.current
    ZenDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("calendar-export"), title = { Text(words.ui("export_calendar")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(words.ui("export_calendar_body"))
            val label = words.ui("office_hours")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ZenLink(words.ui("open_in_calendar"), { scope.launch { repository.exportCalendar(MaterialAction.OPEN, label) } },
                    Modifier.testTag("export-open"), enabled = !busy, symbol = Symbol.CALENDAR)
                if (!desktop) ZenLink(words.text("share_file"), { scope.launch { repository.exportCalendar(MaterialAction.SHARE, label) } },
                    enabled = !busy, symbol = Symbol.SHARE)
                ZenLink(words.text("save_downloads"), { scope.launch { repository.exportCalendar(MaterialAction.SAVE_TO_DOWNLOADS, label) } },
                    Modifier.testTag("export-save"), enabled = !busy, symbol = Symbol.DOWNLOAD)
            }
            if (busy) ZenStatus(words.ui("exporting"), loading = true)
            sync.materialExports["calendar-export"]?.let { ZenNotice("${words.text("file_saved_to")} $it", Tone.SUCCESS) }
            sync.errors["calendar-export"]?.let { ZenNotice(words.failure(it), Tone.DANGER) }
        } },
        confirmButton = { DialogButton(words.text("close"), onDismiss) })
}

/** Announcement comments in the announcement dialog, with a reply field when the course allows comments. */
@Composable
internal fun AnnouncementComments(announcement: Announcement, snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val comments = snapshot.newsComments[announcement.id]
    val key = "write:news-comment:${announcement.id}"
    var draft by rememberSaveable(announcement.id) { mutableStateOf("") }
    var requestId by rememberSaveable(announcement.id) { mutableStateOf("comment:${announcement.id}:${nowMillis()}") }
    val record = snapshot.courseworkWrites[key]
    LaunchedEffect(announcement.id) { repository.refreshNewsComments(announcement.id) }
    LaunchedEffect(record?.status, record?.requestId) {
        if (record?.status == WriteStatus.CONFIRMED && record.requestId == requestId) { draft = ""; requestId = "comment:${announcement.id}:${nowMillis()}" }
    }
    Column(Modifier.testTag("announcement-comments"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(words.ui("comments") + (comments?.size?.takeIf { it > 0 }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface)
        when {
            comments == null && CacheKeys.newsComments(announcement.id) in sync.active -> ZenStatus(words.ui("loading_comments"), loading = true)
            comments.isNullOrEmpty() -> SmallLabel(words.ui("no_comments"))
            else -> comments.forEach { comment ->
                Column {
                    Text(listOf(comment.author, if (comment.timestamp > 0) words.age(comment.timestamp, LocalNow.current) else "").filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(comment.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (announcement.commentsAllowed) {
            OutlinedTextField(draft, { draft = it.take(10_000) }, Modifier.fillMaxWidth().testTag("comment-field"),
                label = { Text(words.ui("write_comment")) }, minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { WriteNotice(record?.takeIf { it.requestId == requestId }, key in sync.active, repository, key) }
                ZenButton(words.ui("post_comment"), { scope.launch { repository.postNewsComment(announcement, draft, requestId) } },
                    Modifier.testTag("comment-send"), enabled = draft.isNotBlank() && key !in sync.active)
            }
        }
    }
}

/** Study groups DigiCampus suggests from shared courses; joining happens on DigiCampus. */
@Composable
internal fun StudyGroupSuggestions(snapshot: CampusSnapshot, repository: CampusRepository, onOpenUrl: (String) -> Unit) {
    val words = LocalWords.current
    LaunchedEffect(repository) { repository.refreshStudyGroups() }
    val groups = snapshot.studyGroups.orEmpty()
    if (groups.isEmpty()) return
    Column(Modifier.padding(top = 18.dp).testTag("study-groups"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(words.ui("study_groups"))
        SmallLabel(words.ui("study_groups_body"))
        Column {
            groups.forEachIndexed { index, group ->
                ZenListItem(GroupPosition.of(index, groups.size), Modifier.testTag("study-group-${group.id}"),
                    { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else group.sourceUrl) }) {
                    ZenIconTile(Symbol.GROUP)
                    ZenListText(group.title, group.description.ifBlank { group.subtitle }, modifier = Modifier.weight(1f))
                    ZenIcon(Symbol.EXTERNAL, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The lecture-period line used on Today and in Calendar. */
@Composable
internal fun termLine(status: TermStatus?, countdown: Boolean = true): String? {
    val words = LocalWords.current
    status ?: return null
    return termText(status, words, countdown)
}

/**
 * The lecture period as a date the student can check against the university calendar, plus how long is left when
 * [countdown] is set (Today). The calendar shows only the date: its countdown would be relative to the viewed week.
 */
internal fun termText(status: TermStatus, words: Words, countdown: Boolean): String {
    val start = campusToday(status.semester.lectureStart)
    val end = campusToday(status.semester.lectureEnd)
    val days = status.daysUntilChange
    fun left(): String = when {
        days > 14 -> words.ui("weeks_left").replace("%d", (days / 7).toString())
        days == 1 -> words.ui("day_left")
        else -> words.ui("days_left").replace("%d", days.toString())
    }
    return when (status.phase) {
        TermPhase.BEFORE_LECTURES -> "${words.ui("lectures_start")} ${words.formatDate(start, true)}" +
            if (countdown) " · " + (if (days == 1) words.ui("starts_tomorrow") else words.ui("in_days").replace("%d", days.toString())) else ""
        TermPhase.LECTURES -> if (countdown && days <= 0) words.ui("last_lecture_day")
            else "${words.ui("lectures_until")} ${words.formatDate(end, true)}" + if (countdown) " · " + left() else ""
        TermPhase.AFTER_LECTURES -> words.ui("lecture_free")
    }
}

@Composable
internal fun HolidayLabel(holiday: Holiday, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZenIcon(Symbol.LEAF, Modifier.size(15.dp), toneColors(Tone.SUCCESS).ink)
        Text(holiday.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = toneColors(Tone.SUCCESS).ink)
    }
}
