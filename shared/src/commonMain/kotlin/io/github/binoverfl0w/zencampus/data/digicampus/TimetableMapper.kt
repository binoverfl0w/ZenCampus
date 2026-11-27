package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.model.*
import kotlin.time.Instant
import kotlinx.datetime.*
import kotlinx.serialization.json.*

/** Projects Stud.IP's weekday/time slots into campus-local dates, honoring course exceptions. */
internal object TimetableMapper {
    fun expand(resources: List<JsonObject>, from: Long, until: Long, semester: Semester?): List<CampusEvent> {
        val firstDay = Instant.fromEpochMilliseconds(from).toLocalDateTime(CAMPUS_ZONE).date
        val lastDay = Instant.fromEpochMilliseconds(until - 1).toLocalDateTime(CAMPUS_ZONE).date
        return resources.flatMap { resource ->
            val type = resource.text("type")
            if (type != "schedule-entries" && type != "seminar-cycle-dates") return@flatMap emptyList()
            val id = resource.text("id").ifBlank { throw CampusException(FailureKind.INVALID_RESPONSE) }
            val a = resource["attributes"] as? JsonObject ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            val weekday = (a["weekday"] as? JsonPrimitive)?.intOrNull
                ?.takeIf { it in 0..6 } ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            val startMinute = timeMinutes(a.text("start"))
            val endMinute = timeMinutes(a.text("end"))
            if (startMinute == 1440) throw CampusException(FailureKind.INVALID_RESPONSE)
            val recurrence = a["recurrence"] as? JsonObject
            val anchor = recurrence?.text("DTSTART")?.takeIf { it.isNotBlank() }?.let(::date)
            val endDate = recurrence?.text("UNTIL")?.takeIf { it.isNotBlank() }?.let(::date)
            val interval = (recurrence?.get("INTERVAL") as? JsonPrimitive)?.intOrNull ?: 1
            if (interval < 1 || (recurrence != null && recurrence.text("FREQ") != "WEEKLY") ||
                (type == "seminar-cycle-dates" && (anchor == null || endDate == null))) {
                throw CampusException(FailureKind.INVALID_RESPONSE)
            }
            val exceptions = when (val value = recurrence?.get("EXDATES")) {
                is JsonArray -> value.toList()
                is JsonObject -> value.values.toList()
                else -> emptyList()
            }
            val excluded = exceptions
                .map { date((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }.toSet()
            val owner = ((resource["relationships"] as? JsonObject)?.get("owner") as? JsonObject)
                ?.get("data") as? JsonObject
            val courseId = if (owner?.text("type") == "courses") owner.text("id") else ""
            val rooms = when (val value = a["locations"]) {
                is JsonArray -> value.toList()
                is JsonObject -> value.values.toList()
                else -> emptyList()
            }.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }.joinToString(", ")
            val events = mutableListOf<CampusEvent>()
            val termFrom = semester?.let { Instant.fromEpochMilliseconds(it.start).toLocalDateTime(CAMPUS_ZONE).date }
            val termUntil = semester?.let { Instant.fromEpochMilliseconds(it.end - 1).toLocalDateTime(CAMPUS_ZONE).date }
            var day = firstDay
            while (day <= lastDay) {
                val matching = (day.dayOfWeek.ordinal + 1) % 7 == weekday &&
                    (anchor == null || day >= anchor) && (endDate == null || day <= endDate) &&
                    (termFrom == null || day >= termFrom) && (termUntil == null || day <= termUntil) &&
                    day !in excluded && (anchor == null || ((day.toEpochDays() - anchor.toEpochDays()) / 7) % interval == 0L)
                if (matching) {
                    val start = atMinute(day, startMinute)
                    val endDay = if (endMinute <= startMinute) day.plus(1, DateTimeUnit.DAY) else day
                    val end = atMinute(endDay, endMinute)
                    if (start in from until until) events += CampusEvent(
                        "schedule:$type:$id", JsonApiMapper.plainText(a.text("title")), start, end,
                        courseId, rooms.ifBlank { a.text("location") }, JsonApiMapper.plainText(a.text("description")),
                        allDay = startMinute == 0 && (endMinute == 0 || endMinute == 1440),
                    )
                }
                day = day.plus(1, DateTimeUnit.DAY)
            }
            events
        }.sortedBy { it.start }
    }

    private fun date(value: String): LocalDate =
        Instant.fromEpochMilliseconds(JsonApiMapper.timestamp(value)).toLocalDateTime(CAMPUS_ZONE).date

    private fun timeMinutes(value: String): Int {
        if (value == "24:00") return 1440
        val time = try { LocalTime.parse(value) } catch (e: IllegalArgumentException) {
            throw CampusException(FailureKind.INVALID_RESPONSE, e)
        }
        return time.hour * 60 + time.minute
    }

    private fun atMinute(day: LocalDate, minute: Int): Long {
        val date = if (minute == 1440) day.plus(1, DateTimeUnit.DAY) else day
        return date.atTime((minute % 1440) / 60, minute % 60).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
    }
}
