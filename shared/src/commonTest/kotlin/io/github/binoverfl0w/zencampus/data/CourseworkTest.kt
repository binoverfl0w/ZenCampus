package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.CourseworkMapper
import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.data.digicampus.text
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
private fun taskResource(renewal: String = "null", renewed: String = "1970-01-01T00:00:00+00:00", submitted: Boolean = false,
    progress: Double = 75.0, canSolve: Boolean = true) = obj("""{"type":"courseware-tasks","id":"task-a","attributes":{
    "submitted":$submitted,"submission-date":"2026-10-15T18:00:00+02:00","progress":$progress,
    "can-solve":$canSolve,"renewal":$renewal,"renewal-date":"$renewed"},"relationships":{
    "task-group":{"data":{"type":"courseware-task-groups","id":"group-a"}},
    "structural-element":{"data":{"type":"courseware-structural-elements","id":"element-a"}}}}""")
private fun group(courseId: String = "course-a") = obj("""{"type":"courseware-task-groups","id":"group-a","attributes":{
    "title":"Graphs &amp; paths","end-date":"2026-10-15T18:00:00+02:00"},"relationships":{
    "course":{"data":{"type":"courses","id":"$courseId"}}}}""")
private val deadline = JsonApiMapper.timestamp("2026-10-15T18:00:00+02:00")

