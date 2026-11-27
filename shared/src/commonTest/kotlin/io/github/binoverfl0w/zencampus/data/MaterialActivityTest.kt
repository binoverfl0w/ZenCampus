package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class MaterialActivityTest {
    private val now = Instant.parse("2026-10-21T10:00:00Z").toEpochMilliseconds()
    private val day = 86_400_000L
    private val account = Account("alice", "Alice", "alice")
    private val root = CampusFolder("root", "c", "Files", folderType = "RootFolder")
    private val notes = CampusFolder("notes", "c", "Exercises", "root", modified = now - 30 * day)
    private val nested = CampusFolder("nested", "c", "Week 3", "notes", modified = now - 30 * day)
    private fun file(id: String, modified: Long = now - day, created: Long = modified, folder: String = "nested") =
        Document(id, "c", folder, "$id.pdf", mimeType = "application/pdf", modified = modified, created = created)
    private fun index(files: List<Document> = listOf(file("exercise"))) = CourseMaterials("c", listOf(root, notes, nested), files)
    private fun folderJson(folder: CampusFolder) = """{"type":"folders","id":"${folder.id}","attributes":{"name":"${folder.name}","is-visible":true,"is-readable":true,"mkdate":"2026-09-01T10:00:00+02:00","chdate":"2026-10-01T12:00:00+02:00"},"relationships":{${if (folder.parentId.isBlank()) "" else "\"parent\":{\"data\":{\"type\":\"folders\",\"id\":\"${folder.parentId}\"}},"}"file-refs":{"meta":{"count":1}}}}"""
    private fun fileJson(id: String = "exercise", folder: String = "nested", range: String = "c", readable: Boolean = true) = """
        {"type":"file-refs","id":"$id","attributes":{"name":"$id.pdf","mkdate":"2026-09-01T10:00:00+02:00","chdate":"2026-10-20T12:00:00+02:00","is-readable":$readable},
        "relationships":{"parent":{"data":{"type":"folders","id":"$folder"}},"range":{"data":{"type":"courses","id":"$range"}}}}
    """.trimIndent()

    @Test fun rollingWindowIncludesOldFilesUpdatedRecentlyAndNewFilesAcrossAllSubfolders() {
        val files = listOf(file("old-updated", now - day, now - 100 * day), file("new", now - 2 * day),
            file("root-file", now - 3 * day, folder = "root"), file("boundary", now - MATERIAL_ACTIVITY_WINDOW),
            file("expired", now - MATERIAL_ACTIVITY_WINDOW - 1), file("future", now + 1), file("undated", 0),
            file("foreign").copy(courseId = "other"))
        assertEquals(listOf("old-updated", "new", "root-file", "boundary"), index(files).recentFiles(now).map { it.id })
        assertEquals(1, index(files).undatedCount)
        assertTrue(files.first().updatedAfterCreation)
        assertFalse(files[1].updatedAfterCreation)
        assertTrue(index(listOf(file("expired", now - MATERIAL_ACTIVITY_WINDOW))).recentFiles(now + 1).isEmpty())
    }

    @Test fun creationDatesAreUsedWhenModificationDatesAreAbsentAndUnknownDatesNeverBecomeNow() {
        val new = file("new", 0, now - day)
        assertEquals(now - day, new.activityAt)
        assertFalse(new.updatedAfterCreation)
        assertEquals(listOf(new), index(listOf(new, file("unknown", 0))).recentFiles(now))
        val oldJson = """{"id":"old","courseId":"c","folderId":"nested","name":"old.pdf","modified":$now}"""
        assertEquals(now, Json.decodeFromString<Document>(oldJson).activityAt)
    }

    @Test fun descendantFileActivityPropagatesWithoutConfusingItWithFolderMetadataChanges() {
        val materials = index(listOf(file("old", now - 20 * day), file("fresh", now - 1000)))
        assertEquals(now - 1000, materials.latestFileActivity("nested"))
        assertEquals(now - 1000, materials.latestFileActivity("notes"))
        assertEquals(now - 1000, materials.latestFileActivity("root"))
        assertEquals("Exercises / Week 3", materials.path("nested"))
        assertEquals("", materials.path("root"))
        assertEquals("", materials.path("unknown"))
        assertEquals(now - 30 * day, materials.folders.single { it.id == "notes" }.modified)
        assertTrue(index(emptyList()).recentFiles(now).isEmpty())
        assertNull(index(emptyList()).latestFileActivity("notes"))
    }

    @Test fun duplicateReferencesAreListedOnceButDistinctCopiesAndRenamedFoldersKeepTheirIdentity() {
        val other = nested.copy(id = "other", name = nested.name)
        val first = file("one"); val second = file("two", folder = "other")
        val material = index(listOf(first, first, second)).copy(folders = listOf(root, notes, nested, other))
        assertEquals(2, material.recentFiles(now).size)
        assertEquals("Exercises / Week 3", material.path("other"))
        val renamed = material.copy(folders = listOf(root, notes.copy(name = "Worksheets"), nested, other))
        assertEquals("Worksheets / Week 3", renamed.path("nested"))
        assertEquals(material, Json.decodeFromString<CourseMaterials>(Json.encodeToString(material)))
    }

    @Test fun dateMappingReadsFolderAndFileSourceDatesInBothEpochAndRfc3339Formats() {
        val folder = JsonApiMapper.folder(Json.parseToJsonElement(folderJson(notes)).jsonObject, "c", "")
        assertEquals(JsonApiMapper.timestamp("2026-10-01T12:00:00+02:00"), folder.modified)
        assertEquals(JsonApiMapper.timestamp("2026-09-01T10:00:00+02:00"), folder.created)
        val resource = Json.parseToJsonElement(fileJson()).jsonObject
        val file = JsonApiMapper.document(resource, "c", "nested")
        assertEquals(JsonApiMapper.timestamp("2026-10-20T12:00:00+02:00"), file.modified)
        assertEquals(JsonApiMapper.timestamp("2026-09-01T10:00:00+02:00"), file.created)
        val epoch = JsonApiMapper.document(Json.parseToJsonElement(fileJson().replace("2026-09-01T10:00:00+02:00", "1756713600")).jsonObject, "c", "nested")
        assertEquals(1_756_713_600_000, epoch.created)
    }

    @Test fun providerPagesTheWholeCourseWithoutOpeningAnySubdirectoryOrFileContents() = runTest {
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("session=test", request.headers[HttpHeaders.Cookie])
            assertEquals(HttpMethod.Get, request.method)
            val path = request.url.encodedPath
            calls += path
            val json = when (path) {
                "/jsonapi.php/v1/courses/c/folders" -> """{"data":[${listOf(root, notes, nested).joinToString(",", transform = ::folderJson)}]}"""
                "/jsonapi.php/v1/courses/c/file-refs" -> if (request.url.parameters["page[offset]"] == null)
                    """{"data":[${fileJson("first", "root")}],"links":{"next":"$DIGICAMPUS_API/courses/c/file-refs?page[offset]=1&page[limit]=100"}}"""
                    else """{"data":[${fileJson()},${fileJson("hidden", readable = false)}]}"""
                else -> error("No folder crawl or download is permitted: ${request.url}")
            }
            respond(json, headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val result = DigiCampusProvider(client, { "session=test" }, { now }).courseMaterials("c")
            assertEquals(listOf("first", "exercise"), result.documents.map { it.id })
            assertEquals("Exercises / Week 3", result.path("nested"))
            assertEquals(3, calls.size)
            assertEquals(now - day, result.latestFileActivity("notes"))
        } finally { client.close() }
    }

    @Test fun mismatchedCourseOrMalformedParentsRejectTheIndexInsteadOfAssigningForeignFiles() = runTest {
        for (bad in listOf(fileJson(range = "other"), fileJson().replace("\"type\":\"folders\"", "\"type\":\"courses\""))) {
            val client = HttpClient(MockEngine { request -> respond(if (request.url.encodedPath.endsWith("/folders"))
                """{"data":[${folderJson(root)}]}""" else """{"data":[$bad]}""", headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")) })
            try { assertEquals(FailureKind.INVALID_RESPONSE, assertFailsWith<CampusException> {
                DigiCampusProvider(client, { "session=test" }).courseMaterials("c")
            }.kind) } finally { client.close() }
        }
    }

    @Test fun aFolderCreatedAfterTheCachedTreeIsRecheckedForItsFullPath() = runTest {
        var folderCalls = 0
        val client = HttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/folders")) {
                folderCalls++
                """{"data":[${(if (folderCalls == 1) listOf(root, notes) else listOf(root, notes, nested)).joinToString(",", transform = ::folderJson)}]}"""
            } else """{"data":[${fileJson()}]}"""
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val provider = DigiCampusProvider(client, { "session=test" }, { now })
            provider.folders("c", null)
            assertEquals("Exercises / Week 3", provider.courseMaterials("c").path("nested"))
            assertEquals(2, folderCalls)
        } finally { client.close() }
    }

    @Test fun simultaneousReadersShareTheIndexAndFailuresKeepTheSuccessfulOfflineSnapshot() = runTest {
        var calls = 0; var failure: FailureKind? = null
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun courseMaterials(courseId: String, force: Boolean): CourseMaterials {
                calls++; delay(100); failure?.let { throw CampusException(it) }; return index()
            }
        }
        val store = MemoryStore()
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, clock = { now })
        repo.refreshCourses()
        coroutineScope { List(10) { launch { repo.refreshCourseMaterials("c") } }.joinAll() }
        repo.refreshCourseMaterials("c"); assertEquals(1, calls)
        failure = FailureKind.SERVER; repo.refreshCourseMaterials("c", true); runCurrent()
        assertEquals(1, repo.snapshot.value.courseMaterials["c"]?.recentFiles(now)?.size)
        repo.close(false)
        val restored = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope,
            conditions = { NetworkConditions(connected = false) }, clock = { now })
        restored.refreshCourseMaterials("c", true); runCurrent()
        assertEquals(2, calls)
        assertEquals(1, restored.snapshot.value.courseMaterials["c"]?.recentFiles(now)?.size)
        assertNull(restored.sync.value.errors["course-files:c"]); assertTrue("course-files:c" in restored.sync.value.stale)
        assertNull(store.read(Account("bob", "Bob", "bob").cacheScope, "course-files:c"))
        restored.close(false)
    }

    @Test fun revokedFolderAccessCannotBeRepopulatedByAnOlderInFlightCourseIndex() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun courseMaterials(courseId: String, force: Boolean): CourseMaterials { gate.await(); return index() }
            override suspend fun folderContent(courseId: String, parentId: String?, force: Boolean): FolderContent = throw CampusException(FailureKind.PERMISSION)
        }
        val store = MemoryStore(); val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, clock = { now })
        val earlier = launch { repo.refreshCourseMaterials("c", true) }; runCurrent()
        repo.refreshFolder("c", null, true)
        gate.complete(Unit); earlier.join()
        assertNull(store.read(account.cacheScope, "course-files:c"))
        repo.close(false)
    }

    @Test fun anUnsupportedCourseIndexDoesNotEraseUsableFolderContents() = runTest {
        val store = MemoryStore(); val repo = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope, clock = { now })
        repo.refreshFolder("c", null)
        repo.refreshCourseMaterials("c", true)
        assertNotNull(store.read(account.cacheScope, CampusRepository.folderKey("c", null)))
        assertNull(store.read(account.cacheScope, "course-files:c"))
        repo.close(false)
    }

    @Test fun aNewerIndexedVersionRevalidatesAnOlderPinnedCopyEvenFromACachedFolderListing() = runTest {
        val old = file("exercise", now - 10 * day)
        val updated = old.copy(modified = now - 1000, name = "Corrected exercise.pdf")
        var transfers = 0
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun courseMaterials(courseId: String, force: Boolean) = index(listOf(updated))
            override suspend fun download(document: Document, write: suspend (ByteArray) -> Unit): Long {
                transfers++; write(byteArrayOf(1, 2, 3, 4)); return 4
            }
        }
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { now })
        repo.refreshCourses(); repo.download(old, pin = true, open = false)
        repo.refreshCourseMaterials("c"); runCurrent()
        val fresh = repo.snapshot.value.courseMaterials.getValue("c").latestMetadata(old)
        repo.download(fresh); runCurrent()
        assertEquals(2, transfers)
        assertEquals(updated.modified, repo.snapshot.value.localDocuments.single().version)
        assertEquals(updated.name, repo.snapshot.value.localDocuments.single().document.name)
        assertTrue(repo.snapshot.value.localDocuments.single().pinned)
        assertEquals(updated, index(listOf(old)).latestMetadata(updated))
        repo.close(false)
    }
}
