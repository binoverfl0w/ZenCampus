package io.github.binoverfl0w.zencampus.ui

import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.services.price
import io.github.binoverfl0w.zencampus.ui.design.courseInitials
import io.github.binoverfl0w.zencampus.ui.study.courseworkAttention
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/**
 * Everything the home-screen widgets show, as ready-to-display text. Built from cached data only, so a widget never waits
 * for the network and never shows anything the app has not loaded. Each platform only lays it out.
 */
@Serializable
data class HomeWidgets(
    val signedIn: Boolean,
    /** "Friday, 20 November" */
    val date: String,
    /** "9° · rain 63%", or null without a forecast. */
    val weather: String?,
    val nextClass: WidgetClass?,
    val due: List<WidgetDue>,
    /** All open items, of which [due] is the start. */
    val dueCount: Int,
    val mensa: WidgetMensa,
    val library: WidgetLibrary,
    val labels: WidgetLabels,
    /** When the text goes stale (a class starts or ends, a day begins), so the platform can refresh then. */
    val refreshAt: Long,
)

/** The next class or appointment; [running] while it is on. */
@Serializable
data class WidgetClass(
    val initials: String, val title: String, val running: Boolean,
    /** "10:00" */
    val start: String,
    /** "10:00 – 11:30" */
    val range: String,
    /** "Now", "Today", "Tomorrow" or "Mon 23 Nov" */
    val day: String,
    val location: String,
)

@Serializable
data class WidgetDue(
    val initials: String, val title: String, val course: String,
    /** "Today 23:59", "Tomorrow 10:00", "Wed 25 Nov · 10:00" or "Overdue" */
    val due: String,
    /** Due within a day, or overdue. */
    val urgent: Boolean,
)

@Serializable
data class WidgetMensa(
    val meals: List<WidgetMeal>,
    /** All dishes shown in the app with the student's filters, of which [meals] is the start. */
    val total: Int,
    /** Why there are no dishes ("Closed today", "No menu today"), or null when there are. */
    val status: String?,
)

@Serializable
data class WidgetMeal(val name: String, val price: String, val favorite: Boolean, val diet: String?)

@Serializable
data class WidgetLibrary(
    val connected: Boolean,
    /** "2 loans · next due 3 Dec", or the invitation to connect. */
    val headline: String,
    /** "1 ready to pick up", "Overdue: …" or "". */
    val detail: String,
    /** A loan is due within two days or overdue, or a book waits to be picked up. */
    val attention: Boolean,
)

@Serializable
data class WidgetLabels(
    val now: String, val next: String, val nextClass: String, val until: String,
    val due: String, val nothingDue: String, val noClasses: String,
    val mensa: String, val library: String, val signIn: String,
    /** "+%d more" */
    val more: String,
)

@Serializable
internal data class WidgetFrame(val at: Long, val content: HomeWidgets)

/** Prepare time-based changes from the saved data for hosts that render a timeline outside the app. */
internal fun homeWidgetFrames(
    snapshot: CampusSnapshot?, menu: MensaMenu?, library: LibraryState, preferences: CampusServicePreferences,
    now: Long, words: Words,
): List<WidgetFrame> = buildList {
    var at = now
    repeat(32) {
        val content = homeWidgets(snapshot, menu, library, preferences, at, words)
        add(WidgetFrame(at, content))
        if (content.refreshAt <= at || content.refreshAt >= now + 86_400_000) return@buildList
        // A deadline is overdue only after its timestamp.
        at = content.refreshAt + 1
    }
}

