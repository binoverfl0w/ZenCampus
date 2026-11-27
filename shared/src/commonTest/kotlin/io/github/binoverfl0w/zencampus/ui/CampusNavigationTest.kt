package io.github.binoverfl0w.zencampus.ui

import kotlinx.serialization.json.Json
import kotlin.test.*

class CampusNavigationTest {
    private fun restored(nav: CampusNavigation) = CampusNavigation.restore(Json.encodeToString(nav.prepared()))

    @Test fun mainTabsAreSelectionsAndEachSelectedRootCanExitImmediately() {
        var nav = CampusNavigation()
        repeat(50) { nav = nav.openTab("courses").openTab("calendar").openTab("today") }
        nav = restored(nav.openTab("calendar"))
        assertFalse(nav.canGoBack)
        assertEquals("calendar", nav.back().current.tab)
        assertEquals(1, nav.history.size)
    }

    @Test fun nestedFoldersBackToTheirParentsThenTheCourseOpenerWithoutReplayingTabs() {
        var nav = CampusNavigation().openTab("calendar").openCourse("a")
            .openCourseTab(1).openCourseTab(2).openFolder("notes").openFolder("week1")
        nav = restored(nav)
        assertEquals(2, nav.history.size)
        assertEquals("notes", nav.back().current.folderPath)
        nav = nav.back().back()
        assertEquals("", nav.current.folderPath)
        assertEquals(2, nav.current.courseTab)
        assertEquals("calendar", nav.back().current.tab)
        assertFalse(nav.back().canGoBack)
    }

    @Test fun courseTabChangesRememberMaterialsButDoNotCreateBackSteps() {
        val materials = CampusNavigation().openTab("courses").openCourse("a").openCourseTab(2).openFolder("notes").openFolder("week1")
        val overview = restored(materials.openCourseTab(0))
        assertEquals("notes|week1", overview.current.folderPath)
        assertEquals("courses", overview.back().current.tab)
        assertEquals(materials.current, overview.openCourseTab(2).current)
    }

    @Test fun breadcrumbsJumpDirectlyAndDoNotLeaveHiddenFolderHistory() {
        val nav = CampusNavigation().openTab("courses").openCourse("a").openCourseTab(2)
            .openFolder("notes").openFolder("week1").openFolder("extra")
        val parent = nav.jumpToFolder("notes")
        assertEquals("notes", parent.current.folderPath)
        assertEquals("", parent.back().current.folderPath)
        assertEquals("courses", nav.jumpToFolder("").back().current.tab)
        assertEquals(nav, nav.jumpToFolder("unrelated"))
        assertEquals("notes", nav.openFolder("notes").current.folderPath)
        assertEquals(nav, nav.openFolder("bad|id"))
    }

    @Test fun boardsRestoreIndependentPathsAndNeverBackIntoAnotherBoard() {
        val course = CampusNavigation().openTab("courses").openCourse("a").openCourseTab(2).openFolder("notes")
        var nav = course.switchBoard(CampusBoard.LIBRARY).openTab("saved").openBook("edition")
        repeat(30) { nav = nav.switchBoard(CampusBoard.MENSA).switchBoard(CampusBoard.STUDY).switchBoard(CampusBoard.LIBRARY) }
        nav = restored(nav)
        assertEquals("edition", nav.current.catalogueBookId)
        assertEquals("saved", nav.back().current.tab)
        assertFalse(nav.back().canGoBack)
        assertEquals(course.current, nav.switchBoard(CampusBoard.STUDY).current)
        assertFalse(nav.switchBoard(CampusBoard.MENSA).canGoBack)
        assertEquals("courses", nav.switchBoard(CampusBoard.STUDY).back().back().current.tab)
    }

