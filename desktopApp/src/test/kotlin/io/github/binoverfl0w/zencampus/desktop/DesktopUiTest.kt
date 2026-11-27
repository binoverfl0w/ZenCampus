package io.github.binoverfl0w.zencampus.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.NetworkConditions
import io.github.binoverfl0w.zencampus.data.SignInKind
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.*
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import io.github.binoverfl0w.zencampus.ui.study.CourseScreen
import io.github.binoverfl0w.zencampus.ui.study.CoursesScreen
import io.github.binoverfl0w.zencampus.ui.study.LocalVipsReader
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.*

/** Isolated profiles and sample providers only; no student session or real device is touched. */
class DesktopUiTest {
    // The profile is deleted only after the Compose rule has disposed the UI: until then the UI's own database queries
    // keep running, and SQLite would recreate an empty database in the deleted folder.
    @get:Rule(order = 0) val profileCleanup = object : org.junit.rules.ExternalResource() {
        override fun after() { if (::root.isInitialized) root.deleteRecursively() }
    }
    @get:Rule(order = 1) val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var container: DesktopContainer
    private var back: (() -> Unit)? = null
    private var serviceRequest by mutableStateOf("")
    private var showSavedSignIns by mutableStateOf(false)
    private var shown by mutableStateOf(true)
    private var auditPdf by mutableStateOf<MaterialPresentation?>(null)
    private var groupsOnly by mutableStateOf(false)
    private var courseResultsOnly by mutableStateOf(false)
    private var groupGerman by mutableStateOf(false)
    private var groupNarrow by mutableStateOf(false)
    private var groupIdenticalTitles by mutableStateOf(false)
    private var openedCourse = ""
    private var saveState: (() -> Unit)? = null
    private var fixtureReader by mutableStateOf<((String, String, String) -> Unit)?>(null)
    private var fixtureOpenedUrl = ""

