package io.github.binoverfl0w.zencampus.android

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.Words
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Office hours, Inbox, launcher routes, Mensa favourites, reservations and handing in a shared file. */
@RunWith(AndroidJUnit4::class)
class CampusExpansionUiTest {
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
    }

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun deliver(intent: Intent) {
        // ActivityScenario matches lifecycle events to its original launch intent, so it is restored afterwards.
        val original = compose.activity.intent
        compose.runOnIdle { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity, intent) }
        compose.waitForIdle()
        compose.runOnIdle { compose.activity.intent = original }
    }

    private fun open(route: String) = deliver(Intent(compose.activity, MainActivity::class.java).putExtra("openService", route))

    private fun waitForTag(tag: String, timeout: Long = 10_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun launcherRoutesOpenOfficeHoursAndABookingAppearsOnTheList() {
        preview()
        open("officehours")
        waitForTag("office-hours")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("staff-teacher").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("office-hours").performScrollToNode(hasTestTag("staff-teacher"))
        compose.onNodeWithTag("staff-teacher").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("slot-weber-slot-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("office-hours").performScrollToNode(hasTestTag("slot-weber-slot-0"))
        compose.onNodeWithTag("slot-weber-slot-0").performClick()
        compose.onNodeWithTag("confirm-booking").assertIsNotEnabled()
        compose.onNodeWithTag("booking-reason").performTextInput("Project topic")
        compose.onNodeWithTag("confirm-booking").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { container.repository.value!!.snapshot.value.consultationBookings.isNotEmpty() }
        val booking = container.repository.value!!.snapshot.value.consultationBookings.single()
        Assert.assertEquals("Project topic", booking.reason)
        compose.onNodeWithTag("office-hours").performScrollToNode(hasTestTag("booking-${booking.id}"))
        compose.onNodeWithTag("booking-${booking.id}").assertIsDisplayed()
    }

    @Test fun inboxHoldsMessagesAndUpdatesAndLauncherRoutesReachThem() {
        preview()
        open("inbox")
        waitForTag("inbox-messages")
        compose.onNodeWithTag("inbox-messages").assertIsSelected()
        compose.onNodeWithTag("inbox-updates").performClick()
        compose.onNodeWithTag("inbox-updates").assertIsSelected()
        open("calendar")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("section-calendar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("section-calendar").assertIsSelected()
    }

    @Test fun favouriteDishesAreRememberedAndAllergenFiltersHideDishes() {
        preview()
        open("mensa")
        compose.waitUntil(10_000) { container.previewServices.mensa.value.menu != null }
        // The sample Mensa is closed at weekends; pick a weekday that the sample menu covers.
        val today = campusToday()
        if (today.dayOfWeek.ordinal >= 5) {
            compose.onNodeWithContentDescription(Words(false).text("next_week")).performClick()
            val monday = weekDates(today.plus(7, DateTimeUnit.DAY)).first()
            compose.onNodeWithContentDescription(Words(false).formatDate(monday, true)).performClick()
        }
        compose.waitUntil(10_000) { compose.onAllNodes(tagStartsWith("favorite-")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(tagStartsWith("favorite-"))[0].performClick()
        compose.waitUntil(5_000) { container.previewServices.preferences.value.favoriteMeals.isNotEmpty() }
        compose.onNodeWithTag("mensa-allergens").performClick()
        compose.onNodeWithTag("allergen-dialog").assertExists()
        compose.onAllNodes(tagStartsWith("allergen-") and hasClickAction())[0].performClick()
        compose.waitUntil(5_000) { container.previewServices.preferences.value.excludedLabels.isNotEmpty() }
    }

    @Test fun readyReservationsAreShownWithTheLoans() {
        preview()
        compose.runOnIdle { container.connectPreviewLibrary() }
        compose.waitUntil(10_000) { container.previewServices.library.value.account?.holds != null }
        open("library")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library-scroll").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-scroll").performScrollToNode(hasTestTag("library-holds"))
        compose.onNodeWithTag("library-holds").assertExists()
        compose.onAllNodesWithText("Ready for pickup").onFirst().assertExists()
    }

    private fun syncedEntries(relativePrefix: String): List<Pair<Long, String>> {
        val resolver = compose.activity.contentResolver
        return resolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Downloads._ID, android.provider.MediaStore.Downloads.RELATIVE_PATH, android.provider.MediaStore.Downloads.DISPLAY_NAME),
            "${android.provider.MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("$relativePrefix%"), null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1) + cursor.getString(2)) }
        }
    }
    private fun deleteEntries(entries: List<Pair<Long, String>>) = entries.forEach {
        compose.activity.contentResolver.delete(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.first), null, null)
    }

    @Test fun syncWritesEveryCourseFileToDownloadsAndASecondSyncIsUpToDate() {
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        preview()
        val course = "Algorithmen und Datenstrukturen"
        val prefix = "Download/ZenCampus/$course/"
        deleteEntries(syncedEntries(prefix))
        try {
            compose.onNodeWithText("Courses").performClick()
            compose.onNodeWithText(course).performClick()
            compose.onNodeWithText("Materials").performClick()
            compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("sync-course-materials"))
            compose.onNodeWithTag("sync-course-materials").performClick()
            waitForTag("sync-summary", 15_000)
            compose.onNodeWithTag("confirm-material-sync").performClick()
            compose.waitUntil(30_000) { container.repository.value!!.materialSync.value?.running == false }
            val progress = container.repository.value!!.materialSync.value!!
            Assert.assertTrue(progress.failed.toString(), progress.failed.isEmpty())
            Assert.assertTrue(progress.total > 0)
            // The snapshot follows the store asynchronously.
            compose.waitUntil(5_000) { container.repository.value!!.snapshot.value.materialSync["algorithms"]?.files?.size == progress.total }
            val record = container.repository.value!!.snapshot.value.materialSync.getValue("algorithms")
            val entries = syncedEntries(prefix)
            Assert.assertEquals(entries.toString(), record.files.size, entries.size)
            // Folder structure is kept: the nested reading folder lives below its parent.
            Assert.assertTrue(entries.toString(), entries.any { it.second.startsWith("${prefix}Lecture notes/") })
            // A clean sync closes its dialog by itself.
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("material-sync-dialog").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("sync-course-materials").performClick()
            waitForTag("sync-up-to-date", 15_000)
            // Nothing to sync: the dialog closes by itself too.
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("material-sync-dialog").fetchSemanticsNodes().isEmpty() }
        } finally { deleteEntries(syncedEntries(prefix)) }
    }

    @Test fun syncingTheSameFileAgainReplacesTheDownloadsCopy() {
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val folder = "ZenCampus sync test ${java.util.UUID.randomUUID().toString().take(8)}"
        val storage = AndroidMaterials(compose.activity)
        try {
            kotlinx.coroutines.runBlocking {
                listOf("first", "second").forEach { content ->
                    val writer = storage.syncWriter(null, listOf(folder, "Week 1"), "notes.txt", "text/plain")
                    writer.write(content.toByteArray()); writer.finish()
                }
                // An aborted write leaves nothing behind.
                storage.syncWriter(null, listOf(folder, "Week 1"), "draft.txt", "text/plain").abort()
            }
            val entries = syncedEntries("Download/ZenCampus/$folder/")
            Assert.assertEquals(listOf("Download/ZenCampus/$folder/Week 1/notes.txt"), entries.map { it.second })
            val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, entries.single().first)
            Assert.assertEquals("second", compose.activity.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally { deleteEntries(syncedEntries("Download/ZenCampus/$folder/")) }
    }

    @Test fun aSharedFileOpensTheFolderChoiceAndThenTheUsualUploadReview() {
        preview()
        val file = File(compose.activity.cacheDir, "exercise-sheet.pdf").apply { writeBytes("%PDF-1.4 preview".toByteArray()) }
        deliver(Intent(Intent.ACTION_SEND).setClass(compose.activity, MainActivity::class.java)
            .setType("application/pdf").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file)))
        waitForTag("share-sheet")
        compose.onNodeWithText("exercise-sheet.pdf").assertExists()
        compose.waitUntil(15_000) { compose.onAllNodes(tagStartsWith("share-folder-")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(tagStartsWith("share-folder-"))[0].performClick()
        compose.waitUntil(10_000) { container.repository.value!!.uploadDraft.value != null }
        Assert.assertEquals("exercise-sheet.pdf", container.repository.value!!.uploadDraft.value!!.name)
        waitForTag("confirm-assignment-upload")
        compose.onNodeWithTag("share-sheet").assertDoesNotExist()
        compose.runOnIdle { container.repository.value!!.dismissUpload() }
        file.delete()
    }
}
