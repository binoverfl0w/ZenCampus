package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.data.CourseSource
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.IOException
import kotlinx.serialization.json.*

/** DigiCampus: Profile, semesters, courses, dates, announcements and comments. */
internal class DigiCampusCourses(connection: DigiCampusConnection) : DigiCampusApi(connection), CourseSource {
    override suspend fun activity(userId: String): List<CampusActivity> {
        val start = ((now() - 14L * 86_400_000L) / 1000).coerceAtLeast(0).toString()
        return collection("users/${userId.path()}/activitystream", mapOf("filter[start]" to start)).resources.map { resource ->
            if (resource.text("type") != "activities") throw CampusException(FailureKind.INVALID_RESPONSE)
            val a = resource.attributes()
            val context = ((resource["relationships"] as? JsonObject)?.get("context") as? JsonObject)?.get("data") as? JsonObject
            CampusActivity(resource.requiredId(), JsonApiMapper.plainText(a.text("title")), JsonApiMapper.plainText(a.text("content")),
                courseId = context?.takeIf { it.text("type") == "courses" }?.text("id").orEmpty(),
                kind = a.text("activity-type"), timestamp = a.text("mkdate").takeIf { it.isNotBlank() }?.let(JsonApiMapper::timestamp) ?: 0)
        }
    }
    override suspend fun holidays(year: Int): List<Holiday> {
        if (year !in 2000..2100) throw CampusException(FailureKind.INVALID_RESPONSE)
        // A plain JSON object keyed by date, or an empty array when the year has none.
        val element = getJsonElement("$DIGICAMPUS_API/holidays?filter%5Byear%5D=$year")
        if (element is JsonArray) { if (element.isEmpty()) return emptyList(); throw CampusException(FailureKind.INVALID_RESPONSE) }
        val days = element as? JsonObject ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        return days.map { (key, value) ->
            val date = runCatching { kotlinx.datetime.LocalDate.parse(key) }.getOrElse { throw CampusException(FailureKind.INVALID_RESPONSE) }
            val entry = value as? JsonObject ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            val name = entry.text("holiday").ifBlank { throw CampusException(FailureKind.INVALID_RESPONSE) }
            Holiday(date, JsonApiMapper.plainText(name), (entry["mandatory"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }.sortedBy { it.date }
    }

    override suspend fun newsComments(newsId: String): List<NewsComment> {
        if (newsId.isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
        val page = collection("news/${newsId.path()}/comments", mapOf("include" to "author"))
        val users = page.included.filter { it.text("type") == "users" }.associateBy { it.text("id") }
        return page.resources.map { resource ->
            if (resource.text("type") != "comments") throw CampusException(FailureKind.INVALID_RESPONSE)
            val a = resource.attributes()
            val author = users[resource.relatedId("author")]?.attributes()
            val name = author?.text("formatted-name").orEmpty().ifBlank {
                listOf(author?.text("given-name").orEmpty(), author?.text("family-name").orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
            }
            NewsComment(resource.requiredId(), JsonApiMapper.plainText(name), JsonApiMapper.plainText(a.text("content")),
                a.text("mkdate").takeIf { it.isNotBlank() }?.let(JsonApiMapper::timestamp) ?: 0L)
        }.sortedBy { it.timestamp }
    }

    override suspend fun postNewsComment(newsId: String, content: String): WriteResult<Unit> {
        if (newsId.isBlank() || content.isBlank() || content.length > 10_000) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val safe = content.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
        val payload = buildJsonObject { putJsonObject("data") { put("type", "comments"); putJsonObject("attributes") { put("content", safe) } } }.toString()
        return when (val result = jsonCreate("news/${newsId.path()}/comments", "comments", payload)) {
            is WriteResult.Confirmed -> WriteResult.Confirmed(Unit)
            is WriteResult.Rejected -> result
            WriteResult.Uncertain -> WriteResult.Uncertain
        }
    }

    override suspend fun studyGroupProposals(): List<Course> {
        val page = collection("studygroup-proposals", paginated = false)
        return page.resources.map { resource ->
            if (resource.text("type") != "courses") throw CampusException(FailureKind.INVALID_RESPONSE)
            JsonApiMapper.course(resource, page.included)
        }
    }

    override suspend fun account(): Account {
        val resource = collection("users/me", paginated = false).resources.singleOrNull()
            ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        return JsonApiMapper.account(resource)
    }

    override suspend fun semesters(): List<Semester> =
        collection("semesters").resources.map(JsonApiMapper::semester)
            .filter { it.end >= now() - 3L * 366 * 86_400_000 && it.start <= now() + 366L * 86_400_000 }
            .sortedByDescending { it.start }

    override suspend fun courses(userId: String, semesterId: String?): List<Course> {
        val parameters = buildMap {
            if (!semesterId.isNullOrBlank()) put("filter[semester]", semesterId)
        }
        val page = collection("users/${userId.path()}/courses", parameters)
        return page.resources.map { JsonApiMapper.course(it, page.included) }
    }

    override suspend fun course(id: String): Course {
        val page = collection("courses/${id.path()}", paginated = false)
        return page.resources.singleOrNull()?.let { JsonApiMapper.course(it, page.included) }
            ?: throw CampusException(FailureKind.INVALID_RESPONSE)
    }

    override suspend fun events(courseId: String): List<CampusEvent> =
        collection("courses/${courseId.path()}/events").resources.map { JsonApiMapper.event(it, courseId) }

    override suspend fun plannerSchedule(course: Course, semester: Semester): PlannerSchedule {
        var denied: CampusException? = null
        val dates = try { events(course.id) } catch (e: CampusException) {
            if (e.kind !in setOf(FailureKind.PERMISSION, FailureKind.NOT_FOUND)) throw e
            denied = e; null
        }
        val published = try {
            val url = URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/course/details/").apply { parameters.append("sem_id", course.id) }.buildString()
            PlannerPublicPages.course(authenticatedCourseHtml(url), course, semester)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (dates == null) throw (denied ?: e)
            null
        }
        return if (dates == null) published ?: throw CampusException(FailureKind.PERMISSION)
            else PlannerSchedule(published?.course ?: course, semester.id,
                dates.filter { it.start < semester.end && it.end > semester.start }, unsupported = 0)
    }

    override suspend fun personalEvents(userId: String, from: Long): List<CampusEvent> =
        collection("users/${userId.path()}/events", mapOf("filter[timestamp]" to (from / 1000).toString()))
            .resources.map { JsonApiMapper.event(it) }

    override suspend fun scheduledEvents(userId: String, from: Long, semesters: List<Semester>): List<CampusEvent> {
        val until = Instant.fromEpochMilliseconds(from).toLocalDateTime(CAMPUS_ZONE).date
            .plus(CALENDAR_WINDOW_DAYS, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
        val terms = semesters.filter { it.start < until && it.end > from }.sortedBy { it.start }
        // A week can span a semester boundary; each timetable is scoped to its own semester.
        val timestamps = terms.map { maxOf(from, it.start) }.distinct().ifEmpty { listOf(from) }
        val events = mutableListOf<CampusEvent>()
        for (timestamp in timestamps) {
            val page = collection("users/${userId.path()}/schedule",
                mapOf("filter[timestamp]" to (timestamp / 1000).toString()), paginated = false)
            val semesterId = page.meta.text("semester").substringBefore('?').substringAfterLast('/')
            val term = semesters.firstOrNull { it.id == semesterId }
                ?: terms.firstOrNull { timestamp >= it.start && timestamp < it.end }
            events += TimetableMapper.expand(page.resources, from, until, term)
        }
        return events.distinctBy { it.id + ":" + it.start }.sortedBy { it.start }
    }

    override suspend fun announcements(courseId: String): List<Announcement> =
        collection("courses/${courseId.path()}/news").resources.map { JsonApiMapper.announcement(it, courseId) }

}
