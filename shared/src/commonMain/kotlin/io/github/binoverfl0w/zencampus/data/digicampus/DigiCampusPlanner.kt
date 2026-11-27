package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.data.PlannerSource
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

/** DigiCampus: Course search, published rooms and lecturer schedules. */
internal class DigiCampusPlanner(connection: DigiCampusConnection) : DigiCampusApi(connection), PlannerSource {
    override suspend fun searchPlannerCourses(request: PlannerSearch): PlannerCoursePage {
        if (request.normalized.length < 3 || request.semesterId.isBlank() || request.offset !in 0..500)
            throw CampusException(FailureKind.INVALID_RESPONSE)
        val url = URLBuilder("$DIGICAMPUS_API/courses").apply {
            parameters.append("filter[q]", request.normalized)
            parameters.append("filter[fields]", if (request.kind == PlannerSearchKind.LECTURER) "lecturer" else "title_lecturer_number")
            parameters.append("filter[semester]", request.semesterId)
            parameters.append("page[offset]", request.offset.toString()); parameters.append("page[limit]", "20")
        }.buildString()
        val document = getJson(url)
        val data = document["data"] as? JsonArray ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        if (data.size > 20) throw CampusException(FailureKind.INVALID_RESPONSE)
        val included = (document["included"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val courses = data.map { value ->
            val resource = value as? JsonObject ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            if (resource.text("type") != "courses") throw CampusException(FailureKind.INVALID_RESPONSE)
            JsonApiMapper.course(resource, included)
        }.distinctBy { it.id }
        val next = link((document["links"] as? JsonObject)?.get("next"))?.let(::trustedPageUrl)
        val meta = document["meta"] as? JsonObject
        val page = meta?.get("page") as? JsonObject
        val total = ((page?.get("total") ?: meta?.get("total")) as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        return PlannerCoursePage(request, courses, next != null || total?.let { request.offset + data.size < it } == true, total)
    }

    override suspend fun publishedRooms(): PublishedRoomSchedule = PlannerPublicPages.rooms(publicHtml(PUBLISHED_ROOMS_URL), now())

    override suspend fun searchCampusRooms(query: String): CampusRoomResults {
        val search = CampusRoomQuery(query)
        if (search.text.length !in 3..120) throw CampusException(FailureKind.INVALID_RESPONSE)
        for (term in search.searchTerms) {
            val url = campusRoomDirectoryUrl(term)
            val page = CampusRoomPages.search(authenticatedCourseHtml(url), term)
            val matching = page.rooms.filter { search.matches(it.name) }
            if (matching.isNotEmpty()) return CampusRoomResults(search.text, matching, CAMPUS_ROOM_SEARCH_VERSION)
        }
        return CampusRoomResults(search.text, emptyList(), CAMPUS_ROOM_SEARCH_VERSION)
    }

    override suspend fun campusRoomWeek(room: CampusRoom, monday: kotlinx.datetime.LocalDate): CampusRoomWeek {
        if (!validCampusRoom(room) || monday.dayOfWeek != kotlinx.datetime.DayOfWeek.MONDAY) throw CampusException(FailureKind.INVALID_RESPONSE)
        val url = URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/resources/ajax/get_booking_plan/${room.id}").apply {
            parameters.append("start", Instant.fromEpochMilliseconds(monday.atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()).toString())
            parameters.append("end", Instant.fromEpochMilliseconds(monday.plus(7, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()).toString())
            parameters.append("booking_types", "0,1,2")
            parameters.append("display_requests", "0"); parameters.append("display_all_requests", "0")
        }.buildString()
        val response = htmlClient.get(url) { header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/json") }
        validateStatus(response.status.value)
        if (response.status != HttpStatusCode.OK || response.contentType()?.contentSubtype?.contains("json") != true) throw CampusException(FailureKind.INVALID_RESPONSE)
        val text = response.bodyAsText()
        if (text.length > 2 * 1024 * 1024) throw CampusException(FailureKind.INVALID_RESPONSE)
        return CampusRoomPages.bookings(text, room, monday)
    }

}
