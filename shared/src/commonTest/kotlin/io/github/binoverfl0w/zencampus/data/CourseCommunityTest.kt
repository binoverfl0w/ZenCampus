package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.data.digicampus.text
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CourseCommunityTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
    @Test fun blubberUsesPublishedUnreadCountsAndPostsOnlyOnce() = runTest {
        var posts = 0
        val client = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Post -> {
                    posts++
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["data"]!!.jsonObject
                    assertEquals("Hello", body["attributes"]!!.jsonObject["content"]!!.jsonPrimitive.content)
                    respond("", HttpStatusCode.Created, headersOf(HttpHeaders.Location, "/jsonapi.php/v1/blubber-comments/created"))
                }
                request.url.encodedPath.endsWith("/comments") -> respond("""{"data":[{"type":"blubber-comments","id":"reply","attributes":{"content-html":"<p>Answer</p>"},"relationships":{"author":{"data":{"type":"users","id":"u"}}}}],"included":[{"type":"users","id":"u","attributes":{"formatted-name":"Alice"}}]}""", headers = json)
                else -> respond("""{"data":[{"type":"blubber-threads","id":"thread","attributes":{"content-html":"<p>Question</p>","is-commentable":true,"latest-activity":"2026-10-30T10:00:00+01:00"},"relationships":{"comments":{"meta":{"unseen-comments":2}},"author":{"data":{"type":"users","id":"u"}}}}],"included":[{"type":"users","id":"u","attributes":{"formatted-name":"Alice"}}]}""", headers = json)
            }
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" })
            val thread = provider.community("c", "blubber").single()
            assertEquals("Question", thread.title); assertEquals(2, thread.unread); assertTrue(thread.canReply)
            assertEquals("Answer", provider.community("c", "blubber", thread.id).single().body.trim())
            assertEquals(WriteResult.Confirmed(Unit), provider.postBlubberReply("thread", "Hello")); assertEquals(1, posts)
        } finally { client.close() }
    }
    @Test fun coursewareFilesMustBelongToTheSelectedBlockAndBeDownloadable() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/jsonapi.php/v1/courseware-blocks/block/file-refs", request.url.encodedPath)
            respond("""{"data":[{"type":"file-refs","id":"file","attributes":{"name":"notes.pdf","is-downloadable":true}}]}""", headers = json)
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" })
            assertEquals("notes.pdf", provider.coursewareFile("course", "block", "file").name)
            assertFailsWith<CampusException> { provider.coursewareFile("course", "block", "other") }
        } finally { client.close() }
    }
    @Test fun coursewarePreservesBlockOrderAndRejectsUnsafeLinks() = runTest {
        val client = HttpClient(MockEngine { respond("""{"data":{"type":"courseware-structural-elements","id":"e","attributes":{"title":"Chapter","can-visit":true},"relationships":{"children":{"data":[]},"containers":{"data":[{"type":"courseware-containers","id":"box"}]}}},"included":[{"type":"courseware-containers","id":"box","attributes":{"position":0},"relationships":{"blocks":{"data":[{"type":"courseware-blocks","id":"heading"},{"type":"courseware-blocks","id":"document"},{"type":"courseware-blocks","id":"bad"}]}}},{"type":"courseware-blocks","id":"heading","attributes":{"position":0,"block-type":"headline","payload":{"title":"Intro"}}},{"type":"courseware-blocks","id":"document","attributes":{"position":1,"block-type":"document","payload":{"title":"Notes","file_id":"file","downloadable":"true"}}},{"type":"courseware-blocks","id":"bad","attributes":{"position":2,"block-type":"link","payload":{"url":"javascript:alert(1)"}}}]}""", headers = json) })
        try {
            val entry = DigiCampusProvider(client, { "fixture" }).community("course", "courseware", "e").single()
            assertEquals(listOf("headline", "file"), entry.parts.map { it.kind })
            assertEquals("document", entry.parts.last().blockId); assertEquals("file", entry.parts.last().target)
            assertTrue(entry.incomplete)
        } finally { client.close() }
    }
    @Test fun coursewareDoesNotRenderHiddenContainers() = runTest {
        val client = HttpClient(MockEngine { respond("""{"data":{"type":"courseware-structural-elements","id":"e","attributes":{"title":"Chapter"},"relationships":{"children":{"data":[]},"containers":{"data":[{"type":"courseware-containers","id":"hidden"}]}}},"included":[{"type":"courseware-containers","id":"hidden","attributes":{"visible":false},"relationships":{"blocks":{"data":[{"type":"courseware-blocks","id":"text"}]}}},{"type":"courseware-blocks","id":"text","attributes":{"block-type":"text","payload":{"text":"Hidden content"}}}]}""", headers = json) })
        try { assertTrue(DigiCampusProvider(client, { "fixture" }).community("course", "courseware", "e").single().parts.isEmpty()) }
        finally { client.close() }
    }
    @Test fun activityOnlyReturnsPublishedContextAndPeerReviewsRespectHiddenAssessments() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("activitystream")) respond("""{"data":[{"type":"activities","id":"a","attributes":{"title":"Course changed","activity-type":"schedule","mkdate":"2026-10-30T10:00:00+01:00"},"relationships":{"context":{"data":{"type":"courses","id":"course"}}}}]}""", headers = json)
            else respond("""{"data":[{"type":"courseware-peer-reviews","id":"review","attributes":{"assessment":{"answer":"Good work"},"is-submitter":true,"is-reviewer":false},"relationships":{"process":{"data":{"type":"courseware-peer-review-processes","id":"p"}}}},{"type":"courseware-peer-reviews","id":"hidden","attributes":{"assessment":null,"is-submitter":true},"relationships":{"process":{"data":{"type":"courseware-peer-review-processes","id":"p"}}}}],"included":[{"type":"courseware-peer-review-processes","id":"p","attributes":{"configuration":{"type":"freetext"}}}]}""", headers = json)
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" })
            assertEquals("course", provider.activity("user").single().courseId)
            val reviews = provider.peerReviews("task")
            assertEquals("Good work", reviews.first().sections.single().text)
            assertFalse(reviews.last().available); assertTrue(reviews.last().sections.isEmpty())
        } finally { client.close() }
    }
    @Test fun peerReviewFileOwnershipIsNotInferredFromAName() {
        val resource = Json.parseToJsonElement("""{"type":"file-refs","id":"f","attributes":{"name":"report.pdf"},"relationships":{"owner":{"data":{"type":"users","id":"student"}}}}""").jsonObject
        assertEquals("student", JsonApiMapper.document(resource, "course", "folder").ownerId)
        val anonymous = Json.parseToJsonElement("""{"type":"file-refs","id":"f","attributes":{"name":"report.pdf"}}""").jsonObject
        assertEquals("", JsonApiMapper.document(anonymous, "course", "folder").ownerId)
    }
    @Test fun participantNamesComeFromVisibleIncludedUsers() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method); assertEquals("/jsonapi.php/v1/courses/c/memberships", request.url.encodedPath)
            assertEquals("user", request.url.parameters["include"])
            respond("""{"data":[{"type":"course-memberships","id":"m","attributes":{"permission":"tutor"},"relationships":{"user":{"data":{"type":"users","id":"u"}}}}],"included":[{"type":"users","id":"u","attributes":{"formatted-name":"Alice"}}]}""", headers = json)
        })
        try { val item = DigiCampusProvider(client, { "fixture" }).community("c", "people").single(); assertEquals("Alice", item.title); assertEquals("tutor", item.secondary) }
        finally { client.close() }
    }
    @Test fun forumListsUnreadCountsAndPreservesAnonymousPosts() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++; assertEquals(HttpMethod.Get, request.method)
            if (request.url.encodedPath.endsWith("forum-discussions")) respond("""{"data":[{"type":"forum-discussions","id":"d","attributes":{"title":"Questions","chdate":"2026-10-30T10:00:00+01:00"},"meta":{"unread-postings-count":2}}]}""", headers = json)
            else respond("""{"data":[{"type":"forum-postings","id":"p","attributes":{"anonymous":true,"content-html":"<p>Hello</p>","mkdate":"2026-10-30T10:00:00+01:00"},"relationships":{"author":{"data":{"type":"users","id":"u"}}}}],"included":[{"type":"users","id":"u","attributes":{"formatted-name":"Hidden"}}]}""", headers = json)
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" })
            assertEquals(2, provider.community("c", "forum").single().unread)
            val post = provider.community("c", "forum", "d").single()
            assertEquals("", post.title); assertEquals("Hello", post.body.trim()); assertEquals(2, calls)
        } finally { client.close() }
    }
    @Test fun wikiPagesAreRenderedAsTextAndNotExecutableHtml() = runTest {
        val client = HttpClient(MockEngine { respond("""{"data":[{"type":"wiki-pages","id":"w","attributes":{"name":"Notes","content":"<p>Hello</p><script>alert(1)</script>","chdate":"2026-10-30T10:00:00+01:00"}}]}""", headers = json) })
        try { val page = DigiCampusProvider(client, { "fixture" }).community("c", "wiki").single(); assertEquals("Hello", page.body.trim()); assertFalse(page.body.contains("alert")) }
        finally { client.close() }
    }
    @Test fun coursewareLoadsPagesOnlyAfterSelectionAndKeepsUnsupportedContentVisible() = runTest {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath.endsWith("courseware-units")) respond("""{"data":[{"type":"courseware-units","id":"u","attributes":{"can-read":true},"relationships":{"structural-element":{"data":{"type":"courseware-structural-elements","id":"e"}}}}],"included":[{"type":"courseware-structural-elements","id":"e","attributes":{"title":"Chapter","can-visit":true}}]}""", headers = json)
            else respond("""{"data":{"type":"courseware-structural-elements","id":"e","attributes":{"title":"Chapter","can-visit":true},"relationships":{"children":{"data":[{"type":"courseware-structural-elements","id":"child"}]},"containers":{"data":[{"type":"courseware-containers","id":"box"}]}}},"included":[{"type":"courseware-structural-elements","id":"child","attributes":{"title":"Next","can-visit":true,"position":0}},{"type":"courseware-containers","id":"box","attributes":{"position":0},"relationships":{"blocks":{"data":[{"type":"courseware-blocks","id":"text"},{"type":"courseware-blocks","id":"video"}]}}},{"type":"courseware-blocks","id":"text","attributes":{"position":0,"block-type":"text","payload":{"text":"<p>Content</p>"}}},{"type":"courseware-blocks","id":"video","attributes":{"position":1,"block-type":"video"}}]}""", headers = json)
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" }); val unit = provider.community("c", "courseware").single()
            assertEquals("e", unit.id); assertEquals(1, paths.size)
            val page = provider.community("c", "courseware", unit.id).single()
            assertEquals("Content", page.body.trim()); assertEquals("child", page.children.single().id); assertTrue(page.incomplete); assertEquals(2, paths.size)
        } finally { client.close() }
    }
    @Test fun forumReplyUsesOnePostAndDoesNotRetryUnknownOutcomes() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++; assertEquals(HttpMethod.Post, request.method)
            val data = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["data"]!!.jsonObject
            assertEquals(false, data["attributes"]!!.jsonObject["anonymous"]!!.jsonPrimitive.boolean)
            assertEquals("discussion", data["relationships"]!!.jsonObject["discussion"]!!.jsonObject["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            respond("", HttpStatusCode.Created, headersOf(HttpHeaders.Location, "/jsonapi.php/v1/forum-postings/p1"))
        })
        try { assertEquals(WriteResult.Confirmed(Unit), DigiCampusProvider(client, { "fixture" }).postForumReply("discussion", "Thanks")); assertEquals(1, calls) }
        finally { client.close() }
        calls = 0
        val lost = HttpClient(MockEngine { calls++; throw IllegalStateException("connection lost") })
        try { assertEquals(WriteResult.Uncertain, DigiCampusProvider(lost, { "fixture" }).postForumReply("discussion", "Thanks")); assertEquals(1, calls) }
        finally { lost.close() }
    }

    @Test fun subscriptionsAndReactionsReadTheirServerIdsAndUseExplicitWrites() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("forum-subscriptions") -> respond("""{"data":[{"type":"forum-subscriptions","id":"s1","attributes":{"notification-type":"all"},"relationships":{"subject":{"data":{"type":"forum-discussions","id":"d"}}}}]}""", headers = json)
                request.method == HttpMethod.Get -> respond("""{"data":[{"type":"forum-posting-reactions","id":"r1","attributes":{"emoji":"👍"},"relationships":{"user":{"data":{"type":"users","id":"u"}}}}]}""", headers = json)
                request.method == HttpMethod.Post -> {
                    val data = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["data"]!!.jsonObject
                    val type = data["type"]!!.jsonPrimitive.content
                    respond("", HttpStatusCode.Created, headersOf(HttpHeaders.Location, "/jsonapi.php/v1/$type/new"))
                }
                else -> respond("", HttpStatusCode.NoContent)
            }
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" })
            assertEquals(ForumSubscription("s1", "d"), provider.forumSubscriptions("c").single())
            assertEquals(ForumReaction("r1", "👍", "u"), provider.forumReactions("p").single())
            assertEquals(WriteResult.Confirmed(Unit), provider.changeForumSubscription("c", "d", null))
            assertEquals(WriteResult.Confirmed(Unit), provider.changeForumSubscription("c", "d", "s1"))
            assertEquals(WriteResult.Confirmed(Unit), provider.changeForumReaction("p", null))
            assertEquals(WriteResult.Confirmed(Unit), provider.changeForumReaction("p", "r1"))
            assertEquals(6, requests.size)
        } finally { client.close() }
    }

    @Test fun coursewareBookmarksAndProgressUseThePublishedContracts() = runTest {
        var mutations = 0
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("courseware-bookmarks") && request.method == HttpMethod.Get ->
                    respond("""{"data":[{"type":"courseware-structural-elements","id":"e"}]}""", headers = json)
                request.url.encodedPath.endsWith("courseware-bookmarks") -> {
                    mutations++; val data = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["data"]!!.jsonArray.single().jsonObject
                    assertEquals("e", data["id"]!!.jsonPrimitive.content)
                    respond("", HttpStatusCode.NoContent)
                }
                else -> respond("""{"e":{"id":1,"progress":{"self":50,"cumulative":75}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        try {
            val provider = DigiCampusProvider(client, { "fixture" })
            assertEquals(listOf("e"), provider.coursewareBookmarks("user"))
            assertEquals(WriteResult.Confirmed(Unit), provider.setCoursewareBookmark("user", "e", true))
            assertEquals(WriteResult.Confirmed(Unit), provider.setCoursewareBookmark("user", "e", false))
            assertEquals(2, mutations)
            assertEquals(75.0, provider.coursewareProgress("unit")["e"])
        } finally { client.close() }
    }

    @Test fun officialGroupsAreReadFromTheCourseStatusGroupsRoute() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/jsonapi.php/v1/courses/c/status-groups", request.url.encodedPath)
            respond("""{"data":[{"type":"status-groups","id":"g","attributes":{"name":"Tutorial A","description":"Weekly group"}}]}""", headers = json)
        })
        try { val group = DigiCampusProvider(client, { "fixture" }).community("c", "groups").single(); assertEquals("Tutorial A", group.title); assertEquals("Weekly group", group.body) }
        finally { client.close() }
    }

}
