package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.digicampus.HIDDEN_MEMBERS_ID
import io.github.binoverfl0w.zencampus.data.digicampus.groupMembers
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CommunitySectionsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")

    @Test fun groupsCarryTheirVisibleMembersSizeAndSelfEnrolment() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/jsonapi.php/v1/courses/c/status-groups", request.url.encodedPath)
            respond("""{"data":[
                {"type":"status-groups","id":"g1","attributes":{"name":"Übung 1","full-name":"Übung 1 (Mo 10:00)","description":null,"size":20,"selfassign":true},
                 "relationships":{"members":{"data":[{"type":"users","id":"u1"},{"type":"users","id":"u2"}]}}},
                {"type":"status-groups","id":"g2","attributes":{"name":"Tutoren","size":0,"selfassign":false},"relationships":{"members":{"data":[]}}}]}""", headers = json)
        })
        try {
            val groups = DigiCampusProvider(client, { "fixture" }).community("c", "groups")
            assertEquals(listOf("Übung 1 (Mo 10:00)", "Tutoren"), groups.map { it.title })
            assertEquals(listOf("u1", "u2"), groups.first().children.map { it.personId })
            assertEquals("2/20|selfassign", groups.first().secondary)
            assertEquals("0", groups.last().secondary)
        } finally { client.close() }
    }

    /** Shaped like DigiCampus's group fragment (course/statusgroups/getgroup); the names are made up. */
    private fun fragment(footer: String) = """<table class="default"><thead><tr class="sortable"><th></th><th><a href="?cid=c&amp;sortby=nachname">Name</a></th><th>Aktionen</th></tr></thead>
        <tbody><tr><td>01</td><td><a href="https://digicampus.uni-augsburg.de/dispatch.php/profile?cid=c&amp;username=zoe"><img class="avatar-small user-zoe" title="Zoe Zander"> Zander, Zoe</a></td>
        <td class="memberactions"><a href="https://digicampus.uni-augsburg.de/dispatch.php/messages/write?cid=c" title="Nachricht"></a></td></tr>
        <tr><td>02</td><td><a href="https://digicampus.uni-augsburg.de/dispatch.php/profile?cid=c&amp;username=anna"><img class="avatar-small user-anna" title="Anna Adler"> Adler, Anna</a></td>
        <td class="memberactions"></td></tr></tbody><tfoot><tr><td colspan="3">$footer</td></tr></tfoot></table>"""

    @Test fun groupMembersAreReadFromTheOfficialGroupFragment() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/dispatch.php/course/statusgroups/getgroup/g1", request.url.encodedPath)
            assertEquals("c", request.url.parameters["cid"])
            assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
            respond(fragment("+ 2 unsichtbare Personen"), headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))
        })
        try {
            val items = DigiCampusProvider(client, { "fixture" }).community("c", "group-members", "g1")
            assertEquals(listOf("Zoe Zander", "Anna Adler"), items.filter { it.id != HIDDEN_MEMBERS_ID }.map { it.title })
            assertEquals(listOf("zoe", "anna"), items.filter { it.id != HIDDEN_MEMBERS_ID }.map { it.id })
            assertEquals(2, items.single { it.id == HIDDEN_MEMBERS_ID }.unread)
        } finally { client.close() }
    }

    @Test fun noHiddenMembersMeansNoHiddenEntryInEitherLanguage() {
        assertTrue(groupMembers(fragment("+ 0 invisible persons")).none { it.id == HIDDEN_MEMBERS_ID })
        assertEquals(3, groupMembers(fragment("+ 3 invisible persons")).single { it.id == HIDDEN_MEMBERS_ID }.unread)
    }

    @Test fun aSignedOutRedirectIsAnAuthenticationFailure() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/index.php")) })
        try { assertEquals(io.github.binoverfl0w.zencampus.model.FailureKind.AUTHENTICATION, assertFailsWith<io.github.binoverfl0w.zencampus.model.CampusException> {
            DigiCampusProvider(client, { "fixture" }).community("c", "group-members", "g1") }.kind) }
        finally { client.close() }
    }

    @Test fun aCourseThreadWithoutTextIsNamedInsteadOfBlank() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"data":[
                {"type":"blubber-threads","id":"t1","attributes":{"name":"Algorithmen und Datenstrukturen","content":"","content-html":"","is-commentable":true,"latest-activity":"2026-11-10T10:00:00+01:00"}},
                {"type":"blubber-threads","id":"t2","attributes":{"name":"x","content":"Wer hat die Folien?\nDanke","is-commentable":true}}]}""", headers = json)
        })
        try {
            val threads = DigiCampusProvider(client, { "fixture" }).community("c", "blubber")
            assertEquals("Algorithmen und Datenstrukturen", threads.first().title)
            assertEquals("", threads.first().body)
            assertEquals("Wer hat die Folien?", threads.last().title)
        } finally { client.close() }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GroupMembersRepositoryTest {
    @Test fun aRefusedMemberListLeavesTheGroupsInPlace() = kotlinx.coroutines.test.runTest {
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun community(courseId: String, section: String, itemId: String): List<io.github.binoverfl0w.zencampus.model.CommunityItem> = when (section) {
                "groups" -> listOf(io.github.binoverfl0w.zencampus.model.CommunityItem("g1", "Übung 1", secondary = "0/3"))
                "group-members" -> throw io.github.binoverfl0w.zencampus.model.CampusException(io.github.binoverfl0w.zencampus.model.FailureKind.PERMISSION)
                else -> emptyList()
            }
        }
        val repo = io.github.binoverfl0w.zencampus.data.study.CampusRepository(io.github.binoverfl0w.zencampus.model.Account("alice", "Alice", "alice"), provider, MemoryStore(), MemoryMaterials(),
            backgroundScope, clock = { 1_000L })
        repo.refreshCourses(); runCurrent()
        val course = repo.snapshot.value.courses.first().id
        repo.refreshCommunity(course, "groups"); runCurrent()
        repo.refreshCommunity(course, "group-members", "g1"); runCurrent()
        repo.refreshCommunity(course, "groups", force = true); runCurrent()
        val groups = repo.snapshot.value.community[io.github.binoverfl0w.zencampus.model.CacheKeys.community(course, "groups")]
        assertEquals(listOf("g1"), groups?.map { it.id })
        assertEquals(io.github.binoverfl0w.zencampus.model.FailureKind.PERMISSION, repo.sync.value.errors[io.github.binoverfl0w.zencampus.model.CacheKeys.community(course, "group-members", "g1")])
        repo.close(false)
    }
}
