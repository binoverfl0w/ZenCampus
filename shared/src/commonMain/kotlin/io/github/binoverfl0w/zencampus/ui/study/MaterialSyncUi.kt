package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.SettingToggle
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenNotice
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import io.github.binoverfl0w.zencampus.ui.design.ZenStatus
import io.github.binoverfl0w.zencampus.ui.design.fileSize
import io.github.binoverfl0w.zencampus.ui.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalMaterialDefaultFolder = staticCompositionLocalOf<String?> { null }
val LocalMaterialResetFolder = staticCompositionLocalOf<String?> { null }
val LocalMaterialAutomaticSync = staticCompositionLocalOf { true }

/** Where a course's synced files go, as shown to the student. */
@Composable
internal fun syncFolderLabel(snapshot: CampusSnapshot, courseId: String): String {
    val words = LocalWords.current
    return snapshot.preferences.materialDestinations[courseId]?.label
        ?: "${LocalMaterialDefaultFolder.current ?: words.text("material_default_folder")} / ${materialFolderName(snapshot.courses.firstOrNull { it.id == courseId }?.title ?: courseId)}"
}

/** The Materials tab panel: the save folder, the last sync and a button to bring every file up to date. */
@Composable
fun CourseSyncPanel(course: Course, snapshot: CampusSnapshot, repository: CampusRepository, onChooseFolder: (() -> Unit)?) {
    val words = LocalWords.current
    val now = LocalNow.current
    val scope = rememberCoroutineScope()
    val progress by repository.materialSync.collectAsState()
    var syncing by remember { mutableStateOf(false) }
    val record = snapshot.materialSync[course.id]
    val destination = snapshot.preferences.materialDestinations[course.id]
    // A cheap estimate from the cached file list; the dialog rereads the course before writing anything.
    val estimate = snapshot.courseMaterials[course.id]?.let {
        materialSyncPlan(it, record, destination?.location.orEmpty(), DigiCampusProvider.MAX_FILE_BYTES)
    }
    val running = progress?.running == true && course.id in progress?.courseIds.orEmpty()
    ZenPanel(Modifier.fillMaxWidth().testTag("course-sync"), padding = 14.dp) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(words.ui("sync_files"), style = MaterialTheme.typography.titleSmall)
                SmallLabel(when {
                    running -> "${progress!!.done} / ${progress!!.total} · ${progress!!.current}"
                    record == null || record.lastSync == 0L -> words.ui("sync_never")
                    estimate != null && estimate.pending.isNotEmpty() ->
                        "${record.files.size} ${words.ui("sync_files_count")} · ${estimate.pending.size} ${words.ui("sync_updates")}"
                    else -> "${record.files.size} ${words.ui("sync_files_count")} · ${words.ui("material_synced")} ${words.age(record.lastSync, now)}"
                })
            }
            ZenButton(words.ui(if (running) "sync_running" else "sync"), { syncing = true }, Modifier.testTag("sync-course-materials"),
                style = ButtonStyle.TONAL, symbol = Symbol.DOWNLOAD)
        }
        if (running) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { progress!!.fraction }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        SmallLabel("${words.ui("sync_into")} ${syncFolderLabel(snapshot, course.id)}")
        if (onChooseFolder != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZenLink(words.text(if (destination == null) "material_choose_folder" else "material_change_folder"), onChooseFolder,
                modifier = Modifier.testTag("choose-material-folder"))
            if (destination != null) ZenLink(LocalMaterialResetFolder.current ?: words.text("material_reset_folder"), { scope.launch { repository.setMaterialDestination(course.id, null) } },
                modifier = Modifier.testTag("reset-material-folder"))
        }
        if (record != null && LocalMaterialAutomaticSync.current) SettingToggle(words.ui("sync_automatic"), words.ui("sync_automatic_body"), record.automatic, inset = 0.dp) {
            scope.launch { repository.setMaterialSyncAutomatic(course.id, it) }
        }
    }
    if (syncing) MaterialSyncDialog(repository, snapshot, listOf(course.id), onChooseFolder) { syncing = false }
}

/** How long a successful sync's result stays before its dialog closes. */
private const val SYNC_RESULT_MILLIS = 1_500L

private val MaterialSyncProgress.fraction: Float get() = when {
    bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    total > 0 -> (done.toFloat() / total).coerceIn(0f, 1f)
    else -> 1f
}

/**
 * Confirms a sync with its size before anything is downloaded, then shows progress and the result.
 * [courseIds] null means every course of the selected semester.
 */
