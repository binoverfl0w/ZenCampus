package io.github.binoverfl0w.zencampus.model

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.time.Instant

const val PUBLISHED_ROOMS_URL = "https://itwiwi-scripts.wiwi.uni-augsburg.de/dev/belegungsplan/"
@Serializable enum class PlannerSearchKind { COURSE, LECTURER }
@Serializable data class PlannerSearch(val query: String, val kind: PlannerSearchKind, val semesterId: String, val offset: Int = 0) {
    val normalized: String get() = query.trim().replace(Regex("\\s+"), " ").take(120)
    val key: String get() = "planner-search:${kind.name}:${semesterId.plannerKey()}:${normalized.lowercase().plannerKey()}:$offset"
}
@Serializable data class PlannerCoursePage(val request: PlannerSearch, val courses: List<Course>, val hasMore: Boolean, val total: Int? = null)
@Serializable data class PlannerSchedule(val course: Course, val semesterId: String, val events: List<CampusEvent>,
    val summaryOnly: Boolean = false, val unsupported: Int = 0)
@Serializable data class PlannedCourse(val course: Course, val semesterId: String, val groups: Set<String>, val enabled: Boolean = true,
    val savedSchedule: PlannerSchedule? = null, val capturedAt: Long = 0)
@Serializable data class PlannerFilter(val room: String = "", val lecturer: String = "", val query: String = "", val includePlans: Boolean = true)
@Serializable data class PlannerOverlay(val course: Course, val semesterId: String)
@Serializable data class SavedPlannerFilter(val id: String, val name: String, val filter: PlannerFilter,
    val publishedRooms: Boolean = false, val overlays: List<PlannerOverlay> = emptyList(), val exploreOnly: Boolean = false, val overlayName: String = "",
    val campusRoom: CampusRoom? = null, val lecturerResult: LecturerCourses? = null)
@Serializable data class PlannerSettings(val view: String = "day", val zoom: Float = 1f, val filter: PlannerFilter = PlannerFilter(),
    val workStart: Int = 8, val workEnd: Int = 20, val minimumGap: Int = 30, val overlayCourseId: String = "", val publishedRooms: Boolean = false,
    val overlaySemesterId: String = "", val selectedDate: String = "", val overlays: List<PlannerOverlay> = emptyList(),
    val exploreOnly: Boolean = false, val overlayName: String = "", val campusRoom: CampusRoom? = null, val lecturerResult: LecturerCourses? = null)
@Serializable data class PublishedRoom(val id: String, val label: String)
@Serializable data class PublishedRoomSchedule(val from: Long, val until: Long, val rooms: List<PublishedRoom>, val entries: List<PlannerEntry>,
    val sourceUrl: String = PUBLISHED_ROOMS_URL)
@Serializable data class PlannerEntry(val event: CampusEvent, val teacher: String = "", val tentative: Boolean = false,
    val summaryOnly: Boolean = false, val roomId: String = "", val explored: Boolean = false, val sourceUrl: String = "") {
    val key: String get() = "${event.courseId}:${event.id}:${event.start}:${if (tentative) "plan" else roomId}"
}
data class PlannerSnapshot(val settings: PlannerSettings = PlannerSettings(), val savedFilters: List<SavedPlannerFilter> = emptyList(),
    val plans: List<PlannedCourse> = emptyList(), val schedules: Map<String, PlannerSchedule> = emptyMap(),
    val pages: Map<String, PlannerCoursePage> = emptyMap(), val rooms: PublishedRoomSchedule? = null, val updatedAt: Map<String, Long> = emptyMap(), val ready: Boolean = false,
    val campusRooms: Map<String, CampusRoomResults> = emptyMap(), val roomWeeks: Map<String, CampusRoomWeek> = emptyMap(),
    val lecturerCourses: Map<String, LecturerCourses> = emptyMap())
