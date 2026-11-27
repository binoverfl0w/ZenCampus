package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class MessageProviderTest {
    @Test fun readsInboxAcrossPagesAndUsesIncludedSenderWithoutMarkingRead() = runTest {
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("session=test", request.headers[HttpHeaders.Cookie])
            calls += request.url.toString()
            if (request.url.parameters["page[offset]"] == "100") respond("""{"data":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
            else respond("""{"data":[{"type":"messages","id":"m1","attributes":{"subject":"Test","message":"<p>Hello</p>","is-read":false,"mkdate":"2026-10-30T10:00:00+01:00"},"relationships":{"sender":{"data":{"type":"users","id":"u1"}}}}],"included":[{"type":"users","id":"u1","attributes":{"formatted-name":"Alice"}}],"links":{"next":"/jsonapi.php/v1/users/me/inbox?page%5Boffset%5D=100"}}""", headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            val messages = DigiCampusProvider(client, { "session=test" }).messages("me", false)
            assertEquals(2, calls.size); assertEquals("Alice", messages.single().sender)
            assertEquals("Hello", messages.single().body.trim()); assertFalse(messages.single().read)
        } finally { client.close() }
    }
    @Test fun invalidReadStateDoesNotBecomeAnEmptyOrReadMessage() = runTest {
        val client = HttpClient(MockEngine { respond("""{"data":[{"type":"messages","id":"m","attributes":{"subject":"A","mkdate":"2026-10-30T10:00:00+01:00"}}]}""", headers = headersOf(HttpHeaders.ContentType,"application/vnd.api+json")) })
        try { assertEquals(FailureKind.INVALID_RESPONSE, assertFailsWith<CampusException> { DigiCampusProvider(client, { "session=test" }).messages("me", false) }.kind) }
        finally { client.close() }
    }
    @Test fun markingReadSendsOneExplicitPatchAndRequiresAConfirmedResponse() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++; assertEquals(HttpMethod.Patch, request.method)
            assertEquals("/jsonapi.php/v1/messages/m1", request.url.encodedPath)
            assertEquals("session=test", request.headers[HttpHeaders.Cookie])
            respond("""{"data":{"type":"messages","id":"m1","attributes":{"is-read":true}}}""", headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try {
            assertEquals(WriteResult.Confirmed(true), DigiCampusProvider(client, { "session=test" }).setMessageRead("m1", true))
            assertEquals(1, calls)
        } finally { client.close() }
    }
    @Test fun failedOrUnverifiableReadWritesAreNeverRepeated() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; throw IllegalStateException("connection closed") })
        try {
            assertEquals(WriteResult.Uncertain, DigiCampusProvider(client, { "session=test" }).setMessageRead("m1", true))
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun sendingUsesOneExplicitPostWithSelectedRecipientAndPlainText() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++; assertEquals(HttpMethod.Post, request.method)
            assertEquals("/jsonapi.php/v1/messages", request.url.encodedPath)
            val data = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["data"]!!.jsonObject
            assertEquals("messages", data["type"]!!.jsonPrimitive.content)
            val attrs = data["attributes"]!!.jsonObject
            assertEquals("Question", attrs["subject"]!!.jsonPrimitive.content)
            assertEquals("A &lt; B<br>thanks", attrs["message"]!!.jsonPrimitive.content)
            val recipient = data["relationships"]!!.jsonObject["recipients"]!!.jsonObject["data"]!!.jsonArray.single().jsonObject
            assertEquals("u1", recipient["id"]!!.jsonPrimitive.content)
            respond("", HttpStatusCode.Created, headersOf(HttpHeaders.Location, "/jsonapi.php/v1/messages/receipt"))
        })
        try {
            assertEquals(WriteResult.Confirmed(Unit), DigiCampusProvider(client, { "fixture" }).sendMessage("u1", "Question", "A < B\nthanks"))
            assertEquals(1, calls)
        } finally { client.close() }
    }
    @Test fun aLostSendResponseIsUncertainAndIsNeverAutomaticallyRetried() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; throw IllegalStateException("connection lost") })
        try {
            assertEquals(WriteResult.Uncertain, DigiCampusProvider(client, { "fixture" }).sendMessage("u1", "Question", "Body"))
            assertEquals(1, calls)
        } finally { client.close() }
    }
    @Test fun foreignReceiptDoesNotConfirmSending() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Created, headersOf(HttpHeaders.Location, "https://example.com/messages/1")) })
        try { assertEquals(WriteResult.Uncertain, DigiCampusProvider(client, { "fixture" }).sendMessage("u1", "Question", "Body")) }
        finally { client.close() }
    }

    @Test fun messageWritesNeverFollowAnUntrustedRedirect() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++; assertEquals("digicampus.uni-augsburg.de", request.url.host)
            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://example.com/steal"))
        }) { followRedirects = true }
        try {
            assertEquals(WriteResult.Uncertain, DigiCampusProvider(client, { "session=private" }).sendMessage("u1", "Question", "Body"))
            assertEquals(1, calls)
        } finally { client.close() }
    }
    @Test fun recipientSearchStopsAtTheFirstPage() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++; assertEquals("Alice Example", request.url.parameters["filter[search]"])
            assertEquals("20", request.url.parameters["page[limit]"])
            respond("""{"data":[{"type":"users","id":"u","attributes":{"formatted-name":"Alice Example"}}],"links":{"next":"https://example.com/steal"}}""", headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json"))
        })
        try { assertEquals("Alice Example", DigiCampusProvider(client, { "session=private" }).findPeople(" Alice Example ").single().name); assertEquals(1, calls) }
        finally { client.close() }
    }

}