@Composable
fun MaterialSyncDialog(repository: CampusRepository, snapshot: CampusSnapshot, courseIds: List<String>?,
    onChooseFolder: (() -> Unit)?, onDismiss: () -> Unit) {
    val words = LocalWords.current
    val automaticSyncAvailable = LocalMaterialAutomaticSync.current
    val progress by repository.materialSync.collectAsState()
    var plans by remember { mutableStateOf<List<MaterialSyncPlan>?>(null) }
    var failure by remember { mutableStateOf<FailureKind?>(null) }
    var started by remember { mutableStateOf(progress?.running == true) }
    val single = courseIds?.singleOrNull()
    var automatic by remember { mutableStateOf(single?.let { snapshot.materialSync[it]?.automatic } ?: false) }
    LaunchedEffect(repository, courseIds) {
        if (started) return@LaunchedEffect
        try { plans = repository.planMaterialSync(courseIds) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = (e as? CampusException)?.kind ?: FailureKind.OFFLINE }
    }
    val current = progress
    val ready = plans
    val missingFolder = !repository.syncsWithoutFolder && ready.orEmpty().any { snapshot.preferences.materialDestinations[it.courseId] == null }
    val dismiss = { if (current?.running != true) repository.dismissMaterialSync(); onDismiss() }
    val nothingToSync = !started && failure == null && ready != null && ready.sumOf { it.pending.size } == 0
    // A clean sync, or a check that finds everything up to date, closes by itself after a moment to read the result.
    // Failures, stops and a missing folder stay until closed.
    val succeeded = started && current != null && !current.running && current.failed.isEmpty() && !current.cancelled && current.stopped == null
    val finished = succeeded || nothingToSync && !missingFolder
    LaunchedEffect(finished) {
        if (finished) { delay(SYNC_RESULT_MILLIS); dismiss() }
    }
    ZenDialog(onDismissRequest = dismiss, modifier = Modifier.testTag("material-sync-dialog"),
        title = { Text(words.ui(if (courseIds == null) "sync_all_title" else "sync_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    started && current != null -> SyncProgressBody(current)
                    failure != null -> Text(words.failure(failure!!))
                    ready == null -> ZenStatus(words.ui("sync_checking"), loading = true)
                    else -> {
                        val pending = ready.sumOf { it.pending.size }
                        val upToDate = ready.sumOf { it.upToDate }
                        val skipped = ready.sumOf { it.skipped.size }
                        if (pending == 0) Text(words.ui("sync_up_to_date"), Modifier.testTag("sync-up-to-date"), style = MaterialTheme.typography.titleMedium)
                        else Text("$pending ${words.ui("sync_files_count")} · ${fileSize(ready.sumOf { it.bytes })}",
                            Modifier.testTag("sync-summary"), style = MaterialTheme.typography.titleMedium)
                        if (upToDate > 0) SmallLabel("$upToDate ${words.ui("sync_already")}")
                        if (skipped > 0) SmallLabel("$skipped ${words.ui("sync_skipped")}")
                        SmallLabel("${words.ui("sync_into")} ${if (single != null) syncFolderLabel(snapshot, single) else words.ui("sync_into_each")}")
                        if (pending > 0) SmallLabel(words.ui("sync_keeps_removed"))
                        if (missingFolder) ZenNotice(words.ui("sync_choose_folder"), Tone.WARN,
                            action = if (onChooseFolder != null && single != null) words.text("material_choose_folder") else null, onAction = { onChooseFolder?.invoke() })
                        if (automaticSyncAvailable && pending > 0) SettingToggle(words.ui("sync_automatic"), words.ui("sync_automatic_body"), automatic, inset = 0.dp) { automatic = it }
                    }
                }
            }
        },
        confirmButton = {
            when {
                started && current?.running == true -> DialogButton(words.ui("sync_stop"), repository::cancelMaterialSync, destructive = true)
                started || failure != null -> DialogButton(words.text("close"), dismiss, primary = true)
                ready != null && ready.sumOf { it.pending.size } > 0 && !missingFolder -> DialogButton(words.ui("sync"), {
                    started = true; repository.startMaterialSync(ready, if (automaticSyncAvailable && (single != null || automatic)) automatic else null)
                }, modifier = Modifier.testTag("confirm-material-sync"), primary = true)
                ready != null -> DialogButton(words.text("close"), dismiss, primary = true)
                else -> {}
            }
        },
        dismissButton = {
            if (started && current?.running == true) DialogButton(words.ui("sync_background"), onDismiss)
            else if (!started && failure == null && !nothingToSync) DialogButton(words.text("cancel"), dismiss)
        })
}

@Composable
private fun SyncProgressBody(progress: MaterialSyncProgress) {
    val words = LocalWords.current
    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().testTag("sync-progress"))
    Text("${progress.done} / ${progress.total} · ${fileSize(progress.bytesDone)} / ${fileSize(progress.bytesTotal)}",
        style = MaterialTheme.typography.titleSmall)
    if (progress.running && progress.current.isNotBlank()) SmallLabel(progress.current)
    if (!progress.running) {
        val written = progress.done - progress.failed.size
        Text(when {
            progress.cancelled -> "${words.ui("sync_stopped")} · $written ${words.ui("sync_written")}"
            progress.stopped != null -> "${words.failure(progress.stopped)} · $written ${words.ui("sync_written")}"
            else -> "$written ${words.ui("sync_written")}"
        }, Modifier.testTag("sync-result"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (progress.failed.isNotEmpty()) {
            val reasons = progress.failed.values.groupingBy { it }.eachCount()
            SmallLabel("${progress.failed.size} ${words.ui("sync_failed")}: " + reasons.entries.joinToString(" · ") { "${words.failure(it.key)} (${it.value})" })
        }
    }
}
