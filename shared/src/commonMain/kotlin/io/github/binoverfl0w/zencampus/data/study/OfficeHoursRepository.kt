package io.github.binoverfl0w.zencampus.data.study

import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.CONSULTATION_TTL
import io.github.binoverfl0w.zencampus.data.study.CampusRepository.Companion.consultationKey
import io.github.binoverfl0w.zencampus.model.*
import kotlin.reflect.typeOf
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json

/**
 * Office hours: consultation slots, bookings and their reconciliation.
 *
 * Part of [CampusRepository]: each operation runs with the repository as receiver (`with(repo)`), so the shared
 * cache engine, provider, store and other areas are reached exactly as from the repository itself.
 */
internal class OfficeHoursRepository(private val repo: CampusRepository) {
    /** Loads the current office-hours blocks of one owner and reconciles this account's bookings with them. */
    suspend fun refreshConsultations(rangeType: String, rangeId: String, rangeName: String, force: Boolean = false) { with(repo) {
        val key = CacheKeys.consultations(rangeType, rangeId)
        refresh(key, CONSULTATION_TTL, force) {
            provider.consultations(rangeType, rangeId, account.id).map { it.copy(rangeName = rangeName.ifBlank { it.rangeName }) }
        }
        val blocks = stored<List<ConsultationBlock>>(key) ?: return
        reconcileBookings(blocks, rangeName)
    } }

    /** Lecturers and tutors of the selected semester's courses, loaded with bounded concurrency. */
    suspend fun refreshCourseStaff(force: Boolean = false) = with(repo) { supervisorScope {
        selectedStoredCourses().map { course -> launch { courseworkSlots.withPermit { refreshCommunity(course.id, "people", force = force) } } }.joinAll()
    }
    }

    private suspend fun reconcileBookings(blocks: List<ConsultationBlock>, rangeName: String) = with(repo) { commitMutex.withLock {
        if (!active) return@withLock
        val current = stored<List<ConsultationBooking>>("consultation-bookings").orEmpty()
        val slots = blocks.flatMap { block -> block.slots.map { block to it } }
        val bySlot = slots.associateBy { it.second.id }
        // Bookings the source shows as ours but we did not record (made elsewhere) are added.
        val found = slots.filter { it.second.bookingId.isNotBlank() && current.none { booking -> booking.id == it.second.bookingId } }
            .map { (block, slot) -> ConsultationBooking(slot.bookingId, slot.id, block.id, block.rangeId, rangeName.ifBlank { block.rangeName },
                slot.start, slot.end, block.room, rangeType = block.rangeType) }
        // A recorded booking whose slot is visible, bookable and not ours any more was cancelled elsewhere.
        val kept = current.filterNot { booking ->
            val slot = bySlot[booking.slotId]?.second
            slot != null && slot.bookingId != booking.id && slot.bookable
        }
        val next = (kept + found).filter { it.end > clock() - 86_400_000 }.distinctBy { it.id }
        if (next != current) store.put(account.cacheScope, "consultation-bookings", json.encodeToString(next), clock())
    }
    }

    suspend fun bookConsultation(block: ConsultationBlock, slot: ConsultationSlot, reason: String, requestId: String) { with(repo) {
        val key = "write:consultation:${slot.id}"
        studentWrite(key, "", slot.id, block.rangeName, requestId, action = { provider.bookConsultation(slot.id, account.id, reason) }) { bookingId ->
            commitMutex.withLock {
                val current = stored<List<ConsultationBooking>>("consultation-bookings").orEmpty()
                val booking = ConsultationBooking(bookingId, slot.id, block.id, block.rangeId, block.rangeName, slot.start, slot.end, block.room, reason.trim(), block.rangeType)
                store.put(account.cacheScope, "consultation-bookings", json.encodeToString((current.filterNot { it.id == bookingId } + booking)), clock())
            }
            null
        }
        if (stored<CourseworkWrite>(key)?.status != WriteStatus.UNCERTAIN) refreshConsultations(block.rangeType, block.rangeId, block.rangeName, force = true)
    } }

    suspend fun cancelConsultation(booking: ConsultationBooking, reason: String, rangeType: String = booking.rangeType) { with(repo) {
        val key = "write:consultation-cancel:${booking.id}"
        studentWrite(key, "", booking.id, booking.rangeName, "", action = { provider.cancelConsultation(booking.id, reason) }) {
            commitMutex.withLock {
                val current = stored<List<ConsultationBooking>>("consultation-bookings").orEmpty()
                store.put(account.cacheScope, "consultation-bookings", json.encodeToString(current.filterNot { it.id == booking.id }), clock())
            }
            null
        }
        if (stored<CourseworkWrite>(key)?.status != WriteStatus.UNCERTAIN) refreshConsultations(rangeType, booking.rangeId, booking.rangeName, force = true)
    } }
}
