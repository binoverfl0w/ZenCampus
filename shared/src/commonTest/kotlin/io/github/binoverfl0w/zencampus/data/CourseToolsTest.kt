package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.CourseToolPages
import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.group
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CourseToolsTest {
    @Test fun courseSubmittedRefreshLoadsReleasedVipsOnlyForTheSelectedCourse() = runTest {
        val requested = mutableListOf<String>()
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun courses(userId: String, semesterId: String?) = listOf(Course(course, "Real-Time Systems"))
            override suspend fun courseTools(courseId: String) = CourseToolPages.tools(tabs(), courseId)
            override suspend fun vipsSheets(courseId: String) = VipsSheets(emptyList())
            override suspend fun vipsResults(courseId: String): List<VipsResult> {
                requested += courseId
                return listOf(VipsResult("42", courseId, "Sheet 3", "$DIGICAMPUS_ORIGIN/dispatch.php/vips/solutions/student_assignment_solutions?assignment_id=42&cid=$courseId", "", "", "7 / 10"))
            }
        }
        val store = MemoryStore(); val account = Account("alice", "Alice", "alice")
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, clock = { 1000 })
        repo.refreshCourses()
        repo.refreshCourseSubmitted("unrelated")
        assertTrue(requested.isEmpty())
        repo.refreshCourseSubmitted(course)
        assertEquals(listOf(course), requested)
        assertNotNull(store.read(account.cacheScope, "vips-results:$course"))
        repo.refreshCourseSubmitted(course, force = true)
        assertEquals(listOf(course, course), requested)
        repo.close(false)
    }
    @Test fun releasedVipsResultsAreReadWithoutOpeningAnAttempt() {
        val html = tabs(selected = "vips") + """<table><caption>Freigegebene Ergebnisse</caption><tbody><tr>
            <td><a href="/dispatch.php/vips/solutions/student_assignment_solutions?assignment_id=42&amp;cid=$course">Sheet 1</a></td>
            <td>01.10.2026, 10:00</td><td>08.10.2026, 10:00</td><td>7</td><td>/</td><td>10</td><td>70 %</td><td></td>
            </tr></tbody></table>"""
        val result = CourseToolPages.vipsResults(html, course).single()
        assertEquals("Sheet 1", result.title); assertEquals("7 / 10", result.points)
        assertEquals(result, CourseToolPages.vipsResults(html.replace("Freigegebene Ergebnisse", "Published results"), course).single())
        assertEquals(emptyList(), CourseToolPages.vipsResults(tabs(selected = "vips") + "<div class='messagebox_info'>There is no finished task collection.</div>", course))
        assertTrue(result.sourceUrl.contains("student_assignment_solutions"))
        assertFailsWith<CampusException> { CourseToolPages.vipsResults(html.replace("assignment_id=42", "assignment_id=no"), course) }
        assertFailsWith<CampusException> { CourseToolPages.vipsResults(tabs(selected = "vips") + "<div>unexpected</div>", course) }
    }
    private val course = "0123456789abcdef0123456789abcdef"
    private fun tabs(extra: String = "", selected: String = "main", cid: String = course) = """
        <ul id="tabs">
        <li id="nav_course_main" class="${if (selected == "main") "current" else ""}"><a href="/dispatch.php/course/overview?cid=$cid"><span class="tab-title">Overview</span></a></li>
        <li id="nav_course_files"><a href="/dispatch.php/course/files?cid=$cid"><span class="tab-title">Files</span></a></li>
        <li id="nav_course_vips" class="${if (selected == "vips") "current" else ""}"><a href="/dispatch.php/vips/sheets?cid=$cid"><span class="tab-title">Exercise sheet</span></a></li>
        $extra</ul>
    """.trimIndent()
    private fun sheetTable(rows: String = sheetRow()) = """
        <table class="default"><caption>Weekly practice</caption><thead><tr>
        ${listOf("title", "start", "end", "type").joinToString("") { "<th><a href='/dispatch.php/vips/sheets?cid=$course&amp;sort=$it'>$it</a></th>" }}
        <th>Status</th><th class="actions">Action</th></tr></thead><tbody>$rows</tbody></table>
    """.trimIndent()
    private fun sheetRow(id: String = "17", title: String = "Sheet 3 · Graphs", end: String = "28.10.2026, 12:00", status: String = "bearbeitet") = """
        <tr><td><a href="/dispatch.php/vips/sheets/show_assignment?assignment_id=$id&amp;cid=$course"><img alt="Worksheet">$title</a></td>
        <td>21.10.2026, 10:00</td><td>$end</td><td>Übungsblatt</td><td>$status</td>
        <td class="actions"><a href="/dispatch.php/vips/sheets/print_assignments?assignment_id=$id">Print</a></td></tr>
    """.trimIndent()

    @Test fun separateRenamedVipsAndFutureToolsAreDiscoveredWithoutInventingFileFolders() {
        val html = tabs("""<li id="nav_course_future"><a href="/plugins.php/future?cid=$course"><span class="tab-title">Reading room</span></a></li>""") +
            """<main><a href="/plugins.php/not-navigation">Ignore course content</a></main>"""
        val tools = CourseToolPages.tools(html, course)
        assertTrue(tools.hasVips)
        assertEquals(listOf("Exercise sheet", "Reading room"), tools.sections.filter { it.additional }.map { it.title })
        assertEquals(vipsSheetsUrl(course), tools.sections.first { it.vips }.sourceUrl)
    }

    @Test fun renamedAndDisabledUnknownTabsRemainVisibleWithAnOfficialCourseFallback() {
        val html = tabs("""<li id="nav_course_custom"><span class="quiet tab-title">Special exercises</span></li>
            <li id="nav_course_remote"><a href="https://other.example/secret"><span class="tab-title">External exercises</span></a></li>
            <li hidden id="nav_course_hidden"><a href="/plugins.php/hidden?cid=$course">Hidden</a></li>""")
        val tools = CourseToolPages.tools(html, course)
        assertEquals("", tools.sections.single { it.id == "custom" }.sourceUrl)
        assertEquals("", tools.sections.single { it.id == "remote" }.sourceUrl)
        assertFalse(tools.sections.any { it.id == "hidden" })
        assertTrue(CourseToolPages.tools(html.replace("Exercise sheet", "Practice &amp; quizzes")
            .replace("nav_course_vips", "nav_course_work.practice"), course).hasVips)
    }

    @Test fun navigationRejectsForeignOrAmbiguousContextsAndIgnoresBaseTags() {
        listOf("https://other.example/dispatch.php/course/files", "//other.example/dispatch.php/course/files",
            "javascript:alert(1)", "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets?cid=other",
            "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets?cid=$course&cid=other",
            "https://student:password@digicampus.uni-augsburg.de/dispatch.php/vips/sheets",
            "$DIGICAMPUS_ORIGIN/index.php?logout=yes").forEach { assertNull(CourseToolPages.navigationUrl(it, course), it) }
        assertEquals(vipsSheetsUrl(course), CourseToolPages.tools("<base href='https://other.example/'>" + tabs(), course)
            .sections.first { it.vips }.sourceUrl)
    }

    @Test fun wrongCourseLoginAndChangedLayoutsNeverBecomeAnEmptySuccessfulIndex() {
        assertEquals(FailureKind.INVALID_RESPONSE, assertFailsWith<CampusException> { CourseToolPages.tools(tabs(cid = "other"), course) }.kind)
        assertEquals(FailureKind.INVALID_RESPONSE, assertFailsWith<CampusException> { CourseToolPages.tools("<ul id='tabs'></ul>", course) }.kind)
        assertEquals(FailureKind.AUTHENTICATION, assertFailsWith<CampusException> { CourseToolPages.tools("<form><input type='password'></form>", course) }.kind)
    }

    @Test fun readsPublishedStudentSheetMetadataWithoutInferringSubmissionOrFollowingActions() {
        val sheets = CourseToolPages.vips(tabs(selected = "vips") + sheetTable(sheetRow() + sheetRow("18", "Untimed practice", "", "–")), course).sheets
        assertEquals(listOf("17", "18"), sheets.map { it.id })
        assertEquals("28.10.2026, 12:00", sheets.first().ends)
        assertEquals("bearbeitet", sheets.first().status)
        assertEquals(VipsKind.PRACTICE, sheets.first().kind)
        assertEquals(JsonApiMapper.timestamp("2026-10-28T12:00:00+01:00"), sheets.first().deadline)
        assertEquals("", sheets.last().ends)
        assertEquals("Weekly practice", sheets.first().group)
        assertEquals("$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=17&cid=$course", sheets.first().sourceUrl)
    }

    @Test fun officialTypeIconsIdentifyWindowsEvenWhenTheDisplayLabelIsUnfamiliar() {
        for ((icon, kind) in listOf("practice" to VipsKind.PRACTICE, "quiz" to VipsKind.SELFTEST, "exam" to VipsKind.EXAM)) {
            val row = sheetRow().replace("<img alt=\"Worksheet\">", "<img src='/assets/images/icons/blue/assignment-$icon.svg?x=1' title='Mode'>")
                .replace("Übungsblatt", "Custom translation")
            val sheet = CourseToolPages.vips(tabs(selected = "vips") + sheetTable(row), course).sheets.single()
            assertEquals(kind, sheet.kind)
            assertNotNull(sheet.endsAt)
            assertEquals(kind == VipsKind.PRACTICE, sheet.deadline != null)
        }
    }

    @Test fun changedDateFormatsKeepThePublishedTextButCannotEnterDueDateAggregation() {
        val sheet = CourseToolPages.vips(tabs(selected = "vips") + sheetTable(sheetRow(end = "Next Tuesday")), course).sheets.single()
        assertEquals("Next Tuesday", sheet.ends)
        assertFalse(sheet.datesVerified)
        assertNull(sheet.deadline)
    }

    @Test fun onlyRecognizedStudentEmptyMessagesProduceAnEmptySheetList() {
        listOf("Es gibt aktuell keine laufenden Aufgabenblätter.", "There are currently no active worksheets.").forEach {
            assertTrue(CourseToolPages.vips(tabs(selected = "vips") + "<div class='messagebox_info'>$it</div>", course).sheets.isEmpty())
        }
        listOf("", "<div class='messagebox_info'>An error occurred.</div>", sheetTable(""), sheetTable().replace("sort=end", "sort=unknown")).forEach {
            assertFailsWith<CampusException> { CourseToolPages.vips(tabs(selected = "vips") + it, course) }
        }
    }

    @Test fun malformedRowsDuplicateSheetsAndForeignAssignmentLinksRejectTheWholeSnapshot() {
        listOf(sheetTable(sheetRow() + sheetRow()), sheetTable().replace("assignment_id=17", "assignment_id=oops"),
            sheetTable().replace("<td>bearbeitet</td>", ""), sheetTable().replace("show_assignment?assignment_id=17&amp;cid=$course", "show_assignment?assignment_id=17&amp;cid=other")).forEach {
            assertFailsWith<CampusException> { CourseToolPages.vips(tabs(selected = "vips") + it, course) }
        }
    }

    @Test fun providerOnlyReadsFixedOverviewAndStudentListNeverStartsOrPreviewsAssignments() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method)
            assertEquals("session=test", req.headers[HttpHeaders.Cookie])
            assertEquals(course, req.url.parameters["cid"])
            requests += req.url.encodedPath
            val html = when (req.url.encodedPath) {
                "/dispatch.php/course/overview" -> tabs()
                "/dispatch.php/vips/sheets" -> tabs(selected = "vips") + sheetTable()
                else -> error("Must not enter an assignment: ${req.url}")
            }
            respond(html, headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))
        })
        try {
            val provider = DigiCampusProvider(client, { "session=test" })
            assertTrue(provider.courseTools(course).hasVips)
            assertEquals("Sheet 3 · Graphs", provider.vipsSheets(course).sheets.single().title)
            assertEquals(listOf("/dispatch.php/course/overview", "/dispatch.php/vips/sheets"), requests)
        } finally { client.close() }
    }

    @Test fun authenticatedHtmlNeverFollowsSignInRedirectsOrAcceptsNetworkRestrictionPages() = runTest {
        for (status in listOf(HttpStatusCode.Found, HttpStatusCode.Forbidden)) {
            var calls = 0
            val client = HttpClient(MockEngine {
                calls++
                respond("<h1>University network required</h1>", status,
                    headersOf(HttpHeaders.Location to listOf("https://other.example/login"), HttpHeaders.ContentType to listOf("text/html")))
            })
            try {
                assertEquals(if (status == HttpStatusCode.Found) FailureKind.AUTHENTICATION else FailureKind.PERMISSION,
                    assertFailsWith<CampusException> { DigiCampusProvider(client, { "session=test" }).courseTools(course) }.kind)
                assertEquals(1, calls)
            } finally { client.close() }
        }
        val restricted = HttpClient(MockEngine {
            respond("<h1>Zugriff nur aus dem Universitätsnetz</h1>", HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        try {
            assertEquals(FailureKind.OFFLINE, assertFailsWith<CampusException> {
                DigiCampusProvider(restricted, { "session=test" }).courseTools(course)
            }.kind)
        } finally { restricted.close() }
    }

    @Test fun independentCourseToolsAndVipsRestoreOfflineAndShareFreshReads() = runTest {
        var toolCalls = 0; var vipsCalls = 0; var network = NetworkConditions()
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun courseTools(courseId: String): CourseTools { toolCalls++; delay(100); return CourseToolPages.tools(tabs(), course) }
            override suspend fun vipsSheets(courseId: String): VipsSheets { vipsCalls++; delay(100); return CourseToolPages.vips(tabs(selected = "vips") + sheetTable(), course) }
        }
        val store = MemoryStore(); val account = Account("alice", "Alice", "alice")
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, conditions = { network }, clock = { 1000 })
        coroutineScope { List(10) { launch { repo.refreshCourseTools(course) } }.joinAll() }
        assertEquals(1, toolCalls); assertEquals(1, vipsCalls)
        repo.close(false)
        network = NetworkConditions(connected = false)
        val restored = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, conditions = { network }, clock = { 1000 })
        restored.refreshCourseTools(course)
        assertNotNull(store.read(account.cacheScope, "tools:$course"))
        assertNotNull(store.read(account.cacheScope, "vips:$course"))
        assertEquals(1, toolCalls); assertEquals(1, vipsCalls)
        assertNull(store.read(Account("bob", "Bob", "bob").cacheScope, "vips:$course"))
        restored.close(false)
    }

    @Test fun malformedVipsRefreshKeepsSavedSheetsButRevokedToolAccessPurgesThem() = runTest {
        var failure: FailureKind? = null; var malformed = false
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun courseTools(courseId: String): CourseTools {
                failure?.let { throw CampusException(it) }; return CourseToolPages.tools(tabs(), course)
            }
            override suspend fun vipsSheets(courseId: String): VipsSheets = CourseToolPages.vips(tabs(selected = "vips") + if (malformed) "" else sheetTable(), course)
        }
        val account = Account("alice", "Alice", "alice"); val store = MemoryStore()
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, clock = { 1000 })
        repo.refreshCourseTools(course)
        val saved = store.read(account.cacheScope, "vips:$course")
        malformed = true; repo.refreshCourseTools(course, true)
        assertEquals(saved, store.read(account.cacheScope, "vips:$course"))
        runCurrent(); assertEquals(FailureKind.INVALID_RESPONSE, repo.sync.value.errors["vips:$course"])
        failure = FailureKind.PERMISSION; repo.refreshCourseTools(course, true)
        assertNull(store.read(account.cacheScope, "vips:$course")); assertNull(store.read(account.cacheScope, "tools:$course"))
        repo.close(false)
    }

    @Test fun disablingVipsInvalidatesAnOlderInFlightSheetResponse() = runTest {
        var enabled = true; val gate = CompletableDeferred<Unit>()
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun courseTools(courseId: String) = if (enabled) CourseToolPages.tools(tabs(), course) else CourseTools(emptyList())
            override suspend fun vipsSheets(courseId: String): VipsSheets { gate.await(); return CourseToolPages.vips(tabs(selected = "vips") + sheetTable(), course) }
        }
        val account = Account("alice", "Alice", "alice"); val store = MemoryStore()
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, clock = { 1000 })
        val earlier = launch { repo.refreshCourseTools(course) }; runCurrent()
        enabled = false; repo.refreshCourseTools(course, true)
        gate.complete(Unit); earlier.join()
        assertNull(store.read(account.cacheScope, "vips:$course"))
        repo.close(false)
    }
}
