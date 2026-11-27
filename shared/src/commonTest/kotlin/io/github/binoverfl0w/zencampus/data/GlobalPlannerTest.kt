package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.CampusRoomPages
import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.digicampus.PlannerPublicPages
import io.github.binoverfl0w.zencampus.data.study.PlannerRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalPlannerTest {
    private val room = CampusRoom("n1002", "N · 1002", "Lecture hall", 120,
        "$DIGICAMPUS_ORIGIN/dispatch.php/resources/room_planning/booking_plan/n1002")
    private val monday = LocalDate(2026, 10, 19)
    private fun time(day: LocalDate = monday, hour: Int = 10) = day.atTime(hour, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
    private fun row(id: String = room.id, name: String = room.name, href: String = room.bookingUrl) = """<tr>
        <td data-range_type="Room" data-id="$id"></td><td>${if (href.isBlank()) name else "<a href='$href'>$name</a>"}</td>
        <td>Lecture hall</td><td>120</td><td class="actions"></td></tr>"""
    private fun search(rows: String = row(), query: String = "1002") = """<form action="/dispatch.php/resources/search/rooms">
        <input name="special__room_name" value="$query"><button name="room_search">Search</button></form>
        <table class="default"><tbody>$rows</tbody></table>"""
    private fun booking(start: String = "2026-10-19T10:00:00", end: String = "2026-10-19T11:30:00", title: String = "Outside my courses", id: String = room.id, allDay: Boolean = false) =
        """{"resourceId":"$id","studip_range_id":"$id","studip_object_id":"booking","start":"$start","end":"$end","title":"$title","allDay":$allDay}"""

    @Test fun directoryUsesStableRoomIdentitiesAndKeepsRestrictedAndDuplicateNamesDistinct() {
        val result = CampusRoomPages.search(search(row() + row("other", room.name, "") + row("third", "N · 1003", room.bookingUrl.replace("n1002", "third"))), "1002")
        assertEquals(3, result.rooms.size)
        assertEquals(120, result.rooms.first().seats)
        assertEquals("", result.rooms[1].bookingUrl)
        assertNotEquals(result.rooms[0].id, result.rooms[1].id)
        assertTrue(validCampusRoom(result.rooms.first()))
    }

    @Test fun onlyVerifiedEmptyMessagesAndTheEchoedSearchCanProduceEmptyResults() {
        for (message in listOf("Es wurden keine Räume gefunden, die zu den angegebenen Suchkriterien passen!", "No rooms could be found that match the specified search criteria!")) {
            assertTrue(CampusRoomPages.search(search("") + "<div class='messagebox_info'>$message</div>", "1002").rooms.isEmpty())
        }
        for (html in listOf(search(""), search(query = "other"), search("") + "<div class='messagebox_info'>Unavailable</div>", search()+"<div class='messagebox_error'>Access failed</div>")) {
            assertFailsWith<CampusException> { CampusRoomPages.search(html, "1002") }
        }
        assertEquals(FailureKind.AUTHENTICATION, assertFailsWith<CampusException> { CampusRoomPages.search("<input type='password'>", "1002") }.kind)
    }

    @Test fun directoryRejectsForeignLinksDuplicateIdsAndWrongRoomBookingPaths() {
        for (html in listOf(search(row()+row()), search(row(href = "https://other.example/booking")),
            search(row(href = room.bookingUrl.replace("n1002", "different"))), search(row(id = "../../escape")),
            search(row(href = room.bookingUrl.replace("https://", "https://user:password@"))))) {
            assertFailsWith<CampusException> { CampusRoomPages.search(html, "1002") }
        }
    }

    @Test fun campusBookingsIncludeUnenrolledCoursesLocksAllDayAndCrossMidnightIntervals() {
        val plan = CampusRoomPages.bookings("[${booking()},${booking("2026-10-19T23:00:00", "2026-10-20T01:00:00", "Lock")},${booking("2026-10-21", "2026-10-22", "<b>Closed</b>", allDay = true)}]", room, monday)
        assertEquals(3, plan.entries.size)
        assertTrue(plan.entries.all { it.event.courseId.isBlank() && it.roomId == "campus:n1002" })
        assertEquals("Closed", plan.entries.last().event.title)
        assertTrue(plannerGaps(plan.entries, LocalDate(2026, 10, 21)).isEmpty())
        assertEquals(1440, plannerGridEvents(plan.entries, monday).last().endMinute)
        assertEquals(room.sourceUrl, plan.entries.first().sourceUrl)
    }

    @Test fun invalidAndForeignBookingRowsFailTheWholeFeedRatherThanInventingFreeGaps() {
        for (json in listOf("{}", "<html>Login</html>", "[${booking()},{}]", "[${booking(id = "other")}]",
            "[${booking(end = "2026-10-19T09:00:00")}]", "[${booking(start = "invalid")}]")) {
            assertFailsWith<CampusException> { CampusRoomPages.bookings(json, room, monday) }
        }
        assertTrue(CampusRoomPages.bookings("[]", room, monday).entries.isEmpty())
    }

    @Test fun currentRoomStatusFollowsTheWholeBusyChainRatherThanTheFirstBookingEnd() {
        val plan = CampusRoomPages.bookings("[${booking()},${booking("2026-10-19T11:00:00", "2026-10-19T12:00:00")},${booking("2026-10-19T12:00:00", "2026-10-19T13:00:00")},${booking("2026-10-19T14:00:00", "2026-10-19T15:00:00")}]", room, monday)
        assertEquals(time(hour = 13), roomBusyUntil(plan.entries, time()))
        assertNull(roomBusyUntil(plan.entries, time(hour = 9)))
        assertNull(roomBusyUntil(plan.entries, time(hour = 13)))
        assertNull(roomBusyUntil(plan.entries.map { it.copy(event = it.event.copy(cancelled = true)) }, time()))
    }

    @Test fun bookingDatesUseCampusTimeAndConservativelyCoverTheRepeatedAutumnHour() {
        val offset = CampusRoomPages.bookings("[${booking("2026-10-19T08:00:00Z", "2026-10-19T09:00:00Z")}]", room, monday)
        assertEquals(time(), offset.entries.single().event.start)
        val autumn = CampusRoomPages.bookings("[${booking("2026-10-25T02:30:00", "2026-10-25T02:45:00")}]", room, monday).entries.single().event
        assertEquals(75 * 60_000L, autumn.end - autumn.start)
        assertFailsWith<CampusException> { CampusRoomPages.bookings("[${booking("2027-03-28T02:30:00", "2027-03-28T03:30:00")}]", room, LocalDate(2027, 3, 22)) }
    }

    @Test fun roomRequestsUseOnlyTheFixedSignedInRoutesAndABoundedBookingWeek() = runTest {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method); assertEquals("session=test", req.headers[HttpHeaders.Cookie]); assertEquals("digicampus.uni-augsburg.de", req.url.host)
            paths += req.url.encodedPath
            if (req.url.encodedPath.endsWith("/rooms")) {
                assertEquals("1", req.url.parameters["room_search"]); assertEquals("1002", req.url.parameters["special__room_name"])
                respond(search(), headers = headersOf(HttpHeaders.ContentType, "text/html"))
            } else {
                assertEquals("0,1,2", req.url.parameters["booking_types"])
                assertEquals("0", req.url.parameters["display_requests"]); assertEquals("0", req.url.parameters["display_all_requests"])
                assertEquals("2026-10-18T22:00:00Z", req.url.parameters["start"])
                assertEquals("2026-10-25T23:00:00Z", req.url.parameters["end"])
                respond("[${booking()}]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        try {
            val provider = DigiCampusProvider(client, { "session=test" })
            assertEquals(1, provider.searchCampusRooms("1002").rooms.size)
            assertEquals(1, provider.campusRoomWeek(room, monday).entries.size)
            assertEquals(listOf("/dispatch.php/resources/search/rooms", "/dispatch.php/resources/ajax/get_booking_plan/n1002"), paths)
        } finally { client.close() }
    }

    @Test fun roomRedirectsAndForgedSelectionsCannotLeakTheSession() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example/")) }) { followRedirects = true }
        try {
            val provider = DigiCampusProvider(client, { "session=test" })
            assertEquals(FailureKind.AUTHENTICATION, assertFailsWith<CampusException> { provider.campusRoomWeek(room, monday) }.kind)
            assertEquals(1, calls)
            assertFailsWith<CampusException> { provider.campusRoomWeek(room.copy(bookingUrl = "https://other.example/"), monday) }
            assertFailsWith<CampusException> { provider.campusRoomWeek(room, monday.plus(1, DateTimeUnit.DAY)) }
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun lecturerTeachingWeekReadsAllMatchingPagesBeyondFortyCourses() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { req ->
            calls++; assertEquals("lecturer", req.url.parameters["filter[fields]"]); assertEquals("term", req.url.parameters["filter[semester]"])
            val offset = req.url.parameters["page[offset]"]!!.toInt()
            val rows = (offset until minOf(offset + 20, 65)).joinToString { """{"type":"courses","id":"other-$it","attributes":{"title":"Outside course $it"}}""" }
            respond("""{"data":[$rows],"meta":{"page":{"total":65}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val result = DigiCampusProvider(client, { "session=test" }).lecturerCourses("Professor", "term")
            assertTrue(result.complete); assertEquals(65, result.courses.size); assertEquals(4, calls)
        } finally { client.close() }
    }

    @Test fun broadLecturerQueriesHaveExplicitPartialCoverageAndRepeatingPagesCannotLookComplete() = runTest {
        for (repeated in listOf(false, true)) {
            var calls = 0
            val client = HttpClient(MockEngine { req ->
                calls++; val offset = if (repeated) 0 else req.url.parameters["page[offset]"]!!.toInt()
                val rows = (offset until offset + 20).joinToString { """{"type":"courses","id":"c-$it","attributes":{"title":"Course $it"}}""" }
                respond("""{"data":[$rows],"meta":{"page":{"total":240}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            })
            try {
                val provider = DigiCampusProvider(client, { "session=test" })
                if (repeated) { assertFailsWith<CampusException> { provider.lecturerCourses("Professor", "term") }; assertEquals(2, calls) }
                else { val result = provider.lecturerCourses("Professor", "term"); assertFalse(result.complete); assertEquals(200, result.courses.size); assertEquals(240, result.total); assertEquals(10, calls) }
            } finally { client.close() }
        }
    }

    @Test fun roomCachesRemainReadableOfflineButPermissionRevocationRemovesTheRoomWeek() = runTest {
        var connected = true; var denied = false; var calls = 0; var now = time()
        val source = object : CampusProvider by TestProvider() {
            override suspend fun searchCampusRooms(query: String) = CampusRoomResults(query, listOf(room))
            override suspend fun campusRoomWeek(room: CampusRoom, monday: LocalDate): CampusRoomWeek {
                calls++; if (denied) throw CampusException(FailureKind.PERMISSION)
                return CampusRoomPages.bookings("[${booking()}]", room, monday)
            }
        }
        val store = MemoryStore()
        val repo = PlannerRepository(Account("a", "A", "a"), source, store, backgroundScope, { NetworkConditions(connected = connected) }, { now }, StandardTestDispatcher(testScheduler))
        repo.searchCampusRooms("1002"); repo.roomWeek(room, monday); runCurrent()
        connected = false; now += 6 * 60_000; repo.roomWeek(room, monday); runCurrent()
        val key = campusRoomWeekKey(room.id, monday)
        assertNotNull(repo.snapshot.value.roomWeeks[key]); assertEquals(FailureKind.OFFLINE, repo.sync.value.errors[key]); assertEquals(1, calls)
        connected = true; denied = true; repo.roomWeek(room, monday, true); runCurrent()
        assertNull(repo.snapshot.value.roomWeeks[key]); assertEquals(FailureKind.PERMISSION, repo.sync.value.errors[key])
        assertNotNull(repo.snapshot.value.campusRooms[campusRoomSearchKey("1002")])
        repo.close()
    }

    @Test fun selectedRoomAndSavedGlobalFilterRestoreWithoutChangingEnrollmentOrOtherAccounts() = runTest {
        val store = MemoryStore(); val account = Account("a", "A", "a")
        fun repository(who: Account) = PlannerRepository(who, TestProvider(), store, backgroundScope, { NetworkConditions(false) }, { time() }, StandardTestDispatcher(testScheduler))
        val first = repository(account)
        first.settings { it.copy(campusRoom = room, exploreOnly = true, filter = PlannerFilter(includePlans = false)) }
        first.saveFilter("Campus room", PlannerFilter(includePlans = false)); first.close()
        val restored = repository(account); val other = repository(account.copy(id = "b")); runCurrent()
        assertEquals(room.id, restored.snapshot.value.settings.campusRoom?.id)
        assertEquals(room.id, restored.snapshot.value.savedFilters.single().campusRoom?.id)
        assertTrue(restored.snapshot.value.plans.isEmpty()); assertNull(other.snapshot.value.settings.campusRoom)
        restored.close(); other.close()
    }

    @Test fun authenticatedEnglishCoursePagesPreserveFortnightlyAndThreeWeeklyTeachingTimes() {
        val term = Semester("term", "Wintersemester 2026/27", time(), LocalDate(2027, 4, 1).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds())
        fun html(interval: String) = """<article class="studip"><h1>General information</h1><table><tr><td>Semester</td><td>WS 2026/27</td></tr></table></article>
            <article class="studip"><h1>Lecturers</h1><section><ul><li>Prof. Example</li></ul></section></article>
            <article class="studip"><h1>Rooms and times</h1><section><h4>N · 1002</h4><ul><li>Monday, 10:00 - 11:30, $interval (from 19.10.26)</li></ul></section></article>"""
        for ((label, days) in listOf("Weekly" to 7, "Fortnightly" to 14, "Dreiwöchentlich" to 21, "Three-weekly" to 21, "Triweekly" to 21)) {
            val result = PlannerPublicPages.course(html(label), Course("outside", "Teaching course"), term)
            assertEquals("Prof. Example", result.course.teacher); assertEquals(0, result.unsupported)
            assertEquals(days.toLong(), result.events[1].date.toEpochDays() - result.events[0].date.toEpochDays(), label)
        }
        val unsupported = PlannerPublicPages.course(html("Every four weeks"), Course("outside", "Teaching course"), term)
        assertTrue(unsupported.events.isEmpty()); assertEquals(1, unsupported.unsupported)
    }
}
