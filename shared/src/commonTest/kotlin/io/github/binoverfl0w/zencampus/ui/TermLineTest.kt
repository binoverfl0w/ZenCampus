package io.github.binoverfl0w.zencampus.ui

import io.github.binoverfl0w.zencampus.data.previewTerm
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.study.termText
import kotlin.test.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant

class TermLineTest {
    private fun at(date: LocalDate, hour: Int = 0, minute: Int = 0, second: Int = 0) =
        LocalDateTime(date.year, date.month, date.day, hour, minute, second).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
    // As Stud.IP stores it: lectures start at midnight and end at 23:59:59 on their last day.
    private val winter = Semester("ws", "WS 2026/27", at(LocalDate(2026, 10, 1)), at(LocalDate(2027, 3, 31), 23, 59, 59),
        at(LocalDate(2026, 10, 12)), at(LocalDate(2027, 2, 6), 23, 59, 59))
    private fun line(today: LocalDate, countdown: Boolean = true, german: Boolean = false) =
        termText(termStatus(listOf(winter), today)!!, Words(german), countdown)

    @Test fun todayNamesTheLastLectureDayAndCountsWholeWeeks() {
        // 14 Nov 2026 to 6 Feb 2027 is 84 days.
        assertEquals("Lectures until Saturday, 6 February · 12 weeks left", line(LocalDate(2026, 11, 14)))
        assertEquals("Vorlesungen bis Samstag, 6. Februar · noch 12 Wochen", line(LocalDate(2026, 11, 14), german = true))
        assertEquals("Lectures until Saturday, 6 February · 10 days left", line(LocalDate(2027, 1, 27)))
        assertEquals("Lectures until Saturday, 6 February · 1 day left", line(LocalDate(2027, 2, 5)))
        assertEquals("Last day of lectures", line(LocalDate(2027, 2, 6)))
        assertEquals("Lecture-free period", line(LocalDate(2027, 2, 7)))
        assertEquals("Lectures start Monday, 12 October · in 7 days", line(LocalDate(2026, 10, 5)))
        assertEquals("Lectures start Monday, 12 October · tomorrow", line(LocalDate(2026, 10, 11)))
    }

    @Test fun theCalendarShowsOnlyTheDateSoBrowsingWeeksCannotChangeACountdown() {
        assertEquals("Lectures until Saturday, 6 February", line(LocalDate(2026, 12, 14), countdown = false))
        assertEquals(line(LocalDate(2026, 11, 14), countdown = false), line(LocalDate(2027, 1, 20), countdown = false))
    }

    @Test fun previewLecturesFollowTheAugsburgPattern() {
        val winter = previewTerm(LocalDate(2026, 11, 14))
        assertEquals("Wintersemester 2026/27", winter.title); assertEquals("Sommersemester 2026", winter.previousTitle)
        assertEquals(LocalDate(2026, 10, 12), winter.lectureStart); assertEquals(LocalDate(2027, 2, 6), winter.lectureEnd)
        assertEquals(previewTerm(LocalDate(2027, 2, 1)), winter)
        val summer = previewTerm(LocalDate(2027, 5, 3))
        assertEquals("Sommersemester 2027", summer.title); assertEquals("Wintersemester 2026/27", summer.previousTitle)
        assertEquals(LocalDate(2027, 4, 19), summer.lectureStart); assertEquals(LocalDate(2027, 7, 24), summer.lectureEnd)
    }
}
