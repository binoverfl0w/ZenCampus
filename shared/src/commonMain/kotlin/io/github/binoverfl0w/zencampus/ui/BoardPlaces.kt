package io.github.binoverfl0w.zencampus.ui

import androidx.compose.runtime.Composable
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone

/** A board's secondary destinations, which live in the rail, the board sheet and quick search, not the bottom bar. */
enum class BoardPlace { COURSEWORK, UPDATES, OFFICE_HOURS, DOWNLOADS, CALENDAR_EXPORT, RESERVATIONS }

/** The places of [destination]'s board, with their badges and which one is open. */
@Composable
internal fun boardPlaces(destination: CampusDestination, dueCount: Int, bookings: Int, readyHolds: Int, onOpen: (BoardPlace) -> Unit): List<RailLink> {
    val words = LocalWords.current
    val screen = destination.screen
    fun link(place: BoardPlace, id: String, symbol: Symbol, label: String, selected: Boolean, badge: String = "", tone: Tone = Tone.NEUTRAL) =
        RailLink(id, symbol, label, selected, badge, tone) { onOpen(place) }
    return when (destination.board) {
        CampusBoard.STUDY -> listOf(
            link(BoardPlace.COURSEWORK, "coursework", Symbol.TASK, words.study("coursework"),
                screen is Screen.Coursework || screen is Screen.Task || screen is Screen.Assignment, if (dueCount > 0) "$dueCount" else "", Tone.WARN),
            link(BoardPlace.UPDATES, "updates", Symbol.BELL, words.text("updates"),
                screen is Screen.Updates || (screen == Screen.Section(CampusBoard.STUDY, "messages") && destination.inboxView == "updates")),
            link(BoardPlace.OFFICE_HOURS, "officehours", Symbol.PEOPLE, words.ui("office_hours"), screen is Screen.OfficeHours,
                if (bookings > 0) "$bookings" else "", Tone.PRIMARY),
            link(BoardPlace.DOWNLOADS, "downloads", Symbol.DOWNLOAD, words.text("downloads"), screen is Screen.Downloads),
            link(BoardPlace.CALENDAR_EXPORT, "calendar-export", Symbol.CALENDAR, words.ui("export_calendar"), false),
        )
        CampusBoard.LIBRARY -> listOf(
            link(BoardPlace.RESERVATIONS, "holds", Symbol.PIN, words.ui("reservations"), false,
                if (readyHolds > 0) "$readyHolds ${words.ui("ready")}" else "", Tone.SUCCESS),
        )
        CampusBoard.MENSA -> emptyList()
    }
}
