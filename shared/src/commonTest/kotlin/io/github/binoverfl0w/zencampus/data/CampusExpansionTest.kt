package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.WidgetFrame
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.design.content
import io.github.binoverfl0w.zencampus.ui.homeWidgetFrames
import io.github.binoverfl0w.zencampus.ui.homeWidgets
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.*

class CampusExpansionTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0) =
        LocalDateTime(year, month, day, hour, minute).toInstant(CAMPUS_ZONE).toEpochMilliseconds()

    // Calendar export

    @Test fun calendarExportEscapesTextFoldsLongLinesAndMarksCancellations() {
        val events = listOf(
            CampusEvent("e1", "Lecture", at(2026, 11, 16, 10, 15), at(2026, 11, 16, 11, 45), courseId = "c",
                location = "HS I, Building; North", description = "Line one\nLine two"),
            CampusEvent("e2", "Excursion", at(2026, 11, 20), at(2026, 11, 21), allDay = true),
            CampusEvent("e3", "Cancelled", at(2026, 11, 17, 8), at(2026, 11, 17, 9), cancelled = true),
            CampusEvent("broken", "Ends before it starts", at(2026, 11, 18, 9), at(2026, 11, 18, 8)),
        )
        val ics = calendarIcs(events, listOf(Course("c", "Algorithms", "", "")), at(2026, 11, 14))
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n")); assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertEquals(3, Regex("BEGIN:VEVENT").findAll(ics).count())
        // Winter time in Augsburg is UTC+1.
        assertContains(ics, "DTSTART:20261116T091500Z"); assertContains(ics, "DTEND:20261116T104500Z")
        assertContains(ics, "SUMMARY:Algorithms · Lecture")
        assertContains(ics, "LOCATION:HS I\\, Building\\; North")
        assertContains(ics, "DESCRIPTION:Line one\\nLine two")
        assertContains(ics, "DTSTART;VALUE=DATE:20261120"); assertContains(ics, "DTEND;VALUE=DATE:20261121")
        assertContains(ics, "STATUS:CANCELLED")
        assertFalse("Ends before it starts" in ics)
        ics.split("\r\n").forEach { assertTrue(it.encodeToByteArray().size <= 75, it) }
    }

    @Test fun foldingNeverSplitsMultiByteCharacters() {
        val folded = foldIcsLine("SUMMARY:" + "ä😀".repeat(40))
        val lines = folded.split("\r\n")
        assertTrue(lines.size > 1)
        lines.forEach { assertTrue(it.encodeToByteArray().size <= 75) }
        assertEquals("SUMMARY:" + "ä😀".repeat(40), lines.first() + lines.drop(1).joinToString("") { it.removePrefix(" ") })
        assertFalse(lines.any { line -> line.lastOrNull()?.isHighSurrogate() == true })
    }

    // Holidays, office hours, comments and contacts on DigiCampus

    @Test fun holidaysAreReadFromTheDateKeyedObjectAndAnEmptyYearIsAnEmptyList() = runTest {
        var year = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("/jsonapi.php/v1/holidays", request.url.encodedPath)
            year = request.url.parameters["filter[year]"]!!.toInt()
            if (year == 2026) respond("""{"2026-12-25":{"holiday":"1. Weihnachtstag","mandatory":true},"2026-12-24":{"holiday":"Heiligabend","mandatory":false}}""", headers = json)
            else respond("[]", headers = json)
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" })
            val holidays = provider.holidays(2026)
            assertEquals(listOf(LocalDate(2026, 12, 24), LocalDate(2026, 12, 25)), holidays.map { it.date })
            assertTrue(holidays.last().mandatory); assertEquals("Heiligabend", holidays.first().name)
            assertTrue(provider.holidays(2027).isEmpty()); assertEquals(2027, year)
        } finally { client.close() }
    }

    @Test fun malformedHolidaysAreRejected() = runTest {
        val client = HttpClient(MockEngine { respond("""{"not-a-date":{"holiday":"x"}}""", headers = json) })
        try { assertFailsWith<CampusException> { DigiCampusProvider(client, { "fixture" }).holidays(2026) } }
        finally { client.close() }
    }

    @Test fun consultationsReadCurrentBlocksTheirSlotsAndOnlyTheViewersOwnBooking() = runTest {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/jsonapi.php/v1/users/teacher/consultations" -> {
                    assertEquals("1", request.url.parameters["filter[current]"])
                    respond("""{"data":[{"type":"consultation-blocks","id":"block","attributes":{"start":"2026-11-18T10:00:00+01:00","end":"2026-11-18T12:00:00+01:00","room":"<b>R 1001</b>","size":1,"require-reason":"yes","confirmation-text":"Bring your draft"}}]}""", headers = json)
                }
                "/jsonapi.php/v1/consultation-blocks/block/slots" -> {
                    assertEquals("bookings", request.url.parameters["include"])
                    respond("""{"data":[
                        {"type":"consultation-slots","id":"s2","attributes":{"start_time":"2026-11-18T10:30:00+01:00","end_time":"2026-11-18T11:00:00+01:00","is-bookable":false},"relationships":{"bookings":{"data":[{"type":"consultation-bookings","id":"other"}]}}},
                        {"type":"consultation-slots","id":"s1","attributes":{"start_time":"2026-11-18T10:00:00+01:00","end_time":"2026-11-18T10:30:00+01:00","is-bookable":false},"relationships":{"bookings":{"data":[{"type":"consultation-bookings","id":"mine"}]}}},
                        {"type":"consultation-slots","id":"s3","attributes":{"start_time":"2026-11-18T11:00:00+01:00","end_time":"2026-11-18T11:30:00+01:00","is-bookable":true}}],
                        "included":[
                        {"type":"consultation-bookings","id":"mine","relationships":{"user":{"data":{"type":"users","id":"me"}}}},
                        {"type":"consultation-bookings","id":"other","relationships":{"user":{"data":{"type":"users","id":"someone"}}}}]}""", headers = json)
                }
                else -> error("Unexpected ${request.url}")
            }
        })
        try {
            val block = DigiCampusProvider(client, { "fixture" }).consultations("users", "teacher", "me").single()
            assertEquals("R 1001", block.room); assertTrue(block.reasonRequired); assertEquals("Bring your draft", block.confirmationText)
            assertEquals(listOf("s1", "s2", "s3"), block.slots.map { it.id })
            assertEquals(listOf("mine", "", ""), block.slots.map { it.bookingId })
            assertEquals(listOf(false, false, true), block.slots.map { it.bookable })
        } finally { client.close() }
    }

    @Test fun consultationRangesAreLimitedToKnownTypes() = runTest {
        val client = HttpClient(MockEngine { error("No request expected") })
        try { assertFailsWith<CampusException> { DigiCampusProvider(client, { "fixture" }).consultations("files", "x", "me") } }
        finally { client.close() }
    }

    @Test fun bookingIsConfirmedOnlyByAVerifiedCreatedLocation() = runTest {
        var location: String? = "/jsonapi.php/v1/consultation-bookings/booked"
        var status = HttpStatusCode.Created
        var body: JsonObject? = null
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/jsonapi.php/v1/consultation-slots/slot/bookings", request.url.encodedPath)
            body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond("", status, location?.let { headersOf(HttpHeaders.Location, it) } ?: headersOf())
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" })
            assertEquals(WriteResult.Confirmed("booked"), provider.bookConsultation("slot", "me", "  Thesis draft "))
            val data = body!!["data"]!!.jsonObject
            assertEquals("Thesis draft", data["attributes"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
            assertEquals("me", data["relationships"]!!.jsonObject["user"]!!.jsonObject["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            location = "https://evil.example/jsonapi.php/v1/consultation-bookings/booked"
            assertEquals(WriteResult.Uncertain, provider.bookConsultation("slot", "me", ""))
            location = null
            assertEquals(WriteResult.Uncertain, provider.bookConsultation("slot", "me", ""))
            status = HttpStatusCode.Conflict
            assertIs<WriteResult.Rejected>(provider.bookConsultation("slot", "me", ""))
        } finally { client.close() }
    }

    @Test fun cancellingABookingNeeds204() = runTest {
        var status = HttpStatusCode.NoContent
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/jsonapi.php/v1/consultation-bookings/b1", request.url.encodedPath)
            respond("", status)
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" })
            assertEquals(WriteResult.Confirmed(Unit), provider.cancelConsultation("b1", "Ill"))
            status = HttpStatusCode.OK
            assertEquals(WriteResult.Uncertain, provider.cancelConsultation("b1", ""))
            status = HttpStatusCode.Forbidden
            assertIs<WriteResult.Rejected>(provider.cancelConsultation("b1", ""))
        } finally { client.close() }
    }

    @Test fun newsCommentsAreReadInOrderAndPostedAsEscapedText() = runTest {
        var posted = ""
        val client = HttpClient(MockEngine { request ->
            assertEquals("/jsonapi.php/v1/news/n1/comments", request.url.encodedPath)
            if (request.method == HttpMethod.Post) {
                posted = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["data"]!!.jsonObject["attributes"]!!.jsonObject["content"]!!.jsonPrimitive.content
                respond("", HttpStatusCode.Created, headersOf(HttpHeaders.Location, "/jsonapi.php/v1/comments/new"))
            } else respond("""{"data":[
                {"type":"comments","id":"b","attributes":{"content":"Second","mkdate":"2026-11-10T10:00:00+01:00"},"relationships":{"author":{"data":{"type":"users","id":"u"}}}},
                {"type":"comments","id":"a","attributes":{"content":"<p>First</p>","mkdate":"2026-11-09T10:00:00+01:00"},"relationships":{"author":{"data":{"type":"users","id":"u"}}}}],
                "included":[{"type":"users","id":"u","attributes":{"given-name":"Ada","family-name":"Lovelace"}}]}""", headers = json)
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" })
            val comments = provider.newsComments("n1")
            assertEquals(listOf("a", "b"), comments.map { it.id })
            assertEquals("First", comments.first().content.trim()); assertEquals("Ada Lovelace", comments.first().author)
            assertEquals(WriteResult.Confirmed(Unit), provider.postNewsComment("n1", "a <b> & c\nnext"))
            assertEquals("a &lt;b&gt; &amp; c<br>next", posted)
            assertIs<WriteResult.Rejected>(provider.postNewsComment("n1", "   "))
        } finally { client.close() }
    }

    @Test fun contactsUseTheRelationshipEndpointAndNeverAddYourself() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/jsonapi.php/v1/users/me/contacts")
                respond("""{"data":[{"type":"users","id":"z","attributes":{"formatted-name":"Zoe"}},{"type":"users","id":"a","attributes":{"username":"anna"}}]}""", headers = json)
            else {
                assertEquals("/jsonapi.php/v1/users/me/relationships/contacts", request.url.encodedPath)
                methods += request.method
                val data = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["data"]!!.jsonArray.single().jsonObject
                assertEquals("friend", data["id"]!!.jsonPrimitive.content)
                respond("", HttpStatusCode.NoContent)
            }
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" })
            assertEquals(listOf("anna", "Zoe"), provider.contacts("me").map { it.name })
            assertEquals(WriteResult.Confirmed(Unit), provider.setContact("me", "friend", true))
            assertEquals(WriteResult.Confirmed(Unit), provider.setContact("me", "friend", false))
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete), methods)
            assertIs<WriteResult.Rejected>(provider.setContact("me", "me", true))
        } finally { client.close() }
    }

    // Term status

    @Test fun termStatusFollowsTheLecturePeriod() {
        val semester = Semester("ws", "WS 26/27", at(2026, 10, 1), at(2027, 3, 31), at(2026, 10, 12), at(2027, 2, 6))
        assertEquals(TermPhase.BEFORE_LECTURES, termStatus(listOf(semester), LocalDate(2026, 10, 5))?.phase)
        assertEquals(7, termStatus(listOf(semester), LocalDate(2026, 10, 5))?.daysUntilChange)
        assertEquals(TermPhase.LECTURES, termStatus(listOf(semester), LocalDate(2026, 11, 14))?.phase)
        assertEquals(TermPhase.AFTER_LECTURES, termStatus(listOf(semester), LocalDate(2027, 2, 20))?.phase)
        assertNull(termStatus(listOf(semester), LocalDate(2027, 5, 1)))
        assertNull(termStatus(listOf(semester.copy(lectureStart = 0)), LocalDate(2026, 11, 14)))
    }

    @Test fun holidaysBetweenIsInclusiveAndBookingsBecomeEvents() {
        val holidays = listOf(Holiday(LocalDate(2026, 12, 26), "B"), Holiday(LocalDate(2026, 12, 24), "A"), Holiday(LocalDate(2027, 1, 7), "C"))
        assertEquals(listOf("A", "B"), holidaysBetween(holidays, LocalDate(2026, 12, 24), LocalDate(2026, 12, 26)).map { it.name })
        val booking = ConsultationBooking("b", "s", "k", "teacher", "Dr. Weber", at(2026, 11, 18, 10), at(2026, 11, 18, 10, 30), room = "R 1")
        assertEquals(listOf("b"), upcomingBookings(listOf(booking), at(2026, 11, 18, 10, 15)).map { it.id })
        assertTrue(upcomingBookings(listOf(booking), at(2026, 11, 18, 11)).isEmpty())
        val event = booking.asEvent("Office hours")
        assertEquals("consultation:b", event.id); assertEquals("Office hours · Dr. Weber", event.title); assertEquals("R 1", event.location)
    }

    // Library and Mensa helpers

    @Test fun holdAndCopyStatusesAreReadConservatively() {
        assertTrue(holdIsReady("Abholbereit bis 20.11.2026"))
        assertTrue(holdIsReady("Liegt bereit in der Zentralbibliothek"))
        assertFalse(holdIsReady("Vorgemerkt, Position 2"))
        assertTrue(copyAvailable("Verfügbar"))
        assertFalse(copyAvailable("Nicht verfügbar"))
        assertFalse(copyAvailable("Entliehen bis 01.12.2026"))
        assertFalse(copyAvailable(""))
    }

    @Test fun mensaLocationsAreLimitedToTheKnownList() {
        assertTrue(mensaUrl(MENSA_LOCATIONS.last().key).endsWith("mensa=${MENSA_LOCATIONS.last().key}"))
        assertTrue(mensaUrl("../evil").endsWith("mensa=$MENSA_DEFAULT_LOCATION"))
    }

    @Test fun favouritesIgnoreCaseAndSpacingAndAllergenFiltersHideDishes() {
        val nuts = MealLabel("Nu", "Schalenfrüchte")
        val curry = MensaMeal("1", "Gemüse  Curry", labels = listOf(nuts))
        val pasta = MensaMeal("2", "Pasta", vegetarian = true)
        val preferences = CampusServicePreferences(favoriteMeals = setOf(favoriteKey("gemüse curry")))
        assertTrue(curry.isFavorite(preferences)); assertFalse(pasta.isFavorite(preferences))
        val menu = MensaMenu("Mensa", listOf(MensaDay(LocalDate(2026, 11, 16), listOf(curry, pasta))), 0)
        assertEquals(listOf("1"), favoriteMealsOn(menu, LocalDate(2026, 11, 16), preferences).map { it.id })
        val excluded = preferences.copy(excludedLabels = setOf("Nu"))
        assertFalse(curry.visibleWith(excluded)); assertTrue(pasta.visibleWith(excluded))
        assertTrue(favoriteMealsOn(menu, LocalDate(2026, 11, 16), excluded).isEmpty())
        assertEquals(listOf("Nu"), menuLabels(menu).map { it.code })
    }

    // Home-screen widget

    @Test fun homeWidgetsShowNextClassDeadlinesDishesAndLibrary() {
        val now = at(2026, 11, 16, 9)
        val snapshot = CampusSnapshot(Account("a", "A", "a"),
            courses = listOf(Course("c", "Algorithms and Data Structures", "", "")),
            events = listOf(CampusEvent("e", "Lecture", at(2026, 11, 16, 10, 15), at(2026, 11, 16, 11, 45), courseId = "c", location = "HS I")),
            coursework = mapOf("c" to listOf(
                CourseworkTask("t2", "c", "Sheet 5", due = at(2026, 11, 19, 12), submitted = false),
                CourseworkTask("t1", "c", "Sheet 4", due = at(2026, 11, 16, 23, 59), submitted = false),
                CourseworkTask("done", "c", "Sheet 3", due = at(2026, 11, 16, 12), submitted = true))),
        )
        val preferences = CampusServicePreferences(favoriteMeals = setOf(favoriteKey("Curry")))
        val menu = MensaMenu("Mensa", listOf(MensaDay(LocalDate(2026, 11, 16), listOf(
            MensaMeal("1", "Pasta", prices = MealPrices(studentCents = 300), vegetarian = true),
            MensaMeal("2", "Curry", prices = MealPrices(studentCents = 350), vegan = true)))), 0)
        val library = LibraryState(connected = true, account = LibraryAccount(loans = listOf(
            LibraryLoan("l1", "Book", dueDate = LocalDate(2026, 11, 17)), LibraryLoan("l2", "Other", dueDate = LocalDate(2026, 12, 3))),
            fetchedAt = now, holds = listOf(LibraryHold("h", "Held", ready = true))))
        val widgets = homeWidgets(snapshot, menu, library, preferences, now, Words(false))

        assertEquals("Monday, 16 November", widgets.date)
        val next = assertNotNull(widgets.nextClass)
        assertEquals("Algorithms and Data Structures", next.title); assertEquals("AD", next.initials)
        assertEquals("Today", next.day); assertEquals("10:15 – 11:45", next.range); assertFalse(next.running)
        assertEquals(at(2026, 11, 16, 10, 15), widgets.refreshAt, "the widget refreshes when the class starts")

        assertEquals(listOf("Sheet 4", "Sheet 5"), widgets.due.map { it.title })
        assertEquals("Today 23:59", widgets.due[0].due); assertTrue(widgets.due[0].urgent)
        assertEquals("Thu 19 Nov · 12:00", widgets.due[1].due); assertFalse(widgets.due[1].urgent)

        assertEquals(listOf("Curry", "Pasta"), widgets.mensa.meals.map { it.name }, "favourites first")
        assertTrue(widgets.mensa.meals[0].favorite); assertEquals("Vegan", widgets.mensa.meals[0].diet)
        assertNull(widgets.mensa.status)

        assertEquals("2 loans · next due Tomorrow", widgets.library.headline)
        assertEquals("1 ready to pick up", widgets.library.detail); assertTrue(widgets.library.attention)

        val during = homeWidgets(snapshot, menu, library, preferences, at(2026, 11, 16, 10, 30), Words(false))
        assertEquals("Now", during.nextClass?.day); assertEquals(at(2026, 11, 16, 11, 45), during.refreshAt)

        val frames = homeWidgetFrames(snapshot, menu, library, preferences, now, Words(false))
        assertEquals(WidgetFrame(now, widgets), frames.first())
        assertTrue(frames[1].content.nextClass?.running == true)
        assertNull(frames[2].content.nextClass)
        assertEquals("Overdue", frames[3].content.due.first().due)
        assertEquals("Tuesday, 17 November", frames.last().content.date)
        val encoded = Json.encodeToString(frames)
        assertEquals(frames, Json.decodeFromString<List<WidgetFrame>>(encoded))
        assertEquals(now, Json.parseToJsonElement(encoded).jsonArray.first().jsonObject.getValue("at").jsonPrimitive.long)

        val signedOut = homeWidgets(null, null, LibraryState(), preferences, now, Words(true))
        val signedOutFrames = homeWidgetFrames(null, null, LibraryState(), preferences, now, Words(true))
        assertEquals(signedOut, Json.decodeFromString<List<WidgetFrame>>(Json.encodeToString(signedOutFrames)).first().content)
        assertFalse(signedOut.signedIn); assertNull(signedOut.nextClass); assertTrue(signedOut.due.isEmpty())
        assertEquals("Heute kein Speiseplan", signedOut.mensa.status)
        assertEquals("Ausweis verbinden", signedOut.library.headline); assertFalse(signedOut.library.connected)
    }
}
