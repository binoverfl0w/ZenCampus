package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.digicampus.MaterialFolderTree
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class MaterialsTest {
    @Test fun ownUploadsRequireKnownOwnerAndAnUploadFolderAncestor() {
        val index = CourseMaterials("course", listOf(
            CampusFolder("upload", "course", "Assignments", folderType = "HomeworkFolder"),
            CampusFolder("nested", "course", "Week 1", parentId = "upload"),
            CampusFolder("other", "course", "Lecture"),
        ), listOf(
            Document("mine", "course", "nested", "mine.pdf", ownerId = "me"),
            Document("unknown", "course", "nested", "unknown.pdf"),
            Document("theirs", "course", "nested", "theirs.pdf", ownerId = "someone"),
            Document("outside", "course", "other", "outside.pdf", ownerId = "me"),
        ))
        assertEquals(listOf("mine"), index.ownUploads("me").map { it.id })
        assertTrue(index.ownUploads("").isEmpty())
    }
    private fun folder(id: String, parent: String = "", name: String = id, count: Int? = 0,
        type: String = "StandardFolder", writable: Boolean? = false, readable: Boolean = true): String = """
        {"type":"folders","id":"$id","attributes":{"name":"$name","folder-type":"$type",
        "is-visible":true,"is-readable":$readable${writable?.let { ",\"is-writable\":$it" }.orEmpty()}},
        "relationships":{${if (parent.isBlank()) "" else "\"parent\":{\"data\":{\"type\":\"folders\",\"id\":\"$parent\"}},"}
        "file-refs":{"meta":{${count?.let { "\"count\":$it" }.orEmpty()}}}}}
    """.trimIndent()
    private fun tree(vararg resources: String) = MaterialFolderTree(resources.map { Json.parseToJsonElement(it).jsonObject }, "course")

    @Test fun recursiveCourseResponseBecomesDirectChildrenAndKeepsDistinctSameNameFolders() {
        val tree = tree(folder("root", type = "RootFolder"), folder("a", "root", "Notes"),
            folder("b", "root", "Notes"), folder("nested", "a"), folder("a", "root", "Notes"))
        assertEquals(listOf("a", "b"), tree.childrenOf(null).map { it.id })
        assertEquals(listOf("nested"), tree.childrenOf("a").map { it.id })
        assertTrue(tree.childrenOf("nested").isEmpty())
    }

    @Test fun distinguishesEmptySubtreesFromFoldersWithFilesAndPreservesSubmissionMetadata() {
        val tree = tree(folder("root", type = "RootFolder"), folder("empty", "root"),
            folder("notes", "root"), folder("week", "notes", count = 2),
            folder("assignments", "root"), folder("submit", "assignments", type = "HomeworkFolder", writable = false),
            folder("writable", "root", writable = true), folder("custom", "root", type = "PluginFolder"))
        assertEquals(5, tree.childrenOf(null).size)
        assertEquals(false, tree.folder("notes").empty)
        assertTrue(tree.folder("assignments").empty == true)
        assertTrue(tree.folder("submit").acceptsUploads)
    }

    @Test fun missingMetadataAndUnreadableUploadFoldersAreNeverTreatedAsEmpty() {
        val tree = tree(folder("root", type = "RootFolder"), folder("count-unknown", "root", count = null),
            folder("permission-unknown", "root", writable = null),
            folder("inbox", "root", type = "HomeworkFolder", writable = true, readable = false))
        assertEquals(3, tree.childrenOf(null).size)
        assertNull(tree.folder("count-unknown").empty)
        assertNull(tree.folder("inbox").empty)
        assertFalse(tree.folder("inbox").readable)
    }

    @Test fun loadsRootFilesAndReusesTheCompleteTreeForNestedNavigation() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/jsonapi.php/v1/courses/course/folders" -> """{"data":[${folder("root", count = 1, type = "RootFolder")},${folder("notes", "root", count = 1)},${folder("nested", "notes")}]}"""
                "/jsonapi.php/v1/folders/root/file-refs" -> """{"data":[{"type":"file-refs","id":"root-file","attributes":{"name":"Course guide.pdf"}}]}"""
                "/jsonapi.php/v1/folders/notes/file-refs" -> """{"data":[{"type":"file-refs","id":"notes-file","attributes":{"name":"Notes.pdf"}}]}"""
                else -> error("Unexpected request: ${request.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val provider = DigiCampusProvider(client, { "session=test" })
            val root = provider.folderContent("course", null)
            assertEquals(listOf("notes"), root.folders.map { it.id })
            assertEquals("Course guide.pdf", root.documents.single().name)
            assertEquals("root", root.currentFolder?.id)
            val nested = provider.folderContent("course", "notes")
            assertEquals(listOf("nested"), nested.folders.map { it.id })
            assertEquals("Notes.pdf", nested.documents.single().name)
            assertEquals(1, requests.count { it.endsWith("courses/course/folders") })
            assertEquals(2, root.version)
        } finally { client.close() }
    }

    @Test fun forceRefreshAndExpiryReloadTheTreeWithoutFetchingWriteOnlyContents() = runTest {
        var now = 1000L
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("/jsonapi.php/v1/courses/course/folders", request.url.encodedPath)
            calls++
            respond("""{"data":[${folder("root", type = "RootFolder")},${folder("inbox", "root", type = "HomeworkFolder", writable = true, readable = false)}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val provider = DigiCampusProvider(client, { "session=test" }, { now })
            assertEquals("inbox", provider.folderContent("course", null).folders.single().id)
            assertTrue(provider.folderContent("course", "inbox").documents.isEmpty())
            assertEquals(1, calls)
            provider.folderContent("course", "inbox", force = true)
            assertEquals(2, calls)
            now += CampusRepository.FILE_TTL + 1
            provider.folderContent("course", "inbox")
            assertEquals(3, calls)
        } finally { client.close() }
    }

    @Test fun paginatedFolderTreeIsCompleteBeforeItIsShownAndDuplicateIdsAreRemoved() = runTest {
        val client = HttpClient(MockEngine { request ->
            val body = if (request.url.parameters["page[offset]"] == "2")
                """{"data":[${folder("notes", "root")},${folder("nested", "notes")}]}"""
            else """{"data":[${folder("root", type = "RootFolder")},${folder("notes", "root")}],"links":{"next":"/jsonapi.php/v1/courses/course/folders?page%5Boffset%5D=2"}}"""
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val provider = DigiCampusProvider(client, { "session=test" })
            assertEquals(listOf("notes"), provider.folderContent("course", null).folders.map { it.id })
            assertEquals(listOf("nested"), provider.folderContent("course", "notes").folders.map { it.id })
        } finally { client.close() }
    }

    @Test fun legacyFolderMetadataKeepsDistinctSameNameFolders() {
        val old = Json.decodeFromString<FolderContent>("""{"folders":[{"id":"a","courseId":"course","name":"Notes"},{"id":"b","courseId":"course","name":"Notes"}]}""")
        assertEquals(0, old.version)
        assertEquals(listOf("a", "b"), old.folders.map { it.id })
    }
}
