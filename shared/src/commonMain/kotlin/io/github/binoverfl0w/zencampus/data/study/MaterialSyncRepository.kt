package io.github.binoverfl0w.zencampus.data.study

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.retryMaterialDownload
import io.github.binoverfl0w.zencampus.data.ZenLog
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
 * Material sync: every course file written straight to its save folder.
 *
 * Part of [CampusRepository]: each operation runs with the repository as receiver (`with(repo)`), so the shared
 * cache engine, provider, store and other areas are reached exactly as from the repository itself.
 */
internal class MaterialSyncRepository(private val repo: CampusRepository) {
    /** Writes the loaded timetable (and booked office hours) as an .ics file and hands it to the platform. */

    private val syncSlots = Semaphore(2)

    private val _materialSync = MutableStateFlow<MaterialSyncProgress?>(null)

    /** The running or last finished sync; null before the first one in this session. */
    val materialSync = _materialSync.asStateFlow()

    private var syncJob: Job? = null

    val syncsWithoutFolder: Boolean get() = repo.materials.syncsWithoutFolder

    /**
     * Reads the complete file list of [courseIds] (all courses of the selected semester when null) and works out what a
     * sync would write. Nothing is downloaded here.
     */
    suspend fun planMaterialSync(courseIds: List<String>? = null, background: Boolean = false): List<MaterialSyncPlan> = with(repo) { supervisorScope {
        val ids = courseIds ?: selectedStoredCourses().map { it.id }
        ids.distinct().map { id -> async {
            courseworkSlots.withPermit { refreshCourseMaterials(id, force = !background) }
            val materials = stored<CourseMaterials>(CacheKeys.courseFiles(id))?.takeIf { it.courseId == id } ?: return@async null
            val records = stored<List<MaterialSyncRecord>>("material-sync").orEmpty()
            val destination = stored<AccountPreferences>("preferences")?.materialDestinations?.get(id)?.location.orEmpty()
            materialSyncPlan(materials, records.firstOrNull { it.courseId == id }, destination, DigiCampusProvider.MAX_FILE_BYTES)
        } }.awaitAll().filterNotNull()
    }
    }

    /** Writes the pending files of [plans]. One sync runs at a time; files already written stay recorded if it stops. */
    fun startMaterialSync(plans: List<MaterialSyncPlan>, automatic: Boolean? = null) { with(repo) {
        if (syncJob?.isActive == true) return
        val work = plans.filter { it.pending.isNotEmpty() || automatic != null }
        syncJob = scope.launch { runMaterialSync(work, automatic) }
    } }

    fun cancelMaterialSync() { with(repo) { syncJob?.cancel() } }

    fun dismissMaterialSync() { with(repo) { if (syncJob?.isActive != true) _materialSync.value = null } }

    suspend fun setMaterialSyncAutomatic(courseId: String, enabled: Boolean) = with(repo) { updateSyncRecord(courseId) { it.copy(automatic = enabled) }
    }

    /** Background refresh: keeps courses with automatic sync up to date when prefetching is allowed. */
    suspend fun autoSyncMaterials() { with(repo) {
        if (!conditions().canPrefetch(settings()) || syncJob?.isActive == true) return
        val automatic = stored<List<MaterialSyncRecord>>("material-sync").orEmpty().filter { it.automatic }.map { it.courseId }
        val selected = selectedStoredCourses().map { it.id }.toSet()
        val ids = automatic.filter { it in selected }
        if (ids.isEmpty()) return
        val plans = planMaterialSync(ids, background = true).filter { it.pending.isNotEmpty() }
        if (plans.isEmpty()) return
        val job = scope.launch { runMaterialSync(plans, null) }
        syncJob = job
        io.github.binoverfl0w.zencampus.data.awaitBackgroundSync(job, coordinator)
    } }

