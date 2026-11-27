package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.data.MessagingSource
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.IOException
import kotlinx.serialization.json.*

/** DigiCampus: Messages, people search and contacts. */
internal class DigiCampusMessaging(connection: DigiCampusConnection) : DigiCampusApi(connection), MessagingSource {
    override suspend fun messages(userId: String, sent: Boolean): List<CampusMessage> {
        val page = collection("users/${userId.path()}/${if (sent) "outbox" else "inbox"}", mapOf("include" to if (sent) "sender,recipients" else "sender"))
        val users = page.included.filter { it.text("type") == "users" }.associateBy { it.text("id") }
        return page.resources.map { resource ->
            if (resource.text("type") != "messages") throw CampusException(FailureKind.INVALID_RESPONSE)
            val attrs = resource.attributes()
            val isRead = (attrs["is-read"] as? JsonPrimitive)?.booleanOrNull ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            val date = JsonApiMapper.timestamp(attrs.text("mkdate"))
            val sender = users[resource.relatedId("sender")]?.attributes()
            val name = sender?.text("formatted-name").orEmpty().ifBlank {
                listOf(sender?.text("given-name").orEmpty(), sender?.text("family-name").orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
            }
            val recipientRefs = ((resource["relationships"] as? JsonObject)?.get("recipients") as? JsonObject)?.get("data") as? JsonArray
            val recipientNames = recipientRefs.orEmpty().mapNotNull { ref ->
                val user = users[(ref as? JsonObject)?.text("id")] ?: return@mapNotNull null
                val userAttrs = user.attributes()
                JsonApiMapper.plainText(userAttrs.text("formatted-name").ifBlank {
                    listOf(userAttrs.text("given-name"), userAttrs.text("family-name")).filter { it.isNotBlank() }.joinToString(" ")
                }.ifBlank { userAttrs.text("username") }).takeIf { it.isNotBlank() }
            }
            CampusMessage(resource.requiredId(), JsonApiMapper.plainText(attrs.text("subject")),
                JsonApiMapper.plainText(attrs.text("message")), date, isRead, JsonApiMapper.plainText(name), resource.relatedId("sender"), recipientNames)
        }.sortedByDescending { it.timestamp }
    }

    override suspend fun findPeople(query: String): List<CampusPerson> {
        val clean = query.trim()
        if (clean.length !in 3..100) throw CampusException(FailureKind.INVALID_RESPONSE)
        val url = URLBuilder("$DIGICAMPUS_API/users").apply {
            parameters.append("filter[search]", clean); parameters.append("page[limit]", "20")
        }.buildString()
        val data = getJson(url)["data"] as? JsonArray ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        if (data.size > 20) throw CampusException(FailureKind.INVALID_RESPONSE)
        return data.map { value ->
            val resource = value as? JsonObject ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            if (resource.text("type") != "users") throw CampusException(FailureKind.INVALID_RESPONSE)
            val attrs = resource.attributes()
            val name = attrs.text("formatted-name").ifBlank {
                listOf(attrs.text("given-name"), attrs.text("family-name")).filter { it.isNotBlank() }.joinToString(" ")
            }.ifBlank { attrs.text("username") }
            CampusPerson(resource.requiredId(), JsonApiMapper.plainText(name), attrs.text("username"))
        }.filter { it.name.isNotBlank() }
    }

    override suspend fun sendMessage(recipientId: String, subject: String, body: String): WriteResult<Unit> {
        if (recipientId.isBlank() || subject.isBlank() || subject.length > 300 || body.isBlank() || body.length > 32_000)
            return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val safeBody = body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
        val response = try {
            htmlClient.post("$DIGICAMPUS_API/messages") {
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
                contentType(ContentType.parse("application/vnd.api+json"))
                setBody(buildJsonObject { putJsonObject("data") {
                    put("type", "messages"); putJsonObject("attributes") { put("subject", subject.trim()); put("message", safeBody) }
                    putJsonObject("relationships") { putJsonObject("recipients") { putJsonArray("data") {
                        add(buildJsonObject { put("type", "users"); put("id", recipientId) })
                    } } }
                } }.toString())
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return WriteResult.Uncertain }
        val status = response.status.value
        if (status in setOf(400, 401, 403, 404, 409, 413, 415, 422)) return WriteResult.Rejected(writeFailure(status))
        if (status != 201) return WriteResult.Uncertain
        val location = response.headers[HttpHeaders.Location] ?: return WriteResult.Uncertain
        val url = try { Url(if (location.startsWith("/")) DIGICAMPUS_ORIGIN + location else location) } catch (_: Exception) { return WriteResult.Uncertain }
        if (url.protocol != URLProtocol.HTTPS || url.host != Url(DIGICAMPUS_API).host || url.port != 443 ||
            !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.parameters.names().isNotEmpty() || url.fragment.isNotBlank() ||
            !Regex("/jsonapi.php/v1/messages/[a-zA-Z0-9_-]+").matches(url.encodedPath)) return WriteResult.Uncertain
        return WriteResult.Confirmed(Unit)
    }

    override suspend fun setMessageRead(id: String, read: Boolean): WriteResult<Boolean> {
        if (id.isBlank()) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val response = try {
            htmlClient.patch("$DIGICAMPUS_API/messages/${id.path()}") {
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
                contentType(ContentType.parse("application/vnd.api+json"))
                setBody(buildJsonObject { putJsonObject("data") {
                    put("type", "messages"); put("id", id); putJsonObject("attributes") { put("is-read", read) }
                } }.toString())
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return WriteResult.Uncertain }
        val status = response.status.value
        if (status in setOf(400, 401, 403, 404, 409, 422)) return WriteResult.Rejected(writeFailure(status))
        if (status !in 200..299) return WriteResult.Uncertain
        val resource = try { Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"] as? JsonObject }
            catch (_: Exception) { null }
        val value = (resource?.attributes()?.get("is-read") as? JsonPrimitive)?.booleanOrNull
        return if (resource?.text("id") == id && resource.text("type") == "messages" && value == read)
            WriteResult.Confirmed(read) else WriteResult.Uncertain
    }

    override suspend fun contacts(userId: String): List<CampusPerson> =
        collection("users/${userId.path()}/contacts").resources.map { resource ->
            if (resource.text("type") != "users") throw CampusException(FailureKind.INVALID_RESPONSE)
            val a = resource.attributes()
            val name = a.text("formatted-name").ifBlank {
                listOf(a.text("given-name"), a.text("family-name")).filter { it.isNotBlank() }.joinToString(" ")
            }.ifBlank { a.text("username") }
            CampusPerson(resource.requiredId(), JsonApiMapper.plainText(name), a.text("username"))
        }.filter { it.name.isNotBlank() }.sortedBy { it.name.lowercase() }

    override suspend fun setContact(userId: String, contactId: String, add: Boolean): WriteResult<Unit> {
        if (userId.isBlank() || contactId.isBlank() || userId == contactId) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val body = buildJsonObject { putJsonArray("data") { add(buildJsonObject { put("type", "users"); put("id", contactId) }) } }.toString()
        val response = try {
            val url = "$DIGICAMPUS_API/users/${userId.path()}/relationships/contacts"
            val block: HttpRequestBuilder.() -> Unit = {
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
                contentType(ContentType.parse("application/vnd.api+json")); setBody(body)
            }
            if (add) htmlClient.post(url, block) else htmlClient.delete(url, block)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return WriteResult.Uncertain }
        val status = response.status.value
        if (status in setOf(400, 401, 403, 404, 409, 422)) return WriteResult.Rejected(writeFailure(status))
        return if (status == 204) WriteResult.Confirmed(Unit) else WriteResult.Uncertain
    }

}
