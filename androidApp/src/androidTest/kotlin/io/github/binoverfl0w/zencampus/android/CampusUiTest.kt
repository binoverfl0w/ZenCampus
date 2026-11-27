@file:OptIn(kotlin.time.ExperimentalTime::class)
package io.github.binoverfl0w.zencampus.android

import android.database.sqlite.SQLiteDatabase
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.BoardSlider
import io.github.binoverfl0w.zencampus.ui.CampusBoard
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ZenBoardTheme
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import io.github.binoverfl0w.zencampus.ui.study.CampusWeatherCard
import io.github.binoverfl0w.zencampus.ui.study.CourseToolsSection
import io.github.binoverfl0w.zencampus.ui.study.CourseworkBrief
import io.github.binoverfl0w.zencampus.ui.study.DocumentRow
import io.github.binoverfl0w.zencampus.ui.study.FolderActivityDetails
import io.github.binoverfl0w.zencampus.ui.study.LocalVipsReader
import io.github.binoverfl0w.zencampus.ui.study.courseToolsItems
import io.github.binoverfl0w.zencampus.ui.study.recentMaterialItems
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampusUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as ZenApplication).container

    @Before fun reset() {
        compose.runOnIdle {
            container.setSettings(AppSettings(theme = ThemeMode.LIGHT, language = AppLanguage.ENGLISH))
            container.signOut()
        }
        compose.waitUntil(10_000) { container.ready.value && container.repository.value == null }
        kotlinx.coroutines.runBlocking { container.previewServices.setPreferences { CampusServicePreferences() } }
        compose.onNodeWithText("Continue with DigiCampus").assertExists()
    }

    private fun preview() {
        compose.runOnIdle { container.preview() }
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courses?.isNotEmpty() == true }
        compose.onNodeWithText("Courses").performClick()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
    }

    private fun switchBoard(id: String) {
        compose.onNodeWithTag("board-switcher").performClick()
        compose.onNodeWithTag("choose-$id").performClick()
    }
    private fun openCards() { compose.onNodeWithContentDescription("Your cards").performClick() }

    private fun openVipsPreview() {
        preview()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.vipsSheets?.get("algorithms") != null }
        compose.onNodeWithText("Materials").performClick()
        compose.waitUntil(10_000) { container.repository.value!!.snapshot.value.folderContents["folder:algorithms:root"] != null }
        compose.waitForIdle()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("vips-sheet-preview-3"))
        compose.onNodeWithTag("vips-sheet-preview-3").assertIsDisplayed().performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.waitUntil(10_000) { device.hasObject(By.text("Vips · Preview")) }
    }

    private fun openPreviewExercise(device: UiDevice) {
        // Chromium exposes links as both a labelled parent and a text child, depending on version.
        val label = By.descStartsWith("1. Breadth-first search")
        val text = By.textStartsWith("1. Breadth-first search")
        try {
            compose.waitUntil(30_000) { device.hasObject(label) || device.hasObject(text) }
        } catch (failure: AssertionError) {
            val hierarchy = ByteArrayOutputStream().also(device::dumpWindowHierarchy).toString("UTF-8")
            throw AssertionError("Vips preview exercise link unavailable. Hierarchy: $hierarchy", failure)
        }
        (device.findObject(label) ?: device.findObject(text)).click()
    }

    @Test fun vipsExercisesAndTargetBlankAttachmentsOpenInsideZenCampusAndBackReturnsToTheSheet() {
        openVipsPreview()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        openPreviewExercise(device)
        compose.waitUntil(10_000) { device.hasObject(By.textContains("Consider an undirected graph")) }
        device.findObject(By.text("Graphs - Exercise sheet 3.pdf")).click()
        compose.waitUntil(15_000) { device.hasObject(By.desc("Page 1")) }
        val saved = container.repository.value!!.snapshot.value.localDocuments.single { it.document.id == "preview-vips-pdf" }
        Assert.assertEquals("Graphs - Exercise sheet 3.pdf", File(saved.path).name)
        Assert.assertTrue(device.hasObject(By.desc("Save a copy")))
        device.findObject(By.desc("More options")).click()
        compose.waitUntil(10_000) { device.hasObject(By.text("Share PDF")) }
        device.pressBack()
        compose.waitUntil(5_000) { !device.hasObject(By.text("Share PDF")) }
        compose.waitForIdle()
        device.pressBack()
        compose.waitUntil(10_000) { device.hasObject(By.textContains("Consider an undirected graph")) }
        device.findObject(By.desc("Files")).click()
        compose.waitUntil(5_000) { device.hasObject(By.text("Keep offline")) }
        device.findObject(By.text("Keep offline")).click()
        compose.waitUntil(10_000) { container.repository.value!!.snapshot.value.localDocuments.any { it.document.id == "preview-vips-pdf" && it.pinned } }
        compose.waitUntil(10_000) { !device.hasObject(By.text("Save a copy")) }
        // Advance Compose's test clock until the popup's exit animation releases its window.
        compose.waitForIdle()
        device.pressBack()
        compose.waitUntil(10_000) { device.hasObject(By.textContains("Practice graph traversal")) }
        device.findObject(By.desc("Back")).click()
        compose.onNodeWithTag("vips-sheet-preview-3").assertExists()
    }

    @Test fun campusBrowserFillsItsSpaceAndAllowsScreenshots() {
        openVipsPreview()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<CampusPageActivity>().single()
            fun find(view: android.view.View): android.webkit.WebView? = view as? android.webkit.WebView
                ?: (view as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
            val web = find(activity.window.decorView)!!
            // wrap_content gives pages a zero-height viewport: CSS 100vh is 0 and DigiCampus's menus open collapsed.
            Assert.assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, web.layoutParams.height)
            Assert.assertEquals(0, activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @Test fun vipsBrowserHistorySurvivesRecreationAndSigningOutClosesTheReader() {
        openVipsPreview()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        openPreviewExercise(device)
        compose.waitUntil(10_000) { device.hasObject(By.textContains("Consider an undirected graph")) }
        instrumentation.runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<CampusPageActivity>().single().recreate()
        }
        compose.waitUntil(15_000) { device.hasObject(By.textContains("Consider an undirected graph")) }
        device.pressBack()
        compose.waitUntil(10_000) { device.hasObject(By.textContains("Practice graph traversal")) }
        container.signOut()
        compose.waitUntil(10_000) { container.repository.value == null && device.hasObject(By.text("Continue with DigiCampus")) }
        compose.onNodeWithText("Continue with DigiCampus").assertIsDisplayed()
    }

    @Test fun separateVipsSheetsAppearInMaterialsAndCourseworkWithoutBecomingFileFolders() {
        preview()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.vipsSheets?.get("algorithms") != null }
        compose.onNodeWithText("Materials").performClick()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("course-tool-vips"))
        compose.onNodeWithTag("course-tool-vips").assertIsDisplayed()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("vips-sheet-preview-3"))
        compose.onNodeWithText("Graphs · Exercise sheet 3").assertIsDisplayed()
        compose.onNodeWithText("Due 26.10.2026, 10:00").assertIsDisplayed()
        compose.onNodeWithTag("course-scroll").performScrollToIndex(0)
        compose.onNodeWithText("Coursework").performClick()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("vips-sheet-preview-3"))
        compose.onNodeWithTag("vips-sheet-preview-3").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("vips-sheet-preview-3").assertExists()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
    }

    @Test fun courseworkOverviewShowsTheVipsDeadlineAndOpensItsReaderDirectly() {
        preview()
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithTag("today-scroll").performScrollToNode(hasText("All coursework"))
        compose.onNodeWithText("All coursework").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.vipsSheets?.get("algorithms") != null }
        compose.onNodeWithTag("coursework-scroll").performScrollToNode(hasTestTag("vips-sheet-preview-3"))
        compose.onNodeWithText("Due 26.10.2026, 10:00").assertIsDisplayed()
        compose.onNodeWithTag("vips-sheet-preview-3").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        Assert.assertTrue(device.wait(Until.hasObject(By.text("Vips · Preview")), 15_000))
        device.pressBack()
        compose.onNodeWithTag("coursework-scroll").assertExists()
        compose.onNodeWithTag("coursework-scroll").performScrollToIndex(0)
        compose.onNodeWithTag("tasks-submitted").performScrollTo().performClick()
        compose.onNodeWithTag("vips-sheet-preview-3").assertDoesNotExist()
        compose.onNodeWithTag("tasks-open").performClick()
        compose.onNodeWithTag("coursework-scroll").performScrollToNode(hasTestTag("vips-sheet-preview-3"))
        compose.onNodeWithText("Due 26.10.2026, 10:00").assertIsDisplayed()
    }

    @Test fun todayShowsVipsDatesAndKeepsExamWindowsDistinctFromExerciseDeadlines() {
        val course = Course("c", "Algorithms")
        val practice = VipsSheet("practice", "c", "Graphs sheet", vipsSheetsUrl("c"),
            "19.10.2026, 10:00", "22.10.2026, 10:00", "Übung")
        val exam = practice.copy(id = "exam", title = "Graph exam", mode = "Klausur", starts = "23.10.2026, 09:00", ends = "23.10.2026, 12:00")
        val snapshot = CampusSnapshot(Account("test", "Test", "test"), courses = listOf(course),
            courseTools = mapOf("c" to CourseTools(listOf(CourseTool("vips", "Sheets", vipsSheetsUrl("c"))))),
            vipsSheets = mapOf("c" to VipsSheets(listOf(exam, practice))))
        var opened = ""
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalWords provides Words(false), LocalNow provides LocalDateTime(2026, 10, 21, 10, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds(),
                LocalVipsReader provides { _: String, _: String, title: String -> opened = title }) {
                ZenTheme(ThemeMode.LIGHT) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("brief-test-scroll")) {
                        item { CourseworkBrief(snapshot, SyncStatus(), {}, { _, _ -> }) }
                    }
                }
            }
        } }
        compose.onNodeWithText("Due 22.10.2026, 10:00").assertIsDisplayed()
        compose.onNodeWithTag("vips-sheet-practice").performClick()
        compose.runOnIdle { Assert.assertEquals("Graphs sheet", opened) }
        compose.onNodeWithTag("brief-test-scroll").performScrollToNode(hasText("Exam window closes 23.10.2026, 12:00"))
        compose.onNodeWithText("Exam window closes 23.10.2026, 12:00").assertIsDisplayed()
        compose.onNodeWithText("Your individual exam time is shown inside Vips.").assertExists()
        compose.onNodeWithText("No open Courseware tasks").assertDoesNotExist()
    }

    @Test fun unknownToolsAndStaleVipsRemainVisibleWithHonestCoverageAtNarrowGermanSize() {
        var opened = ""; var retries = 0
        val course = Course("test", "Test course")
        val snapshot = CampusSnapshot(Account("test", "Test", "test"), courses = listOf(course),
            courseTools = mapOf("test" to CourseTools(listOf(CourseTool("vips", "Übungsblätter", vipsSheetsUrl("test")),
                CourseTool("future", "Ein neues Lernwerkzeug", "$DIGICAMPUS_ORIGIN/plugins.php/future?cid=test")))),
            vipsSheets = mapOf("test" to VipsSheets(listOf(VipsSheet("17", "test", "Graphen und kürzeste Wege", "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=17&cid=test",
                ends = "28.10.2026, 12:00", status = "bearbeitet")))))
        val current = mutableStateOf(snapshot)
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalWords provides Words(true), LocalDensity provides Density(density, 1.3f)) {
                ZenTheme(ThemeMode.LIGHT) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.width(320.dp).testTag("tools-test-scroll")) {
                        courseToolsItems(course, current.value,
                            SyncStatus(stale = setOf("vips:test")), { opened = it }, { retries++ })
                    }
                }
            }
        } }
        compose.onNodeWithTag("tools-test-scroll").performScrollToNode(hasTestTag("course-tool-future"))
        compose.onNodeWithTag("course-tool-future").performClick()
        compose.runOnIdle { Assert.assertEquals("$DIGICAMPUS_ORIGIN/plugins.php/future?cid=test", opened) }
        // A temporary failure keeps the saved sheets with a quiet note instead of a retry button.
        compose.onNodeWithTag("tools-test-scroll").performScrollToNode(hasText("Gespeicherte Liste · wird automatisch aktualisiert"))
        compose.onNodeWithText("Erneut versuchen").assertDoesNotExist()
        compose.runOnIdle { Assert.assertEquals(0, retries) }
        compose.onNodeWithTag("tools-test-scroll").performScrollToNode(hasTestTag("vips-sheet-17"))
        compose.onNodeWithText("Graphen und kürzeste Wege").assertIsDisplayed()
        compose.onNodeWithText("Status in DigiCampus: bearbeitet").assertIsDisplayed()
        compose.onNodeWithText("Keine aktiven Aufgabenblätter in Vips aufgelistet.").assertDoesNotExist()
        compose.runOnIdle { current.value = snapshot.copy(account = snapshot.account.copy(demo = true)) }
        compose.onNodeWithTag("vips-sheet-17").performClick()
        compose.runOnIdle { Assert.assertEquals("Preview sheets must never enter a real assignment", DIGICAMPUS_ORIGIN, opened) }
    }

    @Test fun unverifiedToolsShowAnOfficialFallbackInsteadOfClaimingNoMaterials() {
        var opened = ""
        val course = Course("test", "Test course")
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalWords provides Words(false)) {
                ZenTheme(ThemeMode.LIGHT) {
                    CourseToolsSection(course, CampusSnapshot(Account("test", "Test", "test")),
                        SyncStatus(errors = mapOf("tools:test" to FailureKind.PERMISSION)), { opened = it }, {})
                }
            }
        } }
        compose.onNodeWithText("Course sections could not be checked. More material may be available in DigiCampus.").assertIsDisplayed()
        compose.onNodeWithText("No additional sections listed.").assertDoesNotExist()
        compose.onNodeWithText("Open in DigiCampus").performClick()
        compose.runOnIdle { Assert.assertEquals(courseOverviewUrl("test"), opened) }
    }

    @Test fun longVipsListsReachEverySheetWithoutRenderingAllRowsAtOnce() {
        val course = Course("test", "Test course")
        val snapshot = CampusSnapshot(Account("test", "Test", "test"), courses = listOf(course),
            courseTools = mapOf("test" to CourseTools(listOf(CourseTool("vips", "Exercise sheet", vipsSheetsUrl("test"))))),
            vipsSheets = mapOf("test" to VipsSheets((1..100).map { VipsSheet(it.toString(), "test", "Exercise sheet $it",
                "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=$it&cid=test") })))
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalWords provides Words(false)) {
                ZenTheme(ThemeMode.LIGHT) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("long-tools-scroll")) {
                        courseToolsItems(course, snapshot, SyncStatus(), {}, {})
                    }
                }
            }
        } }
        compose.onNodeWithTag("vips-sheet-100").assertDoesNotExist()
        compose.onNodeWithTag("long-tools-scroll").performScrollToNode(hasTestTag("vips-sheet-100"))
        compose.onNodeWithTag("vips-sheet-100").assertIsDisplayed()
        compose.onNodeWithTag("vips-sheet-1").assertDoesNotExist()
    }
    private fun openSettings() {
        compose.onNodeWithContentDescription("Settings").performClick()
    }

    @Test fun calendarWeekGridScrollsWithThePageUntilZoomedAndExpandsAgainAt100Percent() {
        preview()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithTag("planner-view-week").performClick()
        val page = compose.onNodeWithTag("planner-calendar")
        val body = compose.onNodeWithTag("planner-grid-body")
        fun pagePosition() = page.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        page.performScrollToNode(hasTestTag("planner-week-grid"))
        Assert.assertFalse(body.fetchSemanticsNode().config.contains(SemanticsProperties.VerticalScrollAxisRange))
        val beforePageSwipe = pagePosition()
        body.performTouchInput { swipeUp(durationMillis = 600) }
        Assert.assertTrue("Default timetable swipes must scroll the page", pagePosition() > beforePageSwipe)
        compose.onNodeWithTag("planner-grid-hour-19").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription("Zoom in").performScrollTo().performClick()
        compose.waitUntil(5_000) { container.repository.value!!.planner.snapshot.value.settings.zoom > 1f }
        page.performScrollToNode(hasTestTag("planner-week-grid"))
        Assert.assertTrue(body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
        val beforeInnerSwipe = pagePosition()
        body.performTouchInput {
            swipe(Offset(width * .7f, height * .75f), Offset(width * .7f, height * .5f), durationMillis = 600)
        }
        Assert.assertTrue("Zoomed timetable must allow inner panning", body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        Assert.assertEquals(beforeInnerSwipe, pagePosition(), 1f)

        compose.onNodeWithContentDescription("Zoom out").performScrollTo().performClick()
        compose.waitUntil(5_000) { container.repository.value!!.planner.snapshot.value.settings.zoom == 1f }
        Assert.assertFalse(body.fetchSemanticsNode().config.contains(SemanticsProperties.VerticalScrollAxisRange))
        page.performScrollToNode(hasTestTag("planner-week-grid"))
        weekDates(campusToday()).forEach { compose.onNodeWithTag("planner-grid-header-$it").assertIsDisplayed() }
        compose.onNodeWithTag("planner-grid-hour-8").assertIsDisplayed()
        compose.onNodeWithTag("planner-grid-hour-19").performScrollTo().assertIsDisplayed()
    }

    @Test fun calendarWeekGridZoomsOpensClassDetailsAndRestoresAfterRecreation() {
        preview()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithTag("planner-view-week").performClick()
        compose.onNodeWithTag("planner-calendar").performScrollToNode(hasTestTag("planner-week-grid"))
        val days = weekDates(campusToday())
        val headers = days.map { compose.onNodeWithTag("planner-grid-header-$it").fetchSemanticsNode().boundsInRoot }
        Assert.assertTrue(headers.maxOf { it.width } - headers.minOf { it.width } <= 1f)
        Assert.assertTrue(headers.maxOf { it.top } - headers.minOf { it.top } <= 1f)
        days.forEach { assertSingleLine(compose.onNodeWithTag("planner-grid-temperature-$it", useUnmergedTree = true)) }
        days.forEach { compose.onNodeWithTag("planner-grid-weather-$it", useUnmergedTree = true).assertIsDisplayed() }
        val firstDay = days.first()
        val algorithms = container.repository.value!!.snapshot.value.courses.first { it.id == "algorithms" }
        val eventTitle = compose.onNodeWithTag("planner-event-title-algorithms-$firstDay-10-$firstDay", useUnmergedTree = true)
            .assertTextEquals(algorithms.title)
        val titleLayouts = mutableListOf<TextLayoutResult>()
        eventTitle.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        Assert.assertTrue("The narrow event must draw more than one character", titleLayouts.single().getLineEnd(0, visibleEnd = true) > 1)
        compose.onNodeWithTag("planner-week-grid").performTouchInput {
            down(0, center - Offset(35f, 0f)); down(1, center + Offset(35f, 0f))
            moveTo(0, center - Offset(60f, 0f)); moveTo(1, center + Offset(60f, 0f))
            up(0); up(1)
        }
        compose.waitUntil(5_000) { container.repository.value!!.planner.snapshot.value.settings.zoom > 1f }
        compose.activityRule.scenario.recreate()
        Assert.assertEquals("week", container.repository.value!!.planner.snapshot.value.settings.view)
        compose.onNodeWithTag("planner-calendar").performScrollToNode(hasTestTag("planner-week-grid"))
        val monday = weekDates(campusToday()).first()
        compose.onNodeWithTag("planner-event-algorithms-$monday-10-$monday").performScrollTo().performClick()
        compose.onNodeWithText("Open course").assertExists()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        Assert.assertFalse(compose.activity.isFinishing)
        compose.onNodeWithTag("planner-week-grid").assertExists()
    }

    @Test fun globalRoomSearchShowsBookingsOutsideMyCoursesAndRestoresItsIdentity() {
        preview()
        val enrolled = container.repository.value!!.snapshot.value.courses.map { it.id }.toSet()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithTag("planner-filters").performClick()
        compose.onNodeWithTag("planner-room-filter").performTextInput("1002")
        compose.onNodeWithTag("planner-filter-sheet").performScrollToNode(hasTestTag("planner-global-room-search"))
        compose.onNodeWithTag("planner-global-room-search").performClick()
        compose.onNodeWithTag("planner-mode-rooms").assertIsSelected()
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.campusRooms[campusRoomSearchKey("1002")] != null }
        compose.onNodeWithTag("planner-explorer").performScrollToNode(hasTestTag("campus-room-preview-n1002"))
        compose.onNodeWithTag("campus-room-preview-n1002").performClick()
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.roomWeeks.isNotEmpty() }
        val state = container.repository.value!!.planner.snapshot.value
        Assert.assertEquals("preview-n1002", state.settings.campusRoom?.id)
        compose.onNodeWithText("Room timetable").assertExists()
        Assert.assertFalse(state.settings.publishedRooms)
        Assert.assertTrue(state.roomWeeks.values.single().entries.all { it.event.courseId !in enrolled })
        compose.onNodeWithTag("planner-calendar").performScrollToNode(hasTestTag("campus-room-status"))
        compose.onNodeWithText("Unbooked in the published plan").assertExists()
        compose.onNodeWithText("DigiCampus room plan").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("planner-calendar").performScrollToNode(hasTestTag("campus-room-status"))
        compose.onNodeWithText("DigiCampus room plan").assertExists()
        compose.onNodeWithText("Return to my timetable").performScrollTo().performClick()
        compose.waitUntil(5_000) { container.repository.value!!.planner.snapshot.value.settings.campusRoom == null }
        Assert.assertEquals(enrolled, container.repository.value!!.snapshot.value.courses.map { it.id }.toSet())
    }

    @Test fun globalRoomSearchMatchesAliasesAndRestoresTheResourceRatherThanACourseLocation() {
        preview()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithTag("planner-explore").performClick()
        compose.onNodeWithTag("planner-mode-rooms").performClick()
        compose.onNodeWithTag("planner-search-query").performTextInput("2013 N")
        val key = campusRoomSearchKey("2013 N")
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.campusRooms[key] != null }
        compose.runOnIdle {
            Assert.assertEquals(listOf("preview-n2013"), container.repository.value!!.planner.snapshot.value.campusRooms.getValue(key).rooms.map { it.id })
        }
        compose.onNodeWithTag("planner-search-query").performTextReplacement("N-2.013")
        compose.onNodeWithTag("planner-explorer").performScrollToNode(hasTestTag("campus-room-preview-n2013"))
        compose.onNodeWithTag("campus-room-preview-n2013").assert(hasText("N · 2.013"))
        compose.onNodeWithTag("campus-room-preview-w2013").assertDoesNotExist()
        compose.onNodeWithTag("campus-room-preview-n2013").performClick()
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.roomWeeks.isNotEmpty() }
        compose.activityRule.scenario.recreate()
        val state = container.repository.value!!.planner.snapshot.value
        Assert.assertEquals("preview-n2013", state.settings.campusRoom?.id)
        Assert.assertFalse(state.settings.publishedRooms)
        compose.onNodeWithTag("planner-calendar").performScrollToNode(hasTestTag("campus-room-status"))
        compose.onNodeWithText("DigiCampus room plan").assertExists()
    }

    @Test fun anUnlistedRoomExplainsDirectoryCoverageAndOffersTheOfficialSearch() {
        preview()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithTag("planner-explore").performClick()
        compose.onNodeWithTag("planner-mode-rooms").performClick()
        compose.onNodeWithTag("planner-search-query").performTextInput("9999 N")
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.campusRooms[campusRoomSearchKey("9999 N")] != null }
        compose.onNodeWithTag("planner-explorer").performScrollToNode(hasTestTag("room-directory-gap"))
        compose.onNodeWithTag("room-directory-gap").assertTextContains("Some course locations have no searchable room entry or published booking plan.")
        compose.onNodeWithTag("planner-explorer").performScrollToNode(hasTestTag("room-directory-official"))
        compose.onNodeWithTag("room-directory-official").assertExists()
        compose.onNodeWithTag("campus-room-status").assertDoesNotExist()
        compose.onNodeWithText("Unbooked in the published plan").assertDoesNotExist()
    }

    @Test fun globalLecturerSearchFromFiltersIncludesTeachingOutsideEnrollment() {
        preview()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithTag("planner-filters").performClick()
        compose.onNodeWithTag("planner-teacher-filter").performTextInput("Weber")
        compose.onNodeWithTag("planner-filter-sheet").performScrollToNode(hasTestTag("planner-global-lecturer-search"))
        compose.onNodeWithTag("planner-global-lecturer-search").performClick()
        compose.onNodeWithTag("planner-mode-lecturer").assertIsSelected()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("planner-preview-lecturer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("planner-explorer").performScrollToNode(hasTestTag("planner-preview-lecturer"))
        compose.onNodeWithTag("planner-preview-lecturer").performClick()
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.settings.lecturerResult != null }
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.schedules.values.any { it.course.id == "planning-ai" } }
        val repo = container.repository.value!!
        Assert.assertTrue(repo.planner.snapshot.value.settings.lecturerResult!!.complete)
        compose.onNodeWithText("Teaching week").assertExists()
        Assert.assertTrue(repo.planner.snapshot.value.settings.overlays.any { it.course.id == "planning-ai" })
        Assert.assertFalse(repo.snapshot.value.courses.any { it.id == "planning-ai" })
        compose.activityRule.scenario.recreate()
        Assert.assertEquals("Weber", container.repository.value!!.planner.snapshot.value.settings.lecturerResult?.query)
        compose.onNodeWithTag("planner-calendar").performScrollToNode(hasText("Close course preview"))
        compose.onNodeWithText("Close course preview").performClick()
        compose.waitUntil(5_000) { container.repository.value!!.planner.snapshot.value.settings.lecturerResult == null }
    }

    @Test fun lecturerExplorationSavesSelectedSessionsAndShowsClashesAfterRecreation() {
        preview()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithTag("planner-explore").performClick()
        compose.onNodeWithTag("planner-mode-lecturer").performClick()
        compose.onNodeWithTag("planner-search-query").performTextInput("Weber")
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.pages.values.any { page -> page.courses.any { it.id == "planning-ai" } } }
        compose.onNodeWithTag("planner-explorer").performScrollToNode(hasTestTag("planner-result-planning-ai"))
        compose.onNodeWithTag("planner-result-planning-ai").performClick()
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.schedules.values.any { it.course.id == "planning-ai" } }
        compose.onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
        compose.onNodeWithTag("planner-save-plan").performScrollTo().performClick()
        compose.waitUntil(5_000) { container.repository.value!!.planner.snapshot.value.plans.any { it.course.id == "planning-ai" } }
        Assert.assertEquals(2, container.repository.value!!.planner.snapshot.value.plans.single().groups.size)
        compose.onNodeWithTag("planner-course-detail").performScrollToNode(hasText("Close"))
        compose.onNodeWithText("Close").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("planner-tools").performClick()
        compose.onNodeWithText("Clashes this week").assertExists()
        compose.onNodeWithTag("planner-tools-sheet").performScrollToNode(hasText("Artificial Intelligence"))
        compose.onAllNodesWithText("Artificial Intelligence", substring = true).onFirst().assertExists()
        val repository = container.repository.value!!
        val state = repository.planner.snapshot.value
        val term = repository.snapshot.value.preferences.semesterId!!
        Assert.assertTrue(plannerClashes(plannerEntries(repository.snapshot.value, state, term),
            weekDates(campusToday()).first().atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds(),
            weekDates(campusToday()).first().plus(7, kotlinx.datetime.DateTimeUnit.DAY).atStartOfDayIn(CAMPUS_ZONE).toEpochMilliseconds()).isNotEmpty())
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithTag("planner-calendar").assertExists()
        compose.onNodeWithTag("planner-explore").performClick()
        compose.onNodeWithTag("planner-mode-lecturer").performClick()
        compose.onNodeWithTag("planner-search-query").performTextInput("Weber")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("planner-preview-lecturer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("planner-explorer").performScrollToNode(hasTestTag("planner-preview-lecturer"))
        compose.onNodeWithTag("planner-preview-lecturer").performClick()
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.settings.overlays.size == 2 }
        Assert.assertTrue(container.repository.value!!.planner.snapshot.value.settings.exploreOnly)
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.schedules.values.count { it.course.id in setOf("algorithms", "planning-ai") } == 2 }
    }

    @Test fun calendarFiltersCanBeNamedAppliedAndRestored() {
        preview()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithTag("planner-filters").performClick()
        compose.onNodeWithTag("planner-room-filter").performTextInput("1002")
        compose.onNodeWithTag("planner-teacher-filter").performTextInput("Weber")
        compose.onNodeWithTag("planner-filter-sheet").performScrollToNode(hasTestTag("planner-filter-name"))
        compose.onNodeWithTag("planner-filter-name").performTextInput("Algorithms room")
        compose.runOnIdle {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        compose.onNodeWithTag("planner-filter-sheet").performScrollToNode(hasTestTag("planner-save-filter"))
        compose.onNodeWithTag("planner-save-filter").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { container.repository.value!!.planner.snapshot.value.savedFilters.isNotEmpty() }
        compose.onNodeWithTag("planner-filter-sheet").performScrollToNode(hasText("Algorithms room"))
        compose.onNodeWithText("Algorithms room").performClick()
        compose.activityRule.scenario.recreate()
        Assert.assertEquals("1002", container.repository.value!!.planner.snapshot.value.settings.filter.room)
        Assert.assertEquals("Weber", container.repository.value!!.planner.snapshot.value.settings.filter.lecturer)
        compose.onNodeWithTag("planner-filters").performClick()
        compose.onNodeWithTag("planner-filter-sheet").performScrollToNode(hasText("Algorithms room"))
        compose.onNodeWithText("Algorithms room").assertExists()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        Assert.assertFalse(compose.activity.isFinishing)
    }

    @Test fun roomBrowserScrollingKeepsThePanelFixedAtBothListEdges() {
        preview()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithTag("planner-explore").performClick()
        compose.onNodeWithTag("planner-mode-rooms").performClick()
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.rooms != null }
        val list = compose.onNodeWithTag("planner-explorer")
        val frame = list.fetchSemanticsNode().boundsInRoot
        // Dragging past the first item must not move or dismiss the surrounding panel.
        list.performTouchInput {
            swipe(Offset(width * .8f, height * .25f), Offset(width * .8f, height * .8f), durationMillis = 600)
        }
        Assert.assertEquals(frame, list.fetchSemanticsNode().boundsInRoot)
        repeat(3) {
            list.performTouchInput { swipeUp(durationMillis = 600) }
            Assert.assertEquals(frame, list.fetchSemanticsNode().boundsInRoot)
        }
        compose.onNodeWithText("Close").assertIsDisplayed()
        repeat(3) {
            list.performTouchInput { swipeDown(durationMillis = 600) }
            Assert.assertEquals(frame, list.fetchSemanticsNode().boundsInRoot)
        }
        compose.onNodeWithTag("planner-mode-rooms").assertIsDisplayed()
        // A focused search field and the keyboard must not start competing scrolls.
        compose.onNodeWithTag("planner-search-query").performClick()
        compose.waitUntil(5_000) { list.fetchSemanticsNode().boundsInRoot.height < frame.height * .85f }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle()
        val typingFrame = list.fetchSemanticsNode().boundsInRoot
        repeat(3) {
            list.performTouchInput { swipeUp(durationMillis = 600) }
            Assert.assertEquals(typingFrame, list.fetchSemanticsNode().boundsInRoot)
        }
        compose.onNodeWithText("Close").assertIsDisplayed()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack() // keyboard
        device.pressBack() // panel
        compose.onNodeWithTag("planner-calendar").assertExists()
        Assert.assertFalse(compose.activity.isFinishing)
    }

    @Test fun publishedRoomExplorationShowsTheDateBoundedPlanAndKnownUnbookedWindows() {
        preview()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithTag("planner-explore").performClick()
        compose.onNodeWithTag("planner-mode-rooms").performClick()
        compose.waitUntil(10_000) { container.repository.value!!.planner.snapshot.value.rooms != null }
        compose.onNodeWithTag("planner-explorer").performScrollToNode(hasText("WiWi · 1113"))
        compose.onNodeWithText("WiWi · 1113").performClick()
        compose.waitUntil(5_000) { container.repository.value!!.planner.snapshot.value.settings.publishedRooms }
        compose.onNodeWithTag("planner-calendar").performScrollToNode(hasText("Unbooked in the published plan"))
        compose.onNodeWithText("Unbooked in the published plan").assertExists()
        compose.onNodeWithText("University WiWi booking plan").assertExists()
        compose.activityRule.scenario.recreate()
        Assert.assertEquals("WiWi · 1113", container.repository.value!!.planner.snapshot.value.settings.filter.room)
        Assert.assertTrue(container.repository.value!!.planner.snapshot.value.settings.publishedRooms)
    }

    @Test fun courseworkOverviewSeparatesSubmissionFromProgressAndBackRestoresTheList() {
        preview()
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithTag("today-scroll").performScrollToNode(hasText("All coursework"))
        compose.onNodeWithText("All coursework").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.coursework?.size == 4 }
        compose.onNodeWithTag("coursework-scroll").performScrollToNode(hasText("Vectors and linear maps"))
        compose.onNodeWithTag("task-vectors").performClick()
        compose.onNodeWithText("Not submitted").assertExists()
        compose.onNodeWithTag("task-detail-scroll").performScrollToNode(hasText("100% · Content progress does not confirm submission."))
        compose.onNodeWithText("100% · Content progress does not confirm submission.").assertIsDisplayed()
        compose.onNodeWithTag("task-detail-scroll").performScrollToNode(hasText("Continue in DigiCampus"))
        compose.onNodeWithText("Continue in DigiCampus").assertIsDisplayed()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        compose.onNodeWithTag("tasks-submitted").performScrollTo().performClick()
        compose.onNodeWithTag("task-sorting").assertExists()
        compose.onNodeWithTag("task-vectors").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tasks-submitted").assertIsSelected()
        device.pressBack()
        compose.onNodeWithTag("today-scroll").assertExists()
    }

    @Test fun courseTaskDetailsSurviveRecreationAndBoardSwitchingAndReturnToCoursework() {
        preview()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.onNodeWithText("Coursework").performScrollTo().performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.coursework?.get("algorithms")?.isNotEmpty() == true }
        compose.onNodeWithTag("task-graphs").performScrollTo().performClick()
        compose.onNodeWithText("Shortest paths & graph traversal").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Shortest paths & graph traversal").assertIsDisplayed()
        switchBoard("mensa")
        switchBoard("study")
        compose.onNodeWithText("Shortest paths & graph traversal").assertIsDisplayed()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        compose.onNodeWithText("Coursework").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("task-graphs").assertExists()
        device.pressBack()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
    }

    @Test fun nativeTaskSubmissionRequiresConfirmationAndUpdatesTheRemoteStatus() {
        preview()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Software Engineering"))
        compose.onNodeWithText("Software Engineering").performClick()
        compose.onNodeWithText("Coursework").performScrollTo().performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.coursework?.get("software")?.isNotEmpty() == true }
        compose.onNodeWithTag("task-prototype").performScrollTo().performClick()
        compose.onNodeWithTag("task-detail-scroll").performScrollToNode(hasTestTag("submit-task"))
        compose.onNodeWithTag("submit-task").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Submit this task?").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("Submit this task?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        Assert.assertFalse(container.repository.value!!.snapshot.value.coursework["software"]!!.single().submitted)
        compose.onNodeWithTag("submit-task").performClick()
        compose.onAllNodesWithText("Submit task").onLast().performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.coursework?.get("software")?.single()?.submitted == true }
        compose.onNodeWithText("Preview submission confirmed").assertExists()
        compose.onNodeWithTag("submit-task").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Preview submission confirmed").assertExists()
    }

    @Test fun aSubmissionFolderSupportsPersonalDeadlinesAndLocalCompletionAcrossRecreation() {
        preview()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.onNodeWithText("Coursework").performScrollTo().performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.assignmentFolders?.get("algorithms")?.isNotEmpty() == true }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("assignment-algorithms-submissions"))
        compose.onNodeWithTag("assignment-algorithms-submissions").performClick()
        compose.onNodeWithText("Add personal deadline").performScrollTo().performClick()
        compose.onNodeWithText("Deadline time").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { container.repository.value?.snapshot?.value?.personalDeadlines?.containsKey("algorithms-submissions") == true }
        compose.onNodeWithText("Done on this device").performScrollTo().performClick()
        compose.waitUntil(5_000) { container.repository.value?.snapshot?.value?.personalDeadlines?.get("algorithms-submissions")?.completed == true }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Done on this device").performScrollTo().assertExists()
        Assert.assertTrue(container.repository.value!!.snapshot.value.personalDeadlines["algorithms-submissions"]!!.completed)
        compose.onNodeWithText("Remove deadline").performScrollTo().performClick()
        compose.waitUntil(5_000) { container.repository.value?.snapshot?.value?.personalDeadlines?.isEmpty() == true }
        compose.runOnIdle { container.setSettings(container.settings.value.copy(language = AppLanguage.GERMAN)) }
        compose.onNodeWithText("Persönliche Frist setzen").performScrollTo().performClick()
        val next = compose.onNodeWithText("Uhrzeit der Frist").assertIsDisplayed()
        val bounds = next.fetchSemanticsNode().boundsInWindow
        val displayWidth = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).displayWidth
        Assert.assertTrue("The German date action must fit on screen", bounds.left >= 0f && bounds.right <= displayWidth)
        if (compose.onAllNodesWithTag("personal-deadline-date").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("personal-deadline-date").performTextReplacement("2026-02-30")
            next.assertIsNotEnabled()
            compose.onNodeWithTag("personal-deadline-date").performTextReplacement("2026-10-16")
            next.assertIsEnabled().performClick()
            compose.onNodeWithText("Speichern").performClick()
            compose.waitUntil(5_000) { container.repository.value?.snapshot?.value?.personalDeadlines?.isNotEmpty() == true }
            Assert.assertEquals(LocalDate(2026, 10, 16), campusToday(container.repository.value!!.snapshot.value.personalDeadlines.getValue("algorithms-submissions").due))
        } else compose.onNodeWithText("Abbrechen").performClick()
    }

    @Test fun systemFilePickerCreatesAReviewableUploadAndPreviewReceiptWithoutSendingAutomatically() {
        preview()
        val resolver = compose.activity.contentResolver
        val fileName = "ZenCampus-instrumentation-work.pdf"
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
        })!!
        try {
            resolver.openOutputStream(uri)!!.use { it.write("%PDF-1.4\nPreview assignment".toByteArray()) }
            compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
            compose.onNodeWithText("Coursework").performScrollTo().performClick()
            compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.assignmentFolders?.get("algorithms")?.isNotEmpty() == true }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("assignment-algorithms-submissions"))
            compose.onNodeWithTag("assignment-algorithms-submissions").performClick()
            compose.onNodeWithTag("choose-assignment-file").performScrollTo().performClick()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            var file = device.wait(Until.findObject(By.text(fileName)), 5_000)
            if (file == null) {
                device.findObject(By.desc("Show roots"))?.click()
                device.wait(Until.findObject(By.text("Downloads")), 5_000)?.click()
                file = device.wait(Until.findObject(By.text(fileName)), 10_000)
            }
            Assert.assertNotNull("The test document must appear in the system file picker", file)
            file!!.click()
            compose.waitUntil(10_000) { container.repository.value?.uploadDraft?.value != null }
            Assert.assertNull(container.repository.value!!.snapshot.value.courseworkWrites["write:upload:algorithms-submissions"])
            compose.onNodeWithText(fileName).assertExists()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(fileName).assertExists()
            compose.onNodeWithTag("confirm-assignment-upload").performClick()
            compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courseworkWrites?.get("write:upload:algorithms-submissions")?.status == WriteStatus.CONFIRMED }
            compose.onAllNodesWithText("Preview upload confirmed · no file was sent").onLast().assertExists()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Preview upload confirmed · no file was sent").assertExists()
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun courseworkNotificationDestinationOpensStudyAndPreferencesSurviveRecreation() {
        preview()
        compose.runOnIdle { container.setSettings(container.settings.value.copy(courseworkReminders = true)) }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { container.settings.value.courseworkReminders }
        val originalIntent = compose.activity.intent
        compose.runOnIdle { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity,
            android.content.Intent(compose.activity, MainActivity::class.java).putExtra("openService", "coursework")) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("coursework-scroll").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("coursework-scroll").assertExists()
        compose.onNodeWithText("Deadlines and submissions").assertExists()
        // ActivityScenario matches lifecycle events to its original launch intent.
        compose.runOnIdle { compose.activity.intent = originalIntent }
    }

    @Test fun downloadingMaterialRowStillAcceptsAnOpenRequest() {
        var requests = 0
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalWords provides Words(false)) {
                ZenTheme(ThemeMode.LIGHT) {
                    DocumentRow(Document("busy", "c", "f", "Lecture.pdf", 100, "application/pdf"), null,
                        SyncStatus(active = setOf("download:busy"), materialProgress = mapOf("busy" to MaterialProgress(50, 100))),
                        { requests++ }, {}, {})
                }
            }
        }
        }
        compose.onNodeWithText("Downloading… · 50%").assertIsDisplayed()
        compose.onNodeWithText("Lecture.pdf").performClick()
        compose.runOnIdle { Assert.assertEquals(1, requests) }
        compose.onNodeWithContentDescription("Files").assertIsDisplayed()
    }

    @Test fun blubberThreadsCanBeReadAndRepliedTo() {
        preview()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courseTools?.get("algorithms") != null }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("community-link-blubber"))
        compose.onNodeWithTag("community-link-blubber").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Welcome").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Welcome").performClick()
        compose.onNodeWithText("Welcome to Blubber.").assertExists()
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithTag("forum-reply-body").performTextInput("Thanks for the update")
        compose.onNodeWithTag("forum-send").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Thanks for the update").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun courseFolderPickerPersistsAndExportsWithoutOverwriting() {
        preview()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.onNodeWithText("Materials").performClick()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("choose-material-folder"))
        compose.onNodeWithTag("choose-material-folder").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val roots = device.wait(Until.findObject(By.desc("Show roots")), 10_000)
        roots?.click()
        val storage = device.wait(Until.findObject(By.textContains("sdk_gphone")), 5_000)
        storage?.click()
        val documents = device.wait(Until.findObject(By.text("Documents")), 5_000)
        Assert.assertNotNull("The Android folder picker should expose Documents", documents)
        documents!!.click()
        val choose = device.wait(Until.findObject(By.textContains("Use this folder")), 5_000)
            ?: device.findObject(By.textContains("USE THIS FOLDER"))
        Assert.assertNotNull("The Android folder picker should allow selection", choose)
        choose!!.click()
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)allow"))), 5_000)?.click()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.preferences?.materialDestinations?.containsKey("algorithms") == true }
        val destination = container.repository.value!!.snapshot.value.preferences.materialDestinations.getValue("algorithms")
        Assert.assertEquals("Documents", destination.label)
        Assert.assertTrue(compose.activity.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == destination.location && it.isReadPermission && it.isWritePermission
        })
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.preferences?.materialDestinations?.get("algorithms") == destination }
        val storageBackend = AndroidMaterials(compose.activity)
        val scope = "folder-test-${java.util.UUID.randomUUID()}"
        val name = "ZenCampus test ${java.util.UUID.randomUUID()}.txt"
        val created = mutableListOf<android.net.Uri>()
        try {
            kotlinx.coroutines.runBlocking {
                val writer = storageBackend.create(scope, Document("test", "algorithms", "", name, mimeType = "text/plain"))
                writer.write("content".toByteArray()); val path = writer.finish()
                storageBackend.saveToFolder(path, "text/plain", name, destination)
                storageBackend.saveToFolder(path, "text/plain", name, destination)
            }
            val tree = android.net.Uri.parse(destination.location)
            val parent = android.provider.DocumentsContract.getTreeDocumentId(tree)
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val resolver = compose.activity.contentResolver
            val names = mutableMapOf<String, String>()
            resolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
                while (cursor.moveToNext()) names[cursor.getString(1)] = cursor.getString(0)
            }
            val second = name.removeSuffix(".txt") + " (1).txt"
            listOf(name, second).forEach { filename ->
                val id = names[filename]; Assert.assertNotNull(filename, id)
                val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id!!); created += uri
                Assert.assertEquals("content", resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            }
            // Sync writes into subfolders of the chosen folder and replaces its earlier copy instead of numbering it.
            val folderName = "ZenCampus sync ${java.util.UUID.randomUUID().toString().take(8)}"
            kotlinx.coroutines.runBlocking {
                listOf("first", "second").forEach { content ->
                    val writer = storageBackend.syncWriter(destination, listOf(folderName), "notes.txt", "text/plain")
                    writer.write(content.toByteArray()); writer.finish()
                }
            }
            val folderId = resolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) to cursor.getString(1) else null }.firstOrNull { it.second == folderName }?.first
            }
            Assert.assertNotNull(folderId)
            created += android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, folderId!!)
            val synced = mutableListOf<Pair<String, String>>()
            resolver.query(android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId), arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
                while (cursor.moveToNext()) synced += cursor.getString(0) to cursor.getString(1)
            }
            Assert.assertEquals(listOf("notes.txt"), synced.map { it.second })
            Assert.assertEquals("second", resolver.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, synced.single().first))!!
                .bufferedReader().use { it.readText() })
        } finally {
            created.forEach { runCatching { android.provider.DocumentsContract.deleteDocument(compose.activity.contentResolver, it) } }
            kotlinx.coroutines.runBlocking { storageBackend.clear(scope) }
        }
        compose.onNodeWithTag("reset-material-folder").performScrollTo().performClick()
        compose.waitUntil(5_000) { "algorithms" !in container.repository.value!!.snapshot.value.preferences.materialDestinations }
    }

    @Test fun materialMenuExportsToDownloadsAndOpensWithAThirdPartyReader() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val resolver = compose.activity.contentResolver
        // A saved copy goes where material sync puts it: the course's folder, then its DigiCampus folders.
        val folder = "Download/ZenCampus/Algorithmen und Datenstrukturen/Lecture notes/"
        val name = "01 · Getting started.pdf"
        // The copies of this file; a material sync earlier in the run may already have put one there.
        fun exportedIds(): Set<Long> = resolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Downloads._ID),
            "${android.provider.MediaStore.Downloads.RELATIVE_PATH} = ? AND ${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(folder, name), null)!!.use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
        val before = exportedIds()
        try {
            preview()
            compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
            compose.onNodeWithText("Materials").performClick()
            compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.folderContents?.values?.any {
                it.folders.any { folder -> folder.name == "Lecture notes" }
            } == true }
            compose.onNodeWithTag("course-scroll").performScrollToNode(hasText("Lecture notes"))
            compose.onNodeWithText("Lecture notes").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(name).performScrollTo()
            compose.onAllNodesWithContentDescription("Files").onFirst().performClick()
            compose.onNodeWithText("Save a copy").performClick()
            compose.waitUntil(10_000) { container.repository.value!!.sync.value.materialExports.isNotEmpty() }
            val first = exportedIds()
            Assert.assertEquals(1, first.size)
            Assert.assertTrue("the saved copy is new or replaces the earlier one", (first - before).isNotEmpty())
            compose.onAllNodesWithContentDescription("Files").onFirst().performScrollTo().performClick()
            compose.onNodeWithText("Open with…").performClick()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val reader = device.wait(Until.findObject(By.text("Material test reader")), 10_000)
            Assert.assertNotNull("Android must offer the separate test reader", reader)
            reader!!.click()
            device.findObject(By.text("Just once"))?.click()
            Assert.assertTrue(device.wait(Until.hasObject(By.textContains("Header: %PDF-")), 10_000))
            Assert.assertTrue(device.hasObject(By.textContains("Name: $name")))
            device.pressBack()
            compose.onNodeWithText(name).performScrollTo().performClick()
            compose.waitUntil(10_000) { device.hasObject(By.desc("Page 1")) }
            device.findObject(By.desc("Save a copy")).click()
            // Saving again replaces the copy instead of adding a numbered one.
            compose.waitUntil(10_000) { exportedIds().let { it.size == 1 && it != first } }
            device.pressBack()
        } finally {
            (exportedIds() - before).forEach { id -> resolver.delete(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
        }
    }

    @Test fun browsesNestedMaterialsSavesOfflineAndOpensPdf() {
        preview()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.onNodeWithText("Materials").performClick()
        compose.waitUntil(10_000) {
            container.repository.value?.snapshot?.value?.folderContents?.values?.any { it.folders.any { f -> f.name == "Lecture notes" } } == true
        }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasText("Lecture notes"))
        compose.onNodeWithText("Lecture notes").performClick()
        compose.waitUntil(10_000) {
            container.repository.value?.snapshot?.value?.folderContents?.get("folder:algorithms:algorithms-lectures")
                ?.documents?.any { it.name == "01 · Getting started.pdf" } == true
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("01 · Getting started.pdf"))
        compose.onNodeWithText("01 · Getting started.pdf").performScrollTo()
        compose.onAllNodesWithContentDescription("Files").onFirst().performClick()
        compose.onNodeWithText("Keep offline").performClick()
        compose.waitUntil(10_000) {
            container.repository.value?.snapshot?.value?.localDocuments?.any { it.pinned } == true
        }
        compose.onNodeWithText("Saved offline").assertExists()
        compose.onNodeWithText("01 · Getting started.pdf").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.waitUntil(10_000) { device.hasObject(By.desc("Page 1")) }
        Assert.assertTrue("The first PDF page must render", device.hasObject(By.desc("Page 1")))
        device.pressBack()
    }

    @Test fun courseOverviewTracksNestedRecentFilesAndOpensThemWithoutFolderNavigation() {
        preview()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.waitUntil(15_000) { container.repository.value?.snapshot?.value?.courseMaterials?.get("algorithms") != null }
        compose.runOnIdle {
            val snapshot = container.repository.value!!.snapshot.value
            Assert.assertNull("The tracker must not require visiting nested directories", snapshot.folderContents["folder:algorithms:algorithms-archive"])
            Assert.assertTrue(snapshot.courseMaterials.getValue("algorithms").recentFiles(nowMillis()).isNotEmpty())
        }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("recent-file-algorithms-archive-0"))
        compose.onNodeWithText("Lecture notes / Additional reading").assertIsDisplayed()
        compose.onNodeWithText("Further reading.pdf").assertIsDisplayed()
        compose.onNodeWithTag("recent-file-algorithms-archive-0").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.waitUntil(15_000) { device.hasObject(By.desc("Page 1")) }
        device.pressBack()
        compose.onNodeWithTag("recent-file-algorithms-archive-0").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("recent-file-algorithms-archive-0").assertExists()
        compose.onNodeWithTag("course-scroll").performScrollToIndex(0)
        compose.onNodeWithText("Materials").performClick()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("material-folder-algorithms-lectures"))
        compose.onNodeWithTag("material-folder-algorithms-lectures").assert(hasText("Latest file activity", substring = true))
        compose.onNodeWithTag("material-folder-algorithms-lectures").assert(hasText("Folder updated", substring = true))
    }

    @Test fun staleRecentFilesAndFolderDatesFitGermanAtNarrowWidthWithoutAnAllClearClaim() {
        preview()
        val course = Course("algorithms", "Algorithmen")
        val now = LocalDateTime(2026, 10, 21, 12, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
        val folder = CampusFolder("week", course.id, "Übungsblätter und zusätzliche Aufgaben", "root", modified = now - 20 * 86_400_000L)
        val root = CampusFolder("root", course.id, "Dateien")
        val doc = Document("recent-fixture", course.id, folder.id, "Übungsblatt 3 mit ausführlichen Erläuterungen.pdf", modified = now - 60_000, created = now - 30 * 86_400_000L)
        val snapshot = CampusSnapshot(Account("preview", "Alex", "alex", demo = true), courses = listOf(course),
            courseMaterials = mapOf(course.id to CourseMaterials(course.id, listOf(root, folder), listOf(doc))), updatedAt = mapOf("course-files:${course.id}" to now - 3_600_000))
        val repository = container.repository.value!!
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalWords provides Words(true), LocalNow provides now, LocalDensity provides Density(density, 1.3f)) {
                ZenTheme(ThemeMode.LIGHT) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.width(320.dp).testTag("activity-test-scroll")) {
                        item { FolderActivityDetails(folder, snapshot.courseMaterials[course.id]) }
                        recentMaterialItems(course, snapshot, repository,
                            SyncStatus(errors = mapOf("course-files:${course.id}" to FailureKind.OFFLINE)), now, {})
                    }
                }
            }
        } }
        compose.onNodeWithText("Letzte Dateiaktivität", substring = true).assertExists()
        compose.onNodeWithText("Ordner aktualisiert", substring = true).assertExists()
        compose.onNodeWithTag("activity-test-scroll").performScrollToNode(hasText("Gespeicherte Liste · Aktualisierung fehlgeschlagen. Dateien können sich geändert haben."))
        compose.onNodeWithText("Gespeicherte Liste · Aktualisierung fehlgeschlagen. Dateien können sich geändert haben.").assertIsDisplayed()
        compose.onNodeWithTag("activity-test-scroll").performScrollToNode(hasTestTag("recent-file-recent-fixture"))
        compose.onNodeWithText(doc.name).assertIsDisplayed()
        compose.onNodeWithText("Aktualisiert", substring = true).assertExists()
        compose.onNodeWithText("Keine datierten neuen oder aktualisierten Dateien in den letzten 14 Tagen.").assertDoesNotExist()
    }

    @Test fun upNextOpensTheCalendarOnTheActualClassDate() {
        preview()
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("See in calendar").performScrollTo().performClick()
        compose.onNodeWithText("Your week").assertIsDisplayed()
        val event = nextUpcomingEvent(container.repository.value!!.snapshot.value.events, nowMillis())!!
        compose.onNodeWithContentDescription(io.github.binoverfl0w.zencampus.ui.Words(false).formatDate(event.date, true)).assertIsSelected()
    }

    @Test fun displaysAllFoldersWithoutAFilterAndPreservesSubmissionAccessAcrossRecreation() {
        preview()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.onNodeWithText("Materials").performClick()
        compose.waitUntil(10_000) {
            container.repository.value?.snapshot?.value?.folderContents?.values?.any {
                it.folders.any { folder -> folder.name == "Additional resources" }
            } == true
        }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasText("Additional resources"))
        compose.onNodeWithText("Additional resources").assertIsDisplayed()
        compose.onNodeWithText("Hide empty folders").assertDoesNotExist()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasText("Submit your exercises"))
        compose.onNodeWithText("Submit your exercises").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasText("Additional resources"))
        compose.onNodeWithText("Additional resources").assertIsDisplayed()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasText("Submit your exercises"))
        compose.onNodeWithText("Submit your exercises").performClick()
        compose.waitUntil(10_000) {
            container.repository.value?.snapshot?.value?.folderContents?.values?.any {
                it.currentFolder?.id?.endsWith("submissions") == true
            } == true
        }
        compose.onNodeWithText("Open in DigiCampus").performScrollTo().assertExists()
        compose.onNodeWithText("Use DigiCampus to upload files or submit your work here.").assertExists()
    }

    @Test fun retainsCoursePinAndNavigationOnActivityRecreationAndChangesLanguage() {
        preview()
        compose.onAllNodesWithContentDescription("Pin course").onFirst().performClick()
        compose.waitUntil(5_000) { container.repository.value?.snapshot?.value?.preferences?.pinnedCourses?.isNotEmpty() == true }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unpin course").assertExists()
        compose.runOnIdle { container.setSettings(AppSettings(theme = ThemeMode.DARK, language = AppLanguage.GERMAN)) }
        compose.onNodeWithText("Deine Kurse").assertIsDisplayed()
        compose.onNodeWithContentDescription("Kurs lösen").assertExists()
    }

    @Test fun coursePinSelectionIsVisibleInBothTheListAndDetailScreen() {
        preview()
        compose.onNodeWithTag("course-pin-algorithms").assertIsOff().performClick()
        compose.waitUntil(5_000) { "algorithms" in container.repository.value!!.snapshot.value.preferences.pinnedCourses }
        compose.onNodeWithTag("course-pin-algorithms").assertIsOn()
        compose.onNodeWithTag("course-card-algorithms").performClick()
        compose.onNodeWithTag("course-pin-algorithms").assertIsOn()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("course-pin-algorithms").assertIsOn().performClick()
        compose.waitUntil(5_000) { "algorithms" !in container.repository.value!!.snapshot.value.preferences.pinnedCourses }
        compose.onNodeWithTag("course-pin-algorithms").assertIsOff()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        compose.onNodeWithTag("course-pin-algorithms").assertIsOff()
    }

    @Test fun sectionsReplaceHistoryWhileSettingsReturnToTheSelectedRootAndRootBackLeavesTheApp() {
        preview()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithText("Your week").assertIsDisplayed()
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("Calendar").performClick()
        compose.activityRule.scenario.recreate()
        openSettings()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Offline materials"))
        compose.onNodeWithText("Offline materials").performClick()
        compose.onNodeWithText("No offline files").assertExists()
        device.pressBack()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Appearance"))
        compose.onNodeWithText("Appearance").assertExists()
        device.pressBack()
        compose.onNodeWithText("Your week").assertIsDisplayed()
        compose.onNodeWithTag("section-calendar").assertIsSelected()
        val activity = compose.activity
        device.pressBack()
        device.waitForIdle()
        Assert.assertTrue("Back at a board root must leave the app",
            activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus())
    }

    @Test fun hardwareBackTraversesFolderParentsThenReturnsDirectlyToTheCourseOpener() {
        preview()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.onNodeWithText("Updates").performClick()
        compose.onNodeWithText("Materials").performClick()
        compose.waitUntil(10_000) {
            container.repository.value!!.snapshot.value.folderContents.values.any { it.folders.any { f -> f.name == "Lecture notes" } }
        }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasText("Lecture notes"))
        compose.onNodeWithText("Lecture notes").performClick()
        compose.waitUntil(10_000) {
            container.repository.value!!.snapshot.value.folderContents.values.any { it.folders.any { f -> f.name == "Additional reading" } }
        }
        compose.onNodeWithText("Additional reading").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        device.pressBack()
        compose.onNodeWithText("Additional reading").assertExists()
        device.pressBack()
        compose.onNodeWithText("Materials").performScrollTo().assertIsSelected()
        device.pressBack()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        Assert.assertFalse(compose.activity.isFinishing)
    }

    @Test fun folderBreadcrumbsJumpWithoutLeavingOldStepsAndCourseTabsRememberTheirFolder() {
        preview()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.onNodeWithText("Materials").performClick()
        compose.waitUntil(10_000) {
            container.repository.value!!.snapshot.value.folderContents["folder:algorithms:root"]?.folders?.any { it.id == "algorithms-lectures" } == true
        }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("material-folder-algorithms-lectures"))
        compose.onNodeWithTag("material-folder-algorithms-lectures").performClick()
        compose.waitUntil(10_000) {
            container.repository.value!!.snapshot.value.folderContents["folder:algorithms:algorithms-lectures"]?.folders?.any { it.id == "algorithms-archive" } == true
        }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("material-folder-algorithms-archive"))
        compose.onNodeWithTag("material-folder-algorithms-archive").performClick()
        compose.onNodeWithTag("course-scroll").performScrollToIndex(0)
        compose.onNodeWithText("Overview").performClick()
        compose.onNodeWithText("Materials").performClick()
        compose.onNodeWithTag("materials-ancestor-algorithms-archive").assertExists()
        switchBoard("library")
        switchBoard("study")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("materials-root"))
        compose.onNodeWithTag("materials-root").performClick()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("material-folder-algorithms-lectures"))
        compose.onNodeWithTag("material-folder-algorithms-lectures").assertExists()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.onNodeWithText("Materials").performClick()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("materials-courses"))
        compose.onNodeWithTag("materials-courses").performClick()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
    }

    @Test fun hardwareBackDismissesAnnouncementsAndCalendarShortcutReturnsToToday() {
        preview()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("See in calendar").performScrollTo().performClick()
        compose.onNodeWithText("Your week").assertIsDisplayed()
        device.pressBack()
        compose.onNodeWithTag("today-scroll").performScrollToNode(hasTestTag("weather-chip"))
        compose.onNodeWithTag("weather-chip").assertExists()
        compose.waitUntil(10_000) {
            container.repository.value!!.snapshot.value.announcements.any { it.title == "Exercise sheet 02 is ready" }
        }
        compose.onNodeWithTag("today-scroll").performScrollToNode(hasText("Exercise sheet 02 is ready"))
        compose.onNodeWithText("Exercise sheet 02 is ready").performClick()
        compose.onNodeWithText("Mark as read").assertExists()
        device.pressBack()
        compose.onNodeWithText("Mark as read").assertDoesNotExist()
        compose.onNodeWithTag("today-scroll").performScrollToNode(hasTestTag("weather-chip"))
        compose.onNodeWithTag("weather-chip").assertExists()
        Assert.assertFalse(compose.activity.isFinishing)
    }

    @Test fun signOutRemovesOnlyTheAccountCacheAndReturnsToCentralAccounts() {
        preview()
        val account = container.repository.value!!.account
        openSettings()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Your cards"))
        compose.onNodeWithText("Your cards").performClick()
        compose.onNodeWithContentDescription("Card options · DigiCampus").performClick()
        compose.onNodeWithText("Sign out").performClick()
        compose.onAllNodesWithText("Sign out").onLast().performClick()
        compose.waitUntil(10_000) { container.repository.value == null && cachedRows(account.cacheScope) == 0 }
        compose.onNodeWithText("Connect DigiCampus").assertIsDisplayed()
        val directory = File(compose.activity.filesDir, "materials/" + AndroidMaterials.digest(account.cacheScope))
        Assert.assertFalse(directory.exists())
    }

    @Test fun forecastTimelineShowsTodaysHoursAndAllSevenWeekColumnsAlign() {
        preview()
        compose.onNodeWithText("Today").performClick()
        val today = campusToday()
        val week = weekDates(today)
        compose.waitUntil(10_000) {
            container.repository.value?.snapshot?.value?.weather?.let { weather ->
                weather.hoursOn(today).isNotEmpty() && week.all { weather.day(it) != null }
            } == true
        }
        compose.onNodeWithTag("today-scroll").performScrollToNode(hasTestTag("weather-chip"))
        compose.onNodeWithTag("weather-chip").performClick()
        compose.onNodeWithTag("today-scroll").performScrollToNode(hasTestTag("hourly-forecast"))
        compose.onNodeWithTag("hourly-forecast").assertExists()
        compose.onNodeWithText("Now").assertExists()
        compose.onNodeWithTag("hourly-forecast").performScrollToIndex(0)
        assertHourlyColumnsAlign()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithText("Weather · high / low").assertExists()
        val row = compose.onNodeWithTag("calendar-week-days").fetchSemanticsNode().boundsInRoot
        val cells = week.map { date ->
            compose.onNodeWithContentDescription(Words(false).formatDate(date, true)).fetchSemanticsNode().boundsInRoot
        }
        Assert.assertTrue("The week must span the row", abs(cells.first().left - row.left) < 1.1f && abs(cells.last().right - row.right) < 1.1f)
        Assert.assertTrue("All seven days must have equal widths", cells.maxOf { it.width } - cells.minOf { it.width } < 1.1f)
        Assert.assertTrue("Day and forecast baselines must align", cells.maxOf { it.top } - cells.minOf { it.top } < 1.1f &&
            cells.maxOf { it.bottom } - cells.minOf { it.bottom } < 1.1f)
        compose.onNodeWithContentDescription(Words(false).formatDate(week.last(), true)).performClick().assertIsSelected()
    }

    @Test fun calendarForecastLoadsWhenDataSaverIsEnabled() {
        compose.runOnIdle { container.setSettings(AppSettings(theme = ThemeMode.LIGHT, language = AppLanguage.ENGLISH, dataSaver = true)) }
        preview()
        compose.onNodeWithText("Calendar").performClick()
        val days = weekDates(campusToday())
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.weather?.let { weather -> days.all { weather.day(it) != null } } == true }
        compose.onNodeWithTag("planner-view-week").performClick()
        compose.onNodeWithTag("planner-calendar").performScrollToNode(hasTestTag("planner-week-grid"))
        days.forEach { date ->
            compose.onNodeWithTag("planner-grid-weather-$date", useUnmergedTree = true).assertIsDisplayed()
            val forecast = container.repository.value!!.snapshot.value.weather!!.day(date)!!
            compose.onNodeWithTag("planner-grid-temperature-$date", useUnmergedTree = true)
                .assertTextEquals("${kotlin.math.round(forecast.maxC).toInt()}°")
        }
        compose.onNodeWithTag("calendar-weather-refresh").assertDoesNotExist()
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test fun todayWeatherFitsNarrowScreensLargeTextAndBothLanguages() {
        val date = LocalDate(2026, 10, 14)
        val now = LocalDateTime(date.year, date.month, date.day, 10, 30).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
        val weather = CampusWeather(17.0, 2, 9.0, now, hourly = (0..23).map { hour ->
            HourlyWeather(LocalDateTime(date.year, date.month, date.day, hour, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds(),
                if (hour % 2 == 0) -14.0 else 18.0, 2, 100, true)
        })
        data class Size(val width: Int, val fontScale: Float, val german: Boolean)
        val size = mutableStateOf(Size(280, 1f, false))
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, size.value.fontScale),
                    LocalWords provides Words(size.value.german), LocalNow provides now) {
                    ZenTheme(ThemeMode.LIGHT) {
                        Box(Modifier.width(size.value.width.dp)) {
                            CampusWeatherCard(weather, loading = false, preview = true, onOpenUrl = {})
                        }
                    }
                }
            }
        }
        // Card widths represent 320–411 dp phones after the screen's 20 dp side margins.
        for (width in listOf(280, 320, 344, 371)) for (fontScale in listOf(1f, 1.3f)) for (german in listOf(false, true)) {
            compose.runOnIdle { size.value = Size(width, fontScale, german) }
            compose.onNodeWithTag("hourly-forecast").performScrollToIndex(0)
            assertHourlyColumnsAlign()
            val age = compose.onNodeWithTag("weather-age").fetchSemanticsNode().boundsInRoot
            val source = compose.onNodeWithTag("weather-source").fetchSemanticsNode().boundsInRoot
            Assert.assertTrue("Weather age and source must have space or separate rows at $width dp / $fontScale / $german",
                source.left >= age.right || source.top >= age.bottom)
            assertSingleLine(compose.onNodeWithTag("weather-age"))
            assertSingleLine(compose.onNodeWithTag("weather-source", useUnmergedTree = true))
            assertSingleLine(compose.onNodeWithText("00:00", useUnmergedTree = true))
            assertSingleLine(compose.onNodeWithText(Words(german).text("through_the_day")))
        }
    }

    private fun assertHourlyColumnsAlign() {
        val row = compose.onNodeWithTag("hourly-forecast").fetchSemanticsNode().boundsInRoot
        val cells = compose.onAllNodesWithTag("hourly-forecast-cell").fetchSemanticsNodes()
            .map { it.boundsInRoot }.filter { it.width > 1f && it.height > 1f }
        Assert.assertTrue("A phone must show multiple complete hourly columns", cells.size >= 3)
        Assert.assertTrue("Hourly columns must fill both edges of the inset", abs(cells.first().left - row.left) < 1.1f &&
            abs(cells.last().right - row.right) < 1.1f)
        Assert.assertTrue("Hourly column widths must match within rounding", cells.maxOf { it.width } - cells.minOf { it.width } < 1.1f)
        Assert.assertTrue("Hourly rows must share top and bottom bounds", cells.maxOf { it.top } - cells.minOf { it.top } < 1.1f &&
            cells.maxOf { it.bottom } - cells.minOf { it.bottom } < 1.1f)
    }

    @Test fun campusMenusKeepTheirFiltersAndWeekWhenSwitchingBoards() {
        preview()
        switchBoard("mensa")
        compose.waitUntil(10_000) { container.previewServices.mensa.value.menu != null }
        // The sample Mensa is closed at weekends; then the test works on the next week.
        val weekend = campusToday().dayOfWeek.ordinal >= 5
        val base = if (weekend) campusToday().plus(7, kotlinx.datetime.DateTimeUnit.DAY) else campusToday()
        if (weekend) {
            compose.onNodeWithContentDescription("Next week").performClick()
            compose.onNodeWithContentDescription(Words(false).formatDate(weekDates(base).first(), true)).performClick()
        }
        compose.onAllNodesWithText("Vegan").onFirst().performClick()
        compose.waitUntil(5_000) { container.previewServices.preferences.value.mealFilter == MealFilter.VEGAN }
        // In English the sample dishes are translated; the German name stays underneath for the counter.
        compose.waitUntil(5_000) { container.previewServices.mensa.value.menu!!.days.any { day -> day.meals.any { it.englishName == "Vegetable curry" } } }
        compose.onNodeWithTag("mensa-scroll").performScrollToNode(hasText("Vegetable curry"))
        compose.onNodeWithText("Gemüse-Curry").assertExists()
        compose.onNodeWithText("Vegetable curry").performClick()
        compose.onNodeWithText("Vegan (22) · Soy (So)").assertExists()
        compose.onNodeWithText("Fish fillet with potatoes").assertDoesNotExist()
        compose.onNodeWithTag("mensa-scroll").performScrollToNode(hasText("Student prices"))
        compose.onNodeWithText("Student prices").performClick()
        compose.onNodeWithText("Staff prices").performClick()
        compose.waitUntil(5_000) { container.previewServices.preferences.value.priceGroup == MealPriceGroup.STAFF }
        val sunday = weekDates(base).last()
        compose.onNodeWithContentDescription(Words(false).formatDate(sunday, true)).performClick()
        compose.onNodeWithTag("mensa-scroll").performScrollToNode(hasText("Mensa is closed"))
        compose.onNodeWithText("Mensa is closed").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Mensa is closed").assertExists()
        compose.waitForIdle()
        switchBoard("study")
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        switchBoard("mensa")
        compose.onNodeWithText("Mensa is closed").assertExists()
    }

    @Test fun sampleLibraryRenewalRequiresConfirmationAndDisconnectPreservesCourses() {
        preview()
        switchBoard("library")
        compose.onNodeWithTag("section-loans").performClick()
        openCards()
        compose.onNodeWithText("Connect library account").performScrollTo().performClick()
        compose.waitUntil(10_000) { container.previewServices.library.value.account?.loans?.size == 2 }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        val before = container.previewServices.library.value.account!!.loans.single { it.id == "algorithms-book" }.dueDate
        compose.onNodeWithTag("library-scroll").performScrollToNode(hasText("Renew"))
        compose.onNodeWithText("Renew").performClick()
        compose.onNodeWithText("Renew this book?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        Assert.assertEquals(before, container.previewServices.library.value.account!!.loans.single { it.id == "algorithms-book" }.dueDate)
        compose.onNodeWithText("Renew").performClick()
        compose.onAllNodesWithText("Renew").onLast().performClick()
        compose.waitUntil(10_000) { container.previewServices.library.value.result?.status == RenewalStatus.SUCCESS }
        Assert.assertNotEquals(before, container.previewServices.library.value.account!!.loans.single { it.id == "algorithms-book" }.dueDate)
        compose.onNodeWithText("Renewal confirmed").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.activityRule.scenario.recreate()
        openCards()
        compose.onNodeWithContentDescription("Card options · Library").performClick()
        compose.onNodeWithText("Disconnect library").performClick()
        compose.onAllNodesWithText("Disconnect library").onLast().performClick()
        compose.waitUntil(5_000) { !container.previewServices.library.value.connected }
        compose.onNodeWithText("Connect library account").assertExists()
        Assert.assertTrue(container.repository.value!!.snapshot.value.courses.isNotEmpty())
        device.pressBack()
        compose.onNodeWithTag("library-scroll").assertExists()
        switchBoard("study")
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        switchBoard("library")
        compose.onNodeWithTag("section-loans").assertIsSelected()
    }

    @Test fun firstRunRequiresDigiCampusButLibraryCanBeAddedLater() {
        compose.onNodeWithText("Continue with DigiCampus").performClick()
        compose.onNodeWithText("Connect your accounts").assertIsDisplayed()
        compose.onNodeWithTag("accounts-scroll").performScrollToNode(hasText("Continue"))
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Optional").assertExists()
        compose.runOnIdle { container.preview() }
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courses?.isNotEmpty() == true }
        compose.onNodeWithTag("accounts-scroll").performScrollToNode(hasText("Continue"))
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        compose.onNodeWithTag("today-scroll").assertExists()
        Assert.assertFalse(container.previewServices.library.value.connected)
        openCards()
        compose.onNodeWithText("Connect library account").performScrollTo().performClick()
        compose.waitUntil(10_000) { container.previewServices.library.value.connected }
    }

    @Test fun backAfterConnectingDigiCampusFinishesOptionalSetupAndKeepsBoardsAvailable() {
        compose.onNodeWithText("Continue with DigiCampus").performClick()
        compose.runOnIdle { container.preview() }
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courses?.isNotEmpty() == true }
        compose.onNodeWithTag("accounts-scroll").performScrollToNode(hasText("Continue"))
        compose.onNodeWithText("Continue").assertIsEnabled()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithTag("today-scroll").assertExists()
        slideBoard(1)
        compose.onNodeWithTag("catalogue-query").assertExists()
        slideBoard(-1)
        compose.onNodeWithTag("today-scroll").assertExists()
        openCards()
        compose.onNodeWithText("Connect your accounts").assertDoesNotExist()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithTag("today-scroll").assertExists()
    }

    @Test fun catalogueSearchShowsCopiesSavesBooksAndRestoresBackNavigation() {
        preview()
        switchBoard("library")
        compose.onNodeWithTag("catalogue-query").performTextInput("Cormen")
        compose.onNodeWithText("Author", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Search catalogue").performClick()
        compose.waitUntil(10_000) { container.previewServices.catalogue.search.value.results?.books?.size == 1 }
        compose.onNodeWithTag("catalogue-scroll").performScrollToNode(hasText("Introduction to algorithms"))
        compose.onNodeWithText("Introduction to algorithms").performClick()
        compose.waitUntil(10_000) { container.previewServices.catalogue.record.value.record?.copies?.size == 2 }
        compose.onNodeWithText("Save book").performClick()
        compose.waitUntil(5_000) { container.previewServices.catalogue.saved.value.any { it.id == "sample-algorithms" } }
        compose.onNodeWithTag("catalogue-detail-scroll").performScrollToNode(hasText("81/ST 130 C811(4)"))
        compose.onNodeWithText("Am Standort (Freihandbestand)").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("catalogue-detail-scroll").assertExists()
        switchBoard("mensa")
        switchBoard("library")
        compose.onNodeWithTag("catalogue-detail-scroll").assertExists()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        compose.onNodeWithTag("catalogue-query").performScrollTo().assertTextContains("Cormen")
        compose.onNodeWithTag("section-saved").performClick()
        compose.onNodeWithTag("saved-books-scroll").performScrollToNode(hasText("Introduction to algorithms"))
        compose.onNodeWithText("Introduction to algorithms").performClick()
        compose.onNodeWithText("Remove saved book").performClick()
        compose.waitUntil(5_000) { container.previewServices.catalogue.saved.value.none { it.id == "sample-algorithms" } }
        device.pressBack()
        compose.onNodeWithTag("saved-books-scroll").assertExists()
        switchBoard("study")
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        switchBoard("library")
        compose.onNodeWithTag("section-saved").assertIsSelected()
        compose.onNodeWithTag("section-catalogue").performClick()
        compose.onNodeWithTag("catalogue-query").performScrollTo().assertTextContains("Cormen")
        Assert.assertFalse(compose.activity.isFinishing)
    }

    @Test fun boardsRestoreTheirOwnSectionsAndCardsRemainGlobalAfterRecreation() {
        preview()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithTag("board-switcher").performClick()
        compose.onNodeWithTag("boards-sheet").assertExists()
        device.pressBack()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        switchBoard("library")
        compose.onNodeWithTag("section-loans").performClick()
        compose.onNodeWithText("Manage accounts").assertDoesNotExist()
        switchBoard("mensa")
        compose.onNodeWithTag("board-sections").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        switchBoard("study")
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        switchBoard("library")
        compose.onNodeWithTag("section-loans").assertIsSelected()
        openCards()
        compose.onNodeWithTag("access-card-digi").assertExists()
        compose.onNodeWithTag("access-card-library").assertExists()
        compose.onNodeWithTag("board-sections").assertDoesNotExist()
        device.pressBack()
        compose.onNodeWithTag("section-loans").assertIsSelected()
        Assert.assertFalse(compose.activity.isFinishing)
    }

    @Test fun publicBoardsOfferCatalogueAndMensaButRequireDigiCampusForStudy() {
        compose.onNodeWithText("Library & Mensa").performClick()
        compose.onNodeWithTag("catalogue-query").assertExists()
        slideBoard(1)
        compose.onNodeWithTag("mensa-scroll").assertExists()
        slideBoard(-2)
        compose.onNodeWithText("Connect your accounts").assertExists()
        compose.onNodeWithTag("accounts-scroll").performScrollToNode(hasText("Continue"))
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        compose.onNodeWithTag("mensa-scroll").assertExists()
        slideBoard(-1)
        compose.onNodeWithTag("catalogue-query").assertExists()
        slideBoard(-1)
        compose.onNodeWithTag("accounts-scroll").performScrollToIndex(0)
        compose.onNodeWithText("Connect your accounts").assertExists()
        device.pressBack()
        compose.onNodeWithTag("catalogue-query").assertExists()
    }

    @Test fun leftSliderSwitchesDuringAHeldDragAndRestoresBoardSections() {
        preview()
        val marker = compose.onNodeWithTag("board-slider-marker").fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("board-slider").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("board-slider").performTouchInput {
            down(center)
            moveBy(Offset(0f, -height * .325f))
        }
        compose.onNodeWithTag("catalogue-query").assertExists()
        compose.onNodeWithTag("board-slider-active").assertExists()
        Assert.assertEquals(marker, compose.onNodeWithTag("board-slider-marker").fetchSemanticsNode().boundsInRoot)
        Assert.assertEquals(frame, compose.onNodeWithTag("board-slider").fetchSemanticsNode().boundsInRoot)
        val label = compose.onNodeWithTag("board-slider-active").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("boards-sheet").assertDoesNotExist()
        compose.onNodeWithTag("board-slider").performTouchInput { moveBy(Offset(0f, -height * .325f)) }
        compose.onNodeWithTag("mensa-scroll").assertExists()
        Assert.assertEquals(marker, compose.onNodeWithTag("board-slider-marker").fetchSemanticsNode().boundsInRoot)
        Assert.assertEquals(frame, compose.onNodeWithTag("board-slider").fetchSemanticsNode().boundsInRoot)
        Assert.assertEquals(label.top, compose.onNodeWithTag("board-slider-active").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("board-slider").performTouchInput { moveBy(Offset(0f, height * .325f)); up() }
        compose.onNodeWithTag("board-slider-active").assertDoesNotExist()
        Assert.assertEquals(marker, compose.onNodeWithTag("board-slider-marker").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("catalogue-query").assertExists()
        slideBoard(-1)
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        slideBoard(1)
        compose.onNodeWithTag("section-loans").performClick()
        compose.activityRule.scenario.recreate()
        slideBoard(-1)
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        slideBoard(1)
        compose.onNodeWithTag("section-loans").assertIsSelected()
    }

    @Test fun sliderKeepsSmallDragsAndBackGesturesOutsideItsAreaSafe() {
        preview()
        val bounds = compose.onNodeWithTag("board-slider").fetchSemanticsNode().boundsInRoot
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        Assert.assertEquals("Slider sits at the screen's vertical midpoint", device.displayHeight / 2f, bounds.center.y, 2f)
        Assert.assertEquals(0f, bounds.left, 1f)
        compose.onNodeWithTag("board-slider").performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y - 40f), durationMillis = 250)
        }
        compose.onNodeWithTag("boards-sheet").assertDoesNotExist()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        device.swipe(2, bounds.center.y.toInt(), 2, (bounds.center.y - bounds.height * .325f).toInt(), 18)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("catalogue-query").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("catalogue-query").assertExists()
        openCards()
        compose.onNodeWithTag("access-card-library").assertExists()
        val navMode = android.provider.Settings.Secure.getInt(compose.activity.contentResolver, "navigation_mode", 0)
        if (navMode == 2) {
            device.swipe(2, (bounds.top - 100).toInt(), device.displayWidth / 2, (bounds.top - 100).toInt(), 24)
        } else device.pressBack()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("access-card-library").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("catalogue-query").assertExists()
        compose.onNodeWithTag("access-card-library").assertDoesNotExist()
        Assert.assertFalse(compose.activity.isFinishing)
    }

    @Test fun sliderHapticsTickOnSelectionAndJitterDoesNotRepeatThem() {
        val selected = mutableStateOf(CampusBoard.STUDY)
        val ticks = mutableListOf<HapticFeedbackType>()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { ticks.add(hapticFeedbackType) }
        }
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback, LocalWords provides Words(false)) {
                ZenTheme(ThemeMode.LIGHT) { ZenBoardTheme(selected.value) {
                    BoardSlider(selected.value, { selected.value = it }, Modifier.fillMaxSize()) { }
                } }
            }
        } }
        compose.onNodeWithTag("board-slider").performTouchInput {
            down(center); moveBy(Offset(0f, -height * .325f * .7f))
        }
        compose.runOnIdle { Assert.assertEquals(CampusBoard.LIBRARY, selected.value); Assert.assertEquals(1, ticks.size) }
        compose.onNodeWithTag("board-slider").performTouchInput {
            moveBy(Offset(0f, height * .325f * .2f))
        }
        compose.runOnIdle { Assert.assertEquals(CampusBoard.LIBRARY, selected.value); Assert.assertEquals(1, ticks.size) }
        compose.onNodeWithTag("board-slider").performTouchInput { moveBy(Offset(0f, -height * .325f * 1.5f)); up() }
        compose.onNodeWithTag("board-slider").assertRangeInfoEquals(ProgressBarRangeInfo(2f, 0f..2f, 1))
        compose.onNodeWithTag("board-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.runOnIdle { Assert.assertEquals(listOf(HapticFeedbackType.SegmentTick, HapticFeedbackType.SegmentTick), ticks) }
        compose.onNodeWithTag("board-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle { Assert.assertEquals(CampusBoard.STUDY, selected.value); Assert.assertEquals(3, ticks.size) }
    }

    @Test fun sliderHidesWhileTypingAndRestoresWhenTheKeyboardCloses() {
        preview()
        slideBoard(1)
        compose.onNodeWithTag("catalogue-query").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("board-slider").fetchSemanticsNodes().isEmpty() }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("board-slider").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("catalogue-query").assertExists()
    }

    private fun slideBoard(stops: Int) {
        compose.onNodeWithTag("board-slider").performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y - height * .325f * stops), durationMillis = 350)
        }
        compose.onNodeWithTag("boards-sheet").assertDoesNotExist()
    }

    private fun assertSingleLine(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        Assert.assertEquals(1, layouts.single().lineCount)
        val layout = layouts.single()
        // Intrinsic sizing can retain a wider cached paragraph; check the drawn line rather than unused paragraph space.
        Assert.assertTrue("Weather label '${layout.layoutInput.text}' must fit its width",
            layout.getLineLeft(0) >= -1f && layout.getLineRight(0) <= layout.size.width + 1f)
        Assert.assertTrue("Weather label '${layout.layoutInput.text}' must fit its height", layout.getLineBottom(0) <= layout.size.height + 1f)
        Assert.assertFalse("Weather labels must remain complete", layout.isLineEllipsized(0))
    }

    private fun cachedRows(scope: String): Int {
        val file = compose.activity.getDatabasePath("campus.db")
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM cache_entry WHERE account = ?", arrayOf(scope)).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }
    }
    @Test fun messagesOpenReplyAndShowTheSentReceipt() {
        preview()
        compose.onNodeWithTag("section-messages").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.messages?.get("inbox")?.isNotEmpty() == true }
        compose.onNodeWithTag("message-welcome-message").performClick()
        compose.onNodeWithText("Welcome to the course.").assertIsDisplayed()
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithTag("message-body").performTextInput("Thank you")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("message-body").assertTextContains("Thank you")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.messages?.get("sent")?.any { it.body == "Thank you" } == true }
        compose.onNodeWithText("Sent").performClick()
        compose.onNodeWithText("Re: Welcome").assertIsDisplayed()
        compose.onNodeWithText("Prof. Weber").assertExists()
    }

    @Test fun courseForumParticipantsWikiAndCoursewareNavigateBack() {
        preview()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courseTools?.get("algorithms") != null }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("community-link-forum"))
        compose.onNodeWithTag("community-link-forum").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.community?.get("community:algorithms:forum:") != null }
        compose.onNodeWithTag("community-item-questions").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.community?.get("community:algorithms:forum:questions") != null }
        compose.onNodeWithText("Welcome to the discussion.").assertIsDisplayed()
        compose.waitUntil(10_000) {
            val snapshot = container.repository.value?.snapshot?.value
            snapshot?.forumSubscriptions?.containsKey("algorithms") == true &&
                snapshot.forumReactions.containsKey("forum-reactions:algorithms:post-1")
        }
        compose.onNodeWithTag("forum-subscribe").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.forumSubscriptions?.get("algorithms")?.any { it.discussionId == "questions" } == true }
        compose.onNodeWithTag("forum-subscribe").assertTextContains("Unsubscribe")
        compose.onNodeWithTag("forum-reaction-post-1").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.forumReactions?.get("forum-reactions:algorithms:post-1")?.any { it.userId == "preview" } == true }
        compose.onNodeWithTag("forum-reaction-post-1").assertIsSelected()
        compose.onNodeWithTag("forum-reply").performClick()
        compose.onNodeWithTag("forum-reply-body").performTextInput("Thank you")
        compose.onNodeWithTag("forum-send").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.community?.get("community:algorithms:forum:questions")?.any { it.body == "Thank you" } == true }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("community-item-questions").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("community-link-people"))
        compose.onNodeWithTag("community-link-people").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.community?.get("community:algorithms:people:") != null }
        compose.onNodeWithText("Prof. Weber").assertExists()
        compose.onNodeWithTag("community-groups").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.community?.get("community:algorithms:groups:") != null }
        compose.onNodeWithTag("community-item-tutorial").performClick()
        // A group opens as its own page: facts, description and members; Back returns to the list of groups.
        compose.onNodeWithTag("group-detail").assertExists()
        compose.onNodeWithText("Weekly exercise group.").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("group-member-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Alex Morgan").assertIsDisplayed()
        compose.onNodeWithText("2 of 25 members").assertIsDisplayed()
        compose.onNodeWithText("Join in DigiCampus").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("community-item-tutorial").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("community-link-wiki"))
        compose.onNodeWithTag("community-link-wiki").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.community?.get("community:algorithms:wiki:") != null }
        compose.onNodeWithTag("community-item-wiki-1").performClick()
        compose.onNodeWithText("Useful course notes.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("community-link-courseware"))
        compose.onNodeWithTag("community-link-courseware").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.community?.get("community:algorithms:courseware:") != null }
        compose.onNodeWithTag("community-item-chapter").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.community?.get("community:algorithms:courseware:chapter") != null }
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.coursewareBookmarks != null }
        compose.onNodeWithTag("courseware-bookmark").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.coursewareBookmarks?.contains("chapter") == true }
        compose.onNodeWithTag("courseware-bookmark").assertIsSelected()
        compose.onNodeWithText("Welcome to Courseware.").assertIsDisplayed()
        compose.onNodeWithText("More content in DigiCampus").assertIsDisplayed()
    }

    @Test fun studyUpdatesCombineMessagesFilesAndCourseNews() {
        preview()
        compose.onNodeWithTag("section-today").performClick()
        compose.onNodeWithTag("today-scroll").performScrollToNode(hasText("See all"))
        compose.onNodeWithText("See all").performClick()
        compose.waitUntil(15_000) {
            val state = container.repository.value?.snapshot?.value
            state?.messages?.get("inbox") != null && state.courseMaterials["algorithms"] != null &&
                state.community["community:algorithms:forum:"] != null
        }
        compose.onNodeWithTag("updates-screen").assertIsDisplayed()
        compose.onNodeWithTag("update-message:welcome-message").assertExists()
        compose.onNodeWithTag("updates-screen").performScrollToNode(hasTestTag("update-forum:algorithms:questions"))
        compose.onNodeWithTag("update-forum:algorithms:questions").assertExists()
    }

}
