package io.github.binoverfl0w.zencampus.data.digicampus

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

/** One signed-in DigiCampus connection: the HTTP clients, the cookie and the read limiter shared by every area. */
internal class DigiCampusConnection(clientFactory: () -> HttpClient, val cookie: () -> String, val now: () -> Long) {
    val client by lazy(clientFactory)
    val htmlClient by lazy { client.config { followRedirects = false } }
    val readSlots = Semaphore(DigiCampusApi.READ_CONCURRENCY)
}

/**
 * The JSON:API core every area builds on: paginated reads with retries, verified creates and deletes, and the
 * authenticated HTML pages some tools still need. Cookies only go to the fixed DigiCampus origin.
 */
internal abstract class DigiCampusApi(protected val connection: DigiCampusConnection) {
    protected val client get() = connection.client
    protected val htmlClient get() = connection.htmlClient
    protected val cookie: () -> String get() = connection.cookie
    protected val now: () -> Long get() = connection.now
    private val readSlots get() = connection.readSlots

    protected suspend fun forumCreate(type: String, payload: String): WriteResult<Unit> = when (val result = jsonCreate(type, type, payload)) {
        is WriteResult.Confirmed -> WriteResult.Confirmed(Unit)
        is WriteResult.Rejected -> result
        WriteResult.Uncertain -> WriteResult.Uncertain
    }

    /**
     * POSTs a JSON:API document to [endpoint]. Only a 201 with a Location naming a [createdType] resource on the
     * trusted API origin confirms the write; anything else that is not an explicit rejection stays uncertain.
     */
    protected suspend fun jsonCreate(endpoint: String, createdType: String, payload: String): WriteResult<String> {
        val response = try {
            htmlClient.post("$DIGICAMPUS_API/$endpoint") {
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
                contentType(ContentType.parse("application/vnd.api+json")); setBody(payload)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return WriteResult.Uncertain }
        val status = response.status.value
        if (status in setOf(400, 401, 403, 404, 409, 413, 415, 422)) return WriteResult.Rejected(writeFailure(status))
        if (status != 201) return WriteResult.Uncertain
        val location = response.headers[HttpHeaders.Location] ?: return WriteResult.Uncertain
        val url = try { Url(if (location.startsWith("/")) DIGICAMPUS_ORIGIN + location else location) } catch (_: Exception) { return WriteResult.Uncertain }
        val match = Regex("/jsonapi.php/v1/$createdType/([a-zA-Z0-9_-]+)").matchEntire(url.encodedPath)
        if (url.protocol != URLProtocol.HTTPS || url.host != Url(DIGICAMPUS_API).host || url.port != 443 ||
            !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.parameters.names().isNotEmpty() || url.fragment.isNotBlank() ||
            match == null) return WriteResult.Uncertain
        return WriteResult.Confirmed(match.groupValues[1])
    }

    protected suspend fun forumDelete(type: String, id: String): WriteResult<Unit> {
        if (id.isBlank()) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val response = try {
            htmlClient.delete("$DIGICAMPUS_API/$type/${id.path()}") {
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return WriteResult.Uncertain }
        val status = response.status.value
        if (status in setOf(400, 401, 403, 404, 409, 422)) return WriteResult.Rejected(writeFailure(status))
        return if (status == 204) WriteResult.Confirmed(Unit) else WriteResult.Uncertain
    }

    protected suspend fun publicHtml(url: String): String {
        // Public summaries and faculty bookings never carry the student's credentials.
        val response = client.get(url) { header(HttpHeaders.Accept, "text/html"); header(HttpHeaders.AcceptLanguage, "de") }
        if (response.status.value != 200) throw CampusException(if (response.status.value >= 500) FailureKind.SERVER else FailureKind.INVALID_RESPONSE)
        if (response.contentType()?.contentSubtype != "html") throw CampusException(FailureKind.INVALID_RESPONSE)
        return response.bodyAsText().also { if (it.length > 2 * 1024 * 1024) throw CampusException(FailureKind.INVALID_RESPONSE) }
    }

    /**
     * A fragment the official pages load in the background (X-Requested-With), e.g. one group's members. Only fixed
     * DigiCampus page URLs built by the caller reach here.
     */
    protected suspend fun authenticatedFragment(url: String): String {
        val response = htmlClient.get(url) {
            header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "text/html, */*")
            header("X-Requested-With", "XMLHttpRequest")
        }
        validateStatus(response.status.value)
        if (response.status.value != 200 || response.contentType()?.contentSubtype != "html") throw CampusException(FailureKind.INVALID_RESPONSE)
        return response.bodyAsText().also { if (it.length > 512 * 1024) throw CampusException(FailureKind.INVALID_RESPONSE) }
    }

    protected suspend fun authenticatedCourseHtml(url: String): String {
        // Only our fixed overview/list URLs reach here, never a discovered tool's URL.
        val response = htmlClient.get(url) {
            header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "text/html")
            header(HttpHeaders.AcceptLanguage, "de")
        }
        if (response.status.value == 403) {
            val text = response.bodyAsText()
            // A campus-network gate is not evidence that this account lost course permission.
            if ("Zugriff nur aus dem Universitätsnetz" in text || "Restricted university network access" in text)
                throw CampusException(FailureKind.OFFLINE, httpStatus = 403)
        }
        validateStatus(response.status.value)
        if (response.status.value != 200 || response.contentType()?.contentSubtype != "html")
            throw CampusException(FailureKind.INVALID_RESPONSE)
        return response.bodyAsText().also { if (it.length > 2 * 1024 * 1024) throw CampusException(FailureKind.INVALID_RESPONSE) }
    }

