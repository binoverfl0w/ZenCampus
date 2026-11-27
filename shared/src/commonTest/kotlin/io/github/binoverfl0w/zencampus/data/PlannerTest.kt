@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.digicampus.PlannerPublicPages
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.data.study.PlannerRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.datetime.*

private val plannerDay = LocalDate(2026, 10, 15)
private fun stamp(day: LocalDate = plannerDay, hour: Int = 10, minute: Int = 0) = day.atTime(hour, minute).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
private val term = Semester("s", "Wintersemester 2026/27", stamp(LocalDate(2026, 10, 1), 0), stamp(LocalDate(2027, 4, 1), 0))
private val offered = Course("other", "Algorithms", teacher = "Prof. Example", semesterId = "s")
private fun entry(id: String, hour: Int, minute: Int, length: Int, day: LocalDate = plannerDay, cancelled: Boolean = false, allDay: Boolean = false) =
    PlannerEntry(CampusEvent(id, id, stamp(day, hour, minute), stamp(day, hour, minute) + length * 60_000L, "c", "N · 1002", cancelled = cancelled, allDay = allDay), "Dr. Example")
private fun publicCourse(times: String = "Donnerstag, 10:00 - 11:30, Wöchentlich (ab dem 15.10.26)", semester: String = "WS 2026/27") = """
<article class="studip"><h1>Allgemeine Informationen</h1><table><tr><td>Semester</td><td>$semester</td></tr></table></article>
<article class="studip"><h1>Lehrende</h1><section><ul><li>Prof. Example</li></ul></section></article>
<article class="studip"><h1>Räume und Zeiten</h1><section><h4>N 1002</h4><ul><li>$times</li></ul></section></article>"""
private fun roomPage(date: String = "15.10.2026", start: String = "10:00", end: String = "11:30") = """
<h2>Belegungsplan für die nächsten 14 Tage</h2><center><h3>Raum 1113</h3></center><table>
<tr><td>Datum</td><td>Uhrzeit</td><td>Veranstaltung</td><td>Dozent</td></tr>
<tr><td>$date</td><td></td><td>$start -</td><td>$end</td><td></td><td>V</td><td></td><td>Economics</td><td></td><td>Dr. Example</td></tr></table>
<center><h3>Raum 1114</h3></center><table><tr><td>Datum</td><td>Uhrzeit</td><td>Veranstaltung</td><td>Dozent</td></tr></table>"""

class PlannerMathTest {
    @Test fun clashesUseInstantOverlapAndExcludeCancelledAllDayAndTouchingEdges() {
        val a = entry("a", 10, 0, 60); val b = entry("b", 10, 30, 60)
        val c = entry("c", 11, 30, 60)
        val result = plannerClashes(listOf(a, b, c, entry("cancel", 10, 0, 180, cancelled = true), entry("all", 0, 0, 1440, allDay = true)), stamp(hour = 8), stamp(hour = 20))
        assertEquals(1, result.size); assertEquals(stamp(hour = 10, minute = 30), result.single().from)
        assertEquals(stamp(hour = 11), result.single().until)
    }
    @Test fun gapsMergeOverlapsAndClipToWorkingHours() {
        val gaps = plannerGaps(listOf(entry("early", 7, 0, 90), entry("a", 9, 0, 120), entry("b", 10, 0, 120), entry("last", 19, 0, 120)), plannerDay, 8, 20, 30)
        assertEquals(listOf(PlannerGap(stamp(hour = 8, minute = 30), stamp(hour = 9)), PlannerGap(stamp(hour = 12), stamp(hour = 19))), gaps)
    }
    @Test fun allDayCommitmentsBlockGapsAndMissingEventsAreNotReservations() {
        assertTrue(plannerGaps(listOf(entry("all", 0, 0, 1440, allDay = true)), plannerDay).isEmpty())
        assertEquals(1, plannerGaps(emptyList(), plannerDay).size)
    }
    @Test fun crossMidnightGridEntriesClipToEachDay() {
        val night = entry("night", 23, 0, 120)
        assertEquals(1440, plannerGridEvents(listOf(night), plannerDay).single().endMinute)
        val next = plannerGridEvents(listOf(night), plannerDay.plus(1, DateTimeUnit.DAY)).single()
        assertEquals(0, next.startMinute); assertEquals(60, next.endMinute)
    }
    @Test fun transitiveOverlapClustersShareTwoLanesAndThenReset() {
        val layout = plannerGridEvents(listOf(entry("a", 9, 0, 60), entry("b", 9, 30, 90), entry("c", 10, 30, 90), entry("d", 12, 0, 60)), plannerDay)
        assertEquals(listOf(2, 2, 2, 1), layout.map { it.columns }); assertEquals(listOf(0, 1, 0, 0), layout.map { it.column })
    }
    @Test fun groupsStayStableAcrossWeeksAndChangingTopics() {
        val first = entry("a", 10, 0, 60).event.copy(description = "Trees")
        val second = first.copy(start = stamp(plannerDay.plus(7, DateTimeUnit.DAY)), end = stamp(plannerDay.plus(7, DateTimeUnit.DAY), 11), description = "Graphs")
        assertEquals(plannerGroup(first), plannerGroup(second))
    }
    @Test fun selectedSemesterPlansAndSavedCopiesRemainSeparateFromOfficialEvents() {
        val a = entry("a", 10, 0, 60).event
        val schedule = PlannerSchedule(offered, "s", listOf(a.copy(courseId = "other")))
        val plan = PlannedCourse(offered, "s", setOf(plannerGroup(a)), savedSchedule = schedule)
        val snapshot = CampusSnapshot(Account("a", "A", "a"), events = listOf(a))
        val state = PlannerSnapshot(plans = listOf(plan))
        assertEquals(2, plannerEntries(snapshot, state, "s").size)
        assertEquals(1, plannerEntries(snapshot, state, "different").size)
        assertTrue(plannerEntries(snapshot, state, "s").last().tentative)
        assertEquals(1, plannerEntries(snapshot.copy(events = listOf(a.copy(courseId = "other"))), state, "s").size)
    }
    @Test fun filtersAreCaseInsensitiveAndDoNotTurnMissingTeacherIntoMatches() {
        val a = entry("a", 10, 0, 60)
        assertEquals(listOf(a), filterPlanner(listOf(a), PlannerFilter(room = "1002", lecturer = "example", query = "A")))
        assertTrue(filterPlanner(listOf(a.copy(teacher = "")), PlannerFilter(lecturer = "Example")).isEmpty())
        assertNotEquals(PlannerSearch("a/b", PlannerSearchKind.COURSE, "s").key, PlannerSearch("a?b", PlannerSearchKind.COURSE, "s").key)
    }
}