fun homeWidgets(
    snapshot: CampusSnapshot?, menu: MensaMenu?, library: LibraryState, preferences: CampusServicePreferences,
    now: Long, words: Words,
): HomeWidgets {
    val today = campusToday(now)
    val tomorrow = today.plus(1, DateTimeUnit.DAY)
    fun dayLabel(date: LocalDate) = when (date) {
        today -> words.ui("today"); tomorrow -> words.ui("tomorrow")
        else -> "${words.shortDay(date)} ${words.shortDate(date)}"
    }
    fun courseTitle(id: String?) = snapshot?.courses?.firstOrNull { it.id == id }?.title
    var refreshAt = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()

    val nextClass = snapshot?.let {
        val events = it.events + it.consultationBookings.map { booking -> booking.asEvent(words.ui("office_hours")) }
        nextUpcomingEvent(events, now)
    }?.let { event ->
        val running = event.start <= now
        refreshAt = minOf(refreshAt, if (running) event.end else event.start)
        val title = courseTitle(event.courseId) ?: event.title
        WidgetClass(courseInitials(title), title, running, words.time(event.start),
            "${words.time(event.start)} – ${words.time(event.end)}",
            if (running) words.ui("widget_now") else dayLabel(event.date), event.location)
    }

    val attention = snapshot?.let { courseworkAttention(it, now) }.orEmpty()
    val due = attention.mapNotNull { item ->
        val (courseId, title) = item.task?.let { it.courseId to it.title }
            ?: item.folder?.let { it.courseId to it.name }
            ?: item.sheet?.let { it.courseId to it.title } ?: return@mapNotNull null
        val course = courseTitle(courseId).orEmpty()
        val at = item.due
        item.due?.takeIf { it > now }?.let { refreshAt = minOf(refreshAt, it) }
        val text = when {
            at == null -> words.study("no_due")
            at < now -> words.ui("overdue")
            campusToday(at) == today || campusToday(at) == tomorrow -> "${dayLabel(campusToday(at))} ${words.time(at)}"
            else -> "${dayLabel(campusToday(at))} · ${words.time(at)}"
        }
        WidgetDue(courseInitials(course.ifBlank { title }), title, course, text, at != null && at < now + 86_400_000)
    }

    val day = menu?.days?.firstOrNull { it.date == today }
    val dishes = day?.meals.orEmpty().filter { it.visibleWith(preferences) }.sortedByDescending { it.isFavorite(preferences) }
    val mensa = WidgetMensa(
        dishes.map { meal ->
            WidgetMeal(words.mealName(meal, preferences), words.price(meal.prices.forGroup(preferences.priceGroup)),
                meal.isFavorite(preferences), when { meal.vegan -> words.service("vegan"); meal.vegetarian -> words.service("vegetarian"); else -> null })
        },
        dishes.size,
        when {
            day?.availability == MenuAvailability.CLOSED -> words.ui("widget_closed")
            dishes.isEmpty() -> words.ui("widget_no_menu")
            else -> null
        },
    )

    val account = library.account
    val loans = account?.loans.orEmpty().sortedBy { it.dueDate ?: LocalDate(9999, 1, 1) }
    val ready = account?.holds.orEmpty().count { it.ready }
    val overdue = loans.count { it.dueDate?.let { date -> date < today } == true }
    val libraryWidget = when {
        !library.connected -> WidgetLibrary(false, words.ui("widget_library_connect"), "", false)
        account == null -> WidgetLibrary(true, words.ui("widget_library_unread"), "", false)
        else -> WidgetLibrary(true,
            listOfNotNull(
                if (loans.isEmpty()) words.ui("widget_no_loans") else words.ui(if (loans.size == 1) "widget_loan" else "widget_loans").replace("%d", loans.size.toString()),
                loans.firstOrNull()?.dueDate?.let { words.ui("widget_next_due").replace("%s", dayLabel(it)) },
            ).joinToString(" · "),
            listOfNotNull(
                overdue.takeIf { it > 0 }?.let { words.ui("widget_overdue").replace("%d", it.toString()) },
                ready.takeIf { it > 0 }?.let { words.ui("widget_ready").replace("%d", it.toString()) },
            ).joinToString(" · "),
            overdue > 0 || ready > 0 || loans.any { it.dueDate?.let { date -> date <= today.plus(2, DateTimeUnit.DAY) } == true })
    }

    val weather = snapshot?.weather?.let { forecast ->
        val rain = forecast.hoursOn(today).lastOrNull { it.start <= now }?.precipitationProbability
        listOfNotNull("${forecast.temperatureC.roundToInt()}°", rain?.let { "${words.ui("rain")} $it%" }).joinToString(" · ")
    }

    return HomeWidgets(
        signedIn = snapshot != null,
        date = words.formatDate(today, full = true),
        weather = weather, nextClass = nextClass, due = due, dueCount = due.size, mensa = mensa, library = libraryWidget,
        labels = WidgetLabels(
            now = words.ui("widget_now"), next = words.ui("widget_next"), nextClass = words.ui("widget_next_class"),
            until = words.ui("widget_until"), due = words.ui("widget_due"), nothingDue = words.ui("widget_nothing_due"),
            noClasses = words.ui("widget_no_classes"), mensa = words.ui("widget_mensa"), library = words.ui("widget_library"),
            signIn = words.ui("widget_sign_in"), more = words.ui("widget_more"),
        ),
        refreshAt = refreshAt,
    )
}

/** "23 Nov": the month shortened to three letters, which fits a widget line in both languages. */
private fun Words.shortDate(date: LocalDate): String = formatDate(date).let { "${it.substringBeforeLast(' ')} ${it.substringAfterLast(' ').take(3)}" }
