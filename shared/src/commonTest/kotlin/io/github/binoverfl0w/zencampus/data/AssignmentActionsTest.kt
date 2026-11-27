package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.io.IOException
import kotlinx.serialization.json.*

private val actionNow = JsonApiMapper.timestamp("2026-10-15T12:00:00+02:00")
private fun responseTask(submitted: Boolean) = """{"data":{"id":"task","type":"courseware-tasks","attributes":{
 "submitted":$submitted,"submission-date":"2026-10-16T18:00:00+02:00","can-solve":true,"progress":35},
 "relationships":{"task-group":{"data":{"type":"courseware-task-groups","id":"group"}}}},
 "included":[{"type":"courseware-task-groups","id":"group","attributes":{"title":"Graphs"},
 "relationships":{"course":{"data":{"type":"courses","id":"c"}}}}]}"""
private fun folderJson(id: String = "folder", type: String = "HomeworkFolder", writable: Boolean = true, extra: String = "") = """{"type":"folders","id":"$id","attributes":{
 "name":"Assignments","folder-type":"$type","is-readable":true,"is-writable":$writable$extra},
 "relationships":{"range":{"data":{"type":"courses","id":"c"}}}}"""
private fun receiptJson(owner: String = "alice", folder: String = "folder") = """{"data":{"type":"file-refs","id":"receipt","attributes":{
 "name":"work.pdf","filesize":4,"mime-type":"application/pdf"},"relationships":{
 "parent":{"data":{"type":"folders","id":"$folder"}},"owner":{"data":{"type":"users","id":"$owner"}},
 "range":{"data":{"type":"courses","id":"c"}}}}}"""
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")

class AssignmentProtocolTest {
    @Test fun submitsOnceAndConfirmsWithAnAuthoritativeRead() = runTest {
        var writes = 0; var submitted = false
        val client = HttpClient(MockEngine { request ->
            assertEquals("s=t", request.headers[HttpHeaders.Cookie])
            if (request.method == HttpMethod.Patch) {
                writes++; val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["data"]!!.jsonObject
                assertEquals("task", body["id"]!!.jsonPrimitive.content)
                assertEquals(setOf("submitted"), body["attributes"]!!.jsonObject.keys)
                assertEquals(JsonPrimitive(true), body["attributes"]!!.jsonObject["submitted"])
                submitted = true; respond(responseTask(true), headers = jsonHeaders)
            } else respond(responseTask(submitted), headers = jsonHeaders)
        }) { followRedirects = false }
        try {
            val p = DigiCampusProvider(client, { "s=t" }, { actionNow })
            assertTrue(assertIs<WriteResult.Confirmed<CourseworkDetail>>(p.submitCoursework("c", "task")).value.task.submitted)
            p.submitCoursework("c", "task")
            assertEquals(1, writes, "Already-submitted work must not be submitted again")
        } finally { client.close() }
    }

