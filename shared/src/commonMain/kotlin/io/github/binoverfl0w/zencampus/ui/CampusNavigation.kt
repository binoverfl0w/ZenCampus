package io.github.binoverfl0w.zencampus.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class CampusBoard { STUDY, LIBRARY, MENSA }

@Serializable
data class CampusDestination(
    val tab: String = "today", val route: String? = null,
    val courseTab: Int = 0, val folderPath: String = "", val calendarJump: String = "",
    val catalogueBookId: String = "",
    val courseworkTaskId: String = "", val courseworkCourseId: String = "",
    val communitySection: String = "", val communityItemId: String = "",
    val catalogueQuery: String = "",
    /** Inbox view: "" for messages, "updates" for the updates feed. */
    val inboxView: String = "",
) {
    val board: CampusBoard get() = when {
        route == "mensa" || tab == "mensa" -> CampusBoard.MENSA
        route in setOf("library", "catalogue", "book") || tab in setOf("catalogue", "loans", "saved") -> CampusBoard.LIBRARY
        else -> CampusBoard.STUDY
    }
    val global: Boolean get() = route in setOf("accounts", "settings", "downloads")
    fun normalized(): CampusDestination = when(route) {
        "mensa" -> copy(tab = "mensa", route = null)
        "library" -> copy(tab = "loans", route = null)
        "catalogue" -> copy(tab = "catalogue", route = null)
        else -> if (tab == "services") copy(tab = "catalogue") else this
    }
    internal val identity: String get() = when (route) {
        "task", "assignment" -> "$route:$courseworkCourseId:$courseworkTaskId"
        "book" -> "book:$catalogueBookId"
        "community" -> "community:$courseworkCourseId:$communitySection:$communityItemId"
        "officehours" -> "officehours:$courseworkCourseId"
        null -> "tab:$tab"
        else -> route!!
    }
    internal val family: String? get() = when {
        route?.startsWith("course:") == true -> "course"
        route in setOf("task", "assignment") -> "task"
        route == "book" -> "book"
        else -> null
    }
}

/**
 * What a destination shows, typed. It is derived from the saved [CampusDestination] (whose stored shape must stay
 * readable after updates), so screens match on meaning instead of route strings and shared fields.
 */
sealed interface Screen {
    /** A board section: Today, Courses, Calendar, Inbox, Catalogue, Loans, Saved or Mensa. */
    data class Section(val board: CampusBoard, val tab: String) : Screen
    data class Course(val id: String, val tab: Int, val folderPath: String) : Screen
    data class Community(val courseId: String, val section: String, val itemId: String) : Screen
    data class Task(val courseId: String, val taskId: String) : Screen
    data class Assignment(val courseId: String, val folderId: String) : Screen
    /** Office hours of every lecturer, or of one course when [courseId] is set. */
    data class OfficeHours(val courseId: String) : Screen
    data class Book(val id: String) : Screen
    data object Coursework : Screen
    data object Updates : Screen
    data object Downloads : Screen
    data object Settings : Screen
    data object Accounts : Screen
}

val CampusDestination.screen: Screen get() {
    val route = route ?: return Screen.Section(board, tab)
    return when {
        route.startsWith("course:") -> Screen.Course(route.substringAfter(':'), courseTab, folderPath)
        route == "community" -> Screen.Community(courseworkCourseId, communitySection, communityItemId)
        route == "task" -> Screen.Task(courseworkCourseId, courseworkTaskId)
        route == "assignment" -> Screen.Assignment(courseworkCourseId, courseworkTaskId)
        route == "officehours" -> Screen.OfficeHours(courseworkCourseId)
        route == "book" -> Screen.Book(catalogueBookId)
        route == "coursework" -> Screen.Coursework
        route == "updates" -> Screen.Updates
        route == "downloads" -> Screen.Downloads
        route == "settings" -> Screen.Settings
        route == "accounts" -> Screen.Accounts
        else -> Screen.Section(board, tab)
    }
}

