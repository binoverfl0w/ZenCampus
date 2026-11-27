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

internal data class JsonApiPage(
    val resources: List<JsonObject>, val included: List<JsonObject>, val meta: JsonObject = JsonObject(emptyMap()),
)
internal fun String.path(): String = encodeURLPathPart()
internal fun link(value: JsonElement?): String? = when (value) {
    is JsonPrimitive -> value.contentOrNull
    is JsonObject -> value["href"]?.jsonPrimitive?.contentOrNull
    else -> null
}
internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.flag(key: String, default: Boolean = false): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: default
internal fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0
internal fun JsonObject.attributes(): JsonObject = get("attributes") as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.relation(key: String): JsonObject? =
    ((get("relationships") as? JsonObject)?.get(key) as? JsonObject)
internal fun JsonObject.relatedId(key: String): String = (relation(key)?.get("data") as? JsonObject)?.text("id").orEmpty()
internal fun JsonObject.requiredId(): String = text("id").ifBlank { throw CampusException(FailureKind.INVALID_RESPONSE) }

object JsonApiMapper {
    fun account(resource: JsonObject): Account {
        val a = resource.attributes()
        val givenName = plainText(a.text("given-name"))
        val fullName = plainText(a.text("formatted-name")).ifBlank {
            listOf(givenName, plainText(a.text("family-name"))).filter { it.isNotBlank() }.joinToString(" ")
        }.ifBlank { a.text("username") }
        return Account(resource.requiredId(), fullName, a.text("username"), givenName = givenName)
    }

    fun semester(resource: JsonObject): Semester {
        val a = resource.attributes()
        return Semester(resource.requiredId(), a.text("title"), timestamp(a.text("start")), timestamp(a.text("end")),
            timestampOr(a.text("start-of-lectures"), 0), timestampOr(a.text("end-of-lectures"), 0))
    }

    fun course(resource: JsonObject, included: List<JsonObject> = emptyList()): Course {
        val a = resource.attributes()
        val lecturers = (resource.relation("lecturers")?.get("data") as? JsonArray).orEmpty()
        val teacher = lecturers.mapNotNull { ref ->
            val id = (ref as? JsonObject)?.text("id")
            included.firstOrNull { it.text("id") == id && it.text("type") == "users" }
                ?.attributes()?.text("formatted-name")
        }.joinToString(", ")
        val id = resource.requiredId()
        val semTypeId = resource.relatedId("sem-type")
        val typeName = included.firstOrNull { it.text("type") == "sem-types" && it.text("id") == semTypeId }
            ?.attributes()?.text("name").orEmpty().ifBlank {
                // Older Stud.IP versions publish an institution-specific integer rather than a label.
                a.text("course-type").takeUnless { it.toIntOrNull() != null }.orEmpty()
            }
        return Course(id, a.text("title").ifBlank { a.text("name").ifBlank { id } }, a.text("subtitle"),
            a.text("course-number"), plainText(a.text("description")), a.text("location"), teacher,
            resource.relatedId("start-semester"), resource.relatedId("end-semester"), (id.hashCode() and Int.MAX_VALUE) % 4,
            plainText(typeName))
    }

    fun event(resource: JsonObject, fallbackCourseId: String = ""): CampusEvent {
        val a = resource.attributes()
        val owner = resource.relation("owner")?.get("data") as? JsonObject
        val course = if (owner?.text("type") == "courses") owner.text("id") else fallbackCourseId
        val start = timestamp(a.text("start"))
        val end = timestamp(a.text("end"))
        val midnightSpan = end > start &&
            Instant.fromEpochMilliseconds(start).toLocalDateTime(CAMPUS_ZONE).time == LocalTime(0, 0) &&
            Instant.fromEpochMilliseconds(end).toLocalDateTime(CAMPUS_ZONE).time == LocalTime(0, 0)
        return CampusEvent(resource.requiredId(), plainText(a.text("title")), start, end,
            course, a.text("location"), plainText(a.text("description")), a.flag("is-cancelled"),
            a.flag("all-day") || a.flag("is-all-day") || midnightSpan)
    }

    fun announcement(resource: JsonObject, courseId: String): Announcement {
        val a = resource.attributes()
        return Announcement(resource.requiredId(), courseId, a.text("title"), plainText(a.text("content")),
            timestampOr(a.text("publication-start"), timestampOr(a.text("mkdate"), 0)),
            timestampOr(a.text("publication-end"), Long.MAX_VALUE), a.flag("comments-allowed"))
    }

    fun folder(resource: JsonObject, courseId: String, parentId: String): CampusFolder {
        val a = resource.attributes()
        val data = a["data-content"] as? JsonObject
        fun time(key: String) = data?.text(key)?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
        val permission = (data?.get("permission") as? JsonPrimitive)?.intOrNull
        return CampusFolder(resource.requiredId(), courseId, a.text("name"), resource.relatedId("parent").ifBlank { parentId },
            plainText(a.text("description")), a.flag("is-readable", true), a.text("folder-type"),
            (a["is-writable"] as? JsonPrimitive)?.booleanOrNull,
            ((resource.relation("file-refs")?.get("meta") as? JsonObject)?.get("count") as? JsonPrimitive)
                ?.intOrNull?.takeIf { it >= 0 },
            availableFrom = if (a.text("folder-type") == "TimedFolder") time("start_time") else null,
            availableUntil = if (a.text("folder-type") == "TimedFolder") time("end_time")?.plus(60_000L) else null,
            uploadOnly = a.text("folder-type") == "TimedFolder" && permission != null && permission and 2 != 0 && permission and 4 == 0,
            created = timestampOr(a.text("mkdate"), 0).coerceAtLeast(0), modified = timestampOr(a.text("chdate"), 0).coerceAtLeast(0))
    }

    fun document(resource: JsonObject, courseId: String, folderId: String): Document {
        val a = resource.attributes()
        return Document(resource.requiredId(), courseId, folderId, a.text("name"),
            a.number("filesize"), a.text("mime-type").ifBlank { "application/octet-stream" },
            timestampOr(a.text("chdate"), 0), a.flag("is-downloadable", true),
            (resource["meta"] as? JsonObject)?.text("download-url").orEmpty(),
            created = timestampOr(a.text("mkdate"), 0).coerceAtLeast(0), ownerId =
                ((resource.relation("owner")?.get("data") as? JsonObject)?.takeIf { it.text("type") == "users" }?.text("id")).orEmpty())
    }

    fun timestamp(value: String): Long = parseTimestamp(value)
        ?: throw CampusException(FailureKind.INVALID_RESPONSE)
    private fun timestampOr(value: String, fallback: Long): Long = parseTimestamp(value) ?: fallback
    private fun parseTimestamp(value: String): Long? {
        value.toLongOrNull()?.let { return if (it < 100_000_000_000) it * 1000 else it }
        return try { Instant.parse(value).toEpochMilliseconds() } catch (_: IllegalArgumentException) { null }
    }

    /** Remote markup is displayed as text; scripts/styles never reach a WebView. */
    fun plainText(html: String): String = html
        .replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<a\\b[^>]*href\\s*=\\s*(['\"])(.*?)\\1[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)) { match ->
            val rawHref = match.groupValues[2]
            val href = when {
                rawHref.startsWith("//") -> "https:$rawHref"
                rawHref.startsWith("/") -> "$DIGICAMPUS_ORIGIN$rawHref"
                else -> rawHref
            }
            val label = match.groupValues[3]
            if (href.startsWith("https://") || href.startsWith("http://")) "$label ($href)" else label
        }
        .replace(Regex("<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\n[\\t ]*\n[\\t ]*\n"), "\n\n").trim()
}

