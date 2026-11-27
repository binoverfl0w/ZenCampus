package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VipsReaderTest {
    private val course = "course"
    private val link = "$DIGICAMPUS_ORIGIN/sendfile.php?type=0&file_id=attachment&file_name=Exercise%20sheet.pdf&cid=$course"
    private val account = Account("reader", "Student", "reader")
    private val doc = Document("attachment", course, "exercise-folder", "Exercise sheet.pdf", mimeType = "application/pdf", modified = 10)
    private fun metadata(id: String = "attachment", allowed: Boolean = true) = """{"data":{"type":"file-refs","id":"$id",
        "attributes":{"name":"Übung 3.pdf","filesize":4,"mime-type":"application/pdf","chdate":10,"is-downloadable":$allowed},
        "relationships":{"parent":{"data":{"type":"folders","id":"exercise-folder"}}}}}"""

    @Test fun onlySheetOverviewAndIndexCanBeEntryPoints() {
        assertEquals(vipsSheetsUrl(course), VipsLinks.entry(vipsSheetsUrl(course), course))
        assertEquals(vipsResultsUrl(course), VipsLinks.entry(vipsResultsUrl(course), course))
        val result = "$DIGICAMPUS_ORIGIN/dispatch.php/vips/solutions/student_assignment_solutions?assignment_id=42&cid=$course"
        assertEquals(result, VipsLinks.entry(result, course))
        assertNull(VipsLinks.entry(result.replace("assignment_id=42", "assignment_id=x"), course))
        val entry = "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=17&cid=$course"
        assertEquals(entry, VipsLinks.entry("$entry&solver_id=other&access_code=secret", course))
        listOf("show_exercise", "print_assignments", "begin_assignment", "submit_exercise").forEach {
            assertNull(VipsLinks.entry(entry.replace("show_assignment", it), course), it)
        }
        assertNull(VipsLinks.entry("$entry&cid=other", course))
        assertNull(VipsLinks.entry(entry.replace("cid=course", "cid=other"), course))
        assertTrue(VipsLinks.page(entry.replace("show_assignment", "show_exercise") + "#question", course))
        assertFalse(VipsLinks.page(entry.replace("cid=course", "cid=other"), course))
    }

    @Test fun bothStudipDownloadModesPreserveReadableUnicodeNames() {
        assertEquals("Exercise sheet.pdf", VipsLinks.attachment(link, course)?.name)
        val rewrite = "$DIGICAMPUS_ORIGIN/download/normal/0/attachment/%C3%9Cbung+3.pdf"
        assertEquals("Übung 3.pdf", VipsLinks.attachment(rewrite, course)?.name)
        assertEquals("attachment", VipsLinks.attachment(rewrite.replace("normal", "force_download"), course)?.id)
        assertEquals("attachment", VipsLinks.attachment("$DIGICAMPUS_API/file-refs/attachment/content", course)?.id)
        assertEquals("Attachment", VipsLinks.attachment("$DIGICAMPUS_ORIGIN/sendfile.php?file_id=attachment", course)?.name)
    }

    @Test fun downloadsRejectUntrustedOriginsAmbiguousIdsOtherCoursesAndNonFileActions() {
        listOf(link.replace("https:", "http:"), link.replace("digicampus.uni-augsburg.de", "other.example"),
            link.replace("https://", "https://user:password@"), "$link&file_id=other", "$link&cid=other", "$link#fragment",
            link.replace("type=0", "type=1"), "$link&zip=1", "$link&file_name=other.pdf",
            "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/print_assignments?assignment_id=17&cid=$course",
            "$DIGICAMPUS_ORIGIN/download/normal/0/../../secret").forEach {
            assertNull(VipsLinks.attachment(it, course), it)
        }
    }

    @Test fun linkedMetadataAndDownloadNeverFetchAnExerciseOrStartAnAttempt() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("session=test", request.headers[HttpHeaders.Cookie])
            when (request.url.encodedPath) {
                "/jsonapi.php/v1/file-refs/attachment" -> respond(metadata(), headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
                "/sendfile.php" -> respond(byteArrayOf(1, 2, 3, 4), headers = headersOf(HttpHeaders.ContentType, "application/pdf"))
                else -> error("Unexpected request: ${request.url}")
            }
        })
        try {
            val provider = DigiCampusProvider(client, { "session=test" })
            val document = provider.linkedDocument(course, link)
            assertEquals("Übung 3.pdf", document.name); assertEquals("exercise-folder", document.folderId)
            var bytes = 0; assertEquals(4L, provider.download(document) { bytes += it.size })
            assertEquals(4, bytes)
            assertEquals(listOf("/jsonapi.php/v1/file-refs/attachment", "/sendfile.php"), requests)
        } finally { client.close() }
    }

    @Test fun pluginMetadataMayBeUnavailableWhileThePublishedDownloadIsAuthorized() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound)) {
            val client = HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("attachment")) respond("", status)
                else respond("%PDF-sample", headers = headersOf(HttpHeaders.ContentType, "application/pdf"))
            })
            try {
                val provider = DigiCampusProvider(client, { "session=test" })
                val document = provider.linkedDocument(course, link)
                assertEquals("Exercise sheet.pdf", document.name)
                assertTrue(provider.download(document) {} > 0)
            } finally { client.close() }
        }
    }

    @Test fun explicitDownloadDenialMalformedMetadataAndExpiredSessionsAreNotBypassed() = runTest {
        for ((body, status, expected) in listOf(
            Triple(metadata(allowed = false), HttpStatusCode.OK, FailureKind.PERMISSION),
            Triple(metadata(id = "other"), HttpStatusCode.OK, FailureKind.INVALID_RESPONSE),
            Triple("", HttpStatusCode.Unauthorized, FailureKind.AUTHENTICATION))) {
            var requests = 0
            val client = HttpClient(MockEngine { requests++; respond(body, status, headersOf(HttpHeaders.ContentType, "application/vnd.api+json")) })
            try {
                val provider = DigiCampusProvider(client, { "session=test" })
                val failure = assertFailsWith<CampusException> { provider.download(provider.linkedDocument(course, link)) {} }
                assertEquals(expected, failure.kind); assertEquals(1, requests)
            } finally { client.close() }
        }
    }

    @Test fun attachmentRedirectsNeverForwardCookiesToAnotherHost() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++; assertEquals("digicampus.uni-augsburg.de", request.url.host)
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example/file"))
        }) { followRedirects = true }
        try {
            val error = assertFailsWith<CampusException> { DigiCampusProvider(client, { "session=test" }).download(doc.copy(downloadUrl = link)) {} }
            assertEquals(FailureKind.EXTERNAL_LINK, error.kind); assertEquals(1, requests)
        } finally { client.close() }
    }

    @Test fun mismatchedOrForeignStoredDownloadSourcesFailBeforeAnyRequest() = runTest {
        val client = HttpClient(MockEngine { error("No request allowed") })
        try {
            val provider = DigiCampusProvider(client, { "session=test" })
            for (source in listOf(link.replace("file_id=attachment", "file_id=other"), "https://other.example/file")) {
                assertEquals(FailureKind.INVALID_RESPONSE, assertFailsWith<CampusException> { provider.download(doc.copy(downloadUrl = source)) {} }.kind)
            }
        } finally { client.close() }
    }

    @Test fun repeatedAttachmentTapsShareMetadataTransferAndViewerThenWorkOffline() = runTest {
        var lookups = 0; var connected = true
        val gate = CompletableDeferred<Unit>(); val base = TestProvider()
        val provider = object : CampusProvider by base {
            override suspend fun linkedDocument(courseId: String, url: String): Document { lookups++; gate.await(); return doc }
        }
        val files = MemoryMaterials()
        val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope, conditions = { NetworkConditions(connected = connected) })
        repeat(10) { repo.requestLinkedMaterial(course, link) }; runCurrent()
        assertEquals(1, lookups); assertTrue(files.opened.isEmpty())
        gate.complete(Unit); runCurrent()
        assertEquals(1, base.downloadCalls); assertEquals(1, files.opened.size)
        connected = false; repo.requestLinkedMaterial(course, link); runCurrent()
        assertEquals(1, lookups); assertEquals(1, base.downloadCalls); assertEquals(2, files.opened.size)
        assertTrue(repo.sync.value.errors.isEmpty())
        repo.close(false)
    }

    @Test fun failedAttachmentLookupIsVisibleAndCanRetry() = runTest {
        var fail = true
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun linkedDocument(courseId: String, url: String): Document {
                if (fail) throw CampusException(FailureKind.AUTHENTICATION)
                return doc
            }
        }
        val files = MemoryMaterials(); val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope)
        repo.requestLinkedMaterial(course, link); runCurrent()
        assertEquals(FailureKind.AUTHENTICATION, repo.sync.value.errors["download:attachment"])
        assertTrue(files.opened.isEmpty())
        fail = false; repo.requestLinkedMaterial(course, link); runCurrent()
        assertTrue(repo.sync.value.errors.isEmpty()); assertEquals(1, files.opened.size)
        repo.close(false)
    }

    @Test fun signingOutDuringAttachmentResolutionCannotOpenOrCacheIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun linkedDocument(courseId: String, url: String): Document { withContext(NonCancellable) { gate.await() }; return doc }
        }
        val files = MemoryMaterials(); val store = MemoryStore()
        val repo = CampusRepository(account, provider, store, files, backgroundScope)
        repo.requestLinkedMaterial(course, link); runCurrent()
        val close = launch { repo.close(true) }; runCurrent(); gate.complete(Unit); runCurrent(); close.join()
        assertTrue(files.opened.isEmpty()); assertTrue(files.files.isEmpty())
        assertNull(store.read(account.cacheScope, "downloads"))
    }

    @Test fun openedAttachmentIsKeptInTheCourseFolderUnderVips() = runTest {
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun linkedDocument(courseId: String, url: String): Document = doc
        }
        val files = MemoryMaterials()
        val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope)
        repo.requestLinkedMaterial(course, link, "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=7&cid=$course"); runCurrent()
        assertEquals(1, files.opened.size)
        // The sheet is not in the cached list, so the file goes straight into Vips.
        assertEquals("Downloads/ZenCampus/course/Vips/Exercise sheet.pdf", repo.sync.value.materialExports[doc.id])
        assertContentEquals(byteArrayOf(1, 2, 3, 4), files.synced["Downloads/ZenCampus/course/Vips/Exercise sheet.pdf"])
        repo.close(false)
    }

    @Test fun attachmentStillOpensWhenNoCourseFolderCanBeWritten() = runTest {
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun linkedDocument(courseId: String, url: String): Document = doc
        }
        val files = MemoryMaterials().apply { syncsDefault = false }
        val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope)
        repo.requestLinkedMaterial(course, link); runCurrent()
        assertEquals(1, files.opened.size); assertTrue(files.synced.isEmpty()); assertTrue(repo.sync.value.errors.isEmpty())
        repo.close(false)
    }

    @Test fun attachmentFoldersFollowTheSheetItWasOpenedFrom() {
        val sheets = listOf(VipsSheet("42", course, "Blatt 3: Graphen/Bäume", "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=42&cid=$course"))
        assertEquals(listOf("Vips", "Blatt 3_ Graphen_Bäume"),
            vipsAttachmentFolders("$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=42&cid=$course", sheets))
        assertEquals(listOf("Vips"), vipsAttachmentFolders("$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets?cid=$course", sheets))
        assertEquals(listOf("Vips"), vipsAttachmentFolders("", sheets))
    }
}
