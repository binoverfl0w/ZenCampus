package io.github.binoverfl0w.zencampus.data.study

import io.github.binoverfl0w.zencampus.data.CacheRecord
import io.github.binoverfl0w.zencampus.data.CampusProvider
import io.github.binoverfl0w.zencampus.data.CampusStore
import io.github.binoverfl0w.zencampus.data.NetworkConditions
import io.github.binoverfl0w.zencampus.data.RefreshCoordinator
import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json

/** Planner reads and personal choices share the account cache and lifetime, never its enrolment state. */
class PlannerRepository(private val account: Account, private val provider: CampusProvider, private val store: CampusStore,
    parentScope: CoroutineScope, private val conditions: () -> NetworkConditions, private val clock: () -> Long,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val slots = Semaphore(2)
    private val coordinator = RefreshCoordinator(CoroutineScope(scope.coroutineContext + computeDispatcher))
    private val errors = MutableStateFlow<Map<String, FailureKind>>(emptyMap())
    private var active = true
    val sync = combine(coordinator.active, errors) { running, failures -> SyncStatus(running, failures,
        failures.values.any { it == FailureKind.AUTHENTICATION }) }.stateIn(scope, SharingStarted.Eagerly, SyncStatus())
    val snapshot = store.observe(account.cacheScope).map { records ->
        val map = records.associateBy { it.key }
        PlannerSnapshot(map[SETTINGS].decode<PlannerSettings>() ?: PlannerSettings(),
            map[FILTERS].decode<List<SavedPlannerFilter>>().orEmpty(), map[PLANS].decode<List<PlannedCourse>>().orEmpty(),
            records.filter { it.key.startsWith("planner-schedule:") }.mapNotNull { r -> r.decode<PlannerSchedule>()?.let { r.key to it } }.toMap(),
            records.filter { it.key.startsWith("planner-search:") }.mapNotNull { r -> r.decode<PlannerCoursePage>()?.let { r.key to it } }.toMap(),
            map[ROOMS].decode<PublishedRoomSchedule>(), records.filter { it.key.startsWith("planner-") }.associate { it.key to it.updatedAt }, ready = true,
            campusRooms = records.filter { it.key.startsWith("planner-campus-rooms:") }.mapNotNull { r -> r.decode<CampusRoomResults>()?.let { r.key to it } }.toMap(),
            roomWeeks = records.filter { it.key.startsWith("planner-room-week:") }.mapNotNull { r -> r.decode<CampusRoomWeek>()?.let { r.key to it } }.toMap(),
            lecturerCourses = records.filter { it.key.startsWith("planner-lecturer:") }.mapNotNull { r -> r.decode<LecturerCourses>()?.let { r.key to it } }.toMap())
    }.flowOn(computeDispatcher).stateIn(scope, SharingStarted.Eagerly, PlannerSnapshot())

    suspend fun search(request: PlannerSearch, force: Boolean = false) {
        if (request.normalized.length < 3 || request.semesterId.isBlank() || request.offset < 0 || request.offset > 500) return
        read(request.key, 30 * 60_000L, force) { provider.searchPlannerCourses(request.copy(query = request.normalized)) }
        trim("planner-search:", 40)
    }
    suspend fun inspect(course: Course, semester: Semester, force: Boolean = false, speculative: Boolean = false) {
        val key = plannerScheduleKey(course.id, semester.id)
        read(key, 5 * 60_000L, force, speculative) { provider.plannerSchedule(course, semester) }
        withContext(computeDispatcher) {
            val protected = (stored<List<PlannedCourse>>(PLANS).orEmpty().map { plannerScheduleKey(it.course.id, it.semesterId) } +
                stored<PlannerSettings>(SETTINGS)?.overlays.orEmpty().map { plannerScheduleKey(it.course.id, it.semesterId) }).toSet()
            trim("planner-schedule:", 30, protected)
        }
    }
    suspend fun refreshRooms(force: Boolean = false) = read(ROOMS, 5 * 60_000L, force) { provider.publishedRooms() }
    suspend fun searchCampusRooms(query: String, force: Boolean = false) {
        if (query.trim().length !in 3..120) return
        val key = campusRoomSearchKey(query)
        val legacy = stored<CampusRoomResults>(key)?.version?.let { it < CAMPUS_ROOM_SEARCH_VERSION } == true
        read(key, 30 * 60_000L, force || legacy) { provider.searchCampusRooms(query.trim()) }
        trim("planner-campus-rooms:", 20)
    }
    suspend fun roomWeek(room: CampusRoom, monday: kotlinx.datetime.LocalDate, force: Boolean = false) {
        read(campusRoomWeekKey(room.id, monday), 5 * 60_000L, force) { provider.campusRoomWeek(room, monday) }
        trim("planner-room-week:", 16, setOf(campusRoomWeekKey(room.id, monday)))
    }
    suspend fun prepareLecturer(query: String, semesterId: String, force: Boolean = false): LecturerCourses? {
        if (query.trim().length !in 3..120 || semesterId.isBlank()) return null
        val key = lecturerCoursesKey(query, semesterId)
        read(key, 30 * 60_000L, force) { provider.lecturerCourses(query.trim(), semesterId) }
        trim("planner-lecturer:", 20)
        return stored(key)
    }
    suspend fun settings(transform: (PlannerSettings) -> PlannerSettings) = edit<PlannerSettings>(SETTINGS, PlannerSettings()) {
        val changed = transform(it)
        changed.copy(zoom = changed.zoom.coerceIn(1f, 2.5f), view = changed.view.takeIf { v -> v in setOf("day", "week") } ?: "day",
            workStart = changed.workStart.coerceIn(0, 23), workEnd = changed.workEnd.coerceIn(changed.workStart.coerceIn(0, 23) + 1, 24),
            minimumGap = changed.minimumGap.coerceIn(15, 180))
    }
    suspend fun savePlan(course: Course, semesterId: String, groups: Set<String>) = edit<List<PlannedCourse>>(PLANS, emptyList()) { old ->
        old.filterNot { it.course.id == course.id && it.semesterId == semesterId } + PlannedCourse(course, semesterId, groups,
            savedSchedule = stored<PlannerSchedule>(plannerScheduleKey(course.id, semesterId)), capturedAt = clock())
    }
    suspend fun removePlan(courseId: String, semesterId: String) = edit<List<PlannedCourse>>(PLANS, emptyList()) {
        it.filterNot { plan -> plan.course.id == courseId && plan.semesterId == semesterId }
    }
    suspend fun togglePlan(courseId: String, semesterId: String, enabled: Boolean) = edit<List<PlannedCourse>>(PLANS, emptyList()) {
        it.map { plan -> if (plan.course.id == courseId && plan.semesterId == semesterId) plan.copy(enabled = enabled) else plan }
    }
    suspend fun saveFilter(name: String, filter: PlannerFilter) = edit<List<SavedPlannerFilter>>(FILTERS, emptyList()) {
        val clean = name.trim().take(60)
        val source = stored<PlannerSettings>(SETTINGS) ?: PlannerSettings()
        if (clean.isBlank()) it else (it.filterNot { old -> old.name.equals(clean, true) } + SavedPlannerFilter(clean.lowercase(), clean, filter,
            source.publishedRooms, source.overlays, source.exploreOnly, source.overlayName, source.campusRoom, source.lecturerResult)).takeLast(20)
    }
    suspend fun removeFilter(id: String) = edit<List<SavedPlannerFilter>>(FILTERS, emptyList()) { it.filterNot { f -> f.id == id } }
    fun dismissErrors() { errors.value = emptyMap() }
    suspend fun close() { mutex.withLock { active = false }; job.cancel() }
    private fun checkOpen() { if (!active || scope.coroutineContext[Job]?.isActive == false) throw CancellationException("Planner account closed") }
    private suspend inline fun <reified T> read(key: String, ttl: Long, force: Boolean, speculative: Boolean = false, crossinline fetch: suspend () -> T) {
        try { coordinator.run(key, speculative) {
            slots.withPermit {
                checkOpen()
                val cached = store.read(account.cacheScope, key)
                if (!force && isFresh(cached?.updatedAt, clock(), ttl) && cached.decode<T>() != null) return@withPermit
                if (!conditions().connected) throw CampusException(FailureKind.OFFLINE)
                val value = fetch(); currentCoroutineContext().ensureActive()
                mutex.withLock { checkOpen(); store.put(account.cacheScope, key, json.encodeToString(value), clock()) }
                errors.update { it - key }
            }
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val kind = (e as? CampusException)?.kind ?: FailureKind.OFFLINE
            if (kind in setOf(FailureKind.PERMISSION, FailureKind.NOT_FOUND, FailureKind.AUTHENTICATION)) mutex.withLock {
                if (active) store.remove(account.cacheScope, key)
            }
            if (active) errors.update { it + (key to kind) }
        }
    }
    private suspend inline fun <reified T> edit(key: String, fallback: T, crossinline transform: suspend (T) -> T) = withContext(computeDispatcher) {
        mutex.withLock {
            checkOpen()
            val next = transform(stored<T>(key) ?: fallback)
            store.put(account.cacheScope, key, json.encodeToString(next), clock())
        }
    }
    private suspend fun trim(prefix: String, count: Int, protected: Set<String> = emptySet()) = mutex.withLock {
        if (active) store.observe(account.cacheScope).first().filter { it.key.startsWith(prefix) && it.key !in protected }
            .sortedByDescending { it.updatedAt }.drop(count).forEach { store.remove(account.cacheScope, it.key) }
    }
    private suspend inline fun <reified T> stored(key: String): T? = store.read(account.cacheScope, key).decode()
    private inline fun <reified T> CacheRecord?.decode(): T? = this?.let { runCatching { json.decodeFromString<T>(it.payload) }.getOrNull() }
    companion object {
        const val SETTINGS = "planner-settings"
        const val PLANS = "planner-plans"
        const val FILTERS = "planner-filters"
        const val ROOMS = "planner-rooms"
        val PERSONAL_KEYS = setOf(SETTINGS, PLANS, FILTERS)
    }
}
