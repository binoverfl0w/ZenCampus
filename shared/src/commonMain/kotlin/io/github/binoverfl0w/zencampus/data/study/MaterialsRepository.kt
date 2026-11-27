package io.github.binoverfl0w.zencampus.data.study

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.retryMaterialDownload
import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.FILE_TTL
import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.MATERIAL_ACTIVITY_TTL
import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.STORAGE_BUDGET
import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.folderKey
import io.github.binoverfl0w.zencampus.model.*
import kotlin.reflect.typeOf
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json

/**
 * Course files: folders, file lists, opening, saving and offline copies.
 *
 * Part of [CampusRepository]: each operation runs with the repository as receiver (`with(repo)`), so the shared
 * cache engine, provider, store and other areas are reached exactly as from the repository itself.
 */
internal class MaterialsRepository(private val repo: CampusRepository) {
    /** Folders below the course folder for Vips attachments opened this session, by document id. */
    private val linkedFolders = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Explicit history view loads metadata only; it never opens a Vips exercise or downloads a file. */
    suspend fun refreshSubmittedMaterials() = with(repo) { supervisorScope {
        selectedStoredCourses().map { course -> launch {
            courseworkSlots.withPermit {
                refreshCourseSubmitted(course.id)
            }
        } }.joinAll()
    }
    }

    suspend fun refreshFolder(courseId: String, parentId: String?, force: Boolean = false, background: Boolean = false) { with(repo) {
        val key = folderKey(courseId, parentId)
        // Revalidate pre-hierarchy caches immediately after upgrading, even inside their normal TTL.
        val legacy = stored<FolderContent>(key)?.version?.let { it < 2 } == true
        refresh(key, FILE_TTL, force || legacy, background) {
            provider.folderContent(courseId, parentId, force || legacy)
        }
        trimHistory("folder:", 100)
    } }

    suspend fun refreshCourseMaterials(courseId: String, force: Boolean = false) { with(repo) {
        refresh(CacheKeys.courseFiles(courseId), MATERIAL_ACTIVITY_TTL, force) { provider.courseMaterials(courseId, force) }
        trimHistory("course-files:", 40)
    } }

    suspend fun prefetchFolder(courseId: String, folderId: String) { with(repo) {
        if (!conditions().canPrefetch(settings())) return
        refreshFolder(courseId, folderId, background = true)
    } }