fun plannerScheduleKey(courseId: String, semesterId: String) = "planner-schedule:${semesterId.plannerKey()}:${courseId.plannerKey()}"
private fun String.plannerKey(): String = encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
fun plannerGroup(event: CampusEvent): String {
    val local = Instant.fromEpochMilliseconds(event.start).toLocalDateTime(CAMPUS_ZONE)
    val end = Instant.fromEpochMilliseconds(event.end).toLocalDateTime(CAMPUS_ZONE)
    return "${local.dayOfWeek.ordinal}:${local.hour}:${local.minute}:${end.hour}:${end.minute}:${event.location.trim()}"
}
fun PlannerSnapshot.schedule(courseId: String, semesterId: String): PlannerSchedule? = schedules[plannerScheduleKey(courseId, semesterId)]
fun plannerEntries(snapshot: CampusSnapshot, planner: PlannerSnapshot, semesterId: String): List<PlannerEntry> {
    val schedules = planner.schedules.values.filter { it.semesterId == semesterId }.associateBy { it.course.id }
    val own = snapshot.events.map { event -> PlannerEntry(event,
        schedules[event.courseId]?.course?.teacher?.ifBlank { null } ?: snapshot.courses.firstOrNull { it.id == event.courseId }?.teacher.orEmpty()) }
    val planned = if (planner.settings.filter.includePlans) planner.plans.filter { it.semesterId == semesterId && it.enabled }.flatMap { plan ->
        (schedules[plan.course.id] ?: plan.savedSchedule)?.let { schedule -> schedule.events.filter { plannerGroup(it) in plan.groups }.map {
            PlannerEntry(it, schedule.course.teacher, tentative = true, summaryOnly = schedule.summaryOnly)
        } }.orEmpty()
    } else emptyList()
    // Official enrolled events win when a tentative course contains the exact same occurrence.
    val official = own.map { "${it.event.courseId}:${it.event.start}:${it.event.end}" }.toSet()
    return own + planned.filterNot { "${it.event.courseId}:${it.event.start}:${it.event.end}" in official }
}
fun filterPlanner(entries: List<PlannerEntry>, filter: PlannerFilter): List<PlannerEntry> = entries.filter {
    fun String.matches(q: String) = q.trim().isEmpty() || contains(q.trim(), ignoreCase = true)
    it.event.location.matches(filter.room) && it.teacher.matches(filter.lecturer) &&
        (filter.query.isBlank() || listOf(it.event.title, it.event.description, it.event.courseId).any { text -> text.matches(filter.query) })
}
data class PlannerClash(val first: PlannerEntry, val second: PlannerEntry, val from: Long, val until: Long)
fun plannerClashes(entries: List<PlannerEntry>, from: Long, until: Long): List<PlannerClash> {
    val relevant = entries.filter { !it.event.cancelled && !it.event.allDay && it.event.end > it.event.start && it.event.start < until && it.event.end > from }
        .distinctBy { it.key }.sortedBy { it.event.start }
    return relevant.flatMapIndexed { index, first -> relevant.drop(index + 1).takeWhile { it.event.start < first.event.end }.mapNotNull { second ->
        val start = maxOf(first.event.start, second.event.start, from)
        val end = minOf(first.event.end, second.event.end, until)
        if (start < end) PlannerClash(first, second, start, end) else null
    } }
}
data class PlannerGap(val from: Long, val until: Long)
fun plannerGaps(entries: List<PlannerEntry>, date: LocalDate, startHour: Int = 8, endHour: Int = 20, minimumMinutes: Int = 30): List<PlannerGap> {
    if (startHour !in 0..23 || endHour !in 1..24 || startHour >= endHour || minimumMinutes < 1) return emptyList()
    val start = date.atTime(startHour, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
    val end = if (endHour == 24) date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
        else date.atTime(endHour, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
    val busy = entries.filter { !it.event.cancelled && it.event.end > it.event.start && it.event.start < end && it.event.end > start }
        .sortedBy { it.event.start }
    val gaps = mutableListOf<PlannerGap>(); var cursor = start
    for (entry in busy) {
        val next = maxOf(start, entry.event.start)
        if (next - cursor >= minimumMinutes * 60_000L) gaps += PlannerGap(cursor, next)
        cursor = maxOf(cursor, minOf(end, entry.event.end))
    }
    if (end - cursor >= minimumMinutes * 60_000L) gaps += PlannerGap(cursor, end)
    return gaps
}
data class PlannerGridEvent(val entry: PlannerEntry, val startMinute: Int, val endMinute: Int, val column: Int, val columns: Int)
/** Interval clusters use shared lane counts so simultaneous sessions remain individually tappable. */
fun plannerGridEvents(entries: List<PlannerEntry>, date: LocalDate): List<PlannerGridEvent> {
    val start = date.atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
    val end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
    val sorted = entries.filter { !it.event.allDay && it.event.end > it.event.start && it.event.start < end && it.event.end > start }.sortedBy { it.event.start }
    val result = mutableListOf<PlannerGridEvent>(); val cluster = mutableListOf<Pair<PlannerEntry, Int>>()
    val lanes = mutableListOf<Long>(); var clusterEnd = Long.MIN_VALUE
    fun flush() {
        for ((entry, lane) in cluster) {
            val a = Instant.fromEpochMilliseconds(maxOf(entry.event.start, start)).toLocalDateTime(CAMPUS_ZONE)
            val b = Instant.fromEpochMilliseconds(minOf(entry.event.end, end)).toLocalDateTime(CAMPUS_ZONE)
            val first = a.hour * 60 + a.minute
            val last = if (entry.event.end >= end) 1440 else maxOf(first + 15, b.hour * 60 + b.minute).coerceAtMost(1440)
            result += PlannerGridEvent(entry, first, last, lane, lanes.size)
        }
        cluster.clear(); lanes.clear()
    }
    for (entry in sorted) {
        if (entry.event.start >= clusterEnd) flush()
        val lane = lanes.indexOfFirst { it <= entry.event.start }.let { if (it < 0) lanes.size else it }
        if (lane == lanes.size) lanes += entry.event.end else lanes[lane] = entry.event.end
        cluster += entry to lane; clusterEnd = maxOf(if (cluster.size == 1) Long.MIN_VALUE else clusterEnd, entry.event.end)
    }
    flush(); return result
}
