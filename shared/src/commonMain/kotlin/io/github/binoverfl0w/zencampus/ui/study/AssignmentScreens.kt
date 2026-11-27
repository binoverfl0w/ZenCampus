package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.SettingToggle
import io.github.binoverfl0w.zencampus.ui.action
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.EmptyMoment
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ReadingText
import io.github.binoverfl0w.zencampus.ui.design.RowChevron
import io.github.binoverfl0w.zencampus.ui.design.ScreenTitle
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ToneChip
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenNotice
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import io.github.binoverfl0w.zencampus.ui.design.ZenSheet
import io.github.binoverfl0w.zencampus.ui.design.ZenStatus
import io.github.binoverfl0w.zencampus.ui.design.courseTint
import io.github.binoverfl0w.zencampus.ui.design.fileSize
import io.github.binoverfl0w.zencampus.ui.study
import io.github.binoverfl0w.zencampus.ui.ui
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.*

val LocalAssignmentUpload = staticCompositionLocalOf<(String, String) -> Unit> { { _, _ -> } }

@Composable
fun AssignmentFolderRow(folder: CampusFolder, snapshot: CampusSnapshot, position: GroupPosition = GroupPosition.SINGLE, onClick: () -> Unit) {
    val words = LocalWords.current
    val note = snapshot.personalDeadlines[folder.id]
    ZenListItem(position, Modifier.testTag("assignment-${folder.id}"), onClick) {
        ZenIconTile(Symbol.FOLDER, courseTint(folder.courseId))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZenListText(folder.name, words.action("folder"),
                meta = note?.let { "${words.action("personal")} · ${words.formatDate(campusToday(it.due))} · ${words.time(it.due)}" }.orEmpty(),
                overline = snapshot.courses.firstOrNull { it.id == folder.courseId }?.title.orEmpty())
            if (note?.completed == true) ToneChip(words.action("local_done"), Tone.SUCCESS)
        }
        RowChevron()
    }
}

@Composable
fun StudentWriteResult(record: CourseworkWrite?, busy: Boolean, preview: Boolean, onOfficial: () -> Unit, onAcknowledge: () -> Unit) {
    if (record == null) return
    val words = LocalWords.current
    when {
        busy -> Text(words.action("sending"))
        record.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN) -> {
            ZenNotice("${words.action("uncertain")}. ${words.action("uncertain_body")}", Tone.WARN, words.action("acknowledge"), onAcknowledge,
                secondary = words.study("official"), onSecondary = onOfficial, secondaryExternal = true)
        }
        record.status == WriteStatus.REJECTED -> {
            Text(words.action("rejected"), style = MaterialTheme.typography.titleMedium)
            record.failure?.let { SmallLabel(words.failure(it)) }
        }
        record.status == WriteStatus.CONFIRMED -> {
            Text(words.action(if (record.document != null) { if (preview) "upload_preview" else "upload_confirmed" }
                else if (preview) "confirmed_preview" else "confirmed"), style = MaterialTheme.typography.titleMedium)
            SmallLabel("${words.formatDate(campusToday(record.startedAt))} · ${words.time(record.startedAt)}")
            record.document?.let { SmallLabel("${it.name} · ${fileSize(it.size)}"); SmallLabel("${words.action("upload_receipt")} · ${it.id}") }
        }
    }
}

