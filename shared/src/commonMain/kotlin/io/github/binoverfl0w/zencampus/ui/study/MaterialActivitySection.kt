package io.github.binoverfl0w.zencampus.ui.study

import io.github.binoverfl0w.zencampus.ui.wordTable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.SectionTitle
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import kotlinx.coroutines.launch

fun Words.material(key: String): String = (if (german) materialDe else materialEn)[key] ?: materialEn[key] ?: key
fun Words.materialTime(date: Long): String = "${formatDate(campusToday(date))} ${campusToday(date).year} · ${time(date)}"
internal val materialEn = wordTable(
    "recent" to "Recent files", "refresh" to "Refresh", "window" to "Last 14 days · all accessible subfolders · newest first",
    "checking" to "Checking recent files…", "not_loaded" to "Recent files haven’t been checked yet.",
    "unavailable" to "Recent files could not be checked. Try again or check DigiCampus.",
    "saved" to "Saved list · refresh failed. Files may have changed.",
    "empty" to "No dated file additions or updates in the last 14 days.",
    "undated" to "files have no published date and cannot be placed in this tracker.",
    "checked" to "File list checked", "latest_file" to "Latest file activity", "folder_updated" to "Folder updated",
    "folder_created" to "Folder created", "date_unknown" to "Update time unavailable",
    "added" to "Added", "updated" to "Updated",
)
internal val materialDe = wordTable(
    "recent" to "Aktuelle Dateien",
    "refresh" to "Aktualisieren",
    "window" to "Letzte 14 Tage · alle zugänglichen Unterordner · neueste zuerst",
    "checking" to "Aktuelle Dateien werden geprüft…", "not_loaded" to "Aktuelle Dateien wurden noch nicht geprüft.",
    "unavailable" to "Aktuelle Dateien konnten nicht geprüft werden. Versuche es erneut oder prüfe DigiCampus.",
    "saved" to "Gespeicherte Liste · Aktualisierung fehlgeschlagen. Dateien können sich geändert haben.",
    "empty" to "Keine datierten neuen oder aktualisierten Dateien in den letzten 14 Tagen.",
    "undated" to "Dateien haben kein veröffentlichtes Datum und fehlen daher in dieser Übersicht.",
    "checked" to "Dateiliste geprüft", "latest_file" to "Letzte Dateiaktivität", "folder_updated" to "Ordner aktualisiert",
    "folder_created" to "Ordner erstellt", "date_unknown" to "Aktualisierungszeit nicht verfügbar",
    "added" to "Hinzugefügt", "updated" to "Aktualisiert",
)

@Composable
fun FolderActivityDetails(folder: CampusFolder, materials: CourseMaterials?) {
    val words = LocalWords.current
    val latest = materials?.latestFileActivity(folder.id)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (latest != null) SmallLabel("${words.material("latest_file")} · ${words.materialTime(latest)}")
        val ownTime = maxOf(folder.modified, folder.created)
        if (ownTime > 0) SmallLabel("${words.material(if (folder.modified > 0) "folder_updated" else "folder_created")} · ${words.materialTime(ownTime)}")
        else if (latest == null) SmallLabel(words.material("date_unknown"))
    }
}

fun LazyListScope.recentMaterialItems(course: Course, snapshot: CampusSnapshot, repository: CampusRepository,
    sync: SyncStatus, now: Long, onOpenUrl: (String) -> Unit) {
    val key = CacheKeys.courseFiles(course.id)
    val index = snapshot.courseMaterials[course.id]
    val files = index?.recentFiles(now).orEmpty()
    item(key = "recent-files-header") {
        val words = LocalWords.current
        val scope = rememberCoroutineScope()
        Column(Modifier.testTag("recent-files-section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(words.material("recent"), words.material("refresh"), { scope.launch { repository.refreshCourseMaterials(course.id, true) } })
            SmallLabel(words.material("window"))
            when {
                key in sync.active && index == null -> SmallLabel(words.material("checking"))
                key in sync.errors -> SmallLabel(words.material(if (index != null) "saved" else "unavailable"))
                index == null -> SmallLabel(words.material("not_loaded"))
                files.isEmpty() -> SmallLabel(words.material("empty"))
            }
            snapshot.updatedAt[key]?.let { SmallLabel("${words.material("checked")} ${words.age(it, now)}") }
            if (index != null && index.undatedCount > 0) SmallLabel("${index.undatedCount} ${words.material("undated")}")
        }
    }
    if (files.isNotEmpty()) item(key = "recent-files") { Column {
        files.forEachIndexed { position, document ->
            CourseDocumentRow(document, snapshot, repository, sync, onOpenUrl,
                index?.path(document.folderId)?.ifBlank { null }, Modifier.testTag("recent-file-${document.id}"), GroupPosition.of(position, files.size))
        }
    } }
}

@Composable
fun CourseDocumentRow(document: Document, snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus,
    onOpenUrl: (String) -> Unit, context: String? = null, modifier: Modifier = Modifier, position: GroupPosition = GroupPosition.SINGLE) {
    val scope = rememberCoroutineScope()
    DocumentRow(document, snapshot.localDocuments.firstOrNull { it.document.id == document.id }, sync,
        { repository.requestMaterial(document) },
        { repository.requestMaterial(document, MaterialAction.KEEP_OFFLINE) },
        { scope.launch { repository.removeDownload(document.id) } },
        { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else documentPageUrl(document)) },
        onOpenWith = { repository.requestMaterial(document, MaterialAction.OPEN_WITH) },
        onShare = { repository.requestMaterial(document, MaterialAction.SHARE) },
        onExport = { repository.requestMaterial(document, MaterialAction.SAVE_TO_DOWNLOADS) },
        onRetry = { repository.retryMaterial(document) }, modifier = modifier, context = context, position = position)
}
