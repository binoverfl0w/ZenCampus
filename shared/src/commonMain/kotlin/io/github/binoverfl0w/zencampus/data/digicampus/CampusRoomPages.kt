package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.model.*
import com.fleeksoft.ksoup.Ksoup
import kotlin.time.Instant
import kotlinx.datetime.*
import kotlinx.serialization.json.*

/** The same room search and dated booking feed used by Stud.IP's own resource calendar. */
internal object CampusRoomPages {
    fun search(html: String, query: String): CampusRoomResults {
        val page = Ksoup.parse(html)
        if (page.selectFirst("input[type=password]") != null) throw CampusException(FailureKind.AUTHENTICATION)
        if (page.selectFirst("input[name=special__room_name]")?.attr("value")?.trim() != query.trim())
            throw CampusException(FailureKind.INVALID_RESPONSE)
        if (page.select(".messagebox_error").isNotEmpty()) throw CampusException(FailureKind.INVALID_RESPONSE)
        val records = page.select("td[data-range_type=Room][data-id]")
        val rooms = records.map { handle ->
            val cells = handle.parent()?.children() ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            if (cells.size < 5) throw CampusException(FailureKind.INVALID_RESPONSE)
            val id = handle.attr("data-id")
            val name = cells[1].text().trim()
            if (!Regex("[A-Za-z0-9_-]{1,100}").matches(id) || name.isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
            val link = cells[1].selectFirst("a[href]")?.attr("href").orEmpty()
            val absolute = when {
                link.startsWith("/dispatch.php/") -> DIGICAMPUS_ORIGIN + link
                link.startsWith("dispatch.php/") -> "$DIGICAMPUS_ORIGIN/$link"
                else -> link
            }.substringBefore('?')
            // A missing link means the source does not offer this account a booking plan.
            val candidate = CampusRoom(id, name, cells[2].text().trim(), cells[3].text().trim().toIntOrNull(), absolute)
            if (link.isNotBlank() && !validCampusRoom(candidate)) throw CampusException(FailureKind.INVALID_RESPONSE)
            candidate
        }
        if (rooms.size > 500 || rooms.distinctBy { it.id }.size != rooms.size) throw CampusException(FailureKind.INVALID_RESPONSE)
        if (rooms.isEmpty() && page.select(".messagebox_info").none {
            it.text().trim() in setOf("Es wurden keine Räume gefunden, die zu den angegebenen Suchkriterien passen!",
                "No rooms could be found that match the specified search criteria!")
        }) throw CampusException(FailureKind.INVALID_RESPONSE)
        return CampusRoomResults(query.trim(), rooms, CAMPUS_ROOM_SEARCH_VERSION)
    }

    fun bookings(json: String, room: CampusRoom, monday: LocalDate): CampusRoomWeek {
        if (!validCampusRoom(room)) throw CampusException(FailureKind.INVALID_RESPONSE)
        val data = try { Json.parseToJsonElement(json) as? JsonArray } catch (_: Exception) { null }
            ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        if (data.size > 5000) throw CampusException(FailureKind.INVALID_RESPONSE)
        val from = monday.atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
        val until = monday.plus(7, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()
        val entries = data.mapIndexedNotNull { index, element ->
            val value = element as? JsonObject ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            fun text(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val sourceRoom = text("studip_range_id").ifBlank { text("resourceId") }
            if (sourceRoom.isNotBlank() && sourceRoom != room.id) throw CampusException(FailureKind.INVALID_RESPONSE)
            val allDay = (value["allDay"] as? JsonPrimitive)?.booleanOrNull ?: false
            val start = timestamp(text("start"), allDay, false)
            val end = timestamp(text("end"), allDay, true)
            if (end <= start) throw CampusException(FailureKind.INVALID_RESPONSE)
            if (start >= until || end <= from) return@mapIndexedNotNull null
            val title = Ksoup.parseBodyFragment(text("title")).text().trim().ifBlank { "—" }
            PlannerEntry(CampusEvent("room:${room.id}:${text("studip_object_id")}:$start:$end:$index", title, start, end,
                location = room.name, allDay = allDay), roomId = "campus:${room.id}", sourceUrl = room.sourceUrl)
        }.distinctBy { listOf(it.event.start, it.event.end, it.event.title) }.sortedBy { it.event.start }
        return CampusRoomWeek(room, from, until, entries)
    }

    private fun timestamp(value: String, allDay: Boolean, end: Boolean): Long {
        if (value.isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
        if (allDay) return runCatching { LocalDate.parse(value).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds() }
            .getOrElse { throw CampusException(FailureKind.INVALID_RESPONSE) }
        runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()?.let { return it }
        val local = runCatching { LocalDateTime.parse(value) }.getOrNull() ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        val instant = local.toInstant(CAMPUS_ZONE).toEpochMilliseconds()
        // The official feed deliberately omits offsets. Cover both repeated autumn hours;
        // reject nonexistent spring times instead of offering a false free interval.
        val candidates = listOf(instant - 3_600_000, instant, instant + 3_600_000).filter {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(CAMPUS_ZONE) == local
        }
        return (if (end) candidates.maxOrNull() else candidates.minOrNull()) ?: throw CampusException(FailureKind.INVALID_RESPONSE)
    }
}