/** Each board owns a short return path. Selections and remembered UI state are not Back steps. */
@Serializable
data class CampusNavigation(
    val history: List<CampusDestination> = listOf(CampusDestination()),
    // Kept so pre-0.7.9 saved state can be migrated without losing other boards.
    val boards: Map<CampusBoard, CampusDestination> = emptyMap(),
    val paths: Map<CampusBoard, List<CampusDestination>> = emptyMap(),
    val pathVersion: Int = 0,
) {
    val current: CampusDestination get() = prepared().history.last()
    val canGoBack: Boolean get() = prepared().let {
        it.history.size > 1 || (it.history.last().family == "course" && it.history.last().courseTab == 2 && it.history.last().folderPath.isNotBlank())
    }

    /** Compact old chronological histories once, including state restored after an app update. */
    fun prepared(): CampusNavigation {
        if (pathVersion == 1 && history.isNotEmpty()) return this
        // Public first-run cards have no in-app opener; Back returns to the welcome screen.
        if (history.size == 1 && history.single().global)
            return copy(history = listOf(history.single().normalized()), pathVersion = 1).rememberPath()
        var result = CampusNavigation(pathVersion = 1)
        boards.forEach { (board, destination) ->
            val target = destination.normalized()
            if (!target.global) result = result.copy(paths = result.paths +
                (board to if (target.route == null) listOf(target) else listOf(root(board), target)))
        }
        history.forEach { raw ->
            val target = raw.normalized()
            result = if (target.route == null) result.openTab(target.tab, target.calendarJump) else result.push(target)
        }
        val visited = history.map { it.normalized().board }.toSet()
        boards.filterKeys { it !in visited }.forEach { (board, raw) ->
            val target = raw.normalized()
            if (!target.global) result = result.copy(paths = result.paths +
                (board to if (target.route == null) listOf(target) else listOf(root(board), target)))
        }
        return result.rememberPath()
    }

    private fun rememberPath(): CampusNavigation {
        val local = history.filterNot { it.global }
        val board = history.last().board
        val path = local.ifEmpty { listOf(root(board)) }
        return copy(paths = paths + (board to path), boards = boards + (board to path.last()))
    }
    private fun withHistory(next: List<CampusDestination>): CampusNavigation = copy(history = next).rememberPath()

    fun push(destination: CampusDestination): CampusNavigation {
        val state = prepared()
        val target = destination.normalized().let {
            if (it.global) it.copy(tab = state.history.last().tab) else it
        }
        if (target == state.history.last()) return state
        if (target.board != state.history.last().board) return state.switchBoard(target.board).push(target)
        val existing = state.history.indexOfLast { it.identity == target.identity }
        if (existing >= 0) return state.withHistory(state.history.take(existing) + target)
        val base = if (target.family != null && state.history.last().family == target.family)
            state.history.dropLast(1) else state.history
        val next = base + target
        return state.withHistory(if (next.size <= 16) next else next.take(1) + next.takeLast(15))
    }
    fun back(): CampusNavigation {
        val state = prepared()
        val destination = state.history.last()
        if (destination.route?.startsWith("course:") == true && destination.courseTab == 2 && destination.folderPath.isNotBlank())
            return state.withHistory(state.history.dropLast(1) + destination.copy(folderPath = destination.folderPath.substringBeforeLast('|', "")))
        return if (state.history.size > 1) state.withHistory(state.history.dropLast(1)) else state
    }

    /** Bottom sections select a new root; they do not replay the previous section on Back. */
    fun openTab(tab: String, date: String = ""): CampusNavigation {
        val target = CampusDestination(tab = tab, calendarJump = date).normalized()
        return prepared().rememberPath().withHistory(listOf(target))
    }
    /** A contextual shortcut has an opener, unlike selecting the Calendar bottom section. */
    fun openCalendar(date: String = ""): CampusNavigation = push(CampusDestination(tab = "calendar", calendarJump = date))
    fun openCourse(id: String): CampusNavigation {
        val state = prepared()
        val existing = state.history.indexOfLast { it.route == "course:$id" }
        return if (existing >= 0) state.withHistory(state.history.take(existing + 1))
        else state.push(CampusDestination(tab = state.current.tab, route = "course:$id"))
    }
    fun openCourseTab(tab: Int): CampusNavigation {
        val state = prepared()
        if (state.current.family != "course") return state
        return state.withHistory(state.history.dropLast(1) + state.current.copy(courseTab = tab.coerceIn(0, 3)))
    }
    fun openFolder(id: String): CampusNavigation {
        val state = prepared()
        if (state.current.family != "course" || id.isBlank() || '|' in id) return state
        val ancestors = state.current.folderPath.split('|').filter { it.isNotBlank() }
        val path = if (id in ancestors) ancestors.take(ancestors.indexOf(id) + 1) else ancestors + id
        return state.withHistory(state.history.dropLast(1) + state.current.copy(courseTab = 2, folderPath = path.joinToString("|")))
    }
    fun jumpToFolder(path: String): CampusNavigation {
        val state = prepared()
        if (state.current.family != "course" || (path.isNotBlank() && state.current.folderPath != path && !state.current.folderPath.startsWith("$path|"))) return state
        return state.withHistory(state.history.dropLast(1) + state.current.copy(courseTab = 2, folderPath = path))
    }
    fun openCommunity(courseId: String, section: String, itemId: String = ""): CampusNavigation =
        push(CampusDestination(tab = current.tab, route = "community", courseworkCourseId = courseId, communitySection = section, communityItemId = itemId))
    fun openUpdates(): CampusNavigation = push(CampusDestination(tab = current.tab, route = "updates"))
    /** Office hours of every lecturer, or only of one course and its lecturers. */
    fun openOfficeHours(courseId: String = ""): CampusNavigation =
        push(CampusDestination(tab = current.tab.takeIf { current.board == CampusBoard.STUDY } ?: "today", route = "officehours", courseworkCourseId = courseId))
    /** The Inbox section, on its messages or updates view. */
    fun openInbox(view: String = ""): CampusNavigation {
        val state = switchBoard(CampusBoard.STUDY).openTab("messages")
        return state.withHistory(listOf(state.current.copy(inboxView = view)))
    }
    fun openCoursework(): CampusNavigation = push(CampusDestination(route = "coursework"))
    fun openTask(courseId: String, taskId: String): CampusNavigation = push(current.copy(
        route = if (taskId.startsWith("folder:")) "assignment" else "task", folderPath = "",
        courseworkCourseId = courseId, courseworkTaskId = taskId.removePrefix("folder:")))
    fun openSettings(): CampusNavigation = push(current.copy(route = "settings", folderPath = ""))
    fun openDownloads(): CampusNavigation = push(current.copy(route = "downloads", folderPath = ""))
    fun openCards(): CampusNavigation = push(current.copy(route = "accounts", folderPath = ""))
    fun openService(route: String): CampusNavigation = when (route) {
        "study" -> switchBoard(CampusBoard.STUDY)
        "library-board" -> switchBoard(CampusBoard.LIBRARY)
        "mensa" -> switchBoard(CampusBoard.MENSA)
        "library" -> openTab("loans")
        "catalogue" -> openTab("catalogue")
        "accounts" -> openCards()
        "coursework" -> openCoursework()
        "updates" -> openUpdates()
        "officehours" -> openOfficeHours()
        "today" -> switchBoard(CampusBoard.STUDY).openTab("today")
        "calendar" -> switchBoard(CampusBoard.STUDY).openTab("calendar")
        "inbox" -> openInbox()
        "downloads" -> openDownloads()
        else -> this
    }
    /** Quick find hands a query to the catalogue, which runs it once when shown. */
    fun searchCatalogue(query: String): CampusNavigation {
        val state = switchBoard(CampusBoard.LIBRARY).openTab("catalogue")
        return state.withHistory(listOf(state.current.copy(catalogueQuery = query)))
    }
    fun openBook(id: String): CampusNavigation = push(CampusDestination(
        tab = current.tab.takeIf { it in setOf("catalogue", "saved") } ?: "catalogue", route = "book", catalogueBookId = id))
    fun switchBoard(board: CampusBoard): CampusNavigation {
        val state = prepared().rememberPath()
        if (board == state.history.last().board && !state.history.last().global) return state
        return state.withHistory(state.paths[board] ?: listOf(root(board)))
    }

    companion object {
        fun restore(value: String): CampusNavigation = Json.decodeFromString<CampusNavigation>(value).prepared()
        private fun root(board: CampusBoard) = CampusDestination(tab = when (board) {
            CampusBoard.STUDY -> "today"; CampusBoard.LIBRARY -> "catalogue"; CampusBoard.MENSA -> "mensa"
        })
    }
}
