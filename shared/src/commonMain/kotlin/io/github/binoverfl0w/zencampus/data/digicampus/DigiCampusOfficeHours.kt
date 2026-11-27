package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.data.OfficeHoursSource
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

/** DigiCampus: Office hours and bookings. */
internal class DigiCampusOfficeHours(connection: DigiCampusConnection) : DigiCampusApi(connection), OfficeHoursSource {
    override suspend fun consultations(rangeType: String, rangeId: String, viewerId: String): List<ConsultationBlock> {
        if (rangeType !in setOf("users", "courses", "institutes") || rangeId.isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
        // Without a filter Stud.IP returns no blocks; "current" excludes expired ones.
        val blocks = collection("$rangeType/${rangeId.path()}/consultations", mapOf("filter[current]" to "1")).resources
        return blocks.map { block ->
            if (block.text("type") != "consultation-blocks") throw CampusException(FailureKind.INVALID_RESPONSE)
            val a = block.attributes()
            val blockId = block.requiredId()
            val page = collection("consultation-blocks/${blockId.path()}/slots", mapOf("include" to "bookings"))
            val bookings = page.included.filter { it.text("type") == "consultation-bookings" }.associateBy { it.text("id") }
            val slots = page.resources.map { slot ->
                if (slot.text("type") != "consultation-slots") throw CampusException(FailureKind.INVALID_RESPONSE)
                val sa = slot.attributes()
                val refs = ((slot["relationships"] as? JsonObject)?.get("bookings") as? JsonObject)?.get("data") as? JsonArray
                val mine = refs.orEmpty().mapNotNull { (it as? JsonObject)?.text("id") }.firstOrNull { id ->
                    viewerId.isNotBlank() && bookings[id]?.relatedId("user") == viewerId
                }.orEmpty()
                ConsultationSlot(slot.requiredId(), blockId, JsonApiMapper.timestamp(sa.text("start_time")), JsonApiMapper.timestamp(sa.text("end_time")),
                    JsonApiMapper.plainText(sa.text("note")), sa.flag("is-bookable"), sa.flag("is-locked"), mine)
            }.sortedBy { it.start }
            ConsultationBlock(blockId, rangeType, rangeId, start = JsonApiMapper.timestamp(a.text("start")), end = JsonApiMapper.timestamp(a.text("end")),
                room = JsonApiMapper.plainText(a.text("room")), note = JsonApiMapper.plainText(a.text("note")),
                size = (a["size"] as? JsonPrimitive)?.intOrNull ?: 1,
                requireReason = when (val reason = a["require-reason"]) {
                    is JsonPrimitive -> reason.booleanOrNull?.let { if (it) "yes" else "no" } ?: reason.contentOrNull.orEmpty().ifBlank { "optional" }
                    else -> "optional"
                },
                confirmationText = JsonApiMapper.plainText(a.text("confirmation-text")), slots = slots)
        }.sortedBy { it.start }
    }

    override suspend fun bookConsultation(slotId: String, userId: String, reason: String): WriteResult<String> {
        if (slotId.isBlank() || userId.isBlank() || reason.length > 2_000) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val payload = buildJsonObject { putJsonObject("data") {
            put("type", "consultation-bookings")
            putJsonObject("attributes") { put("reason", reason.trim()) }
            putJsonObject("relationships") {
                putJsonObject("slot") { putJsonObject("data") { put("type", "consultation-slots"); put("id", slotId) } }
                putJsonObject("user") { putJsonObject("data") { put("type", "users"); put("id", userId) } }
            }
        } }.toString()
        return jsonCreate("consultation-slots/${slotId.path()}/bookings", "consultation-bookings", payload)
    }

    override suspend fun cancelConsultation(bookingId: String, reason: String): WriteResult<Unit> {
        if (bookingId.isBlank()) return WriteResult.Rejected(FailureKind.INVALID_RESPONSE)
        val response = try {
            htmlClient.delete("$DIGICAMPUS_API/consultation-bookings/${bookingId.path()}") {
                header(HttpHeaders.Cookie, cookie()); header(HttpHeaders.Accept, "application/vnd.api+json")
                if (reason.isNotBlank()) {
                    contentType(ContentType.parse("application/vnd.api+json"))
                    setBody(buildJsonObject { putJsonObject("data") { put("type", "consultation-bookings"); put("id", bookingId)
                        putJsonObject("attributes") { put("reason", reason.trim().take(2_000)) } } }.toString())
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return WriteResult.Uncertain }
        val status = response.status.value
        if (status in setOf(400, 401, 403, 404, 409, 422)) return WriteResult.Rejected(writeFailure(status))
        return if (status == 204) WriteResult.Confirmed(Unit) else WriteResult.Uncertain
    }

}
