package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.action
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.CourseBadge
import io.github.binoverfl0w.zencampus.ui.design.CoursePinButton
import io.github.binoverfl0w.zencampus.ui.design.EmptyMoment
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LoadingCards
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalReducedMotion
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
import io.github.binoverfl0w.zencampus.ui.design.ZenDivider
import io.github.binoverfl0w.zencampus.ui.design.ZenGroup
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import io.github.binoverfl0w.zencampus.ui.design.ZenTabs
import io.github.binoverfl0w.zencampus.ui.design.courseTint
import io.github.binoverfl0w.zencampus.ui.design.fileSize
import io.github.binoverfl0w.zencampus.ui.study
import io.github.binoverfl0w.zencampus.ui.ui
import kotlinx.coroutines.launch

val LocalChooseMaterialFolder = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
fun CourseScreen(
    course: Course, snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus,
    tab: Int, onTab: (Int) -> Unit, folderPath: String, onFolder: (String) -> Unit,
    onFolderAncestor: (String) -> Unit, onCourses: () -> Unit, onAnnouncement: (String) -> Unit,
    onOpenUrl: (String) -> Unit, onTask: (String, String) -> Unit = { _, _ -> },
    onCommunity: (String, String) -> Unit = { _, _ -> },
) {
    val words = LocalWords.current
    val now = LocalNow.current
    val scope = rememberCoroutineScope()
    val chooseMaterialFolder = LocalChooseMaterialFolder.current
    val stateHolder = rememberSaveableStateHolder()
    var submittedTasks by rememberSaveable(course.id) { mutableStateOf(false) }
    val parentId = folderPath.takeIf { it.isNotBlank() }?.substringAfterLast('|')
    val folderKey = CampusRepository.folderKey(course.id, parentId)
    val content = snapshot.folderContents[folderKey]
    val currentFolder = content?.currentFolder
    val folders = content?.folders.orEmpty().distinctBy { it.id }.sortedBy { it.name.lowercase() }
    val documents = content?.documents.orEmpty().map { snapshot.courseMaterials[course.id]?.latestMetadata(it) ?: it }
        .distinctBy { it.id }.sortedBy { it.name.lowercase() }
    val next = snapshot.events.filter { it.courseId == course.id && it.end > now && !it.cancelled }.minByOrNull { it.start }
    LaunchedEffect(course.id) { repository.visitCourse(course.id) }
    LaunchedEffect(course.id, tab, submittedTasks) { if (tab == 3 && submittedTasks) repository.refreshCourseSubmitted(course.id) }
    LaunchedEffect(parentId, tab) { if (tab == 2) repository.refreshFolder(course.id, parentId) }
    LaunchedEffect(folderKey, folders.firstOrNull { it.readable && it.empty != true }?.id, tab) {
        if (tab == 2) folders.firstOrNull { it.readable && it.empty != true }?.let { repository.prefetchFolder(course.id, it.id) }
    }
    stateHolder.SaveableStateProvider("tab:$tab:${if (tab == 2) folderPath else ""}") {
        LazyColumn(Modifier.fillMaxSize().testTag("course-scroll"), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                val tint = courseTint(course.id)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CourseBadge(course, 52.dp)
                    Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(listOf(course.number, course.typeName).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tint.ink)
                        Text(course.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.semantics { heading() })
                        if (course.teacher.isNotBlank() || course.subtitle.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ZenIcon(Symbol.PEOPLE, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(course.teacher.ifBlank { course.subtitle }, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    CoursePinButton(course.id, course.id in snapshot.preferences.pinnedCourses,
                        { scope.launch { repository.toggleCoursePin(course.id) } })
                }
                Spacer(Modifier.height(18.dp))
                ZenTabs(listOf(words.text("overview"), words.text("updates"), words.text("materials"), words.study("coursework")),
                    tab, onTab, tags = listOf("course-tab-overview", "course-tab-updates", "course-tab-materials", "course-tab-coursework"), fill = true)
            }
            when (tab) {
                3 -> courseworkItems(course, snapshot, repository, sync, submittedTasks, { submittedTasks = it }, onTask, onOpenUrl,
                    { scope.launch { repository.refreshCourseTools(course.id, true) } })
                0 -> {
                    if (next != null) item {
                        SectionTitle(words.text("next_session"))
                        Spacer(Modifier.height(8.dp))
                        ZenPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
                            Text(words.formatDate(next.date, true), style = MaterialTheme.typography.labelLarge, color = courseTint(course.id).ink)
                            Spacer(Modifier.height(4.dp))
                            EventRow(next, course)
                        }
                    }
                    recentMaterialItems(course, snapshot, repository, sync,
                        maxOf(now, snapshot.updatedAt[CacheKeys.courseFiles(course.id)] ?: now), onOpenUrl)
                    if (course.description.isNotBlank() || course.subtitle.isNotBlank()) item {
                        SectionTitle(words.text("about_course"))
                        Spacer(Modifier.height(10.dp))
                        ReadingText(course.description.ifBlank { course.subtitle })
                    }
                    if (course.location.isNotBlank()) item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ZenIcon(Symbol.LOCATION, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                            Text(course.location)
                        }
                    }
                    item { CourseCommunityLinks(course, snapshot, onCommunity) }
                    item { CourseToolsSection(course, snapshot, sync, onOpenUrl,
                        { scope.launch { repository.refreshCourseTools(course.id, true) } }) }
                    item {
                        ZenButton(words.text("open_official"), { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else course.sourceUrl) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), style = ButtonStyle.OUTLINE, trailing = Symbol.EXTERNAL)
                    }
                }
                1 -> {
                    val updates = snapshot.announcements.filter { it.courseId == course.id && it.expires > now }
                    if (updates.isEmpty()) item {
                        if (sync.active.contains(CacheKeys.news(course.id))) LoadingCards()
                        else EmptyMoment(words.text("no_updates"), words.text("stay_in_loop"), Symbol.CHECK)
                    }
                    else item {
                        ZenGroup {
                            updates.forEachIndexed { index, announcement ->
                                if (index > 0) ZenDivider()
                                AnnouncementRow(announcement, course, announcement.id !in snapshot.preferences.readAnnouncements) { onAnnouncement(announcement.id) }
                            }
                        }
                    }
                }
                2 -> {
                    // Saving a copy and syncing share one folder per course.
                    if (parentId == null) item(key = "course-sync") {
                        CourseSyncPanel(course, snapshot, repository, chooseMaterialFolder?.let { choose -> { choose(course.id) } })
                    }
                    item {
                        val path = folderPath.split('|').filter { it.isNotBlank() }
                        val knownFolders = snapshot.courseMaterials[course.id]?.folders.orEmpty() +
                            snapshot.folderContents.filterKeys { it.startsWith("folder:${course.id}:") }.values.flatMap { it.folders }
                        Box {
                            FlowRow(Modifier.fillMaxWidth().padding(start = 0.dp, end = 8.dp).testTag("materials-breadcrumbs"),
                                verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                                ZenLink(words.text("courses"), onCourses, modifier = Modifier.testTag("materials-courses"))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(6.dp))
                                    ZenIcon(Symbol.NEXT, Modifier.size(12.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(6.dp))
                                    if (path.isEmpty()) Text(words.text("files"), Modifier.testTag("materials-root"), style = MaterialTheme.typography.labelLarge)
                                    else ZenLink(words.text("files"), { onFolderAncestor("") }, modifier = Modifier.testTag("materials-root"))
                                }
                                path.forEachIndexed { index, id ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(Modifier.width(6.dp))
                                        ZenIcon(Symbol.NEXT, Modifier.size(12.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(6.dp))
                                        val name = knownFolders.firstOrNull { it.id == id }?.name ?: words.text("materials")
                                        if (index == path.lastIndex) Text(name, Modifier.widthIn(max = 230.dp).testTag("materials-ancestor-$id"),
                                            style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        else ZenLink(name, { onFolderAncestor(path.take(index + 1).joinToString("|")) },
                                            Modifier.widthIn(max = 230.dp).testTag("materials-ancestor-$id"))
                                    }
                                }
                            }
                        }
                    }
                    if (parentId != null && currentFolder != null && (currentFolder.acceptsUploads || !currentFolder.readable)) item {
                        Text(words.text("folder_official_body"), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ZenButton(words.text("open_official"), { onOpenUrl(currentFolder.sourceUrl) }, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, trailing = Symbol.EXTERNAL)
                    }
                    if (currentFolder?.writable == true) item {
                        val upload = LocalAssignmentUpload.current
                        ZenButton(words.action("choose_file"), { upload(course.id, currentFolder.id) }, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
                    }
                    if (folders.isEmpty() && documents.isEmpty()) item {
                        if (sync.active.contains(folderKey)) LoadingCards()
                        else if (content?.currentFolder?.readable == false)
                            EmptyMoment(words.text("folder_restricted"), words.text("folder_restricted_body"), Symbol.FOLDER)
                        else EmptyMoment(words.text("no_materials"), words.text("no_materials_body"), Symbol.FOLDER)
                    }
                    if (folders.isNotEmpty() || documents.isNotEmpty()) item(key = "folder-contents:$folderKey") {
                        val count = folders.size + documents.size
                        Column {
                            folders.forEachIndexed { index, folder ->
                                MaterialFolderRow(folder, course, snapshot, GroupPosition.of(index, count)) { onFolder(folder.id) }
                            }
                            documents.forEachIndexed { index, document ->
                                CourseDocumentRow(document, snapshot, repository, sync, onOpenUrl,
                                    position = GroupPosition.of(folders.size + index, count))
                            }
                        }
                    }
                    if (parentId == null) courseToolsItems(course, snapshot, sync, onOpenUrl,
                        { scope.launch { repository.refreshCourseTools(course.id, true) } })
                }
            }
        }
    }
}

