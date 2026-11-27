package io.github.binoverfl0w.zencampus.model

import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import kotlin.test.*
import kotlinx.datetime.LocalDate

class CalendarSelectionTest {
    private fun stamp(value: String) = JsonApiMapper.timestamp(value)

    @Test fun picksAnEarlierTimetableClassInsteadOfTheCachedNovemberClass() {
        val now = stamp("2026-10-14T08:00:00+02:00")
        val november = CampusEvent("nov", "November lecture", stamp("2026-11-03T10:00:00+01:00"), stamp("2026-11-03T11:30:00+01:00"), "c")
        val today = CampusEvent("schedule:c", "Today's lecture", stamp("2026-10-14T09:00:00+02:00"), stamp("2026-10-14T10:30:00+02:00"), "c")
        assertEquals(today, nextUpcomingEvent(listOf(november, today), now))
    }

    @Test fun allDayCancelledAndFinishedEventsDoNotTakeOverUpNext() {
        val now = stamp("2026-10-14T08:00:00+02:00")
        val events = listOf(
            CampusEvent("holiday", "Holiday", stamp("2026-10-01T00:00:00+02:00"), stamp("2026-11-01T00:00:00+01:00"), allDay = true),
            CampusEvent("cancelled", "Cancelled", now + 1_000, now + 60_000, cancelled = true),
            CampusEvent("past", "Past", now - 60_000, now),
            CampusEvent("next", "Next", now + 3_600_000, now + 5_400_000),
        )
        assertEquals("next", nextUpcomingEvent(events, now)?.id)
        assertTrue(events.first().occursOn(LocalDate(2026, 10, 14)))
    }

    @Test fun overnightEventsAppearOnBothDaysWithoutLeakingPastAMidnightEnd() {
        val overnight = CampusEvent("a", "Study", stamp("2026-10-14T23:00:00+02:00"), stamp("2026-10-15T01:00:00+02:00"))
        assertTrue(overnight.occursOn(LocalDate(2026, 10, 14)))
        assertTrue(overnight.occursOn(LocalDate(2026, 10, 15)))
        assertFalse(overnight.copy(end = stamp("2026-10-15T00:00:00+02:00")).occursOn(LocalDate(2026, 10, 15)))
    }
}
