package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ConsultationRepositoryTest {
    private val account = Account("alice", "Alice Student", "alice")
    private val now = 1_763_200_000_000L

    private class OfficeHours(base: CampusProvider = TestProvider()) : CampusProvider by base {
        var slotBooking = ""
        var bookable = true
        var bookResult: WriteResult<String> = WriteResult.Confirmed("b1")
        var bookCalls = 0
        override suspend fun consultations(rangeType: String, rangeId: String, viewerId: String) = listOf(
            ConsultationBlock("k", rangeType, rangeId, "", 1_763_300_000_000L, 1_763_303_600_000L, room = "R 1",
                slots = listOf(ConsultationSlot("s", "k", 1_763_300_000_000L, 1_763_301_800_000L, bookable = bookable, bookingId = slotBooking))))
        override suspend fun bookConsultation(slotId: String, userId: String, reason: String): WriteResult<String> {
            bookCalls++
            if (bookResult is WriteResult.Confirmed) { slotBooking = "b1"; bookable = false }
            return bookResult
        }
        override suspend fun cancelConsultation(bookingId: String, reason: String): WriteResult<Unit> {
            slotBooking = ""; bookable = true; return WriteResult.Confirmed(Unit)
        }
    }

    @Test fun aConfirmedBookingIsRecordedAndCancellingRemovesIt() = runTest {
        val provider = OfficeHours()
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { now })
        repo.refreshConsultations("users", "weber", "Dr. Weber"); runCurrent()
        val block = repo.snapshot.value.consultations.values.single().single()
        repo.bookConsultation(block, block.slots.single(), "Thesis", "request-1"); runCurrent()
        val booking = repo.snapshot.value.consultationBookings.single()
        assertEquals("b1", booking.id); assertEquals("Dr. Weber", booking.rangeName); assertEquals("Thesis", booking.reason)
        assertEquals(WriteStatus.CONFIRMED, repo.snapshot.value.courseworkWrites["write:consultation:s"]?.status)
        repo.cancelConsultation(booking, ""); runCurrent()
        assertTrue(repo.snapshot.value.consultationBookings.isEmpty())
        repo.close(false)
    }

    @Test fun bookingsMadeOrCancelledElsewhereAreReconciled() = runTest {
        val provider = OfficeHours().apply { slotBooking = "web"; bookable = false }
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { now })
        repo.refreshConsultations("users", "weber", "Dr. Weber"); runCurrent()
        assertEquals(listOf("web"), repo.snapshot.value.consultationBookings.map { it.id })
        provider.slotBooking = ""; provider.bookable = true
        repo.refreshConsultations("users", "weber", "Dr. Weber", force = true); runCurrent()
        assertTrue(repo.snapshot.value.consultationBookings.isEmpty())
        repo.close(false)
    }

    @Test fun anUncertainBookingIsNotRecordedOrRetried() = runTest {
        val provider = OfficeHours().apply { bookResult = WriteResult.Uncertain }
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { now })
        repo.refreshConsultations("users", "weber", "Dr. Weber"); runCurrent()
        val block = repo.snapshot.value.consultations.values.single().single()
        repo.bookConsultation(block, block.slots.single(), "", "request-1"); runCurrent()
        assertTrue(repo.snapshot.value.consultationBookings.isEmpty())
        assertEquals(WriteStatus.UNCERTAIN, repo.snapshot.value.courseworkWrites["write:consultation:s"]?.status)
        repo.bookConsultation(block, block.slots.single(), "", "request-1"); runCurrent()
        assertEquals(1, provider.bookCalls)
        repo.close(false)
    }
}