@Composable
private fun MaterialFolderRow(folder: CampusFolder, course: Course, snapshot: CampusSnapshot, position: GroupPosition, onClick: () -> Unit) {
    val words = LocalWords.current
    ZenListItem(position, Modifier.testTag("material-folder-${folder.id}"), onClick) {
        ZenIconTile(Symbol.FOLDER, courseTint(course.id))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZenListText(folder.name, folder.description)
            FolderActivityDetails(folder, snapshot.courseMaterials[course.id])
            if (folder.acceptsUploads) Text(words.text(if (folder.submissionFolder) "submission_folder" else "upload_folder"),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        RowChevron()
    }
}

@Composable
fun DocumentRow(
    document: Document, local: LocalDocument?, sync: SyncStatus,
    onOpen: () -> Unit, onPin: () -> Unit, onRemove: () -> Unit, onOfficial: (() -> Unit)? = null,
    onOpenWith: (() -> Unit)? = null, onShare: (() -> Unit)? = null, onExport: (() -> Unit)? = null,
    onRetry: () -> Unit = onOpen,
    modifier: Modifier = Modifier, context: String? = null, position: GroupPosition = GroupPosition.SINGLE,
) {
    val words = LocalWords.current
    var menu by remember { mutableStateOf(false) }
    val downloading = CacheKeys.download(document.id) in sync.active
    val action = MaterialAction.entries.firstOrNull { value -> sync.active.any { it.startsWith("material:${value.name}:${document.id}:") } }
    val busy = downloading || action != null
    val error = sync.errors[CacheKeys.download(document.id)]
    ZenListItem(position, modifier, onClick = if (document.downloadable) onOpen else null) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            if (busy && !LocalReducedMotion.current) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else ZenIcon(if (busy) Symbol.CLOCK else Symbol.FILE, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZenListText(document.name,
                if (!document.downloadable) words.text("download_unavailable")
                else "${document.name.substringAfterLast('.', "FILE").uppercase()} · ${fileSize(document.size)}",
                meta = if (document.activityAt > 0) "${words.material(if (document.updatedAfterCreation) "updated" else "added")} · ${words.materialTime(document.activityAt)}" else "",
                overline = context.orEmpty(), titleLines = 3)
            if (busy) {
                val label = if (downloading) "file_downloading" else when (action) {
                    MaterialAction.SAVE_TO_DOWNLOADS -> "file_exporting"
                    MaterialAction.KEEP_OFFLINE -> "file_saving_offline"
                    else -> "file_opening"
                }
                val progress = sync.materialProgress[document.id]
                val detail = if (downloading && progress != null) {
                    progress.total?.let { " · ${(progress.bytes * 100 / it).coerceIn(0, 99)}%" } ?: " · ${fileSize(progress.bytes)}"
                } else ""
                Text(words.text(label) + detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            sync.materialExports[document.id]?.let { location ->
                Text(words.text("file_saved_to") + " " + location, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (local != null) FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToneChip(words.text(if (local.pinned) "saved_offline" else "ready_offline"), Tone.PRIMARY)
                if (document.modified > local.version) ToneChip(words.text("newer_copy"), Tone.WARN)
            }
            if (error != null && !busy) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(words.failure(error), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                ZenLink(words.text("retry"), onRetry)
            }
        }
        Box {
            IconAction(Symbol.MORE, words.text("files"), { menu = true })
            DropdownMenu(menu, { menu = false }) {
                if (document.downloadable) {
                    DropdownMenuItem(text = { Text(words.text("open_file")) }, onClick = { menu = false; onOpen() })
                    if (onOpenWith != null) DropdownMenuItem(text = { Text(words.text("open_with")) }, onClick = { menu = false; onOpenWith() })
                    if (onShare != null) DropdownMenuItem(text = { Text(words.text("share_file")) }, onClick = { menu = false; onShare() })
                    if (onExport != null) DropdownMenuItem(text = { Text(words.text("save_downloads")) }, onClick = { menu = false; onExport() })
                    if (local?.pinned != true) DropdownMenuItem(text = { Text(words.text("keep_offline")) }, onClick = { menu = false; onPin() })
                }
                if (local != null) DropdownMenuItem(text = { Text(words.text("remove_download")) }, onClick = { menu = false; onRemove() })
                if (onOfficial != null) DropdownMenuItem(text = { Text(words.text("open_official")) }, onClick = { menu = false; onOfficial() })
            }
        }
    }
}

@Composable
fun DownloadsScreen(snapshot: CampusSnapshot, repository: CampusRepository, sync: SyncStatus) {
    val words = LocalWords.current
    val scope = rememberCoroutineScope()
    val documents = snapshot.localDocuments.sortedWith(compareByDescending<LocalDocument> { it.pinned }.thenByDescending { it.lastAccess })
    val usedBytes = documents.sumOf { it.size }
    val metadata = (snapshot.courseMaterials.values.flatMap { it.documents } + snapshot.folderContents.values.flatMap { it.documents })
        .groupBy { it.id }.mapValues { (_, versions) -> versions.maxBy { it.modified } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Zen.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenTitle(words.text("downloads"), words.text("downloads_body")) }
        item {
            var syncingAll by remember { mutableStateOf(false) }
            val progress by repository.materialSync.collectAsState()
            ZenPanel(Modifier.fillMaxWidth().testTag("sync-all-panel"), padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(words.ui("sync_all_title"), style = MaterialTheme.typography.titleSmall)
                        SmallLabel(progress?.takeIf { it.running }?.let { "${it.done} / ${it.total} · ${it.current}" } ?: words.ui("sync_all_body"))
                    }
                    ZenButton(words.ui("sync"), { syncingAll = true }, Modifier.testTag("sync-all-materials"), style = ButtonStyle.TONAL, symbol = Symbol.DOWNLOAD)
                }
            }
            if (syncingAll) MaterialSyncDialog(repository, snapshot, null, null) { syncingAll = false }
        }
        item {
            ZenPanel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                SmallLabel(words.text("file_location_hint"))
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (usedBytes.toFloat() / CampusRepository.STORAGE_BUDGET.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SmallLabel("${fileSize(usedBytes)} / ${fileSize(CampusRepository.STORAGE_BUDGET)}")
            }
        }
        if (documents.isEmpty()) item { EmptyMoment(words.text("no_downloads"), words.text("no_downloads_body"), Symbol.DOWNLOAD) }
        if (documents.isNotEmpty()) item(key = "downloads") { Column {
            documents.forEachIndexed { index, saved ->
                val document = metadata[saved.document.id] ?: saved.document
                DocumentRow(document, saved, sync,
                    { repository.requestMaterial(document) },
                    { repository.requestMaterial(document, MaterialAction.KEEP_OFFLINE) },
                    { scope.launch { repository.removeDownload(saved.document.id) } },
                    onOpenWith = { repository.requestMaterial(document, MaterialAction.OPEN_WITH) },
                    onShare = { repository.requestMaterial(document, MaterialAction.SHARE) },
                    onExport = { repository.requestMaterial(document, MaterialAction.SAVE_TO_DOWNLOADS) },
                    onRetry = { repository.retryMaterial(document) }, position = GroupPosition.of(index, documents.size))
            }
        } }
    }
}
