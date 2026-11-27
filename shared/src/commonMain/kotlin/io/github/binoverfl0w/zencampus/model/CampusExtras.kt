package io.github.binoverfl0w.zencampus.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlinx.datetime.toLocalDateTime

/** A public or mandatory holiday as published by DigiCampus for Augsburg. */
@Serializable data class Holiday(val date: LocalDate, val name: String, val mandatory: Boolean = false)

/** An office-hours block of a lecturer, course or institute with its bookable slots. */
@Serializable data class ConsultationBlock(
    val id: String, val rangeType: String, val rangeId: String, val rangeName: String = "",
    val start: Long, val end: Long, val room: String = "", val note: String = "", val size: Int = 1,
    /** Stud.IP publishes "yes", "no" or "optional". */
    val requireReason: String = "optional", val confirmationText: String = "",
    val slots: List<ConsultationSlot> = emptyList(),
) { val reasonRequired: Boolean get() = requireReason == "yes" }

@Serializable data class ConsultationSlot(
    val id: String, val blockId: String, val start: Long, val end: Long, val note: String = "",
    val bookable: Boolean = false, val locked: Boolean = false,
    /** The current user's booking in this slot, when the source exposes it. */
    val bookingId: String = "",
)

/** A booking made by this account, kept locally so it stays visible even when slot bookings are not readable. */
@Serializable data class ConsultationBooking(
    val id: String, val slotId: String, val blockId: String, val rangeId: String, val rangeName: String,
    val start: Long, val end: Long, val room: String = "", val reason: String = "", val rangeType: String = "users",
)

@Serializable data class NewsComment(val id: String, val author: String, val content: String, val timestamp: Long)

/** Where the lecture period stands today; null outside a known semester. */
data class TermStatus(val semester: Semester, val phase: TermPhase, val daysUntilChange: Int)
enum class TermPhase { BEFORE_LECTURES, LECTURES, AFTER_LECTURES }

fun termStatus(semesters: List<Semester>, today: LocalDate): TermStatus? {
    val semester = semesters.firstOrNull { it.lectureStart > 0 && it.lectureEnd > 0 &&
        campusToday(it.start) <= today && today <= campusToday(it.end) } ?: return null
    val start = campusToday(semester.lectureStart)
    val end = campusToday(semester.lectureEnd)
    fun days(target: LocalDate): Int = (target.toEpochDays() - today.toEpochDays()).toInt()
    return when {
        today < start -> TermStatus(semester, TermPhase.BEFORE_LECTURES, days(start))
        today <= end -> TermStatus(semester, TermPhase.LECTURES, days(end))
        else -> TermStatus(semester, TermPhase.AFTER_LECTURES, days(campusToday(semester.end)))
    }
}

/** Holidays in [from, to] inclusive, sorted. */
fun holidaysBetween(holidays: List<Holiday>, from: LocalDate, to: LocalDate): List<Holiday> =
    holidays.filter { it.date in from..to }.sortedBy { it.date }

/** Bookings still ahead of [now], earliest first. */
fun upcomingBookings(bookings: List<ConsultationBooking>, now: Long): List<ConsultationBooking> =
    bookings.filter { it.end > now }.sortedBy { it.start }

/** A booking rendered like a class so it can join Today's timeline and the calendar. */
fun ConsultationBooking.asEvent(officeHoursLabel: String): CampusEvent =
    CampusEvent("consultation:$id", "$officeHoursLabel · $rangeName", start, end, location = room, description = reason)

internal fun LocalDate.nextDay(): LocalDate = plus(1, DateTimeUnit.DAY)
internal fun Long.campusDate(): LocalDate = Instant.fromEpochMilliseconds(this).toLocalDateTime(CAMPUS_ZONE).date
