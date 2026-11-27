package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class DigiCampusProviderTest {
    private fun resource(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun courseTypesSupportCurrentLabelsAndIncludedLegacyTypesWithoutGuessingNumericIds() {
        val current = resource("""{"type":"courses","id":"c","attributes":{"title":"Algorithms","course-type":"Übung"}}""")
        assertEquals("Übung", JsonApiMapper.course(current).typeName)
        val legacy = resource("""{"type":"courses","id":"c","attributes":{"title":"Algorithms","course-type":1},
            "relationships":{"sem-type":{"data":{"type":"sem-types","id":"1"}}}}""")
        assertEquals("", JsonApiMapper.course(legacy).typeName)
        assertEquals("Vorlesung", JsonApiMapper.course(legacy, listOf(resource(
            """{"type":"sem-types","id":"1","attributes":{"name":"Vorlesung"}}"""))).typeName)
    }

    @Test fun materialContentRejectsPendingEmptyAndUnrequestedPartialResponses() = runTest {
        for (status in listOf(HttpStatusCode.Accepted, HttpStatusCode.NoContent, HttpStatusCode.PartialContent)) {
            val client = HttpClient(MockEngine { respond("", status, headersOf(HttpHeaders.ContentType, "application/pdf")) })
            var written = 0
            try {
                val error = assertFailsWith<CampusException> {
                    DigiCampusProvider(client, { "session=test" }).download(Document("f", "c", "d", "notes.pdf")) { written += it.size }
                }
                assertEquals(FailureKind.INVALID_RESPONSE, error.kind); assertEquals(0, written)
            } finally { client.close() }
        }
    }

    @Test fun truncatedMaterialContentIsNeverReportedAsACompleteDownload() = runTest {
        val client = HttpClient(MockEngine { respond("abc", HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType to listOf("application/pdf"), HttpHeaders.ContentLength to listOf("10"))) })
        try {
            val error = assertFailsWith<CampusException> { DigiCampusProvider(client, { "session=test" }).download(Document("f", "c", "d", "notes.pdf")) {} }
            assertEquals(FailureKind.INVALID_RESPONSE, error.kind)
        } finally { client.close() }
    }

    @Test fun usesTheGivenNameInsteadOfAnAcademicTitleOrUsernameForTheGreeting() {
        val account = JsonApiMapper.account(resource("""{"type":"users","id":"a","attributes":{
            "formatted-name":"Dr. Alice Student","given-name":"Alice","family-name":"Student","username":"astudent"}}"""))
        assertEquals("Alice", account.firstName)
        val fallback = JsonApiMapper.account(resource("""{"type":"users","id":"a","attributes":{
            "given-name":"Alice","family-name":"Student","username":"astudent"}}"""))
        assertEquals("Alice Student", fallback.name)
    }

    @Test fun keepsRepeatedCalendarOccurrencesWithTheSameResourceIdAcrossPages() = runTest {
        val first = """{"type":"calendar-events","id":"weekly","attributes":{"title":"Study group","start":"2026-10-13T10:00:00+02:00","end":"2026-10-13T11:00:00+02:00"}}"""
        val second = """{"type":"calendar-events","id":"weekly","attributes":{"title":"Study group","start":"2026-10-20T10:00:00+02:00","end":"2026-10-20T11:00:00+02:00"}}"""
        val client = HttpClient(MockEngine { request ->
            if (request.url.parameters["page[offset]"] == "1") respond("""{"data":[$first,$second]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
            else respond("""{"data":[$first],"links":{"next":"/jsonapi.php/v1/users/a/events?page%5Boffset%5D=1"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val events = DigiCampusProvider(client, { "session=test" }).personalEvents("a", JsonApiMapper.timestamp("2026-10-12T00:00:00+02:00"))
            assertEquals(listOf("2026-10-13", "2026-10-20"), events.map { it.date.toString() })
        } finally { client.close() }
    }

    @Test fun requestsBothTimetablesWhenAWeekCrossesASemesterBoundaryWithoutPaging() = runTest {
        val stamp = JsonApiMapper::timestamp
        val boundary = stamp("2026-10-01T00:00:00+02:00")
        val terms = listOf(Semester("old", "Summer", stamp("2026-04-01T00:00:00+02:00"), boundary),
            Semester("new", "Winter", boundary, stamp("2027-04-01T00:00:00+02:00")))
        val requests = mutableListOf<Long>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("/jsonapi.php/v1/users/a/schedule", request.url.encodedPath)
            assertEquals(setOf("filter[timestamp]"), request.url.parameters.names())
            val timestamp = request.url.parameters["filter[timestamp]"]!!.toLong() * 1000
            requests += timestamp
            val term = if (timestamp < boundary) "old" else "new"
            val weekday = if (term == "old") 2 else 4
            respond("""{"data":[{"type":"schedule-entries","id":"$term","attributes":{
                "title":"$term slot","start":"10:00","end":"11:00","weekday":$weekday}}],
                "meta":{"semester":"https://digicampus.uni-augsburg.de/jsonapi.php/v1/semesters/$term"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val from = stamp("2026-09-28T00:00:00+02:00")
            val events = DigiCampusProvider(client, { "session=test" }).scheduledEvents("a", from, terms)
            assertEquals(listOf(from, boundary), requests)
            assertEquals(listOf("2026-09-29", "2026-10-01", "2026-10-08"), events.map { it.date.toString() })
        } finally { client.close() }
    }

    @Test fun currentUserCheckDoesNotSendForbiddenPaginationParameters() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/jsonapi.php/v1/users/me", request.url.encodedPath)
            assertEquals("Seminar_Session=test", request.headers[HttpHeaders.Cookie])
            assertEquals("application/vnd.api+json", request.headers[HttpHeaders.Accept])
            // Stud.IP's UsersShow inherits allowedPagingParameters = [].
            if (request.url.parameters.names().any { it.startsWith("page[") }) {
                respond("{\"errors\":[{\"status\":\"400\"}]}", HttpStatusCode.BadRequest,
                    headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
            } else respond(
                """{"data":{"id":"student-a","type":"users","attributes":{"username":"student","formatted-name":"A. Student"}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val account = DigiCampusProvider(client, { "Seminar_Session=test" }).account()
            assertEquals("student-a", account.id)
            assertEquals("A. Student", account.name)
        } finally { client.close() }
    }

    @Test fun singleCourseDetailsDoNotSendPaginationParameters() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/jsonapi.php/v1/courses/course-a", request.url.encodedPath)
            assertTrue(request.url.parameters.names().isEmpty())
            respond("""{"data":{"id":"course-a","type":"courses","attributes":{"title":"Algorithms"}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            assertEquals("Algorithms", DigiCampusProvider(client, { "session=test" }).course("course-a").title)
        } finally { client.close() }
    }

    @Test fun rejectedRequestsAreNotReportedAsTemporaryServerDelays() = runTest {
        val client = HttpClient(MockEngine {
            respond("{\"errors\":[{\"status\":\"400\"}]}", HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val error = assertFailsWith<CampusException> { DigiCampusProvider(client, { "session=test" }).account() }
            assertEquals(FailureKind.INVALID_RESPONSE, error.kind)
            assertEquals(400, error.httpStatus)
        } finally { client.close() }
    }

    @Test fun mapsStudipFieldsRelationshipsAndUnknownAttributes() {
        val course = JsonApiMapper.course(resource("""{
            "type":"courses","id":"course-a",
            "attributes":{"title":"Algorithms","course-number":"INF-1002","description":"<p>First</p><script>alert(1)</script><p>Second &amp; third</p>","unknown":"ignored"},
            "relationships":{"start-semester":{"data":{"type":"semesters","id":"ws26"}}}
        }"""))
        assertEquals("Algorithms", course.title)
        assertEquals("INF-1002", course.number)
        assertEquals("ws26", course.semesterId)
        assertEquals("First\nSecond & third", course.description)
        assertFalse(course.description.contains("alert"))

        val file = JsonApiMapper.document(resource("""{
            "type":"file-refs","id":"ref-a",
            "attributes":{"name":"notes.pdf","filesize":2048,"mime-type":"application/pdf","chdate":"2026-10-14T10:00:00+02:00","is-downloadable":false},
            "meta":{"download-url":"https://digicampus.uni-augsburg.de/sendfile.php?file_id=ref-a"}
        }"""), "course-a", "folder-a")
        assertEquals(2048, file.size)
        assertEquals("application/pdf", file.mimeType)
        assertFalse(file.downloadable)
        assertEquals("course-a", file.courseId)
    }

    @Test fun preservesCancellationAndTimezoneOffsets() {
        val before = JsonApiMapper.event(resource("""{"id":"e1","type":"course-events","attributes":{
            "title":"Lecture","start":"2026-10-25T02:30:00+02:00","end":"2026-10-25T02:30:00+01:00","is-cancelled":true},
            "relationships":{"owner":{"data":{"type":"courses","id":"course-a"}}}}"""))
        assertEquals(60 * 60_000L, before.end - before.start)
        assertTrue(before.cancelled)
        assertEquals("course-a", before.courseId)
        assertEquals("2026-10-25", before.date.toString())
    }

    @Test fun readsEveryPageBeforeReturningTheCollection() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += request.url.toString()
            assertEquals("session=test", request.headers[HttpHeaders.Cookie])
            if (request.url.parameters["page[offset]"] == "1") respond(
                """{"data":[{"id":"b","type":"courses","attributes":{"title":"Second"}}],"links":{"next":null}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
            else respond(
                """{"data":[{"id":"a","type":"courses","attributes":{"title":"First"}}],"links":{"next":"/jsonapi.php/v1/users/test/courses?page%5Boffset%5D=1"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        }
        val client = HttpClient(engine) { followRedirects = false }
        try {
            val result = DigiCampusProvider(client, { "session=test" }).courses("test", null)
            assertEquals(listOf("a", "b"), result.map { it.id })
            assertEquals(2, requested.size)
        } finally { client.close() }
    }

    @Test fun rejectsPaginationThatWouldLeakTheSession() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond("""{"data":[],"links":{"next":"https://example.org/steal"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val error = assertFailsWith<CampusException> { DigiCampusProvider(client, { "session=test" }).courses("test", null) }
            assertEquals(FailureKind.INVALID_RESPONSE, error.kind)
            assertEquals(1, requests)
        } finally { client.close() }
    }

    @Test fun failedLaterPageDoesNotReturnPartialResults() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.parameters.contains("next")) respond("", HttpStatusCode.Forbidden)
            else respond("""{"data":[{"id":"a","type":"courses","attributes":{"title":"First"}}],"links":{"next":"https://digicampus.uni-augsburg.de/jsonapi.php/v1/courses?next=1"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val error = assertFailsWith<CampusException> { DigiCampusProvider(client, { "" }).courses("test", null) }
            assertEquals(FailureKind.PERMISSION, error.kind)
        } finally { client.close() }
    }

    @Test fun htmlAndMissingDataAreNotAcceptedAsSuccessfulApiResponses() = runTest {
        val client = HttpClient(MockEngine { respond("<html>Login</html>", headers = headersOf(HttpHeaders.ContentType, "text/html")) })
        try {
            val error = assertFailsWith<CampusException> { DigiCampusProvider(client, { "" }).account() }
            assertEquals(FailureKind.INVALID_RESPONSE, error.kind)
        } finally { client.close() }
    }

    @Test fun rejectsUntrustedPortsCredentialsAndSchemes() {
        listOf(
            "http://digicampus.uni-augsburg.de/jsonapi.php/v1/users/me",
            "https://digicampus.uni-augsburg.de:8443/jsonapi.php/v1/users/me",
            "https://user:secret@digicampus.uni-augsburg.de/jsonapi.php/v1/users/me",
            "https://digicampus.uni-augsburg.de.evil.org/jsonapi.php/v1/users/me",
        ).forEach { assertFailsWith<CampusException> { DigiCampusProvider.trustedPageUrl(it) } }
    }
}