    private suspend fun runMaterialSync(plans: List<MaterialSyncPlan>, automatic: Boolean?) { with(repo) {
        val total = plans.sumOf { it.pending.size }
        var progress = MaterialSyncProgress(plans.map { it.courseId }, total, bytesTotal = plans.sumOf { it.bytes }, running = true)
        _materialSync.value = progress
        val progressMutex = Mutex()
        suspend fun update(change: (MaterialSyncProgress) -> MaterialSyncProgress) = progressMutex.withLock {
            progress = change(progress); if (active) _materialSync.value = progress
        }
        try {
            val courses = selectedStoredCourses().associateBy { it.id }
            for (plan in plans) {
                checkActive()
                val destination = stored<AccountPreferences>("preferences")?.materialDestinations?.get(plan.courseId)
                val key = destination?.location.orEmpty()
                // A new folder starts a fresh record, so everything is written there.
                updateSyncRecord(plan.courseId) { record ->
                    (if (record.destination != key) record.copy(destination = key, files = emptyList()) else record)
                        .let { if (automatic != null) it.copy(automatic = automatic) else it }
                }
                if (destination == null && !materials.syncsWithoutFolder) {
                    update { it.copy(failed = it.failed + plan.pending.associate { target -> target.document.id to FailureKind.STORAGE }, done = it.done + plan.pending.size) }
                    continue
                }
                // The default folder holds every course, so each course gets its own directory there.
                val prefix = if (destination == null) listOf(materialFolderName(courses[plan.courseId]?.title ?: plan.courseId)) else emptyList()
                coroutineScope {
                    plan.pending.map { target -> launch {
                        syncSlots.withPermit {
                            update { it.copy(current = target.name) }
                            try {
                                val location = writeSyncedMaterial(target, destination, prefix)
                                updateSyncRecord(plan.courseId) { record ->
                                    record.copy(files = record.files.filterNot { it.documentId == target.document.id } +
                                        SyncedMaterial(target.document.id, target.folders, target.name, target.document.modified, target.document.size, location))
                                }
                                update { it.copy(done = it.done + 1, bytesDone = it.bytesDone + target.document.size.coerceAtLeast(0)) }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                val kind = (e as? CampusException)?.kind ?: FailureKind.STORAGE
                                if (kind == FailureKind.AUTHENTICATION) { recordError("material-sync", e); throw e }
                                update { it.copy(done = it.done + 1, failed = it.failed + (target.document.id to kind)) }
                            }
                        }
                    } }.joinAll()
                }
                updateSyncRecord(plan.courseId) { it.copy(lastSync = clock()) }
            }
            update { it.copy(running = false, current = "", finishedAt = clock()) }
            _materialSync.value?.let { result ->
                ZenLog.info("material-sync", "${result.done - result.failed.size} of ${result.total} files written" +
                    if (result.failed.isEmpty()) "" else ", failed: " + result.failed.values.groupingBy { it }.eachCount().entries.joinToString { "${it.key} ×${it.value}" })
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { update { it.copy(running = false, current = "", cancelled = true, finishedAt = clock()) } }
            throw e
        } catch (e: Exception) {
            ZenLog.warn("material-sync", "stopped", e)
            update { it.copy(running = false, current = "", finishedAt = clock(), stopped = (e as? CampusException)?.kind ?: FailureKind.OFFLINE) }
        }
    } }

    private suspend fun writeSyncedMaterial(target: MaterialSyncTarget, destination: MaterialDestination?, prefix: List<String>): String { with(repo) {
        val document = target.document
        if (!conditions().connected) throw CampusException(FailureKind.OFFLINE)
        // Each attempt writes a fresh temporary file; the existing copy is only replaced by a complete one.
        return retryMaterialDownload {
            val writer = materials.syncWriter(destination, prefix + target.folders, target.name, document.mimeType)
            var finished = false
            try {
                var bytes = 0L
                val size = provider.download(document) { chunk ->
                    currentCoroutineContext().ensureActive()
                    writer.write(chunk); bytes += chunk.size
                    if (bytes > DigiCampusProvider.MAX_FILE_BYTES) throw CampusException(FailureKind.STORAGE)
                }
                if (size != bytes || (document.size > 0 && size == 0L)) throw CampusException(FailureKind.INVALID_RESPONSE)
                checkActive()
                writer.finish().also { finished = true }
            } finally {
                if (!finished) withContext(NonCancellable) { runCatching { writer.abort() } }
            }
        }
    } }

    private suspend fun updateSyncRecord(courseId: String, change: (MaterialSyncRecord) -> MaterialSyncRecord) = with(repo) { commitMutex.withLock {
        checkActive()
        val records = stored<List<MaterialSyncRecord>>("material-sync").orEmpty()
        val current = records.firstOrNull { it.courseId == courseId } ?: MaterialSyncRecord(courseId)
        store.put(account.cacheScope, "material-sync", json.encodeToString(records.filterNot { it.courseId == courseId } + change(current)), clock())
    }
    }
}