    /**
     * Opens a Vips attachment and keeps a copy in the course folder under Vips/<sheet title>. [page] is the Vips page it
     * was opened from. UI requests belong to the account, so leaving/recomposing a row cannot lose the open action.
     */
    fun requestLinkedMaterial(courseId: String, url: String, page: String = "") { with(repo) {
        val link = VipsLinks.attachment(url, courseId) ?: return
        val folders = vipsAttachmentFolders(page, snapshot.value.vipsSheets[courseId]?.sheets.orEmpty())
        linkedFolders.update { it + (link.id to folders) }
        scope.launch {
            try {
                coordinator.run("linked-material:${link.id}") {
                    checkActive()
                    _errors.update { it - CacheKeys.download(link.id) }
                    val cached = stored<List<LocalDocument>>("downloads").orEmpty().firstOrNull { it.document.id == link.id }
                    val document = if (!conditions().connected && cached != null) cached.document else provider.linkedDocument(courseId, link.url)
                    currentCoroutineContext().ensureActive(); checkActive()
                    performMaterial(document, MaterialAction.OPEN, pin = false, copyToCourse = true)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { recordError(CacheKeys.download(link.id), error) }
        }
    } }

    fun requestMaterial(document: Document, action: MaterialAction = MaterialAction.OPEN) { with(repo) {
        scope.launch { performMaterial(document, action, action == MaterialAction.KEEP_OFFLINE) }
    } }

    fun requestCoursewareFile(courseId: String, elementId: String, part: CoursewarePart) { with(repo) {
        if (part.kind != "file" || snapshot.value.community["community:$courseId:courseware:$elementId"]
                ?.none { part in it.parts } != false) return
        scope.launch {
            try {
                coordinator.run("courseware-file:${part.target}") {
                    val document = provider.coursewareFile(courseId, part.blockId, part.target)
                    performMaterial(document, MaterialAction.OPEN, false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { recordError(CacheKeys.download(part.target), e) }
        }
    } }

    suspend fun setMaterialDestination(courseId: String, destination: MaterialDestination?) = with(repo) { updatePreferences { preferences ->
        preferences.copy(materialDestinations = if (destination == null) preferences.materialDestinations - courseId
            else preferences.materialDestinations + (courseId to destination))
    }
    }

    fun retryMaterial(document: Document) = with(repo) { requestMaterial(document, retryMaterials.value[document.id] ?: MaterialAction.OPEN)
    }

    suspend fun download(document: Document, pin: Boolean = false, open: Boolean = true) = with(repo) {
        performMaterial(document, if (open) MaterialAction.OPEN else MaterialAction.KEEP_OFFLINE, pin)
    }

    /**
     * Writes the saved file at [path] where material sync would put it: the course's chosen folder, or
     * ZenCampus/<course> in Downloads, then its DigiCampus folders (Vips/<sheet> for Vips attachments). An existing copy
     * is replaced. Null when this device cannot write there without a chosen folder.
     */
    private suspend fun saveInCourseFolder(document: Document, path: String): String? = with(repo) {
        val destination = stored<AccountPreferences>("preferences")?.materialDestinations?.get(document.courseId)
        if (destination == null && !materials.syncsWithoutFolder) return null
        val course = if (destination != null) emptyList() else
            listOf(materialFolderName(selectedStoredCourses().firstOrNull { it.id == document.courseId }?.title ?: document.courseId))
        val folders = linkedFolders.value[document.id]
            ?: stored<CourseMaterials>(CacheKeys.courseFiles(document.courseId))?.let { materialFolderPath(it.folders, document.folderId) }.orEmpty()
        val writer = materials.syncWriter(destination, course + folders, materialSyncName(document), document.mimeType)
        var finished = false
        try {
            materials.copyInto(path, writer)
            writer.finish().also { finished = true }
        } finally {
            if (!finished) withContext(NonCancellable) { runCatching { writer.abort() } }
        }
    }

    private suspend fun performMaterial(document: Document, action: MaterialAction, pin: Boolean, copyToCourse: Boolean = false) { with(repo) {
        try {
            // Coalesce the delivery too: repeated taps must never launch several viewers.
            coordinator.run("material:${action.name}:${document.id}:$pin") {
                checkActive()
                _errors.update { it - CacheKeys.download(document.id) }
                if (action == MaterialAction.SAVE_TO_DOWNLOADS) materialFeedback.update { it.copy(exports = it.exports - document.id) }
                ensureMaterial(document)
                // Each waiter applies its pin intent, even when it joined another request's transfer.
                downloadMutex.withLock {
                    checkActive()
                    val records = stored<List<LocalDocument>>("downloads").orEmpty()
                    val saved = records.firstOrNull { it.document.id == document.id } ?: throw CampusException(FailureKind.STORAGE)
                    if (!materials.exists(saved.path)) throw CampusException(FailureKind.STORAGE)
                    val path = materials.prepare(saved.path, saved.document)
                    val updated = saved.copy(path = path, pinned = saved.pinned || pin, lastAccess = clock())
                    try { commit("downloads", json.encodeToString(records.map { if (it.document.id == document.id) updated else it })) }
                    catch (error: Throwable) {
                        if (path != saved.path) withContext(NonCancellable) { runCatching { materials.remove(path) } }
                        throw error
                    }
                    if (path != saved.path) runCatching { materials.remove(saved.path) }
                    currentCoroutineContext().ensureActive(); checkActive()
                    // A copy in the course folder is a convenience: when it cannot be written the file still opens.
                    if (copyToCourse) try {
                        saveInCourseFolder(saved.document, path)?.let { location -> materialFeedback.update { it.copy(exports = it.exports + (document.id to location)) } }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    when (action) {
                        MaterialAction.OPEN -> materials.open(path, saved.document.mimeType, saved.document.name)
                        MaterialAction.OPEN_WITH -> materials.openWith(path, saved.document.mimeType, saved.document.name)
                        MaterialAction.SHARE -> materials.share(path, saved.document.mimeType, saved.document.name)
                        MaterialAction.SAVE_TO_DOWNLOADS -> (saveInCourseFolder(saved.document, path)
                            ?: materials.saveToDownloads(path, saved.document.mimeType, saved.document.name))?.let { location ->
                            materialFeedback.update { it.copy(exports = it.exports + (document.id to location)) }
                        }
                        MaterialAction.KEEP_OFFLINE -> Unit
                    }
                }
                _errors.update { it - CacheKeys.download(document.id) }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (error is CampusException && error.kind in setOf(FailureKind.PERMISSION, FailureKind.NOT_FOUND)) purgeCourseMaterials(document.courseId)
            retryMaterials.update { it + (document.id to action) }
            recordError(CacheKeys.download(document.id), error)
        }
    } }

    private suspend fun ensureMaterial(document: Document) = with(repo) { coordinator.run(CacheKeys.download(document.id)) {
        downloadSlots.withPermit {
            val epoch = downloadMutex.withLock {
                checkActive()
                val existing = stored<List<LocalDocument>>("downloads").orEmpty().firstOrNull { it.document.id == document.id }
                if (existing != null && materials.exists(existing.path) && (existing.version >= document.modified || !conditions().connected)) return@withPermit
                Triple(materialEpoch, removedMaterials[document.id] ?: 0L, materialCourseEpochs[document.courseId] ?: 0L)
            }
            if (!conditions().connected) throw CampusException(FailureKind.OFFLINE)
            if (!document.downloadable) throw CampusException(FailureKind.PERMISSION)
            if (document.size > DigiCampusProvider.MAX_FILE_BYTES) throw CampusException(FailureKind.STORAGE)
            retryMaterialDownload {
                downloadMutex.withLock {
                    currentCoroutineContext().ensureActive(); checkActive()
                    if (epoch != Triple(materialEpoch, removedMaterials[document.id] ?: 0L, materialCourseEpochs[document.courseId] ?: 0L))
                        throw CancellationException("Material removed")
                }
                val writer = materials.create(account.cacheScope, document)
                var committed = false
                var bytes = 0L; var lastNotice = -1L
                materialFeedback.update { it.copy(progress = it.progress + (document.id to MaterialProgress(0, document.size.takeIf { size -> size > 0 }))) }
                try {
                    val size = provider.download(document) { chunk ->
                        currentCoroutineContext().ensureActive()
                        writer.write(chunk); bytes += chunk.size
                        if (bytes > DigiCampusProvider.MAX_FILE_BYTES) throw CampusException(FailureKind.STORAGE)
                        val notice = if (document.size > 0) bytes * 100 / document.size else bytes / (256 * 1024)
                        if (notice != lastNotice) {
                            lastNotice = notice
                            materialFeedback.update { it.copy(progress = it.progress + (document.id to MaterialProgress(bytes, document.size.takeIf { size -> size > 0 }))) }
                        }
                    }
                    if (size != bytes || (document.size > 0 && size == 0L)) throw CampusException(FailureKind.INVALID_RESPONSE)
                    downloadMutex.withLock {
                        currentCoroutineContext().ensureActive(); checkActive()
                        if (epoch != Triple(materialEpoch, removedMaterials[document.id] ?: 0L, materialCourseEpochs[document.courseId] ?: 0L))
                            throw CancellationException("Material removed")
                        val path = writer.finish()
                        val records = stored<List<LocalDocument>>("downloads").orEmpty()
                        val previous = records.firstOrNull { it.document.id == document.id }
                        var remaining = records.filterNot { it.document.id == document.id }
                        var total = remaining.sumOf { it.size } + size
                        val evicted = mutableListOf<LocalDocument>()
                        for (candidate in remaining.filterNot { cached -> cached.pinned || coordinator.active.value.any { key ->
                            MaterialAction.entries.any { key.startsWith("material:${it.name}:${cached.document.id}:") }
                        } }.sortedBy { it.lastAccess }) {
                            if (total <= STORAGE_BUDGET) break
                            total -= candidate.size; evicted += candidate
                        }
                        if (total > STORAGE_BUDGET) throw CampusException(FailureKind.STORAGE)
                        remaining = remaining.filterNot { it in evicted }
                        val saved = LocalDocument(document, path, size, previous?.pinned == true, clock(), document.modified)
                        commit("downloads", json.encodeToString(remaining + saved))
                        committed = true
                        // Cleanup failure must never abort the new file after its manifest was committed.
                        (evicted + listOfNotNull(previous)).filter { it.path != path }.forEach { runCatching { materials.remove(it.path) } }
                    }
                } finally {
                    if (!committed) withContext(NonCancellable) { runCatching { writer.abort() } }
                    materialFeedback.update { it.copy(progress = it.progress - document.id) }
                }
            }
        }
    }
    }

    suspend fun removeDownload(id: String) = with(repo) { downloadMutex.withLock {
        removedMaterials[id] = (removedMaterials[id] ?: 0L) + 1
        materialFeedback.update { it.copy(exports = it.exports - id) }
        val records = stored<List<LocalDocument>>("downloads").orEmpty()
        commit("downloads", json.encodeToString(records.filterNot { it.document.id == id }))
        records.filter { it.document.id == id }.forEach { materials.remove(it.path) }
    }
    }

    suspend fun clearDownloads() = with(repo) { downloadMutex.withLock {
        materialEpoch++
        materialFeedback.value = CampusRepository.MaterialFeedback()
        commit("downloads", json.encodeToString(emptyList<LocalDocument>()))
        materials.clear(account.cacheScope)
    }
    }
}