@Composable
fun AssignmentFolderScreen(courseId: String, folderId: String, snapshot: CampusSnapshot, repository: CampusRepository,
    sync: SyncStatus, onOpenUrl: (String) -> Unit, onMaterials: () -> Unit) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val upload = LocalAssignmentUpload.current
    val folder = snapshot.assignmentFolders[courseId]?.firstOrNull { it.id == folderId }
    val note = snapshot.personalDeadlines[folderId]
    var deadlineDialog by rememberSaveable { mutableStateOf(false) }
    val key = "write:upload:$folderId"
    val record = snapshot.courseworkWrites[key]
    val busy = key in sync.active
    val unresolved = record?.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN)
    LaunchedEffect(courseId) { repository.refreshCoursework(courseId) }
    val official = { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else folder?.sourceUrl?.takeIf(String::isNotBlank) ?: courseFilesUrl(courseId)) }
    LazyColumn(Modifier.fillMaxSize().testTag("assignment-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (folder == null) item { EmptyMoment(words.study("unavailable"), words.action("folder_unavailable")) }
        else {
            item { ScreenTitle(folder.name, words.action("folder_body")); Spacer(Modifier.height(10.dp)); SmallLabel(snapshot.courses.firstOrNull { it.id == courseId }?.title.orEmpty()) }
            if (folder.description.isNotBlank()) item { ReadingText(folder.description) }
            item {
                SmallLabel(words.action("folder_scope"))
                folder.availableFrom?.let { SmallLabel("${words.action("available_from")} · ${words.formatDate(campusToday(it))} · ${words.time(it)}") }
                folder.availableUntil?.let { SmallLabel("${words.action("available_until")} · ${words.formatDate(campusToday(it - 60_000))} · ${words.time(it - 60_000)}") }
            }
            item {
                ZenPanel(Modifier.fillMaxWidth()) {
                    Text(words.action("personal"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp)); SmallLabel(words.action("personal_body"))
                    if (note != null) { Spacer(Modifier.height(12.dp)); Text("${words.formatDate(campusToday(note.due), true)} · ${words.time(note.due)}") }
                    ZenLink(words.action(if (note == null) "set_deadline" else "edit_deadline"), { deadlineDialog = true })
                    if (note != null) {
                        SettingToggle(words.action("local_done"), words.action("local_done_body"), note.completed, inset = 0.dp) { scope.launch { repository.completePersonalDeadline(folderId, it) } }
                        ZenLink(words.action("remove_deadline"), { scope.launch { repository.setPersonalDeadline(courseId, folderId, null) } })
                    }
                }
            }
            if (record != null) item {
                ZenPanel(Modifier.fillMaxWidth()) { StudentWriteResult(record, busy, snapshot.account.demo, official,
                    { scope.launch { repository.acknowledgeWrite(key) } }) }
            }
            item {
                if (folder.writable == true && !unresolved) ZenButton(words.action("choose_file"), { upload(courseId, folderId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("choose-assignment-file"), enabled = !busy, style = ButtonStyle.PRIMARY)
                else if (folder.writable != true) SmallLabel(words.action("upload_closed"))
                Spacer(Modifier.height(8.dp)); SmallLabel(words.action("file_limit"))
                SmallLabel(words.action("receipt_body"))
                ZenLink(words.action("materials"), onMaterials)
                ZenButton(words.study("official"), official, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
            }
        }
    }
    if (deadlineDialog) PersonalDeadlineDialog(note?.due, { deadlineDialog = false }) { due ->
        deadlineDialog = false; scope.launch { repository.setPersonalDeadline(courseId, folderId, due) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalDeadlineDialog(initial: Long?, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val words = LocalWords.current
    var selectingTime by rememberSaveable { mutableStateOf(false) }
    val local = initial?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(CAMPUS_ZONE) }
    val density = LocalDensity.current
    val compact = LocalWindowInfo.current.containerSize.width / density.density < 360f || density.fontScale > 1.2f
    var dateText by rememberSaveable { mutableStateOf((local?.date ?: campusToday()).toString()) }
    var selectedDateForTime by rememberSaveable { mutableStateOf(dateText) }
    val enteredDate = remember(dateText) { runCatching { LocalDate.parse(dateText) }.getOrNull() }
    val picker = rememberDatePickerState(initialSelectedDateMillis = (local?.date ?: campusToday()).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        initialDisplayMode = if (compact) DisplayMode.Input else DisplayMode.Picker)
    val time = rememberTimePickerState(initialHour = local?.hour ?: 18, initialMinute = local?.minute ?: 0, is24Hour = true)
    if (!selectingTime && compact) ZenDialog(onDismiss, title = { Text(words.action("personal")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(dateText, { dateText = it }, Modifier.fillMaxWidth().testTag("personal-deadline-date"),
                label = { Text(words.action("date")) }, supportingText = { Text(words.action("date_format")) },
                singleLine = true, isError = enteredDate == null)
        } },
        confirmButton = { DialogButton(words.action("deadline_time"), { selectedDateForTime = enteredDate!!.toString(); selectingTime = true }, enabled = enteredDate != null, primary = true) },
        dismissButton = { DialogButton(words.action("cancel"), onDismiss) })
    else if (!selectingTime) DatePickerDialog(onDismiss, confirmButton = { DialogButton(words.action("deadline_time"), {
            selectedDateForTime = Instant.fromEpochMilliseconds(picker.selectedDateMillis!!).toLocalDateTime(TimeZone.UTC).date.toString()
            selectingTime = true
        }, enabled = picker.selectedDateMillis != null, primary = true) },
        dismissButton = { DialogButton(words.action("cancel"), onDismiss) }) { DatePicker(picker) }
    else ZenDialog(onDismiss, title = { Text(words.action("deadline_time")) }, text = { TimeInput(time) },
        confirmButton = { DialogButton(words.action("save"), {
            val date = LocalDate.parse(selectedDateForTime)
            onSave(date.atTime(time.hour, time.minute).toInstant(CAMPUS_ZONE).toEpochMilliseconds())
        }, primary = true) }, dismissButton = { DialogButton(words.action("cancel"), onDismiss) })
}

@Composable
fun AssignmentUploadDialog(repository: CampusRepository, snapshot: CampusSnapshot, sync: SyncStatus) {
    val draft by repository.uploadDraft.collectAsState()
    val selectionError by repository.uploadSelectionError.collectAsState()
    val scope = rememberCoroutineScope()
    val words = LocalWords.current
    if (selectionError != null) ZenDialog(repository::dismissUpload, title = { Text(words.action("choose_file")) },
        text = { Text(words.action("select_error")) }, confirmButton = { DialogButton(words.action("close"), repository::dismissUpload) })
    val selected = draft ?: return
    val key = "write:upload:${selected.folderId}"
    val record = snapshot.courseworkWrites[key]
    val busy = key in sync.active
    val unresolved = record?.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN)
    val folder = snapshot.assignmentFolders[selected.courseId]?.firstOrNull { it.id == selected.folderId }
        ?: snapshot.folderContents.values.firstNotNullOfOrNull { it.currentFolder?.takeIf { f -> f.id == selected.folderId } }
    // The official action is supplied by the host composition; it never submits automatically.
    val openUrl = LocalAssignmentOpenUrl.current
    val showOfficial = { openUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else folder?.sourceUrl?.takeIf(String::isNotBlank) ?: courseFilesUrl(selected.courseId)) }
    ZenDialog(onDismissRequest = { if (!busy) repository.dismissUpload() }, title = { Text(words.action("upload")) },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(selected.name, style = MaterialTheme.typography.titleMedium); SmallLabel(fileSize(selected.bytes.size.toLong()))
                Text(snapshot.courses.firstOrNull { it.id == selected.courseId }?.title.orEmpty())
                Text(folder?.name.orEmpty())
                SmallLabel(words.action("upload_body"))
                if (snapshot.account.demo) SmallLabel(words.action("submit_preview"))
                sync.errors[key]?.let { SmallLabel(words.failure(it)) }
                StudentWriteResult(record, busy, snapshot.account.demo, showOfficial, { scope.launch { repository.acknowledgeWrite(key) } })
            }
        }, confirmButton = {
            if (record?.status == WriteStatus.CONFIRMED) DialogButton(words.action("close"), repository::dismissUpload)
            else DialogButton(words.action(if (busy) "sending" else "upload"), { scope.launch { repository.confirmUpload() } }, modifier = Modifier.testTag("confirm-assignment-upload"), enabled = !busy && !unresolved, primary = true)
        }, dismissButton = { if (!busy && record?.status != WriteStatus.CONFIRMED) DialogButton(words.action("cancel"), repository::dismissUpload) })
}
val LocalAssignmentOpenUrl = staticCompositionLocalOf<(String) -> Unit> { {} }

/** A file handed to ZenCampus by another app, waiting for the student to choose a submission folder. */
class IncomingShare(val name: String, val onChoose: (courseId: String, folderId: String) -> Unit, val onDismiss: () -> Unit)
val LocalIncomingShare = compositionLocalOf<IncomingShare?> { null }

/** Lists open submission folders; choosing one reads the file and opens the usual upload review. Nothing uploads from here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareIntoFolderSheet(share: IncomingShare, repository: CampusRepository, snapshot: CampusSnapshot, sync: SyncStatus) {
    val words = LocalWords.current
    val now = LocalNow.current
    // Submission folders come from the Study sync; nudge it in case the last one is old.
    LaunchedEffect(repository) { repository.requestStudySync() }
    val courses = snapshot.courses.associateBy { it.id }
    val folders = snapshot.assignmentFolders.values.flatten()
        .filter { it.writable == true && (it.availableFrom ?: 0) <= now && (it.availableUntil ?: Long.MAX_VALUE) > now }
        .distinctBy { it.id }
        .sortedWith(compareBy<CampusFolder> { snapshot.personalDeadlines[it.id]?.due ?: it.availableUntil ?: Long.MAX_VALUE }.thenBy { courses[it.courseId]?.title.orEmpty() })
    ZenSheet(share.onDismiss, Modifier.testTag("share-sheet")) {
        Column(Modifier.padding(horizontal = Zen.gutter).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(words.ui("share_into"), style = MaterialTheme.typography.titleLarge)
            Text(share.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SmallLabel(words.ui("share_into_body"))
            if (folders.isEmpty()) {
                if (sync.refreshing) ZenStatus(words.text("refreshing"), loading = true)
                else ZenNotice(words.ui("share_no_folders"), Tone.NEUTRAL)
            }
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                folders.forEachIndexed { index, folder ->
                    ZenListItem(GroupPosition.of(index, folders.size), Modifier.testTag("share-folder-${folder.id}"), { share.onChoose(folder.courseId, folder.id) }) {
                        ZenIconTile(Symbol.FOLDER, courseTint(folder.courseId))
                        ZenListText(folder.name, courses[folder.courseId]?.title.orEmpty(),
                            meta = folder.availableUntil?.let { "${words.action("available_until")} · ${words.formatDate(campusToday(it - 60_000))} · ${words.time(it - 60_000)}" }.orEmpty(),
                            modifier = Modifier.weight(1f))
                        RowChevron()
                    }
                }
            }
            ZenButton(words.text("cancel"), share.onDismiss, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
        }
    }
}