    @Before fun start() {
        root = Files.createTempDirectory("zen-desktop-ui-").toFile()
        container = DesktopContainer(DesktopPaths(root), MemoryVault(), allowTestData = true)
        container.setSettings(AppSettings(theme = ThemeMode.LIGHT, language = AppLanguage.ENGLISH))
        compose.setContent {
            if (auditPdf != null) PdfWindow(auditPdf!!, container) { auditPdf = null }
            val repo by container.repository.collectAsState()
            val ready by container.ready.collectAsState()
            val settings by container.settings.collectAsState()
            if (courseResultsOnly && repo != null) {
                val snapshot by repo!!.snapshot.collectAsState()
                val sync by repo!!.sync.collectAsState()
                val course = snapshot.courses.first { it.id == "algorithms" }
                val resultUrl = "$DIGICAMPUS_ORIGIN/dispatch.php/vips/solutions/student_assignment_solutions?assignment_id=3&cid=${course.id}"
                val tools = CourseTools(listOf(CourseTool("vips", "Exercise Sheets", vipsSheetsUrl(course.id))))
                val result = VipsResult("3", course.id, "Exercises Sheet 03", resultUrl, points = "7 / 10")
                val fixture = snapshot.copy(courseTools = mapOf(course.id to tools), vipsResults = mapOf(course.id to listOf(result)))
                CompositionLocalProvider(LocalWords provides Words(false)) {
                    ZenTheme(ThemeMode.LIGHT) {
                        CourseScreen(course, fixture, repo!!, sync, 3, {}, "", {}, {}, {}, {}, {}, { _, _ -> })
                    }
                }
            } else if (groupsOnly && repo != null) {
                val snapshot by repo!!.snapshot.collectAsState()
                val sync by repo!!.sync.collectAsState()
                val density = LocalDensity.current.density
                val original = snapshot.courses.first { it.id == "algorithms" }
                val lecture = if (groupIdenticalTitles) original.copy(typeName = "Vorlesung", subtitle = "") else original
                val courses = snapshot.courses.map { if (it.id == lecture.id) lecture else it } + listOf(
                    lecture.copy(id = "exercise", title = if (groupIdenticalTitles) lecture.title else "Übung zu ${lecture.title}", subtitle = "Exercise class", typeName = "Übung", number = "INF-1003"),
                    lecture.copy(id = "tutorial", title = "${lecture.title} (Tutorium)", subtitle = "Tutorial", typeName = "Tutorium", number = "INF-1004"))
                CompositionLocalProvider(LocalWords provides Words(groupGerman), LocalDensity provides Density(density, if (groupNarrow) 1.5f else 1f)) {
                    ZenTheme(if (groupNarrow) ThemeMode.DARK else ThemeMode.LIGHT) {
                        Surface(Modifier.width(if (groupNarrow) 320.dp else 760.dp).fillMaxHeight().testTag("groups-layout"),
                            color = MaterialTheme.colorScheme.background) {
                            CoursesScreen(snapshot.copy(courses = courses), repo!!, sync, { openedCourse = it })
                        }
                    }
                }
            } else {
            if (shown) key(repo?.account?.cacheScope ?: "public") { PersistentDesktopState(container, repo?.account?.cacheScope ?: "public", onSaveReady = { saveState = it }) {
                CompositionLocalProvider(LocalVipsReader provides fixtureReader) {
                    ZenApp(repo, settings, NetworkConditions(), ready, "en", {}, container::signOut,
                        container::setSettings, { fixtureOpenedUrl = it }, onBackHandler = { back = it }, services = container.previewServices,
                        onConnectLibrary = container::connectPreviewLibrary, serviceRequest = serviceRequest,
                        signInSession = container.signInSession.takeIf { showSavedSignIns })
                }
            } }
            }
        }
        compose.waitUntil(10_000) { container.ready.value }
    }
    @After fun stop() {
        runBlocking { container.close() }
    }
    private fun preview() {
        compose.runOnIdle { container.preview() }
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courses?.size == 4 }
        compose.waitForIdle()
    }
    private fun navigate(route: String) { compose.runOnIdle { serviceRequest = "$route#${System.nanoTime()}" } }

    private fun groupedCourses() {
        preview()
        compose.runOnIdle { groupsOnly = true }
        compose.onNodeWithTag("course-member-algorithms").assertExists()
    }

    @Test fun courseSubmittedShowsReleasedVipsResult() {
        preview()
        compose.runOnIdle { courseResultsOnly = true }
        compose.onNodeWithTag("tasks-submitted").performClick()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasText("Exercises Sheet 03"))
        compose.onNodeWithText("Exercises Sheet 03").assertExists()
        compose.onNodeWithText("7 / 10").assertExists()
    }
    private fun chooseGroupMember(id: String) {
        compose.onNodeWithTag("course-groups-editor").performScrollToNode(hasTestTag("course-group-select-$id"))
        compose.onNodeWithTag("course-group-select-$id").performClick()
    }
    private fun captureGroups(name: String) {
        compose.waitForIdle()
        val output = File("build/reports/course-groups/$name.png").apply { parentFile.mkdirs() }
        val bitmap = compose.onNodeWithTag("groups-layout").captureToImage().asSkiaBitmap()
        Image.makeFromBitmap(bitmap).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { output.writeBytes(it.bytes) } }
    }

    @Test fun messagesAndUpdatesWorkOnDesktopWithIsolatedFixtures() {
        preview()
        compose.onNodeWithTag("section-messages").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.messages?.get("inbox")?.isNotEmpty() == true }
        // The row can still be merged into the loading list for a frame after the data arrives.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("message-welcome-message").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message-welcome-message").performClick()
        compose.onNodeWithText("Welcome to the course.").assertExists()
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithTag("message-body").performTextInput("Thank you")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.messages?.get("sent")?.any { it.body == "Thank you" } == true }
        compose.onNodeWithText("Sent").performClick()
        compose.onNodeWithText("Re: Welcome").assertExists()
        val output = File("build/reports/study-expansion/desktop-messages.png").apply { parentFile.mkdirs() }
        val bitmap = compose.onNodeWithTag("board-content-column").captureToImage().asSkiaBitmap()
        Image.makeFromBitmap(bitmap).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { output.writeBytes(it.bytes) } }
    }

    @Test fun groupedClassesOpenDirectlyAndPinFeedbackSurvivesFiltering() {
        groupedCourses()
        compose.onNodeWithTag("courses-scroll").performScrollToNode(hasTestTag("course-pin-exercise"))
        compose.onNodeWithTag("course-pin-exercise").assertIsOff().performClick()
        compose.waitUntil(5_000) { "exercise" in container.repository.value!!.snapshot.value.preferences.pinnedCourses }
        compose.onNodeWithTag("course-pin-exercise").assertIsOn()
        Assert.assertEquals("Pinning must not open the class", "", openedCourse)
        compose.onNodeWithTag("courses-scroll").performScrollToIndex(0)
        captureGroups("grouped-selected-pin")
        compose.onNodeWithTag("courses-pinned-filter").performClick()
        compose.onNodeWithTag("course-member-algorithms").assertDoesNotExist()
        compose.onNodeWithTag("course-member-exercise").performClick()
        Assert.assertEquals("exercise", openedCourse)
        compose.onNodeWithTag("courses-pinned-filter").performClick()
        compose.onNodeWithTag("courses-query").performTextInput("INF-1003")
        compose.onNodeWithTag("course-member-exercise").assertExists()
        compose.onNodeWithTag("course-member-algorithms").assertDoesNotExist()
    }

    @Test fun automaticGroupsCanBeRenamedAndKeepRemovedClassesSeparateAfterRefreshing() {
        groupedCourses()
        compose.onNodeWithContentDescription("Edit group").performClick()
        compose.onNodeWithTag("course-group-name").performTextReplacement("Algorithms")
        chooseGroupMember("tutorial")
        compose.onNodeWithTag("course-group-save").performClick()
        compose.waitUntil(5_000) { container.repository.value!!.snapshot.value.preferences.courseGroups.singleOrNull()?.title == "Algorithms" }
        compose.onNodeWithTag("course-groups-dialog").assertDoesNotExist()
        Assert.assertEquals(setOf("tutorial"), container.repository.value!!.snapshot.value.preferences.separateCourseIds)
        compose.onNodeWithTag("course-member-exercise").assertExists()
        compose.onNodeWithTag("course-member-tutorial").assertDoesNotExist()
        runBlocking { container.repository.value!!.refreshCourses(force = true) }
        compose.onNodeWithText("Algorithms").assertExists()
        compose.onNodeWithTag("courses-scroll").performScrollToNode(hasTestTag("course-card-tutorial"))
        compose.onNodeWithTag("course-card-tutorial").assertExists()
    }

    @Test fun separatedClassesCanBeManuallyGroupedWithAnotherSubject() {
        groupedCourses()
        compose.onNodeWithContentDescription("Edit group").performClick()
        compose.onNodeWithTag("course-groups-editor").performScrollToNode(hasTestTag("course-group-separate"))
        compose.onNodeWithTag("course-group-separate").performClick()
        compose.waitUntil(5_000) { container.repository.value!!.snapshot.value.preferences.separateCourseIds.size == 3 }
        compose.onNodeWithTag("course-member-algorithms").assertDoesNotExist()
        compose.onNodeWithTag("courses-scroll").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Course groups").performClick()
        compose.onNodeWithTag("course-group-new").performClick()
        chooseGroupMember("algorithms")
        chooseGroupMember("math")
        compose.onNodeWithTag("course-groups-editor").performScrollToIndex(0)
        compose.onNodeWithTag("course-group-name").performTextReplacement("Foundations")
        compose.onNodeWithTag("course-group-save").performClick()
        compose.waitUntil(5_000) { container.repository.value!!.snapshot.value.preferences.courseGroups.singleOrNull()?.title == "Foundations" }
        compose.onNodeWithText("Foundations").assertExists()
        compose.onNodeWithTag("course-member-math").assertExists()
        Assert.assertEquals(setOf("algorithms", "math"), container.repository.value!!.snapshot.value.preferences.courseGroups.single().courseIds)
        Assert.assertTrue("exercise" in container.repository.value!!.snapshot.value.preferences.separateCourseIds)
    }

    @Test fun cancellingDoesNotSaveAndEditingDoesNotLoseUnloadedGroupMembers() {
        groupedCourses()
        compose.onNodeWithContentDescription("Edit group").performClick()
        compose.onNodeWithTag("course-group-name").performTextReplacement("Discard me")
        compose.onNodeWithText("Cancel").performClick()
        Assert.assertTrue(container.repository.value!!.snapshot.value.preferences.courseGroups.isEmpty())
        runBlocking { container.repository.value!!.saveCourseGroup(null, "Saved group", setOf("algorithms", "unloaded")) }
        compose.waitUntil(5_000) { container.repository.value!!.snapshot.value.preferences.courseGroups.isNotEmpty() }
        compose.onNodeWithContentDescription("Edit group").performClick()
        compose.onNodeWithText("1 not in the current list").assertExists()
        compose.onNodeWithTag("course-group-name").performTextReplacement("Still saved")
        compose.onNodeWithTag("course-group-save").performClick()
        compose.waitUntil(5_000) { container.repository.value!!.snapshot.value.preferences.courseGroups.single().title == "Still saved" }
        Assert.assertEquals(setOf("algorithms", "unloaded"), container.repository.value!!.snapshot.value.preferences.courseGroups.single().courseIds)
    }

    @Test fun groupedCoursesAndSelectedPinsFitNarrowGermanLargeText() {
        groupedCourses()
        runBlocking { container.repository.value!!.toggleCoursePin("exercise") }
        compose.waitUntil(5_000) { "exercise" in container.repository.value!!.snapshot.value.preferences.pinnedCourses }
        compose.runOnIdle { groupGerman = true; groupNarrow = true }
        compose.onNodeWithTag("courses-scroll").performScrollToNode(hasTestTag("course-pin-exercise"))
        compose.onNodeWithTag("course-pin-exercise").assertIsOn().assertIsDisplayed()
        captureGroups("grouped-german-large-text")
        compose.onNodeWithTag("courses-scroll").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Kursgruppen").assertIsDisplayed().performClick()
        compose.onNodeWithTag("course-groups-dialog").assertIsDisplayed()
    }

    @Test fun sourceTypeLabelsDistinguishIdenticallyNamedClasses() {
        groupedCourses()
        compose.runOnIdle { groupIdenticalTitles = true }
        compose.onNodeWithText("Vorlesung · INF-1002").assertExists()
        compose.onNodeWithText("Übung · INF-1003").assertExists()
        compose.onNodeWithTag("course-member-exercise").performScrollTo().performClick()
        Assert.assertEquals("exercise", openedCourse)
        compose.onNodeWithTag("courses-scroll").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Edit group").performClick()
        compose.onNodeWithTag("course-groups-editor").performScrollToNode(hasTestTag("course-group-select-exercise"))
        compose.onNodeWithText("Übung · INF-1003 · Prof. Dr. Weber").assertIsDisplayed()
        compose.onNodeWithTag("course-groups-editor").performScrollToIndex(0)
        compose.onNodeWithTag("course-group-search").performTextInput("Übung")
        compose.onNodeWithTag("course-group-select-exercise").assertExists()
        compose.onNodeWithTag("course-group-select-algorithms").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("courses-query").performTextInput("Übung")
        compose.onNodeWithTag("course-member-exercise").assertExists()
        compose.onNodeWithTag("course-member-algorithms").assertDoesNotExist()
    }

    @Test fun groupEditorStateCanPersistAndReopenWithoutSavingPreferences() {
        preview()
        compose.onNodeWithTag("section-courses").performClick()
        compose.onNodeWithContentDescription("Course groups").performClick()
        compose.onNodeWithTag("course-group-new").performClick()
        chooseGroupMember("algorithms"); chooseGroupMember("math")
        compose.onNodeWithTag("course-groups-editor").performScrollToIndex(0)
        compose.onNodeWithTag("course-group-name").performTextReplacement("Draft group")
        compose.runOnIdle { saveState!!.invoke(); shown = false }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        compose.onNodeWithTag("course-group-name").assertTextContains("Draft group")
        compose.onNodeWithTag("course-group-save").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        Assert.assertTrue(container.repository.value!!.snapshot.value.preferences.courseGroups.isEmpty())
    }

    @Test fun captureDesktopReleaseScreens() {
        val output = File("build/reports/ui-audit/desktop").apply { mkdirs() }
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asSkiaBitmap()
            Image.makeFromBitmap(bitmap).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { File(output, "$name.png").writeBytes(it.bytes) }
            }
        }
        capture("welcome")
        preview()
        capture("today")
        navigate("coursework")
        compose.onNodeWithTag("coursework-scroll").assertIsDisplayed()
        capture("coursework")
        compose.onNodeWithTag("section-courses").performClick()
        capture("courses")
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        capture("course")
        navigate("library-board")
        capture("library")
        navigate("mensa")
        compose.waitUntil(10_000) { container.previewServices.mensa.value.menu != null }
        capture("mensa")
        navigate("accounts")
        compose.onNodeWithTag("accounts-scroll").assertIsDisplayed()
        capture("your-cards")
        compose.onNodeWithTag("rail-settings").performClick()
        capture("settings")
        runBlocking {
            val vault = checkNotNull(container.signInSession.savedSignIns)
            vault.save(SignInKind.STUDY, SignInKind.STUDY.defaultSite, "student@example.test", "Fixture", "Study account")
            vault.save(SignInKind.LIBRARY, SignInKind.LIBRARY.defaultSite, "12345", "Fixture", "Library account")
        }
        compose.runOnIdle { showSavedSignIns = true }
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasText("Saved sign-ins"))
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasText("Library account"))
        capture("settings-saved-sign-ins")
    }

    @Test fun pdfToolbarFitsNarrowWindowAndUsesGerman() {
        val name = "Algorithmen und Datenstrukturen – Übungsblatt mit ausführlichem Titel.pdf"
        val file = File(root, "reader-fixture.pdf")
        org.apache.pdfbox.pdmodel.PDDocument().use { pdf ->
            pdf.addPage(org.apache.pdfbox.pdmodel.PDPage()); pdf.save(file)
        }
        compose.runOnIdle {
            container.setSettings(AppSettings(theme = ThemeMode.LIGHT, language = AppLanguage.GERMAN))
            auditPdf = MaterialPresentation(file, name, "application/pdf")
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Seite 1").fetchSemanticsNodes().isNotEmpty() }
        if (System.getenv("HYPRLAND_INSTANCE_SIGNATURE") != null) {
            // Scope the compositor override to this test process; tiled windows ignore AWT sizing.
            val action = "hl.dispatch(hl.dsp.window.float({window=\"pid:${ProcessHandle.current().pid()}\",action=\"enable\"}))"
            val command = ProcessBuilder("hyprctl", "eval", action).redirectErrorStream(true).start()
            command.inputStream.readBytes()
            check(command.waitFor() == 0)
        }
        javax.swing.SwingUtilities.invokeAndWait {
            java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>()
                .single { it.title == "$name · ZenCampus" }.setSize(620, 700)
        }
        compose.onNodeWithText("An Breite anpassen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vergrößern").assertIsDisplayed()
        // Native Compose windows lay out on AWT's EDT; activate their semantics there too.
        val setText = compose.onNodeWithText("Text suchen").fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        javax.swing.SwingUtilities.invokeAndWait { setText(AnnotatedString("missing fixture text")) }
        val search = compose.onNodeWithText("Suchen").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        javax.swing.SwingUtilities.invokeAndWait { search() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Kein passender Text").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val output = File("build/reports/ui-audit/desktop/pdf-narrow-german.png")
        output.parentFile.mkdirs()
        // Native AWT animations use real frames rather than this rule's virtual clock.
        Thread.sleep(500)
        // The test-rule screenshot API captures its own scene; this reader owns a native AWT scene.
        javax.swing.SwingUtilities.invokeAndWait {
            val window = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().single { it.title == "$name · ZenCampus" }
            fun layer(component: java.awt.Component): org.jetbrains.skiko.SkiaLayer? =
                component as? org.jetbrains.skiko.SkiaLayer ?: (component as? java.awt.Container)?.components?.firstNotNullOfOrNull(::layer)
            val reader = checkNotNull(layer(window))
            Assert.assertTrue("PDF review must use a narrow reader", reader.width <= 620)
            checkNotNull(reader.screenshot()).use { bitmap ->
                Image.makeFromBitmap(bitmap).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { png -> output.writeBytes(png.bytes) } }
            }
        }
        val more = compose.onNodeWithContentDescription("Weitere Optionen").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        javax.swing.SwingUtilities.invokeAndWait { more() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Kopie speichern").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Kopie speichern").assertIsDisplayed()
        compose.runOnIdle { auditPdf = null }
    }

    @Test fun welcomeOffersLiveConnectionsWithoutASampleEntryPoint() {
        compose.onNodeWithText("Continue with DigiCampus").assertIsDisplayed()
        compose.onNodeWithText("Library & Mensa").assertIsDisplayed()
        compose.onNodeWithText("Explore a preview").assertDoesNotExist()
    }

    @Test fun wideWindowsPlaceTheRailBesideTheContentAndHeaderColumn() {
        preview()
        val rail = compose.onNodeWithTag("board-rail").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("board-content-column").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("Content must start after the rail", content.left >= rail.right - 1f)
        compose.onNodeWithTag("board-sections").assertExists()
        compose.onNodeWithTag("board-sections-column").assertDoesNotExist()
        compose.onNodeWithTag("board-slider").assertDoesNotExist()
        compose.onNodeWithTag("section-courses").performClick()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        val header = compose.onNodeWithTag("board-header-column").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("The detail header must sit beside the rail", header.left >= rail.right - 1f)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
    }

    @Test fun quickFindOpensCoursesAndHandsQueriesToTheCatalogue() {
        preview()
        navigate("search")
        compose.onNodeWithTag("quick-search").assertIsDisplayed()
        compose.onNodeWithTag("quick-search-query").performTextInput("Algorithmen")
        compose.onNode(hasText("Algorithmen und Datenstrukturen") and hasAnyAncestor(hasTestTag("quick-search"))).performClick()
        compose.onNodeWithTag("course-scroll").assertExists()
        compose.onNodeWithTag("rail-search").performClick()
        compose.onNodeWithTag("quick-search-query").performTextInput("Cormen")
        compose.onNodeWithText("Search the catalogue for “Cormen”").performClick()
        compose.onNodeWithTag("quick-search").assertDoesNotExist()
        compose.onNodeWithTag("catalogue-query").assertTextContains("Cormen")
    }

    @Test fun publicRailCanChooseMensa() {
        compose.onNodeWithText("Library & Mensa").performClick()
        compose.onNodeWithTag("section-catalogue").assertExists()
        compose.onNodeWithTag("rail-board-mensa").performClick()
        compose.onNodeWithTag("mensa-scroll").assertIsDisplayed()
        compose.onNodeWithTag("rail-settings").assertDoesNotExist()
    }

    @Test fun boardAndPlannerSheetsCanPersistDismissAndReopen() {
        preview()
        compose.onNodeWithTag("rail-board-library").performClick()
        compose.onNodeWithTag("section-catalogue").assertExists()
        compose.onNodeWithTag("rail-board-study").performClick()
        compose.onNodeWithTag("section-calendar").performClick()
        compose.onNodeWithTag("planner-filters").performClick()
        compose.onNodeWithTag("planner-filter-sheet").assertIsDisplayed()
        compose.runOnIdle { saveState!!.invoke() }
        Assert.assertTrue(root.listFiles()!!.any { it.name.startsWith("ui-") && it.readText().contains("SheetValue") })
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithTag("planner-filter-sheet").assertDoesNotExist()
        compose.onNodeWithTag("planner-filters").performClick()
        compose.onNodeWithTag("planner-filter-sheet").assertIsDisplayed()
    }

    @Test fun allBoardsAndCardsRemainAccessibleWithDesktopNavigation() {
        preview()
        compose.onNodeWithTag("section-courses").performClick()
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courseTools?.containsKey("algorithms") == true }
        compose.runOnIdle { back?.invoke() }
        compose.onNodeWithText("Your courses").assertIsDisplayed()
        navigate("library-board")
        compose.onNodeWithTag("section-catalogue").assertExists()
        compose.onNodeWithTag("section-loans").assertExists()
        compose.onNodeWithTag("rail-cards").performClick()
        compose.onAllNodesWithText("Your cards").onFirst().assertIsDisplayed()
        compose.runOnIdle { back?.invoke() }
        compose.onNodeWithTag("section-catalogue").assertExists()
        navigate("mensa")
        compose.onNodeWithTag("mensa-scroll").assertExists()
        navigate("study")
        compose.onNodeWithText("Your courses").assertIsDisplayed()
    }

    @Test fun materialsAndLinkedVipsPdfUseDesktopStorageAndViewer() {
        preview()
        compose.onNodeWithTag("section-courses").performClick()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.onNodeWithText("Materials").performClick()
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.vipsSheets?.containsKey("algorithms") == true }
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("vips-sheet-preview-3"))
        compose.onNodeWithTag("vips-sheet-preview-3").assertIsDisplayed()
        var opened: MaterialPresentation? = null
        compose.runOnIdle {
            container.materials.onPdf = { opened = it }
            container.repository.value!!.requestLinkedMaterial("algorithms", "$DIGICAMPUS_ORIGIN/sendfile.php?type=0&file_id=preview-vips-pdf&file_name=Graphs%20-%20Exercise%20sheet%203.pdf&cid=algorithms",
                "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment?assignment_id=3&cid=algorithms")
        }
        compose.waitUntil(15_000) { opened != null }
        // A copy is kept in the course folder, below Vips and the sheet it came from.
        val copy = File(root, "Downloads/ZenCampus/Algorithmen und Datenstrukturen/Vips/Graphs · Exercise sheet 3/Graphs - Exercise sheet 3.pdf")
        Assert.assertTrue(copy.path, copy.isFile)
        Assert.assertEquals("application/pdf", opened!!.mime)
        Assert.assertEquals("Graphs - Exercise sheet 3.pdf", opened!!.name)
        Assert.assertTrue(opened!!.file.isFile)
        // Real paths on every platform: macOS links /var to /private/var, and Windows may name temp folders in 8.3 form.
        Assert.assertTrue(opened!!.file.toPath().toRealPath().startsWith(root.toPath().toRealPath()))
    }

    @Test fun courseLinksStillRespondAfterClosingPdfWindow() {
        preview()
        compose.onNodeWithTag("section-courses").performClick()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("community-link-people"))
        val name = "popup-focus-fixture.pdf"
        val file = File(root, name)
        org.apache.pdfbox.pdmodel.PDDocument().use { pdf -> pdf.addPage(org.apache.pdfbox.pdmodel.PDPage()); pdf.save(file) }
        compose.runOnIdle { auditPdf = MaterialPresentation(file, name, "application/pdf") }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty() }
        javax.swing.SwingUtilities.invokeAndWait {
            val frame = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().single { it.title == "$name · ZenCampus" }
            frame.dispatchEvent(java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING))
        }
        compose.waitUntil(10_000) { auditPdf == null }
        // Closing the window can recompose the course list, and a lazy list may drop the off-screen link meanwhile.
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("community-link-people"))
        compose.onNodeWithTag("community-link-people").performMouseInput { click() }
        compose.onNodeWithTag("community-people").assertIsDisplayed()
    }

    @Test(timeout = 90_000) fun courseLinksStillRespondAfterClosingVipsWindow() {
        org.junit.Assume.assumeTrue("Optional native browser integration", System.getenv("ZEN_COURSE_POPUP_TEST") == "1")
        val browser = DesktopBrowser(container)
        fun current(): Any? = DesktopBrowser::class.java.getDeclaredField("current").apply { isAccessible = true }.get(browser)
        try {
            compose.runOnIdle { fixtureReader = browser::vips; container.closePrivateReaders = browser::closeVipsWindow }
            preview()
            compose.onNodeWithTag("section-courses").performMouseInput { click() }
            compose.onNodeWithText("Algorithmen und Datenstrukturen").performMouseInput { click() }
            compose.onNodeWithText("Materials").performMouseInput { click() }
            compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.vipsSheets?.containsKey("algorithms") == true }
            compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("vips-sheet-preview-3"))
            compose.onNodeWithTag("vips-sheet-preview-3").performMouseInput { click() }
            compose.waitUntil(30_000) { current()?.let { c -> c.javaClass.getDeclaredField("browser").apply { isAccessible = true }.get(c) as? org.cef.browser.CefBrowser }?.identifier?.let { it > 0 } == true }
            javax.swing.SwingUtilities.invokeAndWait {
                val c = checkNotNull(current())
                val frame = c.javaClass.getDeclaredField("frame").apply { isAccessible = true }.get(c) as java.awt.Frame
                frame.dispatchEvent(java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING))
            }
            compose.waitUntil(10_000) { current() == null }
            compose.onNodeWithTag("course-scroll").performScrollToIndex(0)
            compose.onNodeWithText("Overview").performMouseInput { click() }
            compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("community-link-people"))
            compose.onNodeWithTag("community-link-people").performMouseInput { click() }
            compose.onNodeWithTag("community-people").assertIsDisplayed()
            compose.runOnIdle { back?.invoke() }
            compose.onNodeWithTag("course-scroll").performScrollToNode(hasText("Open in DigiCampus"))
            compose.onNodeWithText("Open in DigiCampus").performMouseInput { click() }
            compose.waitUntil(5_000) { fixtureOpenedUrl.isNotBlank() }
        } finally {
            compose.runOnIdle { fixtureReader = null }
            runBlocking { browser.close() }
        }
    }


    @Test fun saveableStateCanBeWrittenWithRealComposeSavers() {
        preview()
        navigate("library-board")
        compose.onNodeWithTag("section-catalogue").assertExists()
        compose.waitUntil(8_000) {
            root.listFiles()?.filter { it.name.startsWith("ui-") }?.any { it.readText().contains("CatalogueField") } == true
        }
        Assert.assertTrue(root.listFiles()!!.filter { it.name.startsWith("ui-") }.all { !it.readText().contains("cookie", true) })
    }

    @Test fun reopenedCompositionRestoresTheCourseAndMaterialsTab() {
        preview()
        compose.onNodeWithTag("section-courses").performClick()
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.onNodeWithText("Materials").performClick()
        compose.waitUntil(8_000) {
            root.listFiles()?.filter { it.name.startsWith("ui-") }?.any { it.readText().contains("course:algorithms") } == true
        }
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        compose.onNodeWithTag("course-scroll").assertExists()
        compose.onNodeWithTag("course-tab-materials").assertIsSelected()
        compose.onNodeWithTag("materials-root").assertExists()
        compose.runOnIdle { back?.invoke() }
        compose.onNodeWithText("Your courses").assertIsDisplayed()
    }
}


class DesktopUiTestZenLink {
    @get:Rule val compose = createComposeRule()

    @Test fun leadingPaddingIsClickableAndTheTextStaysAligned() {
        var clicks = 0
        compose.setContent {
            ZenTheme(ThemeMode.LIGHT) {
                Column(Modifier.testTag("link-test").padding(24.dp)) {
                    Text("Heading")
                    ZenLink("Action", { clicks++ }, Modifier.testTag("action-link"))
                }
            }
        }
        val heading = compose.onNodeWithText("Heading").getUnclippedBoundsInRoot()
        val text = compose.onNodeWithText("Action", useUnmergedTree = true).getUnclippedBoundsInRoot()
        Assert.assertEquals(heading.left, text.left)
        compose.onNodeWithTag("link-test").performMouseInput {
            click(Offset(text.left.toPx() - 4.dp.toPx(), ((text.top + text.bottom) / 2).toPx()))
            click(Offset(text.right.toPx() + 4.dp.toPx(), ((text.top + text.bottom) / 2).toPx()))
        }
        compose.runOnIdle { Assert.assertEquals(2, clicks) }
    }
}
