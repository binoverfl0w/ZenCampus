package io.github.binoverfl0w.zencampus.data.study

import io.github.binoverfl0w.zencampus.data.CacheRecord
import io.github.binoverfl0w.zencampus.data.CampusProvider
import io.github.binoverfl0w.zencampus.data.CampusStore
import io.github.binoverfl0w.zencampus.data.CampusWeatherProvider
import io.github.binoverfl0w.zencampus.data.MaterialStorage
import io.github.binoverfl0w.zencampus.data.NetworkConditions
import io.github.binoverfl0w.zencampus.data.RefreshCoordinator
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
 * Study data for one account, which owns its jobs, credentials and cache scope for its whole lifetime.
 *
 * This file holds the shared core: the cache engine ([refresh]: freshness, quiet retries, stale and error state,
 * invalidation epochs), the snapshot screens observe, and the account lifecycle. Each area lives in its own class
 * (`StudySyncRepository`, `CoursesRepository`, `CourseworkRepository`, `MaterialsRepository`, ...); the forwarders at
 * the end of this class are the public API and double as its table of contents.
 */
class CampusRepository(
    val account: Account,
    internal val provider: CampusProvider,
    internal val store: CampusStore,
    internal val materials: MaterialStorage,
    parentScope: CoroutineScope,
    internal val conditions: () -> NetworkConditions = { NetworkConditions() },
    internal val settings: () -> AppSettings = { AppSettings() },
    internal val clock: () -> Long = ::nowMillis,
    internal val weatherProvider: CampusWeatherProvider? = null,
) {
    internal val job = SupervisorJob(parentScope.coroutineContext[Job])
    internal val scope = CoroutineScope(parentScope.coroutineContext + job)
    internal val coordinator = RefreshCoordinator(scope)
    internal val commitMutex = Mutex()
    internal val downloadMutex = Mutex()
    internal val downloadSlots = Semaphore(2)
    internal var materialEpoch = 0L
    internal val removedMaterials = mutableMapOf<String, Long>()
    internal val materialCourseEpochs = mutableMapOf<String, Long>()
    internal val retryMaterials = MutableStateFlow<Map<String, MaterialAction>>(emptyMap())
    internal data class MaterialFeedback(val progress: Map<String, MaterialProgress> = emptyMap(), val exports: Map<String, String> = emptyMap())
    internal val materialFeedback = MutableStateFlow(MaterialFeedback())
    internal val writeMutex = Mutex()
    internal val _uploadDraft = MutableStateFlow<UploadDraft?>(null)
    val uploadDraft = _uploadDraft.asStateFlow()
    internal val _uploadSelectionError = MutableStateFlow<FailureKind?>(null)
    val uploadSelectionError = _uploadSelectionError.asStateFlow()
    internal val courseworkSlots = Semaphore(2)
    internal val courseworkEpochs = mutableMapOf<String, Long>()
    internal val courseToolEpochs = mutableMapOf<String, Long>()
    internal val courseFileEpochs = mutableMapOf<String, Long>()
    internal val communityEpochs = mutableMapOf<String, Long>()
    internal var active = true
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val planner = PlannerRepository(account, provider, store, scope, conditions, clock)
    internal val _errors = MutableStateFlow<Map<String, FailureKind>>(emptyMap())
    /** Keys with nothing cached that failed temporarily and are waiting for an automatic retry; they read as loading. */
    internal val _retrying = MutableStateFlow<Set<String>>(emptySet())
    /** Keys whose latest read failed temporarily while cached data stayed on screen; never shown as errors. */
    internal val _stale = MutableStateFlow<Map<String, FailureKind>>(emptyMap())
    internal val retryMutex = Mutex()
    internal val retryJobs = mutableMapOf<String, Job>()
    // Conflated: a burst of writes during a sync becomes one rebuild instead of one per write.
    /**
     * Decoded records of the last snapshot, owned by the snapshot collector alone (workers decode with a throwaway memo).
     * Declared before [snapshot]: its collector starts during construction and must find this initialised.
     */
    private val snapshotMemo = HashMap<String, Decoded>()
    private val unread = CampusSnapshot(account)
    // Conflated: a burst of writes during a sync becomes one rebuild instead of one per write.
    val snapshot: StateFlow<CampusSnapshot> = store.observe(account.cacheScope).conflate()
        .map { snapshotFrom(it, snapshotMemo) }
        .stateIn(scope, SharingStarted.Eagerly, unread)
    /**
     * True once the saved snapshot has been read (possibly empty). Until then [snapshot] is a blank placeholder, and
     * showing it would draw empty cards that the saved data replaces a moment later.
     */
    val restored: StateFlow<Boolean> = snapshot.map { it !== unread }.stateIn(scope, SharingStarted.Eagerly, false)
    val sync: StateFlow<SyncStatus> = combine(combine(coordinator.active, _retrying, _stale, ::Triple), _errors, planner.sync, materialFeedback) { (running, retrying, stale), errors, planning, files ->
        SyncStatus(running + retrying + planning.active, errors + planning.errors, errors.values.any { it == FailureKind.AUTHENTICATION } || planning.sessionExpired,
            files.progress, files.exports, stale.keys)
    }.stateIn(scope, SharingStarted.Eagerly, SyncStatus())

    /** Retries one read later, after the network is back; a newer schedule for the same key replaces it. */
    internal suspend fun scheduleRetry(key: String, delayMillis: Long, retry: suspend () -> Unit) = retryMutex.withLock {
        retryJobs.remove(key)?.cancel()
        retryJobs[key] = scope.launch {
            delay(delayMillis + kotlin.random.Random.nextLong(500))
            while (!conditions().connected) delay(5_000)
            retryMutex.withLock { retryJobs.remove(key) }
            retry()
        }
    }

    suspend fun initialize() {
        syncStudy()
    }

    internal suspend fun <T> studentWrite(key: String, courseId: String, targetId: String, title: String, requestId: String = "",
        action: suspend () -> WriteResult<T>, accepted: suspend (T) -> Document?) {
        try { coordinator.run(key) {
            writeMutex.withLock {
                checkActive()
                val previous = stored<CourseworkWrite>(key)
                if (previous?.status in setOf(WriteStatus.SENDING, WriteStatus.UNCERTAIN)) return@withLock
                if (requestId.isNotBlank() && previous?.status == WriteStatus.CONFIRMED && previous.requestId == requestId) return@withLock
                val attempt = CourseworkWrite(key, courseId, targetId, title, clock(), requestId = requestId)
                if (!conditions().connected) {
                    commit(key, json.encodeToString(attempt.copy(status = WriteStatus.REJECTED, failure = FailureKind.OFFLINE)))
                    return@withLock
                }
                // Persist before sending: after process death an unresolved write must never be retried automatically.
                commit(key, json.encodeToString(attempt))
                val result = try { action() } catch (e: CancellationException) { throw e } catch (_: Exception) { WriteResult.Uncertain }
                currentCoroutineContext().ensureActive()
                val record = when (result) {
                    is WriteResult.Confirmed -> {
                        val doc = accepted(result.value)
                        attempt.copy(status = WriteStatus.CONFIRMED, document = doc)
                    }
                    is WriteResult.Rejected -> {
                        if (result.failure == FailureKind.AUTHENTICATION) recordError(key, CampusException(result.failure))
                        attempt.copy(status = WriteStatus.REJECTED, failure = result.failure)
                    }
                    WriteResult.Uncertain -> attempt.copy(status = WriteStatus.UNCERTAIN)
                }
                ZenLog.info("write", "${key.substringBefore(':')} ${record.status}${record.failure?.let { " $it" } ?: ""}")
                commit(key, json.encodeToString(record))
                if (record.failure != FailureKind.AUTHENTICATION) _errors.update { it - key }
            }
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) { recordError(key, CampusException(FailureKind.STORAGE, e)) }
    }

    suspend fun clearCache() {
        val records = store.observe(account.cacheScope).first()
        commitMutex.withLock {
            checkActive()
            records.filterNot { it.key in setOf("preferences", "downloads", "personal-deadlines") || it.key in PlannerRepository.PERSONAL_KEYS || it.key.startsWith("write:") }.forEach {
                store.remove(account.cacheScope, it.key)
            }
        }
        refreshToday(force = true)
    }

    suspend fun close(purge: Boolean) {
        planner.close()
        commitMutex.withLock { active = false; _uploadDraft.value = null; _uploadSelectionError.value = null }
        job.cancelAndJoin()
        if (purge) {
            store.purge(account.cacheScope)
            materials.clear(account.cacheScope)
        }
    }

    fun dismissErrors() { _errors.value = emptyMap(); planner.dismissErrors() }

    internal suspend inline fun <reified T> refresh(
        key: String, ttl: Long, force: Boolean, background: Boolean = false, crossinline fetch: suspend () -> T,
    ) = refreshCore(key, ttl, force, background, { payload -> runCatching { json.decodeFromString<T>(payload) }.isSuccess },
        { json.encodeToString(fetch()) })

    /**
     * Reads [key] unless its cache is fresh. A temporary failure (offline, timeout, server busy) never replaces data:
     * with a cached copy it stays silent; either way the read is retried automatically after [RETRY_DELAYS].
     * Only definite failures, or a temporary one with nothing cached after every retry, reach [sync] errors.
     */
    internal suspend fun refreshCore(
        key: String, ttl: Long, force: Boolean, background: Boolean,
        valid: (String) -> Boolean, fetch: suspend () -> String, attempt: Int = 0,
    ) {
        try {
            coordinator.run(key, speculative = background) {
                if (!active) return@run
                val cached = store.read(account.cacheScope, key)
                if (!force && isFresh(cached?.updatedAt, clock(), ttl) && valid(cached!!.payload)) return@run
                if (!conditions().connected) throw CampusException(FailureKind.OFFLINE)
                val taskCourse = key.takeIf { it.startsWith("task:") || it.startsWith("peer-reviews:") }?.substringAfter(':')?.substringBefore(':')
                val taskEpoch = commitMutex.withLock { courseworkEpochs[taskCourse] ?: 0L }
                val vipsCourse = key.takeIf { it.startsWith("vips:") || it.startsWith("vips-results:") }?.substringAfter(':')
                val vipsEpoch = commitMutex.withLock { courseToolEpochs[vipsCourse] ?: 0L }
                val materialCourse = key.takeIf { it.startsWith("course-files:") || it.startsWith("folder:") }
                    ?.substringAfter(':')?.substringBefore(':')
                val fileEpoch = commitMutex.withLock { courseFileEpochs[materialCourse] ?: 0L }
                val communityScope = when {
                    key.startsWith("community:") -> key.split(':').take(3).joinToString(":")
                    key.startsWith("forum-subs:") || key.startsWith("forum-reactions:") -> "community:${key.substringAfter(':').substringBefore(':')}:forum"
                    else -> null
                }
                val communityEpoch = commitMutex.withLock { communityEpochs[communityScope] ?: 0L }
                val value = fetch()
                currentCoroutineContext().ensureActive()
                commitMutex.withLock {
                    checkActive()
                    if (communityScope != null && communityEpoch != (communityEpochs[communityScope] ?: 0L))
                        throw CampusException(FailureKind.PERMISSION)
                    if (taskCourse != null && taskEpoch != (courseworkEpochs[taskCourse] ?: 0L))
                        throw CampusException(FailureKind.PERMISSION)
                    if (vipsCourse != null && vipsEpoch != (courseToolEpochs[vipsCourse] ?: 0L))
                        throw CampusException(FailureKind.PERMISSION)
                    if (materialCourse != null && fileEpoch != (courseFileEpochs[materialCourse] ?: 0L))
                        throw CampusException(FailureKind.PERMISSION)
                    store.put(account.cacheScope, key, value, clock())
                }
                _errors.update { it - key }
                _retrying.update { it - key }
                _stale.update { it - key }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = e as? CampusException ?: CampusException(FailureKind.OFFLINE, e)
            // Only the key's kind is logged; the rest of a cache key holds course and record IDs.
            val kind = key.substringBefore(':')
            if (failure.transient) ZenLog.debug("refresh", "$kind: ${ZenLog.describe(failure)}, attempt ${attempt + 1}")
            else if (e !is CampusException) ZenLog.error("refresh", "$kind failed unexpectedly", e)
            else ZenLog.warn("refresh", "$kind: ${ZenLog.describe(failure)}")
            if (failure.transient && active) {
                val cached = store.read(account.cacheScope, key)?.payload?.takeIf(valid) != null
                val retry = attempt < RETRY_DELAYS.size
                if (retry) scheduleRetry(key, failure.retryAfterMillis ?: RETRY_DELAYS[attempt]) {
                    refreshCore(key, ttl, true, true, valid, fetch, attempt + 1)
                }
                // Cached data stays on screen without an error; with nothing to show, the key reads as loading while it retries.
                if (cached) { _errors.update { it - key }; _retrying.update { it - key }; _stale.update { it + (key to failure.kind) }; return }
                if (retry) { _errors.update { it - key }; _retrying.update { it + key }; return }
                _retrying.update { it - key }
            }
            if (e is CampusException && e.kind in setOf(FailureKind.PERMISSION, FailureKind.NOT_FOUND)) {
                commitMutex.withLock {
                    if (active) {
                        store.remove(account.cacheScope, key)
                        if (key.startsWith("community:")) {
                            val prefix = key.split(':').take(3).joinToString(":")
                            communityEpochs[prefix] = (communityEpochs[prefix] ?: 0L) + 1
                            val courseId = key.substringAfter(':').substringBefore(':')
                            val forum = prefix == "community:$courseId:forum"
                            store.observe(account.cacheScope).first().filter {
                                it.key.startsWith("$prefix:") || (forum && (it.key == CacheKeys.forumSubscriptions(courseId) || it.key.startsWith("forum-reactions:$courseId:")))
                            }.forEach { store.remove(account.cacheScope, it.key) }
                        }
                        if (key.startsWith("tasks:")) {
                            val courseId = key.substringAfter(':')
                            courseworkEpochs[courseId] = (courseworkEpochs[courseId] ?: 0L) + 1
                            store.observe(account.cacheScope).first().filter { it.key.startsWith("task:$courseId:") || it.key.startsWith("peer-reviews:$courseId:") }
                                .forEach { store.remove(account.cacheScope, it.key) }
                        }
                        if (key.startsWith("tools:")) {
                            val courseId = key.substringAfter(':')
                            courseToolEpochs[courseId] = (courseToolEpochs[courseId] ?: 0L) + 1
                            store.remove(account.cacheScope, CacheKeys.vips(courseId))
                            store.remove(account.cacheScope, CacheKeys.vipsResults(courseId))
                        }
                    }
                }
                if (key.startsWith("folder:") || (key.startsWith("course-files:") && e.kind == FailureKind.PERMISSION))
                    purgeCourseMaterials(key.substringAfter(':').substringBefore(':'))
            }
            recordError(key, e)
        }
    }

    internal suspend fun purgeCourseMaterials(courseId: String) = downloadMutex.withLock {
        materialCourseEpochs[courseId] = (materialCourseEpochs[courseId] ?: 0L) + 1
        val folders = store.observe(account.cacheScope).first().filter { it.key.startsWith("folder:$courseId:") }
        commitMutex.withLock {
            if (active) {
                courseFileEpochs[courseId] = (courseFileEpochs[courseId] ?: 0L) + 1
                folders.forEach { store.remove(account.cacheScope, it.key) }
                store.remove(account.cacheScope, CacheKeys.courseFiles(courseId))
            }
        }
        val records = stored<List<LocalDocument>>("downloads").orEmpty()
        val affected = records.filter { it.document.courseId == courseId }
        if (affected.isEmpty()) return@withLock
        commit("downloads", json.encodeToString(records.filterNot { it in affected }))
        affected.forEach { materials.remove(it.path) }
    }

    internal fun recordError(key: String, error: Exception) {
        if (active) { _errors.update { it + (key to ((error as? CampusException)?.kind ?: FailureKind.OFFLINE)) }; _stale.update { it - key } }
    }

    internal suspend fun commit(key: String, payload: String) = commitMutex.withLock {
        checkActive()
        store.put(account.cacheScope, key, payload, clock())
    }

    internal fun checkActive() { if (!active || !job.isActive) throw CancellationException("Account closed") }

    internal suspend fun updatePreferences(transform: (AccountPreferences) -> AccountPreferences) = commitMutex.withLock {
        checkActive()
        val old = stored<AccountPreferences>("preferences") ?: AccountPreferences()
        val updated = transform(old)
        if (updated != old) store.put(account.cacheScope, "preferences", json.encodeToString(updated), clock())
    }

    internal suspend inline fun <reified T> stored(key: String): T? =
        store.read(account.cacheScope, key)?.let { runCatching { json.decodeFromString<T>(it.payload) }.getOrNull() }

    internal suspend fun selectedStoredCourses(): List<Course> {
        val id = (stored<AccountPreferences>("preferences") ?: AccountPreferences()).semesterId.orEmpty()
        return stored<List<Course>>(CacheKeys.courses(id)).orEmpty()
    }

    internal suspend fun trimHistory(prefix: String, count: Int) {
        val records = store.observe(account.cacheScope).first().filter { it.key.startsWith(prefix) }.sortedByDescending { it.updatedAt }
        commitMutex.withLock {
            if (active) records.drop(count).forEach { store.remove(account.cacheScope, it.key) }
        }
    }

    internal inline fun <reified T> CacheRecord?.decode(): T? =
        this?.let { runCatching { json.decodeFromString<T>(payload) }.getOrNull() }

    /**
     * Decoded cache records of the last snapshot, reused while a record is unchanged. A full sync writes dozens of
     * records; without this every write re-parsed the whole account cache. Only the snapshot collector uses it.
     */
    internal class Decoded(val updatedAt: Long, val length: Int, val hash: Int, val type: String, val value: Any?)

    internal inline fun <reified T> CacheRecord?.cached(decoded: MutableMap<String, Decoded>): T? {
        val record = this ?: return null
        val type = typeOf<T>().toString()
        val hash = record.payload.hashCode()
        decoded[record.key]?.takeIf { it.updatedAt == record.updatedAt && it.length == record.payload.length && it.hash == hash && it.type == type }
            ?.let { @Suppress("UNCHECKED_CAST") return it.value as T? }
        val value = runCatching { json.decodeFromString<T>(record.payload) }.getOrNull()
        decoded[record.key] = Decoded(record.updatedAt, record.payload.length, hash, type, value)
        return value
    }

    internal fun snapshotFrom(records: List<CacheRecord>, memo: MutableMap<String, Decoded> = HashMap()): CampusSnapshot {
        val map = records.associateBy { it.key }
        memo.keys.retainAll(map.keys)
        val semesters = map["semesters"].cached<List<Semester>>(memo).orEmpty()
        val preferences = map["preferences"].cached<AccountPreferences>(memo) ?: AccountPreferences()
        val selected = preferences.semesterId ?: currentSemester(semesters, clock())?.id.orEmpty()
        val courses = map[CacheKeys.courses(selected)].cached<List<Course>>(memo).orEmpty().map { course ->
            map[CacheKeys.course(course.id)].cached<Course>(memo) ?: course
        }
        val courseIds = courses.map { it.id }.toSet()
        val events = calendarEvents(records, courseIds, memo)
        val announcements = courseIds.flatMap { map[CacheKeys.news(it)].cached<List<Announcement>>(memo).orEmpty() }
            .distinctBy { it.id }.sortedByDescending { it.published }
        val folders = records.filter { it.key.startsWith("folder:") }
            .mapNotNull { record -> record.cached<FolderContent>(memo)?.let { record.key to it } }.toMap()
        val profile = map["profile"].cached<Account>(memo)?.takeIf { it.cacheScope == account.cacheScope } ?: account
        return CampusSnapshot(profile, semesters, courses, events, announcements, folders,
            map["downloads"].cached<List<LocalDocument>>(memo).orEmpty(), preferences,
            records.associate { it.key to it.updatedAt }, weather = map["weather"].cached<CampusWeather>(memo),
            coursework = courseIds.mapNotNull { id -> map[CacheKeys.tasks(id)].cached<List<CourseworkTask>>(memo)?.let { id to it } }.toMap(),
            courseworkDetails = records.filter { it.key.startsWith("task:") }.mapNotNull { record ->
                record.cached<CourseworkDetail>(memo)?.takeIf { it.task.courseId in courseIds }?.let { it.task.id to it }
            }.toMap(),
            assignmentFolders = courseIds.mapNotNull { id -> map[CacheKeys.uploadFolders(id)].cached<List<CampusFolder>>(memo)?.let { id to it } }.toMap(),
            personalDeadlines = map["personal-deadlines"].cached<List<PersonalDeadline>>(memo).orEmpty().filter { it.courseId in courseIds }.associateBy { it.folderId },
            courseworkWrites = records.filter { it.key.startsWith("write:") }.mapNotNull { record ->
                record.cached<CourseworkWrite>(memo)?.takeIf { it.courseId in courseIds || record.key.substringAfter("write:").substringBefore(':') in ACCOUNT_WRITES }
                    ?.let { record.key to it }
            }.toMap(),
            courseTools = courseIds.mapNotNull { id -> map[CacheKeys.tools(id)].cached<CourseTools>(memo)?.let { id to it } }.toMap(),
            vipsSheets = courseIds.mapNotNull { id -> map[CacheKeys.vips(id)].cached<VipsSheets>(memo)?.let { id to it } }.toMap(),
            courseMaterials = courseIds.mapNotNull { id -> map[CacheKeys.courseFiles(id)].cached<CourseMaterials>(memo)?.takeIf { it.courseId == id }?.let { id to it } }.toMap(),
            messages = listOf("inbox", "sent").mapNotNull { folder -> map["messages:$folder"].cached<List<CampusMessage>>(memo)?.let { folder to it } }.toMap(),
            messageWrite = map["write:message"].cached<CourseworkWrite>(memo),
            community = records.filter { it.key.startsWith("community:") && it.key.substringAfter(':').substringBefore(':') in courseIds }
                .mapNotNull { record -> record.cached<List<CommunityItem>>(memo)?.let { record.key to it } }.toMap(),
            forumSubscriptions = courseIds.mapNotNull { id -> map[CacheKeys.forumSubscriptions(id)].cached<List<ForumSubscription>>(memo)?.let { id to it } }.toMap(),
            forumReactions = records.filter { it.key.startsWith("forum-reactions:") && it.key.substringAfter(':').substringBefore(':') in courseIds }
                .mapNotNull { record -> record.cached<List<ForumReaction>>(memo)?.let { record.key to it } }.toMap(),
            coursewareBookmarks = map["courseware-bookmarks"].cached<List<String>>(memo),
            coursewareProgress = records.filter { it.key.startsWith("courseware-progress:") && it.key.substringAfter(':').substringBefore(':') in courseIds }
                .mapNotNull { record -> record.cached<Map<String, Double>>(memo)?.let { record.key to it } }.toMap(),
            activity = map["activity"].cached<List<CampusActivity>>(memo).orEmpty(),
            vipsResults = courseIds.mapNotNull { id -> map[CacheKeys.vipsResults(id)].cached<List<VipsResult>>(memo)?.let { id to it } }.toMap(),
            peerReviews = records.filter { it.key.startsWith("peer-reviews:") && it.key.substringAfter(':').substringBefore(':') in courseIds }
                .mapNotNull { record -> record.cached<List<PeerReview>>(memo)?.let { record.key to it } }.toMap(),
            holidays = records.filter { it.key.startsWith("holidays:") }.flatMap { it.cached<List<Holiday>>(memo).orEmpty() }.distinctBy { it.date }.sortedBy { it.date },
            consultations = records.filter { it.key.startsWith("consultations:") }
                .mapNotNull { record -> record.cached<List<ConsultationBlock>>(memo)?.let { record.key.substringAfter("consultations:") to it } }.toMap(),
            consultationBookings = map["consultation-bookings"].cached<List<ConsultationBooking>>(memo).orEmpty().sortedBy { it.start },
            newsComments = records.filter { it.key.startsWith("news-comments:") }
                .mapNotNull { record -> record.cached<List<NewsComment>>(memo)?.let { record.key.substringAfter("news-comments:") to it } }.toMap(),
            contacts = map["contacts"].cached<List<CampusPerson>>(memo),
            studyGroups = map["studygroups"].cached<List<Course>>(memo),
            materialSync = map["material-sync"].cached<List<MaterialSyncRecord>>(memo).orEmpty().associateBy { it.courseId })
    }

    /** Newer complete calendar windows also supersede missing events in overlapping old windows. */
    internal fun calendarEvents(records: List<CacheRecord>, courseIds: Set<String>, memo: MutableMap<String, Decoded>): List<CampusEvent> {
        val (detailed, covered) = calendarWindowEvents(records, "personal:", memo)
        val events = detailed.toMutableList()
        records.filter { it.key.startsWith("events:") && it.key.substringAfter(':') in courseIds }
            .sortedByDescending { it.updatedAt }.forEach { record ->
                events += record.cached<List<CampusEvent>>(memo).orEmpty().filter { event -> covered.none { event.start in it } }
            }
        // Dated course events (including cancellations and room changes) override timetable slots.
        val datedCourseSlots = events.filter { it.courseId.isNotBlank() }.map { it.courseId to it.start }.toSet()
        val (scheduled, _) = calendarWindowEvents(records, "schedule:", memo)
        events += scheduled.filter { it.courseId.isBlank() || (it.courseId to it.start) !in datedCourseSlots }
        return events.distinctBy { it.id + ":" + it.start }.sortedBy { it.start }
    }

    internal fun calendarWindowEvents(records: List<CacheRecord>, prefix: String, memo: MutableMap<String, Decoded>): Pair<List<CampusEvent>, List<LongRange>> {
        val covered = mutableListOf<LongRange>()
        val events = mutableListOf<CampusEvent>()
        val windows = records.filter { it.key.startsWith(prefix) }
            .sortedWith(compareByDescending<CacheRecord> { it.updatedAt }
                .thenByDescending { it.key.substringAfter(':').toLongOrNull() ?: 0 })
        for (record in windows) {
            val from = record.key.substringAfter(':').toLongOrNull() ?: continue
            val content = record.cached<List<CampusEvent>>(memo) ?: continue
            val until = Instant.fromEpochMilliseconds(from).toLocalDateTime(CAMPUS_ZONE).date
                .plus(CALENDAR_WINDOW_DAYS, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
            events += content.filter { event -> covered.none { event.start in it } }
            covered += from until until
        }
        return events to covered
    }


    // --- StudySync -------------------------------------------------------------------------------------------
    internal val studySyncArea = StudySyncRepository(this)
    val studySync get() = studySyncArea.studySync
    fun setForeground(visible: Boolean) = studySyncArea.setForeground(visible)
    fun requestStudySync(force: Boolean = false, now: Boolean = false) = studySyncArea.requestStudySync(force, now)
    suspend fun syncStudy(force: Boolean = false) = studySyncArea.syncStudy(force)

    // --- Courses ---------------------------------------------------------------------------------------------
    internal val coursesArea = CoursesRepository(this)
    val weatherLoading get() = coursesArea.weatherLoading
    suspend fun refreshWeather(force: Boolean = false, visible: Boolean = false, dates: List<LocalDate> = emptyList()) = coursesArea.refreshWeather(force, visible, dates)
    suspend fun refreshToday(force: Boolean = false) = coursesArea.refreshToday(force)
    suspend fun refreshStudyUpdates(force: Boolean = false) = coursesArea.refreshStudyUpdates(force)
    suspend fun refreshSemesters(force: Boolean = false) = coursesArea.refreshSemesters(force)
    suspend fun refreshProfile(force: Boolean = false) = coursesArea.refreshProfile(force)
    suspend fun refreshCourses(force: Boolean = false) = coursesArea.refreshCourses(force)
    suspend fun ensureWeek(date: kotlinx.datetime.LocalDate, force: Boolean = false, background: Boolean = false) = coursesArea.ensureWeek(date, force, background)
    suspend fun preparePlannerWeek(date: kotlinx.datetime.LocalDate, force: Boolean = false) = coursesArea.preparePlannerWeek(date, force)
    suspend fun visitCourse(id: String, force: Boolean = false) = coursesArea.visitCourse(id, force)
    suspend fun refreshAnnouncements(id: String, force: Boolean = false, background: Boolean = false) = coursesArea.refreshAnnouncements(id, force, background)
    suspend fun refreshHolidays(force: Boolean = false) = coursesArea.refreshHolidays(force)
    suspend fun refreshNewsComments(newsId: String, force: Boolean = false) = coursesArea.refreshNewsComments(newsId, force)
    suspend fun postNewsComment(announcement: Announcement, content: String, requestId: String) = coursesArea.postNewsComment(announcement, content, requestId)
    suspend fun refreshStudyGroups(force: Boolean = false) = coursesArea.refreshStudyGroups(force)
    suspend fun exportCalendar(action: MaterialAction, officeHoursLabel: String) = coursesArea.exportCalendar(action, officeHoursLabel)
    suspend fun selectSemester(id: String) = coursesArea.selectSemester(id)
    suspend fun toggleCoursePin(id: String) = coursesArea.toggleCoursePin(id)
    suspend fun saveCourseGroup(id: String?, title: String, courseIds: Set<String>, previousMembers: Set<String> = emptySet()) = coursesArea.saveCourseGroup(id, title, courseIds, previousMembers)
    suspend fun separateCourses(courseIds: Set<String>) = coursesArea.separateCourses(courseIds)
    suspend fun markRead(id: String) = coursesArea.markRead(id)

    // --- Coursework ------------------------------------------------------------------------------------------
    internal val courseworkArea = CourseworkRepository(this)
    suspend fun refreshCourseSubmitted(courseId: String, force: Boolean = false) = courseworkArea.refreshCourseSubmitted(courseId, force)
    suspend fun refreshAllCoursework(force: Boolean = false) = courseworkArea.refreshAllCoursework(force)
    suspend fun refreshCoursework(courseId: String, force: Boolean = false, background: Boolean = false) = courseworkArea.refreshCoursework(courseId, force, background)
    suspend fun refreshCourseTools(courseId: String, force: Boolean = false, background: Boolean = false) = courseworkArea.refreshCourseTools(courseId, force, background)
    suspend fun refreshPeerReviews(courseId: String, taskId: String, force: Boolean = false) = courseworkArea.refreshPeerReviews(courseId, taskId, force)
    suspend fun refreshCourseworkDetail(courseId: String, taskId: String, force: Boolean = false) = courseworkArea.refreshCourseworkDetail(courseId, taskId, force)
    suspend fun selectUpload(draft: UploadDraft) = courseworkArea.selectUpload(draft)
    fun uploadSelectionFailed(failure: FailureKind) = courseworkArea.uploadSelectionFailed(failure)
    fun dismissUpload() = courseworkArea.dismissUpload()
    suspend fun setPersonalDeadline(courseId: String, folderId: String, due: Long?) = courseworkArea.setPersonalDeadline(courseId, folderId, due)
    suspend fun completePersonalDeadline(folderId: String, completed: Boolean) = courseworkArea.completePersonalDeadline(folderId, completed)
    suspend fun acknowledgeWrite(key: String) = courseworkArea.acknowledgeWrite(key)
    suspend fun submitTask(courseId: String, taskId: String) = courseworkArea.submitTask(courseId, taskId)
    suspend fun confirmUpload() = courseworkArea.confirmUpload()
    suspend fun updateNotificationSources(now: Long) = courseworkArea.updateNotificationSources(now)
    suspend fun courseworkReminders(now: Long) = courseworkArea.courseworkReminders(now)

    // --- Community -------------------------------------------------------------------------------------------
    internal val communityArea = CommunityRepository(this)
    suspend fun refreshCoursewareBookmarks(force: Boolean = false) = communityArea.refreshCoursewareBookmarks(force)
    suspend fun refreshCoursewareProgress(courseId: String, unitId: String, force: Boolean = false) = communityArea.refreshCoursewareProgress(courseId, unitId, force)
    suspend fun setCoursewareBookmark(courseId: String, elementId: String, selected: Boolean, requestId: String) = communityArea.setCoursewareBookmark(courseId, elementId, selected, requestId)
    suspend fun refreshForumSubscriptions(courseId: String, force: Boolean = false) = communityArea.refreshForumSubscriptions(courseId, force)
    suspend fun refreshForumReactions(courseId: String, postId: String, force: Boolean = false) = communityArea.refreshForumReactions(courseId, postId, force)
    suspend fun setForumSubscription(courseId: String, discussionId: String, subscribed: Boolean, requestId: String) = communityArea.setForumSubscription(courseId, discussionId, subscribed, requestId)
    suspend fun setForumReaction(courseId: String, discussionId: String, postId: String, selected: Boolean, requestId: String) = communityArea.setForumReaction(courseId, discussionId, postId, selected, requestId)
    suspend fun postForumReply(courseId: String, discussionId: String, body: String, requestId: String) = communityArea.postForumReply(courseId, discussionId, body, requestId)
    suspend fun postBlubberReply(courseId: String, threadId: String, body: String, requestId: String) = communityArea.postBlubberReply(courseId, threadId, body, requestId)
    suspend fun postBlubberThread(courseId: String, body: String, requestId: String) = communityArea.postBlubberThread(courseId, body, requestId)
    suspend fun refreshCommunity(courseId: String, section: String, itemId: String = "", force: Boolean = false) = communityArea.refreshCommunity(courseId, section, itemId, force)

    // --- Messages --------------------------------------------------------------------------------------------
    internal val messagesArea = MessagesRepository(this)
    suspend fun refreshMessages(sent: Boolean = false, force: Boolean = false) = messagesArea.refreshMessages(sent, force)
    suspend fun findPeople(query: String) = messagesArea.findPeople(query)
    suspend fun sendMessage(recipient: CampusPerson, subject: String, body: String, requestId: String) = messagesArea.sendMessage(recipient, subject, body, requestId)
    suspend fun setMessageRead(id: String, read: Boolean) = messagesArea.setMessageRead(id, read)
    suspend fun refreshContacts(force: Boolean = false) = messagesArea.refreshContacts(force)
    suspend fun setContact(person: CampusPerson, add: Boolean) = messagesArea.setContact(person, add)

    // --- OfficeHours -----------------------------------------------------------------------------------------
    internal val officeHoursArea = OfficeHoursRepository(this)
    suspend fun refreshConsultations(rangeType: String, rangeId: String, rangeName: String, force: Boolean = false) = officeHoursArea.refreshConsultations(rangeType, rangeId, rangeName, force)
    suspend fun refreshCourseStaff(force: Boolean = false) = officeHoursArea.refreshCourseStaff(force)
    suspend fun bookConsultation(block: ConsultationBlock, slot: ConsultationSlot, reason: String, requestId: String) = officeHoursArea.bookConsultation(block, slot, reason, requestId)
    suspend fun cancelConsultation(booking: ConsultationBooking, reason: String, rangeType: String = booking.rangeType) = officeHoursArea.cancelConsultation(booking, reason, rangeType)

    // --- Materials -------------------------------------------------------------------------------------------
    internal val materialsArea = MaterialsRepository(this)
    suspend fun refreshSubmittedMaterials() = materialsArea.refreshSubmittedMaterials()
    suspend fun refreshFolder(courseId: String, parentId: String?, force: Boolean = false, background: Boolean = false) = materialsArea.refreshFolder(courseId, parentId, force, background)
    suspend fun refreshCourseMaterials(courseId: String, force: Boolean = false) = materialsArea.refreshCourseMaterials(courseId, force)
    suspend fun prefetchFolder(courseId: String, folderId: String) = materialsArea.prefetchFolder(courseId, folderId)
    fun requestLinkedMaterial(courseId: String, url: String, page: String = "") = materialsArea.requestLinkedMaterial(courseId, url, page)
    fun requestMaterial(document: Document, action: MaterialAction = MaterialAction.OPEN) = materialsArea.requestMaterial(document, action)
    fun requestCoursewareFile(courseId: String, elementId: String, part: CoursewarePart) = materialsArea.requestCoursewareFile(courseId, elementId, part)
    suspend fun setMaterialDestination(courseId: String, destination: MaterialDestination?) = materialsArea.setMaterialDestination(courseId, destination)
    fun retryMaterial(document: Document) = materialsArea.retryMaterial(document)
    suspend fun download(document: Document, pin: Boolean = false, open: Boolean = true) = materialsArea.download(document, pin, open)
    suspend fun removeDownload(id: String) = materialsArea.removeDownload(id)
    suspend fun clearDownloads() = materialsArea.clearDownloads()

    // --- MaterialSync ----------------------------------------------------------------------------------------
    internal val materialSyncArea = MaterialSyncRepository(this)
    val materialSync get() = materialSyncArea.materialSync
    val syncsWithoutFolder get() = materialSyncArea.syncsWithoutFolder
    suspend fun planMaterialSync(courseIds: List<String>? = null, background: Boolean = false) = materialSyncArea.planMaterialSync(courseIds, background)
    fun startMaterialSync(plans: List<MaterialSyncPlan>, automatic: Boolean? = null) = materialSyncArea.startMaterialSync(plans, automatic)
    fun cancelMaterialSync() = materialSyncArea.cancelMaterialSync()
    fun dismissMaterialSync() = materialSyncArea.dismissMaterialSync()
    suspend fun setMaterialSyncAutomatic(courseId: String, enabled: Boolean) = materialSyncArea.setMaterialSyncAutomatic(courseId, enabled)
    suspend fun autoSyncMaterials() = materialSyncArea.autoSyncMaterials()

    companion object {
        const val WEATHER_TTL = 30 * 60_000L
        const val COURSE_TTL = 30L * 60 * 1000
        const val STUDY_SYNC_INTERVAL = 15L * 60_000
        const val STUDY_SYNC_MIN_GAP = 2L * 60_000
        /** Automatic retries after a temporary failure: soon, then less eagerly. */
        val RETRY_DELAYS = listOf(5_000L, 20_000L, 90_000L)
        const val EVENT_TTL = 5L * 60 * 1000
        const val FILE_TTL = 15L * 60 * 1000
        const val MATERIAL_ACTIVITY_TTL = 5L * 60 * 1000
        const val STORAGE_BUDGET = 250L * 1024 * 1024
        fun folderKey(courseId: String, parentId: String?) = CacheKeys.folder(courseId, parentId)
        /** Writes that belong to the account rather than one course; they stay visible regardless of the selected semester. */
        val ACCOUNT_WRITES = setOf("consultation", "consultation-cancel", "news-comment", "contact")
        const val CONSULTATION_TTL = 3L * 60 * 1000
        fun consultationKey(rangeType: String, rangeId: String) = CacheKeys.consultationRange(rangeType, rangeId)
    }
}
internal fun <T> Set<T>.takeLastSet(count: Int): Set<T> = toList().takeLast(count).toSet()
