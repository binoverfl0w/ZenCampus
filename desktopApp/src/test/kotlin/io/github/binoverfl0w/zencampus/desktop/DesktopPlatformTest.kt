package io.github.binoverfl0w.zencampus.desktop

import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.CampusNavigation
import io.github.binoverfl0w.zencampus.ui.CampusBoard
import androidx.compose.runtime.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

internal class MemoryVault : SessionVault {
    val records = mutableMapOf<String, String>()
    var fail = false
    override fun read(service: String): String? { if (fail) error("locked"); return records[service] }
    override fun write(service: String, value: String) { if (fail) error("locked"); records[service] = value }
    override fun clear(service: String) { if (fail) error("locked"); records.remove(service) }
}

class DesktopPlatformTest {
    private fun temp() = Files.createTempDirectory("zen-desktop-test-").toFile()
    /** Container start-up on a cold CI runner (Windows especially) can take well over ten seconds. */
    private val WAIT = 30_000L
    @Test fun closingContainerIsNotSkippedWhenBrowserCleanupFails() = runBlocking {
        val calls = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            closeDesktopResources({ calls += "browser"; error("fixture failure") }, { calls += "container" })
        }
        assertEquals(listOf("browser", "container"), calls)
    }
    @Test fun browserProfilesFromEarlierRunsAreRemoved() {
        val cache = Files.createTempDirectory("zen-cache-").toFile()
        try {
            File(cache, "browser-session-123/Default").mkdirs(); File(cache, "browser-session-123/Default/Cookies").writeText("x")
            File(cache, "chromium-152").mkdirs(); File(cache, "browser-session-note.txt").writeText("kept")
            clearBrowserProfiles(cache)
            assertEquals(setOf("chromium-152", "browser-session-note.txt"), cache.list()!!.toSet(), "only earlier profiles go; Chromium itself stays")
        } finally { cache.deleteRecursively() }
    }

    @Test fun productionProfilesRejectLegacySampleDataWithoutRemovingLibraryAccess(): Unit = runBlocking {
        val root = temp()
        val vault = MemoryVault()
        val demo = SavedSession(DemoProvider().account(), "")
        val library = desktopJson.encodeToString(LibrarySession("fixture-library", "JSESSIONID=fixture-only"))
        vault.records["digicampus"] = desktopJson.encodeToString(demo)
        vault.records["library"] = library
        File(root, "preview").writeText("")
        val container = DesktopContainer(DesktopPaths(root), vault)
        try {
            withTimeout(10_000) { container.ready.first { it } }
            assertNull(container.repository.value)
            assertNull(vault.records["digicampus"])
            assertEquals(library, vault.records["library"])
            assertTrue(container.services.library.value.connected)
            assertFalse(File(root, "preview").exists())
            assertFailsWith<IllegalStateException> { container.preview() }
        } finally { container.close(); root.deleteRecursively() }
    }
    @Test fun downloadCommitAbortAndReadableNames() = runBlocking {
        val root = temp()
        try {
            val paths = DesktopPaths(root); val materials = DesktopMaterials(paths)
            val document = Document("1", "course", "folder", "Exercise sheet 3.pdf", mimeType = "application/pdf")
            val writer = materials.create("account", document)
            assertFalse(root.walk().any { it.name == document.name })
            writer.write("%PDF-test".toByteArray()); val path = writer.finish()
            assertEquals(document.name, File(path).name); assertTrue(materials.exists(path))
            assertEquals("%PDF-test", File(path).readText())
            val aborted = materials.create("account", document); aborted.write(byteArrayOf(1)); aborted.abort()
            assertFalse(root.walk().any { it.name == ".download.part" })
            val first = materials.saveToDownloads(path, document.mimeType, document.name)
            val second = materials.saveToDownloads(path, document.mimeType, document.name)
            assertNotEquals(first, second); assertEquals(File(first).readText(), File(second).readText())
            assertTrue(first.startsWith(root.path))
            val custom = File(root, "My course").apply { mkdirs() }
            File(custom, document.name).writeText("existing")
            val destination = MaterialDestination(custom.absolutePath, custom.absolutePath)
            val customCopy = File(materials.saveToFolder(path, document.mimeType, document.name, destination))
            assertEquals("existing", File(custom, document.name).readText())
            assertEquals("%PDF-test", customCopy.readText())
            assertEquals("Exercise sheet 3 (1).pdf", customCopy.name)
            materials.clear("account"); assertFalse(File(path).exists()); assertTrue(File(first).exists())
            assertTrue(customCopy.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun pathsAndSymlinksCannotEscapeTheMaterialRoot() = runBlocking {
        val root = temp(); val outside = temp()
        try {
            val paths = DesktopPaths(root); val materials = DesktopMaterials(paths)
            val target = File(outside, "private.txt").apply { writeText("unrelated") }
            assertFailsWith<CampusException> { materials.remove(target.path) }
            val link = File(paths.materials, "escape")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertFailsWith<CampusException> { materials.remove(File(link, "private.txt").path) }
            assertEquals("unrelated", target.readText())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun genericBinPdfIsRenamedWithoutLosingItsOriginal() = runBlocking {
        val root = temp()
        try {
            val materials = DesktopMaterials(DesktopPaths(root))
            val document = Document("1", "c", "f", "Random 483.bin")
            val writer = materials.create("a", document); writer.write("%PDF-1.4".toByteArray()); val old = writer.finish()
            val prepared = materials.prepare(old, document)
            assertEquals("Random 483.pdf", File(prepared).name)
            assertTrue(File(old).exists()); assertEquals(File(old).readText(), File(prepared).readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun profileFilesArePrivateAndPreferencesSurviveRestart() {
        val root = temp()
        try {
            val paths = DesktopPaths(root); val file = File(paths.data, "prefs.json")
            DesktopPreferencesStore(file).update { it.copy(settings = AppSettings(theme = ThemeMode.DARK), width = 1380) }
            val restored = DesktopPreferencesStore(file).read()
            assertEquals(ThemeMode.DARK, restored.settings.theme); assertEquals(1380, restored.width)
            // Windows has no POSIX permissions; its per-user profile folders are private already.
            if (!isWindows) {
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root.toPath()))
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun savedNavigationAndScrollValuesRoundTripAsTypedJson() {
        val root = temp()
        try {
            val file = File(root, "ui.json"); val state = DesktopStateStore(file)
            val nav = CampusNavigation().openCourse("course").openCourseTab(2).openFolder("sheet")
            state.registry.registerProvider("navigation") { listOf(desktopJson.encodeToString(nav), 12, 99L, 1.25f, true) }
            state.registry.registerProvider("holders") { mapOf("key" to listOf(0, 180), "enum" to CampusBoard.LIBRARY) }
            state.save()
            val restored = DesktopStateStore(file).registry.consumeRestored("navigation") as List<*>
            assertEquals(nav, desktopJson.decodeFromString<CampusNavigation>(restored[0] as String))
            assertEquals(listOf(12, 99L, 1.25f, true), restored.drop(1))
            val holder = DesktopStateStore(file).registry.consumeRestored("holders") as Map<*, *>
            assertEquals(CampusBoard.LIBRARY, holder["enum"])
            state.save(allowed = false); assertFalse(file.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun composeMutableStateAndPrimitiveStateRestoreTheirRequiredTypes() {
        val boolean = UiValue.encode(mutableStateOf(false))!!.decode() as MutableState<*>
        assertEquals(false, boolean.value)
        val number = UiValue.encode(mutableIntStateOf(8))!!.decode() as MutableIntState
        assertEquals(8, number.intValue)
        val decimal = UiValue.encode(mutableFloatStateOf(1.25f))!!.decode() as MutableFloatState
        assertEquals(1.25f, decimal.floatValue)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun materialSheetPositionsCanBeSavedAndRestored() {
        val root = temp()
        try {
            val file = File(root, "ui.json")
            val state = DesktopStateStore(file)
            SheetValue.entries.forEach { position ->
                assertTrue(state.registry.canBeSaved(position), "Cannot save $position")
                state.registry.registerProvider(position.name) { position }
            }
            state.save()
            val restored = DesktopStateStore(file)
            SheetValue.entries.forEach { position ->
                assertEquals(position, restored.registry.consumeRestored(position.name))
            }
            assertFalse(state.registry.canBeSaved(java.time.DayOfWeek.MONDAY))
            assertFailsWith<IllegalArgumentException> { UiValue("enum", "java.time.DayOfWeek#MONDAY").decode() }
        } finally { root.deleteRecursively() }
    }

    @Test fun previewSessionRestoresAndSignOutRevokesIt() = runBlocking {
        val root = temp(); val vault = MemoryVault()
        try {
            var container = DesktopContainer(DesktopPaths(root), vault, allowTestData = true)
            withTimeout(WAIT) { container.ready.first { it } }
            container.preview()
            withTimeout(WAIT) { container.repository.filterNotNull().first() }
            withTimeout(WAIT) { container.repository.value!!.snapshot.first { it.courses.isNotEmpty() } }
            assertTrue(container.repository.value!!.account.demo)
            container.close()
            container = DesktopContainer(DesktopPaths(root), vault, allowTestData = true)
            withTimeout(WAIT) { container.ready.first { it } }
            assertTrue(container.repository.value!!.account.demo)
            container.signOut()
            withTimeout(WAIT) { container.repository.first { it == null } }
            delay(100)
            container.close()
            container = DesktopContainer(DesktopPaths(root), vault, allowTestData = true)
            withTimeout(WAIT) { container.ready.first { it } }
            assertNull(container.repository.value); container.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun signedOutKeyringRecordCannotResurrectEvenWhenClearFailed() = runBlocking {
        val root = temp(); val vault = MemoryVault()
        try {
            vault.records["digicampus"] = desktopJson.encodeToString(SavedSession(Account("old", "Old account", "old", demo = true), "secret"))
            atomicPrivateWrite(File(root, "digicampus-signed-out"), byteArrayOf())
            val container = DesktopContainer(DesktopPaths(root), vault, allowTestData = true)
            withTimeout(10_000) { container.ready.first { it } }
            assertNull(container.repository.value); container.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun assignmentSelectionIsBoundedAndKeepsFileIdentity() {
        val root = temp()
        try {
            val file = File(root, "My solution.pdf").apply { writeText("%PDF-test") }
            val draft = readDesktopAssignment(file, "c", "f")
            assertEquals("My solution.pdf", draft.name); assertEquals("c", draft.courseId); assertEquals("f", draft.folderId)
            assertFailsWith<CampusException> { readDesktopAssignment(File(root, "missing"), "c", "f") }
            val empty = File(root, "empty").apply { createNewFile() }
            assertFailsWith<CampusException> { readDesktopAssignment(empty, "c", "f") }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(MAX_UPLOAD_BYTES.toLong() + 1) }
            assertFailsWith<CampusException> { readDesktopAssignment(file, "c", "f") }
        } finally { root.deleteRecursively() }
    }

    @Test fun pdfReaderRendersAndFindsTextAcrossPages() = runBlocking {
        val root = temp()
        try {
            val file = File(root, "sample.pdf")
            PDDocument().use { document ->
                repeat(2) { index ->
                    val page = PDPage(); document.addPage(page)
                    PDPageContentStream(document, page).use { stream ->
                        stream.beginText(); stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 18f)
                        stream.newLineAtOffset(32f, 700f); stream.showText(if (index == 0) "Trees and graphs" else "Shortest paths"); stream.endText()
                    }
                }
                document.save(file)
            }
            PdfSession.open(file).use { pdf ->
                assertEquals(2, pdf.pages); assertEquals(listOf(1), pdf.find("shortest")); assertEquals(listOf(0), pdf.find("GRAPHS"))
                val image = pdf.render(0, 800); assertEquals(800, image.width); assertTrue(image.height > 0)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun browserNavigationRestrictsSchemesAccountsAndCourseContext() {
        val signIn = BrowserPurpose.SignIn(SignInService.DIGICAMPUS)
        assertEquals(BrowserNavigation.ALLOW, browserNavigation(signIn, "https://login.uni-augsburg.de/sso", false))
        assertEquals(BrowserNavigation.BLOCK, browserNavigation(signIn, "file:///etc/passwd", true))
        assertEquals(BrowserNavigation.BLOCK, browserNavigation(signIn, "https://evil.example/?cookie=x", false))
        assertEquals(BrowserNavigation.BLOCK, browserNavigation(signIn, "https://digicampus.uni-augsburg.de.evil.example", false))
        val vips = BrowserPurpose.Campus("scope", "course", vipsSheetsUrl("course"), "Sheets", false)
        assertEquals(BrowserNavigation.ALLOW, browserNavigation(vips, "$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_exercise?cid=course&exercise_id=1", true))
        assertEquals(BrowserNavigation.ALLOW, browserNavigation(vips, "$DIGICAMPUS_ORIGIN/dispatch.php/course/forum?cid=course", true), "DigiCampus pages stay signed in")
        assertEquals(BrowserNavigation.BLOCK, browserNavigation(vips, "$DIGICAMPUS_ORIGIN/logout.php", true))
        assertEquals(BrowserNavigation.EXTERNAL, browserNavigation(vips, "https://example.org/paper.html", true))
        assertEquals(BrowserNavigation.ATTACHMENT, browserNavigation(vips, "$DIGICAMPUS_ORIGIN/sendfile.php?type=0&file_id=123&cid=course", true))
        assertEquals(BrowserNavigation.RECONNECT, browserNavigation(vips, "$DIGICAMPUS_ORIGIN/index.php", false))
        assertEquals(BrowserNavigation.BLOCK, browserNavigation(vips.copy(preview = true), "https://example.com", true))
        assertEquals(BrowserNavigation.BLOCK, browserNavigation(vips, "javascript:alert(1)", true))
    }
}
