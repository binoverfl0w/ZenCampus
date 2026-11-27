package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarIntegrationTest {
    @Test fun algorithmsAtTenSurvivesAnEmptyCalendarAndReplacesACachedNovemberUpNext() = runTest {
        val now = JsonApiMapper.timestamp("2026-10-14T10:05:00+02:00")
        val account = Account("student-a", "Example Student", "student")
        val store = MemoryStore()
        // A course opened in the older connector could leave only this future class cached.
        val november = CampusEvent("november", "Algorithms", JsonApiMapper.timestamp("2026-11-03T10:00:00+01:00"),
            JsonApiMapper.timestamp("2026-11-03T11:30:00+01:00"), "algorithms")
        store.put(account.cacheScope, "events:algorithms", Json.encodeToString(listOf(november)), now - 60_000)
        val requested = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requested += request.url.encodedPath
            assertEquals("session=test", request.headers[HttpHeaders.Cookie])
            val body = when (request.url.encodedPath) {
                "/jsonapi.php/v1/users/me" -> """{"data":{"type":"users","id":"student-a","attributes":{
                    "formatted-name":"Example Student","given-name":"Example","username":"student"}}}"""
                "/jsonapi.php/v1/semesters" -> """{"data":[{"type":"semesters","id":"ws26","attributes":{
                    "title":"Winter 2026/27","start":"2026-10-01T00:00:00+02:00","end":"2027-04-01T00:00:00+02:00"}}]}"""
                "/jsonapi.php/v1/users/student-a/courses" -> """{"data":[{"type":"courses","id":"algorithms",
                    "attributes":{"title":"Algorithms"},"relationships":{"start-semester":{"data":{"type":"semesters","id":"ws26"}}}}]}"""
                "/jsonapi.php/v1/users/student-a/schedule" -> {
                    assertNull(request.url.parameters["page[limit]"])
                    """{"data":[{"type":"seminar-cycle-dates","id":"algorithms-weekly","attributes":{
                        "title":"Algorithms","weekday":3,"start":"10:00","end":"11:30","locations":["Lecture hall"],
                        "recurrence":{"FREQ":"WEEKLY","INTERVAL":1,"DTSTART":"2026-10-14T10:00:00+02:00",
                            "UNTIL":"2027-02-10T10:00:00+01:00","EXDATES":[]}},
                        "relationships":{"owner":{"data":{"type":"courses","id":"algorithms"}}}}],
                        "meta":{"semester":"https://digicampus.uni-augsburg.de/jsonapi.php/v1/semesters/ws26"}}"""
                }
                "/jsonapi.php/v1/holidays" -> "[]"
                "/jsonapi.php/v1/users/student-a/events", "/jsonapi.php/v1/courses/algorithms/news",
                "/jsonapi.php/v1/courseware-tasks", "/jsonapi.php/v1/courses/algorithms/folders",
                // The later sync steps can start before the assertions on a busy machine; they find nothing.
                "/jsonapi.php/v1/users/student-a/inbox", "/jsonapi.php/v1/users/student-a/activitystream",
                "/jsonapi.php/v1/courses/algorithms/file-refs" -> """{"data":[]}"""
                "/dispatch.php/course/overview" -> return@MockEngine respond(
                    """<ul id="tabs"><li id="nav_course_main" class="current"><a href="/dispatch.php/course/overview?cid=algorithms"><span class="tab-title">Overview</span></a></li></ul>""",
                    headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        val repository = CampusRepository(account, DigiCampusProvider(client, { "session=test" }, { now }),
            store, MemoryMaterials(), backgroundScope, clock = { now })
        try {
            repository.initialize()
            advanceUntilIdle()
            val snapshot = repository.snapshot.value
            val today = snapshot.events.filter { it.occursOn(campusToday(now)) }
            assertEquals(1, today.size)
            assertEquals("Algorithms", today.single().title)
            assertEquals(JsonApiMapper.timestamp("2026-10-14T10:00:00+02:00"), today.single().start)
            assertEquals(today.single(), nextUpcomingEvent(snapshot.events, now))
            assertEquals(today.single(), snapshot.events.single { it.occursOn(campusToday(now)) })
            assertTrue(requested.contains("/jsonapi.php/v1/users/student-a/schedule"))
            assertTrue(repository.sync.value.errors.isEmpty(), "errors=${repository.sync.value.errors} requested=$requested")
        } finally {
            repository.close(false)
            client.close()
        }
    }
}