    @Test fun oneHeldSliderGestureCanCrossAndReverseBoardsWithoutAddingReturnSteps() {
        val course = CampusNavigation().openTab("courses").openCourse("a").openCourseTab(2).openFolder("notes")
        val book = course.switchBoard(CampusBoard.LIBRARY).openBook("edition")
        val nav = book.switchBoard(CampusBoard.STUDY).switchBoard(CampusBoard.LIBRARY)
            .switchBoard(CampusBoard.MENSA).switchBoard(CampusBoard.STUDY).switchBoard(CampusBoard.LIBRARY)
        assertEquals(book.history, nav.history)
        assertEquals(course.current, restored(nav).switchBoard(CampusBoard.STUDY).current)
        assertEquals("catalogue", nav.back().current.tab)
        assertFalse(nav.back().canGoBack)
    }

    @Test fun tasksReturnToTheirExactCourseTabOrCourseworkList() {
        val overview = CampusNavigation().openCoursework()
        val task = restored(overview.openTask("course", "task"))
        assertEquals(overview.current, task.back().current)
        assertEquals(CampusDestination(), task.back().back().current)
        val course = CampusNavigation().openTab("calendar").openCourse("a").openCourseTab(3)
        val detail = course.openTask("a", "task").switchBoard(CampusBoard.LIBRARY).switchBoard(CampusBoard.STUDY)
        assertEquals(course.current, detail.back().current)
        assertEquals("calendar", detail.back().back().current.tab)
        assertEquals("assignment", course.openTask("a", "folder:submission").current.route)
    }

    @Test fun reopeningAnAncestorCollapsesCyclesAndSiblingDetailsReplaceEachOther() {
        val course = CampusNavigation().openTab("courses").openCourse("a").openCourseTab(3)
        val task = course.openTask("a", "task")
        assertEquals(course.current, task.openCourse("a").current)
        assertEquals(course.history, task.openCourse("a").history)
        assertEquals(task.history.size, task.openTask("a", "other").history.size)
        var many = CampusNavigation().openTab("courses")
        repeat(100) { many = many.openCourse(it.toString()) }
        assertEquals(2, many.history.size)
        assertEquals("courses", many.back().current.tab)
        val book = CampusNavigation().openTab("saved").openBook("first").openBook("second")
        assertEquals("saved", book.back().current.tab)
        assertFalse(book.back().canGoBack)
    }

    @Test fun globalCardsAndSettingsKeepTheirOpenerButDoNotReplaceBoardMemory() {
        val book = CampusNavigation().openTab("saved").openBook("edition")
        val global = restored(book.openSettings().openDownloads().openCards())
        assertEquals("downloads", global.back().current.route)
        assertEquals("settings", global.back().back().current.route)
        assertEquals(book.current, global.back().back().back().current)
        assertEquals(book.current, global.switchBoard(CampusBoard.LIBRARY).current)
        assertEquals(book.current, global.switchBoard(CampusBoard.STUDY).switchBoard(CampusBoard.LIBRARY).current)
        assertEquals(3, global.openSettings().history.size)
        assertEquals(book.current, global.openSettings().back().current)
        val publicMensa = CampusNavigation(listOf(CampusDestination(tab = "mensa")))
        val setup = publicMensa.push(CampusDestination(tab = "today", route = "accounts"))
        assertEquals(CampusBoard.MENSA, setup.current.board)
        assertEquals(publicMensa.current, setup.back().current)
        assertFalse(setup.back().canGoBack)
    }

    @Test fun contextualCalendarShortcutReturnsToItsOpenerWhileSectionSelectionRemovesTheAnchor() {
        val nav = CampusNavigation().openCalendar("2026-11-03")
        assertEquals("2026-11-03", restored(nav).current.calendarJump)
        assertEquals("today", nav.back().current.tab)
        assertEquals("calendar", nav.openCourse("a").back().current.tab)
        assertEquals(2, nav.openCalendar("2026-11-04").history.size)
        assertFalse(nav.openTab("calendar").canGoBack)
        assertFalse(nav.openTab("courses").canGoBack)
    }