    protected fun requireFolderCourse(resource: JsonObject, courseId: String) {
        val range = resource.relation("range")?.get("data") as? JsonObject
        if (range?.text("type") != "courses" || range.text("id") != courseId) throw CampusException(FailureKind.INVALID_RESPONSE)
    }
    protected fun writeFailure(status: Int): FailureKind = when (status) {
        401 -> FailureKind.AUTHENTICATION; 403 -> FailureKind.PERMISSION; 404 -> FailureKind.NOT_FOUND
        413 -> FailureKind.STORAGE; else -> FailureKind.INVALID_RESPONSE
    }

    internal suspend fun collection(
        path: String, queryParameters: Map<String, String> = emptyMap(), paginated: Boolean = true,
    ): JsonApiPage {
        var next: String? = URLBuilder("$DIGICAMPUS_API/$path").apply {
            queryParameters.forEach { (key, value) -> this.parameters.append(key, value) }
            // Stud.IP rejects pagination on single-resource routes, including users/me.
            if (paginated) this.parameters.append("page[limit]", "100")
        }.buildString()
        val visited = mutableSetOf<String>()
        val resources = mutableListOf<JsonObject>()
        val included = mutableListOf<JsonObject>()
        var meta = JsonObject(emptyMap())
        var pages = 0
        while (next != null) {
            val url = trustedPageUrl(next)
            if (!visited.add(url) || ++pages > MAX_PAGES) throw CampusException(FailureKind.INVALID_RESPONSE)
            val document = getJson(url)
            if (pages == 1) meta = document["meta"] as? JsonObject ?: meta
            val data = document["data"] ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            val items = when (data) {
                is JsonArray -> data.map { it as? JsonObject ?: throw CampusException(FailureKind.INVALID_RESPONSE) }
                is JsonObject -> listOf(data)
                JsonNull -> emptyList()
                else -> throw CampusException(FailureKind.INVALID_RESPONSE)
            }
            resources += items
            included += (document["included"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            next = link((document["links"] as? JsonObject)?.get("next"))
        }
        return JsonApiPage(resources.distinctBy { resource ->
            val base = resource.text("type") + ":" + resource.text("id")
            // Calendar recurrences keep their resource ID but have distinct occurrence times.
            if (resource.text("type") in setOf("course-events", "calendar-events")) {
                val attributes = resource.attributes()
                base + ":" + attributes.text("start") + ":" + attributes.text("end")
            } else base
        }, included, meta)
    }

    protected suspend fun getJson(url: String): JsonObject =
        getJsonElement(url) as? JsonObject ?: throw CampusException(FailureKind.INVALID_RESPONSE)

    /**
     * One JSON:API read. Network failures (including a connection dropped while the body is read), timeouts, 429 and
     * 5xx are retried with backoff; at most [READ_CONCURRENCY] reads run at once so a full refresh never floods
     * DigiCampus. Definite answers (401/403/404, non-JSON) are not retried.
     */
    protected suspend fun getJsonElement(url: String): JsonElement {
        var last: CampusException? = null
        for (attempt in 0 until READ_ATTEMPTS) {
            if (attempt > 0) delay(retryDelay(attempt, last?.retryAfterMillis))
            val outcome = readSlots.withPermit {
                try {
                    val response = htmlClient.get(url) {
                        header(HttpHeaders.Accept, "application/vnd.api+json")
                        header(HttpHeaders.Cookie, cookie())
                    }
                    val status = response.status.value
                    if (status == 429 || status == 408 || status in 500..599) {
                        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()
                        val wait = retryAfter?.toLongOrNull()?.times(1000)
                        // A long or unparseable Retry-After is a real outage; waiting it out here would stall the refresh.
                        if (retryAfter != null && (wait == null || wait > MAX_RETRY_AFTER)) throw CampusException(FailureKind.SERVER, httpStatus = status)
                        Result.failure<JsonElement>(CampusException(FailureKind.SERVER, httpStatus = status, retryAfterMillis = wait))
                    } else {
                        validateStatus(status)
                        if (response.contentType()?.contentSubtype?.contains("json") != true) throw CampusException(FailureKind.INVALID_RESPONSE)
                        val text = response.bodyAsText()
                        if (text.length > MAX_JSON_CHARS) throw CampusException(FailureKind.INVALID_RESPONSE)
                        Result.success(try { Json.parseToJsonElement(text) } catch (e: Exception) { throw CampusException(FailureKind.INVALID_RESPONSE, e) })
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: CampusException) { throw e }
                catch (e: Exception) { Result.failure(CampusException(FailureKind.OFFLINE, e)) }
            }
            outcome.onSuccess { return it }
            last = outcome.exceptionOrNull() as? CampusException
        }
        throw last ?: CampusException(FailureKind.SERVER)
    }

    protected fun retryDelay(attempt: Int, retryAfter: Long?): Long =
        retryAfter?.coerceIn(0, MAX_RETRY_AFTER) ?: (BACKOFF_MILLIS[(attempt - 1).coerceAtMost(BACKOFF_MILLIS.lastIndex)] + Random.nextLong(250))


    companion object {
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        const val MAX_PAGES = 50
        const val READ_ATTEMPTS = 4
        const val READ_CONCURRENCY = 4
        const val MAX_RETRY_AFTER = 20_000L
        private val BACKOFF_MILLIS = listOf(600L, 1_800L, 4_500L)
        const val MAX_JSON_CHARS = 8 * 1024 * 1024

        fun trustedPageUrl(value: String): String {
            val resolved = if (value.startsWith("/")) DIGICAMPUS_ORIGIN + value else value
            val url = try { Url(resolved) } catch (e: Exception) { throw CampusException(FailureKind.INVALID_RESPONSE, e) }
            if (url.protocol != URLProtocol.HTTPS || url.host != "digicampus.uni-augsburg.de"
                || url.port != 443 || !url.encodedPath.startsWith("/jsonapi.php/v1/")
                || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) {
                throw CampusException(FailureKind.INVALID_RESPONSE)
            }
            return url.toString()
        }

        fun validateStatus(status: Int) {
            if (status in 200..299) return
            val failure = when {
                status == 401 || status in 300..399 -> FailureKind.AUTHENTICATION
                status == 403 -> FailureKind.PERMISSION
                status == 404 -> FailureKind.NOT_FOUND
                status in setOf(400, 405, 406, 415, 422) -> FailureKind.INVALID_RESPONSE
                else -> FailureKind.SERVER
            }
            throw CampusException(failure, httpStatus = status)
        }
    }
}
