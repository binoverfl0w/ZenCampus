package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.data.digicampus.TimetableMapper
import io.github.binoverfl0w.zencampus.model.*
import kotlin.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

class TimetableMapperTest {
    private fun stamp(value: String) = JsonApiMapper.timestamp(value)
    private fun resource(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun cycle(
        weekday: Int = 3, start: String = "09:00", end: String = "10:30",
        first: String = "2026-10-14T09:00:00+02:00", last: String = "2027-02-10T09:00:00+01:00",
        interval: Int = 1, exceptions: String = "[]",
    ) = resource("""{"type":"seminar-cycle-dates","id":"cycle-a","attributes":{
        "title":"INF-1002 Algorithms","start":"$start","end":"$end","weekday":$weekday,
        "recurrence":{"FREQ":"WEEKLY","INTERVAL":$interval,"DTSTART":"$first","UNTIL":"$last","EXDATES":$exceptions},
        "locations":["N · 1002"]},"relationships":{"owner":{"data":{"type":"courses","id":"c"}}}}""")

    @Test fun projectsWeeklyClassesUsingTheActualStudipWeekdayAndRoomFields() {
        val events = TimetableMapper.expand(listOf(cycle()), stamp("2026-10-12T00:00:00+02:00"), stamp("2026-10-27T00:00:00+01:00"), null)
        assertEquals(listOf("2026-10-14", "2026-10-21"), events.map { it.date.toString() })
        assertEquals("c", events.first().courseId)
        assertEquals("N · 1002", events.first().location)
        assertEquals(stamp("2026-10-14T09:00:00+02:00"), events.first().start)
    }

    @Test fun honorsBiweeklyIntervalsBoundsAndExceptionDates() {
        val entry = cycle(weekday = 1, first = "2026-10-12T09:00:00+02:00", last = "2026-11-09T09:00:00+01:00", interval = 2,
            exceptions = """{"cancelled":"2026-10-26T09:00:00+01:00"}""")
        val events = TimetableMapper.expand(listOf(entry), stamp("2026-10-05T00:00:00+02:00"), stamp("2026-11-17T00:00:00+01:00"), null)
        assertEquals(listOf("2026-10-12", "2026-11-09"), events.map { it.date.toString() })
    }

    @Test fun doesNotInventOctoberClassesForACycleThatStartsInNovember() {
        val entry = cycle(weekday = 2, first = "2026-11-03T09:00:00+01:00")
        assertTrue(TimetableMapper.expand(listOf(entry), stamp("2026-10-12T00:00:00+02:00"), stamp("2026-10-27T00:00:00+01:00"), null).isEmpty())
    }

    @Test fun keepsTheCampusWallClockTimeAcrossTheDaylightSavingChange() {
        val entry = cycle(weekday = 0, start = "10:00", end = "11:00", first = "2026-10-18T10:00:00+02:00", last = "2026-11-01T10:00:00+01:00")
        val events = TimetableMapper.expand(listOf(entry), stamp("2026-10-12T00:00:00+02:00"), stamp("2026-11-02T00:00:00+01:00"), null)
        assertEquals(listOf("2026-10-18", "2026-10-25", "2026-11-01"), events.map { it.date.toString() })
        assertEquals(169 * 60 * 60_000L, events[1].start - events[0].start)
        assertEquals(events[0].start + 60 * 60_000, events[0].end)
    }

    @Test fun importsPersonalWeeklyTimetableEntriesAndMidnightEndTimes() {
        val entry = resource("""{"type":"schedule-entries","id":"custom","attributes":{
            "title":"Study group","description":"<p>Bring notes</p>","start":"23:00","end":"24:00","weekday":3}}""")
        val events = TimetableMapper.expand(listOf(entry), stamp("2026-10-12T00:00:00+02:00"), stamp("2026-10-27T00:00:00+01:00"), null)
        assertEquals(2, events.size)
        assertEquals("", events.first().courseId)
        assertEquals("Bring notes", events.first().description)
        assertEquals(stamp("2026-10-15T00:00:00+02:00"), events.first().end)
        assertFalse(events.first().occursOn(LocalDate(2026, 10, 15)))
    }

    @Test fun doesNotProjectAFormerSemestersTimetableIntoTheNewSemester() {
        val entry = resource("""{"type":"schedule-entries","id":"old","attributes":{
            "title":"Old slot","start":"10:00","end":"11:00","weekday":2}}""")
        val term = Semester("old", "Summer", stamp("2026-04-01T00:00:00+02:00"), stamp("2026-10-01T00:00:00+02:00"))
        val events = TimetableMapper.expand(listOf(entry), stamp("2026-09-28T00:00:00+02:00"), stamp("2026-10-13T00:00:00+02:00"), term)
        assertEquals(listOf("2026-09-29"), events.map { it.date.toString() })
    }
}
