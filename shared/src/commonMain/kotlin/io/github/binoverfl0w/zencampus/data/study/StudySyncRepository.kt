package io.github.binoverfl0w.zencampus.data.study

import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.STUDY_SYNC_INTERVAL
import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.STUDY_SYNC_MIN_GAP
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
 * Study sync: one refresh for all of Study, when it runs and when it last finished.
 *
 * Part of [CampusRepository]: each operation runs with the repository as receiver (`with(repo)`), so the shared
 * cache engine, provider, store and other areas are reached exactly as from the repository itself.
 */
internal class StudySyncRepository(private val repo: CampusRepository) {
    private val _studySync = MutableStateFlow(StudySyncState())

    /** The one Study refresh: whether it runs, and when it last finished. Screens read the cache and this state. */
    val studySync: StateFlow<StudySyncState> = _studySync.asStateFlow()

    private var studyJob: Job? = null

    private var studyForcePending = false

    private val studyMutex = Mutex()

    private var foreground = true

    init { with(repo) {
        scope.launch {
            store.read(account.cacheScope, "study-sync")?.payload?.toLongOrNull()?.let { last ->
                _studySync.update { it.copy(lastCompleted = it.lastCompleted ?: last) }
            }
        }
        // While the app is in front, Study stays current without screens asking for it.
        scope.launch {
            while (true) {
                delay(STUDY_SYNC_INTERVAL)
                if (foreground && conditions().connected) requestStudySync()
            }
        }
    } }

    /** The app came to the front (or went away); coming back refreshes when the last sync is getting old. */
    fun setForeground(visible: Boolean) { with(repo) {
        foreground = visible
        if (visible) requestStudySync()
    } }

    /**
     * Starts a Study sync unless one is running. [force] (pull to refresh) rereads everything; otherwise it is skipped
     * shortly after the last one and each source keeps its own freshness. A forced request during a sync runs right after it.
     */
    fun requestStudySync(force: Boolean = false, now: Boolean = false) { with(repo) {
        scope.launch {
            studyMutex.withLock {
                if (studyJob?.isActive == true) { if (force) studyForcePending = true; return@withLock }
                val last = _studySync.value.lastCompleted
                if (!force && !now && last != null && clock() - last < STUDY_SYNC_MIN_GAP) return@withLock
                studyJob = scope.launch { runStudySync(force) }
            }
        }
    } }

    /** Runs a sync and waits for it, for background workers. */
    suspend fun syncStudy(force: Boolean = false) { with(repo) {
        studyMutex.withLock { studyJob?.takeIf { it.isActive } }?.join()
        val job = studyMutex.withLock { scope.launch { runStudySync(force) }.also { studyJob = it } }
        io.github.binoverfl0w.zencampus.data.awaitBackgroundSync(job, coordinator)
    } }

    private suspend fun runStudySync(force: Boolean) { with(repo) {
        if (!active) return
        _studySync.update { it.copy(running = true) }
        val started = clock()
        try {
            // Today's essentials first: profile, semesters, courses, this week, holidays.
            refreshToday(force)
            val courses = selectedStoredCourses()
            // Per-course content keeps its own freshness. Data saver (or a low battery) leaves it to pull to refresh;
            // background runs are already limited to Wi-Fi by their workers.
            if (force || (!settings().dataSaver && !conditions().batteryLow)) supervisorScope {
                launch { refreshMessages(sent = false, force = force) }
                launch { refresh("activity", 5 * 60_000L, force) { provider.activity(account.id) } }
                courses.map { course -> launch {
                    courseworkSlots.withPermit {
                        refreshAnnouncements(course.id, force)
                        refreshCoursework(course.id, force)
                        refreshCourseTools(course.id, force)
                        refreshCourseMaterials(course.id, force)
                        // Forum and Blubber activity feed Updates; only courses that use them are read.
                        val tools = stored<CourseTools>(CacheKeys.tools(course.id))
                        if (tools?.sections?.any { it.id.contains("forum", true) || it.sourceUrl.contains("/forum", true) } == true)
                            refreshCommunity(course.id, "forum", force = force)
                        if (tools?.sections?.any { it.id.contains("blubber", true) || it.sourceUrl.contains("/blubber", true) } == true)
                            refreshCommunity(course.id, "blubber", force = force)
                    }
                } }.joinAll()
            }
            val done = clock()
            commitMutex.withLock { if (active) store.put(account.cacheScope, "study-sync", done.toString(), done) }
            _studySync.update { it.copy(lastCompleted = done) }
            val failed = sync.value.errors.values.groupingBy { it }.eachCount()
            ZenLog.info("sync", "study sync of ${courses.size} courses took ${done - started} ms" +
                if (failed.isEmpty()) "" else ", failing: " + failed.entries.joinToString { "${it.key} ×${it.value}" })
        } finally {
            _studySync.update { it.copy(running = false) }
            val again = studyMutex.withLock { studyForcePending.also { studyForcePending = false } }
            if (again && active) requestStudySync(force = true)
        }
    } }
}