class CourseworkProtocolTest {
    @Test fun readsOnlyFilteredLearnerTasksWithoutPaginationOrMutations() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("session=test", request.headers[HttpHeaders.Cookie])
            assertFalse(request.url.parameters.names().any { it.startsWith("page[") })
            requests += request.url.encodedPath
            if (request.url.encodedPath.endsWith("task-a")) {
                assertEquals("task-group,task-feedback,structural-element.containers.blocks", request.url.parameters["include"])
                respond("""{"data":${taskResource()},"included":[${group()}]}""", headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
            } else {
                assertEquals("course-a", request.url.parameters["filter[cid]"])
                assertEquals("task-group,structural-element", request.url.parameters["include"])
                respond("""{"data":[${taskResource()}],"included":[${group()}]}""", headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
            }
        })
        try {
            val provider = DigiCampusProvider(client, { "session=test" })
            val task = provider.coursework("course-a").single()
            assertEquals(deadline, task.due)
            assertEquals("Graphs & paths", task.title)
            assertFalse(task.submitted)
            assertEquals(75.0, task.progress)
            assertEquals(task, provider.courseworkDetail("course-a", "task-a").task)
            assertEquals(listOf("/jsonapi.php/v1/courseware-tasks", "/jsonapi.php/v1/courseware-tasks/task-a"), requests)
        } finally { client.close() }
    }

    @Test fun taskFeedbackIsShownWithoutPrefetchingAnExercise() = runTest {
        val task = taskResource().toString().replace("\"structural-element\":", "\"task-feedback\":{\"data\":{\"type\":\"courseware-task-feedback\",\"id\":\"f\"}},\"structural-element\":")
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("task-group,task-feedback,structural-element.containers.blocks", request.url.parameters["include"])
            respond("""{"data":$task,"included":[${group()},{"type":"courseware-task-feedback","id":"f","attributes":{"content":"<p>Good work</p><script>bad()</script>"}}]}""", headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try { assertEquals("Good work", DigiCampusProvider(client, { "fixture" }).courseworkDetail("course-a", "task-a").feedback.trim()) }
        finally { client.close() }
    }

    @Test fun onlyAGrantedExtensionChangesTheDeadlineAndProgressIsAlreadyAPercentage() {
        val extended = "2026-10-17T18:00:00+02:00"
        for (status in listOf("pending", "declined", "granted")) {
            val task = CourseworkMapper.task(taskResource("\"$status\"", extended, progress = 100.0), listOf(group()), "course-a")
            assertEquals(if (status == "granted") JsonApiMapper.timestamp(extended) else deadline, task.due)
            assertEquals(deadline, task.originalDue)
            assertEquals(100.0, task.progress)
            assertFalse(task.submitted, "100% content progress must never mean submitted")
        }
        assertTrue(CourseworkMapper.task(taskResource(submitted = true), listOf(group()), "course-a").submitted)
    }

    @Test fun missingOrEpochDeadlinesRemainUnknownAndMalformedDatesFail() {
        val epoch = taskResource().toString().replace("2026-10-15T18:00:00+02:00", "1970-01-01T00:00:00+00:00")
        val zeroGroup = obj(group().toString().replace("2026-10-15T18:00:00+02:00", "1970-01-01T00:00:00+00:00"))
        assertNull(CourseworkMapper.task(obj(epoch), listOf(zeroGroup), "course-a").due)
        assertNull(CourseworkMapper.task(taskResource("\"granted\""), listOf(group()), "course-a").due)
        assertFailsWith<CampusException> { CourseworkMapper.task(obj(epoch.replace("1970-01-01T00:00:00+00:00", "garbage")), listOf(group()), "course-a") }
        assertNull(CourseworkMapper.task(taskResource(progress = 101.0), listOf(group()), "course-a").progress)
    }

    @Test fun rejectsCrossCourseOrIncompleteMetadataAndExcludesTasksTheUserCannotSolve() {
        assertFalse(CourseworkMapper.isSolvable(taskResource(canSolve = false)))
        assertFailsWith<CampusException> { CourseworkMapper.task(taskResource(), listOf(group("other")), "course-a") }
        assertFailsWith<CampusException> { CourseworkMapper.task(taskResource(), emptyList(), "course-a") }
        assertFailsWith<CampusException> { CourseworkMapper.isSolvable(obj("""{"type":"wrong","id":"a"}""")) }
        val unknownStatus = obj(taskResource().toString().replace("\"submitted\":false,", ""))
        assertFailsWith<CampusException> { CourseworkMapper.task(unknownStatus, listOf(group()), "course-a") }
    }

    @Test fun rendersOnlyVisibleKnownTextBlocksAndKeepsInteractiveContentInDigiCampus() {
        val element = obj("""{"type":"courseware-structural-elements","id":"element-a","attributes":{"can-visit":true},
            "relationships":{"containers":{"data":[{"type":"courseware-containers","id":"container-a"}]}}}""")
        val container = obj("""{"type":"courseware-containers","id":"container-a","attributes":{"visible":true},
            "relationships":{"blocks":{"data":[{"type":"courseware-blocks","id":"text"},{"type":"courseware-blocks","id":"hidden"},{"type":"courseware-blocks","id":"quiz"}]}}}""")
        val text = obj("""{"type":"courseware-blocks","id":"text","attributes":{"visible":true,"block-type":"text", "payload":{"text":"<p>Read <a href='/help.php'>instructions</a>.</p><script>secret()</script>"}}}""")
        val hidden = obj("""{"type":"courseware-blocks","id":"hidden","attributes":{"visible":false,"block-type":"text","payload":{"text":"Hidden answer"}}}""")
        val quiz = obj("""{"type":"courseware-blocks","id":"quiz","attributes":{"visible":true,"block-type":"quiz","payload":{"answer":"Not for rendering"}}}""")
        val detail = CourseworkMapper.detail(taskResource(), listOf(group(), element, container, text, hidden, quiz), "course-a")
        assertEquals("Read instructions (https://digicampus.uni-augsburg.de/help.php).", detail.sections.single().text)
        assertTrue(detail.hasInteractiveContent)
        val forbidden = obj(element.toString().replace("\"can-visit\":true", "\"can-visit\":false"))
        assertTrue(CourseworkMapper.detail(taskResource(), listOf(group(), forbidden, container, text), "course-a").sections.isEmpty())
    }

    @Test fun aLaterPageFailureDoesNotReturnAPartialTaskList() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.parameters["next"] != null) respond("""{"errors":[{"status":"403"}]}""", HttpStatusCode.Forbidden)
            else respond("""{"data":[${taskResource()}],"included":[${group()}],"links":{"next":"/jsonapi.php/v1/courseware-tasks?next=1"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try { assertEquals(FailureKind.PERMISSION, assertFailsWith<CampusException> { DigiCampusProvider(client, { "s=t" }).coursework("course-a") }.kind) }
        finally { client.close() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CourseworkRepositoryTest {
    private val account = Account("alice", "Alice Student", "alice")
    private val task = CourseworkTask("task", "c", "Graphs", deadline, false, 100.0)

    @Test fun sharesConcurrentLoadsUsesTheCacheAndRetainsItOfflineAcrossRecreation() = runTest {
        val store = MemoryStore()
        var calls = 0
        var network = NetworkConditions()
        var now = deadline
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun coursework(courseId: String): List<CourseworkTask> { calls++; delay(100); return listOf(task) }
        }
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, conditions = { network }, clock = { now })
        repo.refreshCourses()
        coroutineScope { List(12) { launch { repo.refreshCoursework("c", true) } }.joinAll() }
        assertEquals(1, calls)
        repo.refreshCoursework("c")
        assertEquals(1, calls)
        repo.refreshCoursework("c", true)
        assertEquals(2, calls)
        network = NetworkConditions(connected = false); now += CampusRepository.EVENT_TTL + 1
        repo.refreshCoursework("c")
        runCurrent()
        assertEquals(listOf(task), repo.snapshot.value.coursework["c"])
        assertNull(repo.sync.value.errors["tasks:c"]); assertTrue("tasks:c" in repo.sync.value.stale)
        repo.close(false)
        val restored = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, conditions = { network }, clock = { now })
        runCurrent()
        assertEquals(listOf(task), restored.snapshot.value.coursework["c"])
        assertEquals(2, calls)
        restored.close(false)
    }

    @Test fun permissionRevocationRemovesTasksAndTheirCachedDetailsWhileServerFailureKeepsThem() = runTest {
        var failure: FailureKind? = null
        val store = MemoryStore()
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun coursework(courseId: String): List<CourseworkTask> { failure?.let { throw CampusException(it) }; return listOf(task) }
            override suspend fun courseworkDetail(courseId: String, taskId: String) = CourseworkDetail(task, listOf(CourseworkSection(text = "Private instructions")))
        }
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope)
        repo.refreshCourses(); repo.refreshCoursework("c"); repo.refreshCourseworkDetail("c", "task")
        failure = FailureKind.SERVER; repo.refreshCoursework("c", true); runCurrent()
        assertEquals(listOf(task), repo.snapshot.value.coursework["c"])
        assertNotNull(store.read(account.cacheScope, "task:c:task"))
        failure = FailureKind.PERMISSION; repo.refreshCoursework("c", true); runCurrent()
        assertFalse("c" in repo.snapshot.value.coursework)
        assertNull(store.read(account.cacheScope, "task:c:task"))
        assertTrue(repo.snapshot.value.courseworkDetails.isEmpty())
        repo.close(false)
    }

    @Test fun anExplicitOverviewIsBoundedAndSemesterAndAccountCachesStaySeparate() = runTest {
        val store = MemoryStore()
        val base = TestProvider().apply { offeredCourses = (1..7).map { Course("c$it", "Course $it") } }
        var active = 0; var maximum = 0
        val provider = object : CampusProvider by base {
            override suspend fun coursework(courseId: String): List<CourseworkTask> {
                active++; maximum = maxOf(maximum, active); delay(100); active--
                return listOf(task.copy(id = "t$courseId", courseId = courseId))
            }
        }
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope)
        repo.refreshCourses(); repo.refreshAllCoursework(); runCurrent()
        assertEquals(2, maximum); assertEquals(7, repo.snapshot.value.coursework.size)
        base.offeredCourses = listOf(Course("other", "Other semester"))
        repo.selectSemester("other"); runCurrent()
        assertTrue(repo.snapshot.value.coursework.isEmpty())
        val other = CampusRepository(account.copy(id = "bob"), provider, store, MemoryMaterials(), backgroundScope)
        runCurrent(); assertTrue(other.snapshot.value.coursework.isEmpty())
        repo.close(false); other.close(false)
    }

    @Test fun todayWarmsAtMostThreeCoursesAndDataSaverSuppressesSpeculation() = runTest {
        var calls = 0
        var dataSaver = true
        val base = TestProvider().apply { offeredCourses = (1..8).map { Course("c$it", "Course $it") } }
        val provider = object : CampusProvider by base {
            override suspend fun coursework(courseId: String): List<CourseworkTask> { calls++; return emptyList() }
        }
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, settings = { AppSettings(dataSaver = dataSaver) })
        repo.refreshToday(); assertEquals(0, calls)
        dataSaver = false; repo.refreshToday(); assertEquals(3, calls)
        repo.close(false)
    }

    @Test fun aRevokedCourseCannotBeRepopulatedByAnOlderInFlightDetail() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun coursework(courseId: String): List<CourseworkTask> = throw CampusException(FailureKind.PERMISSION)
            override suspend fun courseworkDetail(courseId: String, taskId: String): CourseworkDetail {
                gate.await(); return CourseworkDetail(task, listOf(CourseworkSection(text = "Old private text")))
            }
        }
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope)
        val detail = launch { repo.refreshCourseworkDetail("c", "task") }; runCurrent()
        repo.refreshCoursework("c", true)
        gate.complete(Unit); detail.join()
        assertNull(store.read(account.cacheScope, "task:c:task"))
        repo.close(false)
    }

    @Test fun aFreshDetailUpdatesTheListStatusWithoutMakingTheWholeListLookFresh() = runTest {
        val store = MemoryStore()
        var now = deadline
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun coursework(courseId: String) = listOf(task)
            override suspend fun courseworkDetail(courseId: String, taskId: String) = CourseworkDetail(task.copy(submitted = true))
        }
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, clock = { now })
        repo.refreshCourses(); repo.refreshCoursework("c"); now += 60_000
        repo.refreshCourseworkDetail("c", "task"); runCurrent()
        assertTrue(repo.snapshot.value.coursework["c"]!!.single().submitted)
        assertEquals(deadline, store.read(account.cacheScope, "tasks:c")!!.updatedAt)
        assertEquals(now, store.read(account.cacheScope, "task:c:task")!!.updatedAt)
        repo.close(false)
    }

    @Test fun closingAnAccountRejectsEvenACancellationIgnoringLateTaskResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun coursework(courseId: String): List<CourseworkTask> { withContext(NonCancellable) { gate.await() }; return listOf(task) }
        }
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope)
        val request = launch { repo.refreshCoursework("c") }; runCurrent()
        val close = launch { repo.close(true) }; runCurrent()
        gate.complete(Unit); close.join(); request.join()
        assertNull(store.read(account.cacheScope, "tasks:c"))
    }
}