    @Test fun oldChronologicalStateIsCompactedOnUpgradeWithoutLosingCurrentFolderOrOtherBoards() {
        val legacy = """{"history":[{"tab":"today"},{"tab":"courses"},{"tab":"courses","route":"course:a"},{"tab":"courses","route":"course:a","courseTab":1},{"tab":"courses","route":"course:a","courseTab":2},{"tab":"courses","route":"course:a","courseTab":2,"folderPath":"notes"},{"tab":"services","route":"library"},{"tab":"courses","route":"course:a","courseTab":2,"folderPath":"notes|week1"}]}"""
        val nav = CampusNavigation.restore(legacy)
        assertEquals("notes|week1", nav.current.folderPath)
        assertEquals(2, nav.history.size)
        assertEquals("courses", nav.back().back().back().current.tab)
        assertFalse(nav.back().back().back().canGoBack)
        assertEquals("loans", nav.switchBoard(CampusBoard.LIBRARY).current.tab)
        assertFalse(nav.switchBoard(CampusBoard.LIBRARY).canGoBack)
        assertEquals(nav, restored(nav))
    }

    @Test fun legacyMixedServicesAndRememberedBoardsNormalizeAndEmptyStateHasASafeRoot() {
        val legacy = """{"history":[{"tab":"today"},{"tab":"services","route":"library"}],"boards":{"MENSA":{"tab":"services","route":"mensa"},"STUDY":{"tab":"courses","route":"course:a","courseTab":2,"folderPath":"notes"}}}"""
        val nav = CampusNavigation.restore(legacy)
        assertEquals("loans", nav.current.tab)
        assertNull(nav.current.route)
        assertFalse(nav.canGoBack)
        assertEquals("mensa", nav.switchBoard(CampusBoard.MENSA).current.tab)
        assertFalse(CampusNavigation.restore("""{"history":[]}""").canGoBack)
        val setup = CampusNavigation.restore("""{"history":[{"route":"accounts"}]}""")
        assertFalse(setup.canGoBack)
        assertEquals("accounts", restored(setup).current.route)
        val onlyRemembered = CampusNavigation.restore("""{"history":[{"tab":"saved"}],"boards":{"STUDY":{"tab":"courses","route":"course:a","courseTab":2,"folderPath":"notes"}}}""")
        assertEquals("notes", onlyRemembered.switchBoard(CampusBoard.STUDY).current.folderPath)
    }
}

class ScreenMappingTest {
    @Test fun destinationsMapToTypedScreens() {
        val nav = CampusNavigation()
        kotlin.test.assertEquals(Screen.Section(CampusBoard.STUDY, "today"), nav.current.screen)
        kotlin.test.assertEquals(Screen.Course("c", 2, "a|b"), nav.openCourse("c").openFolder("a").openFolder("b").current.screen)
        kotlin.test.assertEquals(Screen.Community("c", "forum", "t1"), nav.openCommunity("c", "forum", "t1").current.screen)
        kotlin.test.assertEquals(Screen.Task("c", "t"), nav.openTask("c", "t").current.screen)
        kotlin.test.assertEquals(Screen.Assignment("c", "f"), nav.openTask("c", "folder:f").current.screen)
        kotlin.test.assertEquals(Screen.OfficeHours("c"), nav.openOfficeHours("c").current.screen)
        kotlin.test.assertEquals(Screen.Book("b"), nav.openBook("b").current.screen)
        kotlin.test.assertEquals(Screen.Section(CampusBoard.MENSA, "mensa"), nav.switchBoard(CampusBoard.MENSA).current.screen)
        kotlin.test.assertEquals(Screen.Downloads, nav.openDownloads().current.screen)
    }

    @Test fun savedStateFromOlderVersionsStillRestoresToTheSameScreen() {
        val saved = """{"history":[{"tab":"today"},{"tab":"today","route":"community","courseworkCourseId":"c","communitySection":"wiki","communityItemId":"p"}],"pathVersion":1}"""
        kotlin.test.assertEquals(Screen.Community("c", "wiki", "p"), CampusNavigation.restore(saved).current.screen)
    }
}