class PlannerPublicPagesTest {
    @Test fun publishedRecurringCourseTimesAreBoundedAndExplicitlySummaries() {
        val result = PlannerPublicPages.course(publicCourse(), offered, term)
        assertTrue(result.summaryOnly); assertEquals("Prof. Example", result.course.teacher)
        assertTrue(result.events.size > 10); assertTrue(result.events.all { it.start >= term.start && it.end <= term.end })
        assertEquals(stamp(), result.events.first().start)
    }
    @Test fun mismatchedSemesterAndUnrecognisedTimesAreNeverInvented() {
        assertFailsWith<CampusException> { PlannerPublicPages.course(publicCourse(semester = "SS 2024"), offered, term) }
        val result = PlannerPublicPages.course(publicCourse("Nach Vereinbarung"), offered, term)
        assertEquals(1, result.unsupported); assertTrue(result.events.isEmpty())
    }
    @Test fun oneOffPublishedEventsAreNotRepeatedAndInvalidDatesAreRejected() {
        assertEquals(1, PlannerPublicPages.course(publicCourse("Donnerstag, 15.10.26, 10:00 - 11:30 Uhr"), offered, term).events.size)
        assertTrue(PlannerPublicPages.course(publicCourse("Donnerstag, 31.02.26, 10:00 - 11:30 Uhr"), offered, term).events.isEmpty())
    }
    @Test fun roomIdsAreNamespacedAndEmptyTablesStillHaveCoverage() {
        val result = PlannerPublicPages.rooms(roomPage(), stamp())
        assertEquals(listOf("wiwi:1113", "wiwi:1114"), result.rooms.map { it.id })
        assertEquals("WiWi · 1113", result.entries.single().event.location)
        assertEquals("Dr. Example", result.entries.single().teacher)
        assertEquals(plannerDay.plus(14, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds(), result.until)
    }
    @Test fun malformedRoomRowOrMissingSourceHeadingRejectsTheWholeSnapshot() {
        assertFailsWith<CampusException> { PlannerPublicPages.rooms(roomPage(start = "oops"), stamp()) }
        assertFailsWith<CampusException> { PlannerPublicPages.rooms(roomPage().replace("Belegungsplan für die nächsten 14 Tage", "Sign in"), stamp()) }
    }
    @Test fun bookingsOutsideTheConservativePublishedWindowAreNotAvailabilityEvidence() {
        assertTrue(PlannerPublicPages.rooms(roomPage("29.10.2026"), stamp()).entries.isEmpty())
    }
}

class PlannerProtocolTest {
    @Test fun lecturerSearchUsesSemesterAndOneBoundedPageAndNeverMutates() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++; assertEquals(HttpMethod.Get, request.method); assertEquals("secret", request.headers[HttpHeaders.Cookie])
            assertEquals("lecturer", request.url.parameters["filter[fields]"]); assertEquals("s", request.url.parameters["filter[semester]"])
            assertEquals("20", request.url.parameters["page[limit]"]); assertEquals("20", request.url.parameters["page[offset]"])
            respond("""{"data":[{"type":"courses","id":"c","attributes":{"title":"Algorithms"}}],"meta":{"page":{"total":50}},"links":{"next":"$DIGICAMPUS_API/courses?page[offset]=40"}}""", headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val result = DigiCampusProvider(client, { "secret" }).searchPlannerCourses(PlannerSearch("  Example  ", PlannerSearchKind.LECTURER, "s", 20))
            assertTrue(result.hasMore); assertEquals(50, result.total); assertEquals(1, calls)
        } finally { client.close() }
    }
    @Test fun aForeignPaginationLinkIsRejectedBeforeAnyCredentialedFollowup() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("""{"data":[],"links":{"next":"https://example.com/courses"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            assertFailsWith<CampusException> { DigiCampusProvider(client, { "secret" }).searchPlannerCourses(PlannerSearch("Example", PlannerSearchKind.COURSE, "s")) }
            assertEquals(1, calls)
        } finally { client.close() }
    }
    @Test fun deniedDatedEventsUseTheSameOriginAuthenticatedPublishedCourseSummary() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            if (request.url.encodedPath.endsWith("/events")) { assertEquals("secret", request.headers[HttpHeaders.Cookie]); respond("", HttpStatusCode.Forbidden) }
            else { assertEquals("/dispatch.php/course/details/", request.url.encodedPath); assertEquals("secret", request.headers[HttpHeaders.Cookie]); respond(publicCourse(), headers = headersOf(HttpHeaders.ContentType, "text/html")) }
        }) { followRedirects = false }
        try { assertTrue(DigiCampusProvider(client, { "secret" }).plannerSchedule(offered, term).summaryOnly) } finally { client.close() }
    }
    @Test fun publishedRoomReadsNeverCarryUniversityCredentialsOrAuthorization() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("itwiwi-scripts.wiwi.uni-augsburg.de", request.url.host)
            assertNull(request.headers[HttpHeaders.Cookie]); assertNull(request.headers[HttpHeaders.Authorization])
            respond(roomPage(), headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        try { assertEquals(2, DigiCampusProvider(client, { "secret" }, { stamp() }).publishedRooms().rooms.size) } finally { client.close() }
    }
}

class PlannerRepositoryTest {
    private val account = Account("a", "Alice", "alice")
    @Test fun searchesDeduplicateCacheAndKeepStaleDataOnNetworkFailures() = runTest {
        var calls = 0; var now = stamp(); var fail = false
        val source = object : CampusProvider by TestProvider() {
            override suspend fun searchPlannerCourses(request: PlannerSearch): PlannerCoursePage {
                calls++; delay(100); if (fail) throw CampusException(FailureKind.SERVER)
                return PlannerCoursePage(request, listOf(offered), false)
            }
        }
        val repository = PlannerRepository(account, source, MemoryStore(), backgroundScope, { NetworkConditions() }, { now }, StandardTestDispatcher(testScheduler))
        val request = PlannerSearch("Example", PlannerSearchKind.COURSE, "s")
        coroutineScope { listOf(launch { repository.search(request) }, launch { repository.search(request) }).joinAll() }
        repository.search(request); runCurrent(); assertEquals(1, calls)
        fail = true; now += 31 * 60_000; repository.search(request); runCurrent()
        assertEquals(offered, repository.snapshot.value.pages[request.key]!!.courses.single())
        assertEquals(FailureKind.SERVER, repository.sync.value.errors[request.key])
    }
    @Test fun accountLocalPlansFiltersAndSettingsSurviveRepositoryRecreationOffline() = runTest {
        val store = MemoryStore()
        val first = PlannerRepository(account, TestProvider(), store, backgroundScope, { NetworkConditions() }, { stamp() }, StandardTestDispatcher(testScheduler))
        first.savePlan(offered, "s", setOf("group")); first.saveFilter("Quiet room", PlannerFilter(room = "1002")); first.settings { it.copy(view = "week", zoom = 1.75f, publishedRooms = true) }; first.saveFilter("Published room", PlannerFilter(room = "WiWi · 1113"))
        first.close()
        val restored = PlannerRepository(account, TestProvider(), store, backgroundScope, { NetworkConditions(false) }, { stamp() }, StandardTestDispatcher(testScheduler))
        runCurrent(); assertEquals(1, restored.snapshot.value.plans.size); assertTrue(restored.snapshot.value.savedFilters.any { it.name == "Quiet room" }); assertTrue(restored.snapshot.value.savedFilters.single { it.name == "Published room" }.publishedRooms)
        assertEquals(1.75f, restored.snapshot.value.settings.zoom)
        val other = PlannerRepository(account.copy(id = "bob"), TestProvider(), store, backgroundScope, { NetworkConditions() }, { stamp() }, StandardTestDispatcher(testScheduler))
        runCurrent(); assertTrue(other.snapshot.value.plans.isEmpty())
    }
    @Test fun plansKeepTheirSelectedTimesWhenDisposableReadCachesAreCleared() = runTest {
        val store = MemoryStore()
        val source = object : CampusProvider by TestProvider() { override suspend fun plannerSchedule(course: Course, semester: Semester) = PlannerSchedule(course, semester.id, listOf(entry("a", 10, 0, 60).event.copy(courseId = course.id))) }
        val repository = PlannerRepository(account, source, store, backgroundScope, { NetworkConditions() }, { stamp() }, StandardTestDispatcher(testScheduler))
        repository.inspect(offered, term); repository.savePlan(offered, "s", setOf(plannerGroup(entry("a", 10, 0, 60).event)))
        store.remove(account.cacheScope, plannerScheduleKey("other", "s")); runCurrent()
        assertEquals(1, plannerEntries(CampusSnapshot(account), repository.snapshot.value, "s").size)
    }
    @Test fun aClosedAccountRejectsLateResultsEvenWhenTheProviderIgnoresCancellation() = runTest {
        val store = MemoryStore(); val gate = CompletableDeferred<Unit>()
        val source = object : CampusProvider by TestProvider() { override suspend fun searchPlannerCourses(request: PlannerSearch): PlannerCoursePage {
            withContext(NonCancellable) { gate.await() }; return PlannerCoursePage(request, listOf(offered), false)
        } }
        val repository = PlannerRepository(account, source, store, backgroundScope, { NetworkConditions() }, { stamp() }, StandardTestDispatcher(testScheduler))
        val request = PlannerSearch("Example", PlannerSearchKind.COURSE, "s")
        val job = launch { repository.search(request) }; runCurrent(); repository.close(); store.purge(account.cacheScope); gate.complete(Unit); job.join()
        assertNull(store.read(account.cacheScope, request.key))
    }
    @Test fun offlineReadsDoNotQueueNetworkWorkAndPermissionLossPurgesAReadSnapshot() = runTest {
        var online = true; var denied = false; var calls = 0
        val store = MemoryStore()
        val source = object : CampusProvider by TestProvider() { override suspend fun searchPlannerCourses(request: PlannerSearch): PlannerCoursePage {
            calls++; if (denied) throw CampusException(FailureKind.PERMISSION); return PlannerCoursePage(request, listOf(offered), false)
        } }
        val repository = PlannerRepository(account, source, store, backgroundScope, { NetworkConditions(connected = online) }, { stamp() }, StandardTestDispatcher(testScheduler))
        val request = PlannerSearch("Example", PlannerSearchKind.COURSE, "s")
        repository.search(request); online = false; repository.search(request, true); assertEquals(1, calls)
        online = true; denied = true; repository.search(request, true); assertNull(store.read(account.cacheScope, request.key))
    }
    @Test fun adjacentWeekPrefetchHonoursDataSaverAndReusesTheExistingTwoWeekWindow() = runTest {
        val provider = TestProvider(); val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope,
            settings = { AppSettings(dataSaver = true) }, clock = { stamp() })
        repo.preparePlannerWeek(plannerDay); val calls = provider.personalCalls
        repo.preparePlannerWeek(plannerDay.plus(7, DateTimeUnit.DAY))
        assertEquals(calls, provider.personalCalls); assertEquals(1, calls)
        repo.close(false)
    }
    @Test fun anActiveLecturerPreviewKeepsAllFortyLoadedCoursesAcrossCacheTrimming() = runTest {
        val store = MemoryStore()
        val source = object : CampusProvider by TestProvider() {
            override suspend fun plannerSchedule(course: Course, semester: Semester) = PlannerSchedule(course, semester.id, emptyList())
        }
        val repository = PlannerRepository(account, source, store, backgroundScope, { NetworkConditions() }, { stamp() }, StandardTestDispatcher(testScheduler))
        val courses = (1..40).map { offered.copy(id = "course-$it") }
        repository.settings { it.copy(overlays = courses.map { course -> PlannerOverlay(course, term.id) }) }
        courses.forEach { repository.inspect(it, term) }; runCurrent()
        assertEquals(40, repository.snapshot.value.schedules.size)
        repository.settings { it.copy(overlays = emptyList()) }
        repository.inspect(offered, term); runCurrent()
        assertEquals(30, repository.snapshot.value.schedules.size)
    }
}
