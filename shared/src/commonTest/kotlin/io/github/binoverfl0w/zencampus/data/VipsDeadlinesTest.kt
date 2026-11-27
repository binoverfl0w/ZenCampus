package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VipsDeadlinesTest {
    private val now = Instant.parse("2026-10-21T08:00:00Z").toEpochMilliseconds()
    private fun sheet(id: String = "1", mode: String = "Übung", end: String = "22.10.2026, 10:00") =
        VipsSheet(id, "c", "Sheet $id", "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=$id&cid=c",
            "19.10.2026, 10:00", end, mode, "bearbeitet")
    private fun snapshot(sheets: List<VipsSheet> = listOf(sheet())) = CampusSnapshot(Account("a", "A", "a"),
        courses = listOf(Course("c", "Algorithms")),
        courseTools = mapOf("c" to CourseTools(listOf(CourseTool("vips", "Exercises", vipsSheetsUrl("c"))))),
        vipsSheets = mapOf("c" to VipsSheets(sheets)), updatedAt = mapOf("tools:c" to now, "vips:c" to now))

    @Test fun publishedDatesUseCampusTimeOnBothSidesOfTheAutumnClockChange() {
        assertEquals(Instant.parse("2026-10-22T08:00:00Z").toEpochMilliseconds(), sheet().deadline)
        assertEquals(Instant.parse("2026-10-26T09:00:00Z").toEpochMilliseconds(), sheet(end = "26.10.2026, 10:00").deadline)
        assertEquals(Instant.parse("2026-03-30T08:00:00Z").toEpochMilliseconds(), vipsTimestamp("30.03.2026, 10:00"))
    }

    @Test fun malformedAmbiguousAndNonexistentTimesStayUnverified() {
        listOf("tomorrow", "31.02.2026, 10:00", "21.10.2026, 25:00", "25.10.2026, 02:30", "29.03.2026, 02:30", "10/22/2026 10:00").forEach {
            assertNull(vipsTimestamp(it), it)
            assertNull(sheet(end = it).deadline, it)
            assertFalse(sheet(end = it).datesVerified, it)
        }
        assertFalse(sheet(end = "18.10.2026, 10:00").datesVerified)
        assertNull(sheet(end = "18.10.2026, 10:00").deadline)
        assertFalse(sheet(end = "").datesVerified)
        assertFalse(sheet().copy(starts = "").datesVerified)
    }

    @Test fun oldSerializedCachesGainStructuredDeadlinesWithoutRefetchingOrLosingStatus() {
        val old = """{"id":"1","courseId":"c","title":"Graphs","sourceUrl":"${sheet().sourceUrl}","starts":"19.10.2026, 10:00","ends":"22.10.2026, 10:00","mode":"Übung","status":"bearbeitet"}"""
        val restored = Json.decodeFromString<VipsSheet>(old)
        assertEquals(sheet().deadline, restored.deadline)
        assertEquals("bearbeitet", restored.status)
        assertEquals(restored, Json.decodeFromString<VipsSheet>(Json.encodeToString(restored)))
    }

    @Test fun practiceSelftestExamAndUnknownModesKeepDifferentDateSemantics() {
        listOf("Übung", "Exercises", "Übungsblatt", "Exercise sheet").forEach { assertNotNull(sheet(mode = it).deadline) }
        listOf("Selbsttest", "Self test", "Klausur", "Written examination", "Custom mode").forEach {
            assertNull(sheet(mode = it).deadline)
            assertNotNull(sheet(mode = it).endsAt)
        }
        assertEquals(VipsKind.EXAM, sheet(mode = "Renamed").copy(publishedKind = VipsKind.EXAM).kind)
        assertTrue(sheet(mode = "Self test", end = "").datesVerified)
        assertNull(sheet(mode = "Self test", end = "").endsAt)
    }

    @Test fun todayIncludesUpcomingDatedSheetsAndWindowsOnlyFromSelectedCoursesInDateOrder() {
        val futureExam = sheet("exam", "Klausur", "29.10.2026, 12:00").copy(starts = "22.10.2026, 09:00")
        val current = snapshot().copy(vipsSheets = mapOf("c" to VipsSheets(listOf(sheet(), futureExam,
                sheet("later", end = "26.10.2026, 10:00"), sheet("past", end = "20.10.2026, 10:00"),
                sheet("unlimited", "Selbsttest", ""), sheet("distant", end = "31.10.2026, 10:00"), sheet("invalid", end = "?"))),
                "other-semester" to VipsSheets(listOf(sheet("foreign").copy(courseId = "other-semester")))))
        assertEquals(listOf("exam", "1", "later"), current.upcomingVipsSheets(now).map { it.id })
        assertEquals(futureExam.startsAt, futureExam.attentionAt(now))
        assertEquals(futureExam.endsAt, futureExam.attentionAt(futureExam.startsAt!!))
    }

    @Test fun unknownUnloadedFailedOrMalformedVipsSourcesNeverCountAsChecked() {
        assertTrue(snapshot().vipsDatesChecked(emptyMap()))
        assertTrue(snapshot(emptyList()).vipsDatesChecked(emptyMap()))
        assertFalse(snapshot().copy(courseTools = emptyMap()).vipsDatesChecked(emptyMap()))
        assertFalse(snapshot().copy(vipsSheets = emptyMap()).vipsDatesChecked(emptyMap()))
        assertFalse(snapshot(listOf(sheet(end = "?"))).vipsDatesChecked(emptyMap()))
        for (key in listOf("tools:c", "vips:c")) {
            assertFalse(snapshot().vipsDatesChecked(mapOf(key to FailureKind.OFFLINE)))
            assertFalse(snapshot().vipsDatesChecked(emptyMap(), setOf(key)))
        }
        assertTrue(snapshot().copy(courseTools = mapOf("c" to CourseTools(emptyList())), vipsSheets = emptyMap()).vipsDatesChecked(emptyMap()))
    }

    @Test fun freshPracticeDeadlineRemindersDoNotTreatProgressAsConfirmedSubmission() {
        val reminder = courseworkReminders(snapshot(), emptyMap(), now).single()
        assertEquals("vips:c:1", reminder.id)
        assertEquals("Vips · Sheet 1", reminder.title)
        assertEquals(sheet().deadline, reminder.due)
        assertTrue(courseworkReminders(snapshot(listOf(sheet(mode = "Self test"), sheet("2", "Klausur"))), emptyMap(), now).isEmpty())
    }

    @Test fun remindersRequireFreshSuccessfulToolsAndSheetsAndAnOpenFutureDeadline() {
        for (key in listOf("tools:c", "vips:c")) {
            assertTrue(courseworkReminders(snapshot(), mapOf(key to FailureKind.SERVER), now).isEmpty())
            assertTrue(courseworkReminders(snapshot().copy(updatedAt = snapshot().updatedAt - key), emptyMap(), now).isEmpty())
            assertTrue(courseworkReminders(snapshot().copy(updatedAt = snapshot().updatedAt + (key to now - 300_001)), emptyMap(), now).isEmpty())
        }
        assertTrue(courseworkReminders(snapshot().copy(courseTools = emptyMap()), emptyMap(), now).isEmpty())
        assertTrue(courseworkReminders(snapshot(listOf(sheet().copy(starts = "22.10.2026, 09:00"))), emptyMap(), now).isEmpty())
        assertTrue(courseworkReminders(snapshot(listOf(sheet(end = "21.10.2026, 10:00"))), emptyMap(), now).isEmpty())
        assertTrue(courseworkReminders(snapshot(listOf(sheet(end = "23.10.2026, 10:00"))), emptyMap(), now).isEmpty())
    }

    @Test fun todayWarmsSafeVipsMetadataAndOfflineRestorationRetainsDeadlines() = runTest {
        var calls = 0
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun account() = snapshot().account
            override suspend fun courseTools(courseId: String) = snapshot().courseTools.getValue("c")
            override suspend fun vipsSheets(courseId: String): VipsSheets { calls++; return VipsSheets(listOf(sheet())) }
        }
        val store = MemoryStore(); val account = snapshot().account
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, clock = { now })
        repo.refreshToday(true); runCurrent()
        assertEquals(1, calls)
        assertEquals(sheet().deadline, repo.snapshot.value.upcomingVipsSheets(now).single().deadline)
        repo.close(false)
        val restored = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope,
            conditions = { NetworkConditions(connected = false) }, clock = { now })
        runCurrent()
        assertEquals(sheet().deadline, restored.snapshot.value.listedVipsSheets().single().deadline)
        restored.refreshCourseTools("c", true); runCurrent()
        assertEquals(1, calls)
        assertTrue(restored.courseworkReminders(now).isEmpty())
        restored.close(false)
    }
}