    @Test fun lostSubmissionResponseIsReconciledWithoutRepeatingThePatch() = runTest {
        var writes = 0; var submitted = false
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Patch) { writes++; submitted = true; throw IOException("lost response") }
            respond(responseTask(submitted), headers = jsonHeaders)
        }) { followRedirects = false }
        try {
            assertIs<WriteResult.Confirmed<CourseworkDetail>>(DigiCampusProvider(client, { "s=t" }, { actionNow }).submitCoursework("c", "task"))
            assertEquals(1, writes)
        } finally { client.close() }
    }

    @Test fun aServerErrorWithNoConfirmedSubmissionRemainsUncertainAndIsNeverRetried() = runTest {
        var writes = 0
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Patch) { writes++; respond("", HttpStatusCode.InternalServerError) }
            else respond(responseTask(false), headers = jsonHeaders)
        }) { followRedirects = false }
        try {
            assertEquals(WriteResult.Uncertain, DigiCampusProvider(client, { "s=t" }, { actionNow }).submitCoursework("c", "task"))
            assertEquals(1, writes)
        } finally { client.close() }
    }

    @Test fun overdueAndUnsolvableTasksNeverTriggerAWritingRequest() = runTest {
        for (body in listOf(responseTask(false).replace("2026-10-16", "2026-10-14"), responseTask(false).replace("\"can-solve\":true", "\"can-solve\":false"))) {
            val client = HttpClient(MockEngine { request -> assertEquals(HttpMethod.Get, request.method); respond(body, headers = jsonHeaders) })
            try { assertIs<WriteResult.Rejected>(DigiCampusProvider(client, { "s=t" }, { actionNow }).submitCoursework("c", "task")) }
            finally { client.close() }
        }
    }

    @Test fun uploadsMultipartOnceAndValidatesTheCreatedFileReceipt() = runTest {
        var posts = 0
        val client = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Post -> {
                    posts++
                    assertEquals("/jsonapi.php/v1/folders/folder/file-refs", request.url.encodedPath)
                    val body = request.body.toByteArray().decodeToString()
                    assertTrue(body.contains("filename=\"work.pdf\"")); assertTrue(Regex("name=(\"file\"|file)(;|\\r?\\n)").containsMatchIn(body), body)
                    respond("", HttpStatusCode.Created, headersOf(HttpHeaders.Location, "$DIGICAMPUS_API/file-refs/receipt"))
                }
                request.url.encodedPath.endsWith("/receipt") -> respond(receiptJson(), headers = jsonHeaders)
                else -> respond("""{"data":${folderJson()}}""", headers = jsonHeaders)
            }
        }) { followRedirects = false }
        try {
            val draft = UploadDraft("c", "folder", "work.pdf", "application/pdf", byteArrayOf(1,2,3,4))
            val result = DigiCampusProvider(client, { "s=t" }).uploadAssignment(draft, "alice")
            assertEquals("receipt", assertIs<WriteResult.Confirmed<Document>>(result).value.id)
            assertEquals(1, posts)
        } finally { client.close() }
    }

    @Test fun untrustedLocationsAndMismatchedReceiptsCannotConfirmAnUpload() = runTest {
        for ((location, receipt) in listOf("https://attacker.example/file" to receiptJson(), "$DIGICAMPUS_API/file-refs/receipt" to receiptJson(owner = "other"), "$DIGICAMPUS_API/file-refs/receipt" to receiptJson(folder = "other"))) {
            var posts = 0
            val client = HttpClient(MockEngine { request ->
                assertEquals("digicampus.uni-augsburg.de", request.url.host)
                when {
                    request.method == HttpMethod.Post -> { posts++; respond("", HttpStatusCode.Created, headersOf(HttpHeaders.Location, location)) }
                    request.url.encodedPath.endsWith("/receipt") -> respond(receipt, headers = jsonHeaders)
                    else -> respond("""{"data":${folderJson()}}""", headers = jsonHeaders)
                }
            }) { followRedirects = false }
            try {
                assertEquals(WriteResult.Uncertain, DigiCampusProvider(client, { "s=t" }).uploadAssignment(UploadDraft("c","folder","work.pdf","application/pdf",byteArrayOf(1,2,3,4)), "alice"))
                assertEquals(1, posts)
            } finally { client.close() }
        }
    }

    @Test fun closedOrCrossCourseFoldersRejectBeforeUploadAndServerErrorsDoNotRepeatIt() = runTest {
        for (mode in listOf("closed", "wrong-course", "error")) {
            var posts = 0
            val folder = when(mode) { "closed" -> folderJson(writable = false); "wrong-course" -> folderJson().replace("\"id\":\"c\"", "\"id\":\"other\""); else -> folderJson() }
            val client = HttpClient(MockEngine { request ->
                if (request.method == HttpMethod.Post) { posts++; respond("", HttpStatusCode.InternalServerError) }
                else respond("""{"data":$folder}""", headers = jsonHeaders)
            }) { followRedirects = false }
            try {
                val result = DigiCampusProvider(client, { "s=t" }).uploadAssignment(UploadDraft("c","folder","work.pdf","application/pdf",byteArrayOf(1,2,3,4)), "alice")
                if (mode == "error") { assertEquals(WriteResult.Uncertain, result); assertEquals(1, posts) }
                else { assertIs<WriteResult.Rejected>(result); assertEquals(0, posts) }
            } finally { client.close() }
        }
    }

    @Test fun recognizesHomeworkAndTimedUploadOnlyFoldersWithoutInventingDeadlines() {
        fun parse(body: String) = JsonApiMapper.folder(Json.parseToJsonElement(body).jsonObject, "c", "")
        val homework = parse(folderJson())
        assertTrue(homework.submissionFolder); assertNull(homework.availableUntil)
        assertFalse(parse(folderJson(type = "InboxFolder")).submissionFolder)
        val timed = parse(folderJson(type = "TimedFolder", extra = ",\"data-content\":{\"permission\":3,\"end_time\":1760626800}"))
        assertTrue(timed.submissionFolder); assertEquals(1760626860000L, timed.availableUntil)
        assertFalse(parse(folderJson(type = "TimedFolder", extra = ",\"data-content\":{\"permission\":7}")).submissionFolder)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AssignmentRepositoryTest {
    private val account = Account("alice", "Alice", "alice")
    private val folder = CampusFolder("folder", "c", "Homework", folderType = "HomeworkFolder", writable = true)
    private val task = CourseworkTask("t", "c", "Graphs", actionNow + 60_000, false, canSolve = true)

    @Test fun anUncertainWriteSurvivesRecreationAndBlocksRetriesUntilAcknowledged() = runTest {
        val store = MemoryStore(); var calls = 0
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun uploadAssignment(draft: UploadDraft, ownerId: String): WriteResult<Document> { calls++; delay(100); return WriteResult.Uncertain }
        }
        val draft = UploadDraft("c", "folder", "work.pdf", "application/pdf", byteArrayOf(1))
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope)
        repo.selectUpload(draft)
        coroutineScope { List(6) { launch { repo.confirmUpload() } }.joinAll() }
        assertEquals(1, calls)
        repo.clearCache()
        assertEquals(WriteStatus.UNCERTAIN, Json.decodeFromString<CourseworkWrite>(store.read(account.cacheScope,"write:upload:folder")!!.payload).status)
        repo.close(false)
        val restored = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope)
        restored.selectUpload(draft); restored.confirmUpload(); assertEquals(1, calls)
        restored.acknowledgeWrite("write:upload:folder"); restored.confirmUpload(); assertEquals(2, calls)
        restored.close(false)
    }

    @Test fun aConfirmedSelectionCannotBeUploadedTwiceEvenIfTheUiHasNotCaughtUp() = runTest {
        var calls = 0
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun uploadAssignment(draft: UploadDraft, ownerId: String): WriteResult<Document> {
                calls++; return WriteResult.Confirmed(Document("receipt","c","folder",draft.name,draft.bytes.size.toLong()))
            }
        }
        val repo = CampusRepository(account,provider,MemoryStore(),MemoryMaterials(),backgroundScope)
        repo.selectUpload(UploadDraft("c","folder","work.pdf","application/pdf",byteArrayOf(1)))
        repo.confirmUpload();repo.confirmUpload(); assertEquals(1,calls)
        repo.close(false)
    }

    @Test fun offlineWritesAreNotQueuedAndLocalDeadlinesStayAccountAndSemesterScoped() = runTest {
        val store = MemoryStore(); var calls = 0
        val base = TestProvider()
        val provider = object : CampusProvider by base {
            override suspend fun submitCoursework(courseId: String, taskId: String): WriteResult<CourseworkDetail> { calls++; return WriteResult.Confirmed(CourseworkDetail(task.copy(submitted=true))) }
        }
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, conditions = { NetworkConditions(connected=false) })
        repo.submitTask("c","t"); assertEquals(0,calls)
        repo.setPersonalDeadline("c","folder",actionNow+1000)
        repo.close(false)
        val restored = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope)
        restored.refreshCourses(); runCurrent()
        assertEquals(actionNow+1000, restored.snapshot.value.personalDeadlines["folder"]!!.due)
        base.offeredCourses = listOf(Course("other","Other")); restored.selectSemester("new"); runCurrent()
        assertTrue(restored.snapshot.value.personalDeadlines.isEmpty())
        val bob = CampusRepository(account.copy(id="bob"),provider,store,MemoryMaterials(),backgroundScope); runCurrent()
        assertTrue(bob.snapshot.value.personalDeadlines.isEmpty())
        restored.close(false);bob.close(false)
    }

    @Test fun confirmedSubmissionUpdatesCachedStatusAndStopsItsReminder() = runTest {
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun coursework(courseId: String) = listOf(task)
            override suspend fun assignmentFolders(courseId: String, force: Boolean) = listOf(folder)
            override suspend fun submitCoursework(courseId: String, taskId: String): WriteResult<CourseworkDetail> = WriteResult.Confirmed(CourseworkDetail(task.copy(submitted=true)))
        }
        val repo = CampusRepository(account,provider,MemoryStore(),MemoryMaterials(),backgroundScope,clock={actionNow})
        repo.refreshCourses();repo.refreshCoursework("c"); assertEquals(1,repo.courseworkReminders(actionNow).size)
        repo.submitTask("c","t");runCurrent()
        assertTrue(repo.snapshot.value.coursework["c"]!!.single().submitted)
        assertTrue(repo.courseworkReminders(actionNow).isEmpty())
        repo.close(false)
    }

    @Test fun sourceFailuresRemainIndependentAndPersonalDeadlinesDoNotBecomeRemoteSubmissionState() = runTest {
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun coursework(courseId: String): List<CourseworkTask> = throw CampusException(FailureKind.PERMISSION)
            override suspend fun assignmentFolders(courseId: String, force: Boolean) = listOf(folder)
        }
        val repo = CampusRepository(account,provider,MemoryStore(),MemoryMaterials(),backgroundScope,clock={actionNow})
        repo.refreshCourses();repo.refreshCoursework("c");repo.setPersonalDeadline("c","folder",actionNow+60_000);runCurrent()
        assertFalse("c" in repo.snapshot.value.coursework)
        assertEquals("folder",repo.snapshot.value.assignmentFolders["c"]!!.single().id)
        assertEquals(1,repo.courseworkReminders(actionNow).size)
        repo.completePersonalDeadline("folder",true)
        assertTrue(repo.courseworkReminders(actionNow).isEmpty())
        repo.close(false)
    }

    @Test fun aPersistedInFlightSubmissionNeverAutomaticallyResendsAfterProcessRestoration() = runTest {
        val store = MemoryStore(); var calls = 0
        val record = CourseworkWrite("write:task:t", "c", "t", "Graphs", actionNow)
        store.put(account.cacheScope, record.key, Json.encodeToString(record), actionNow)
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun submitCoursework(courseId: String, taskId: String): WriteResult<CourseworkDetail> { calls++; return WriteResult.Uncertain }
        }
        val repo = CampusRepository(account,provider,store,MemoryMaterials(),backgroundScope)
        repo.submitTask("c","t"); assertEquals(0,calls)
        repo.acknowledgeWrite(record.key); repo.submitTask("c","t"); assertEquals(1,calls)
        repo.close(false)
    }

    @Test fun unableToPersistAnIntentPreventsTheRemoteAction() = runTest {
        var calls = 0
        val store = object : CampusStore by MemoryStore() {
            override suspend fun put(scope: String, key: String, payload: String, updatedAt: Long) { throw IllegalStateException("disk unavailable") }
        }
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun submitCoursework(courseId: String, taskId: String): WriteResult<CourseworkDetail> { calls++; return WriteResult.Uncertain }
        }
        val repo = CampusRepository(account,provider,store,MemoryMaterials(),backgroundScope)
        repo.submitTask("c","t");runCurrent(); assertEquals(0,calls)
        assertEquals(FailureKind.STORAGE, repo.sync.value.errors["write:task:t"])
        repo.close(false)
    }

    @Test fun staleFailedSubmittedCompletedAndPastDueItemsNeverGenerateReminders() {
        val snapshot = CampusSnapshot(account, courses=listOf(Course("c","Course")), coursework=mapOf("c" to listOf(task)), assignmentFolders=mapOf("c" to listOf(folder)),
            personalDeadlines=mapOf("folder" to PersonalDeadline("c","folder",actionNow+60_000)), updatedAt=mapOf("tasks:c" to actionNow,"upload-folders:c" to actionNow))
        assertEquals(2,courseworkReminders(snapshot,emptyMap(),actionNow).size)
        assertTrue(courseworkReminders(snapshot,mapOf("tasks:c" to FailureKind.OFFLINE,"upload-folders:c" to FailureKind.PERMISSION),actionNow).isEmpty())
        assertTrue(courseworkReminders(snapshot.copy(updatedAt=emptyMap()),emptyMap(),actionNow).isEmpty())
        assertTrue(courseworkReminders(snapshot.copy(coursework=mapOf("c" to listOf(task.copy(submitted=true))),personalDeadlines=mapOf("folder" to PersonalDeadline("c","folder",actionNow+60_000,true))),emptyMap(),actionNow).isEmpty())
        assertTrue(courseworkReminders(snapshot,emptyMap(),actionNow+120_000).isEmpty())
    }
}
