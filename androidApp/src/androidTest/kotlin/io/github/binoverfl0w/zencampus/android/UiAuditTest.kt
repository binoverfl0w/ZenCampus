package io.github.binoverfl0w.zencampus.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.binoverfl0w.zencampus.ui.ZenApp
import io.github.binoverfl0w.zencampus.data.NetworkConditions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.binoverfl0w.zencampus.model.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Release visual review, using isolated fixtures on the QA emulator only. */
@RunWith(AndroidJUnit4::class)
class UiAuditTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as ZenApplication).container
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun capture(name: String, narrow: Boolean = false) {
        compose.waitForIdle()
        if (narrow) {
            compose.onNodeWithTag("board-header-column").assertIsDisplayed()
            if (compose.onAllNodesWithTag("board-sections-column").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("board-sections-column").assertIsDisplayed()
        }
        val file = File(compose.activity.getExternalFilesDir(null), "ui-audit/$name.png")
        file.parentFile!!.mkdirs()
        if (narrow) file.outputStream().use {
            Assert.assertTrue(compose.onNodeWithTag("audit-shell").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } else Assert.assertTrue(device.takeScreenshot(file))
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/ZenCampus-UI-audit-${BuildConfig.VERSION_NAME}")
        }
        val resolver = compose.activity.contentResolver
        val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
    }
    private fun board(id: String) {
        compose.onNodeWithTag("board-switcher").performClick()
        compose.onNodeWithTag("choose-$id").performClick()
    }
    @Test fun reviewReleaseScreensWithoutUsingAStudentAccount() {
        compose.runOnIdle {
            container.setSettings(AppSettings(theme = ThemeMode.LIGHT, language = AppLanguage.ENGLISH))
            container.signOut()
        }
        compose.waitUntil(10_000) { container.ready.value && container.repository.value == null }
        runBlocking { container.previewServices.setPreferences { CampusServicePreferences() } }
        compose.onNodeWithText("Continue with DigiCampus").assertIsDisplayed()
        compose.onNodeWithText("Explore a preview").assertDoesNotExist()
        capture("01-welcome")
        compose.runOnIdle { container.preview() }
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courses?.size == 4 }
        capture("02-today")
        compose.onNodeWithTag("today-scroll").performScrollToNode(hasText("All coursework"))
        compose.onNodeWithText("All coursework").performClick()
        capture("03-coursework")
        device.pressBack()
        compose.onNodeWithTag("section-courses").performClick()
        capture("04-courses")
        compose.onNodeWithText("Algorithmen und Datenstrukturen").performClick()
        compose.waitUntil(10_000) { container.repository.value!!.snapshot.value.vipsSheets["algorithms"] != null }
        capture("05-course-overview")
        compose.onNodeWithText("Materials").performClick()
        capture("06-materials")
        compose.onNodeWithTag("course-scroll").performScrollToNode(hasTestTag("vips-sheet-preview-3"))
        capture("07-vips")
        device.pressBack()
        compose.onNodeWithTag("section-calendar").performClick()
        capture("08-calendar-day")
        compose.onNodeWithTag("planner-view-week").performClick()
        compose.onNodeWithTag("planner-calendar").performScrollToNode(hasTestTag("planner-week-grid"))
        capture("09-calendar-week")
        compose.onNodeWithTag("planner-calendar").performScrollToNode(hasTestTag("planner-filters"))
        compose.onNodeWithTag("planner-filters").performClick()
        capture("10-planner-filters")
        device.pressBack()
        board("library")
        capture("11-library-catalogue")
        compose.onNodeWithTag("catalogue-query").performTextInput("Cormen")
        compose.onNodeWithText("Search catalogue").performClick()
        compose.waitUntil(10_000) { container.previewServices.catalogue.search.value.results?.books?.size == 1 }
        compose.onNodeWithTag("catalogue-scroll").performScrollToNode(hasText("Introduction to algorithms"))
        capture("12-library-results")
        compose.onNodeWithText("Introduction to algorithms").performClick()
        compose.waitUntil(10_000) { container.previewServices.catalogue.record.value.record?.copies?.size == 2 }
        capture("13-library-book")
        compose.onNodeWithContentDescription("Your cards").performClick()
        compose.onNodeWithText("Connect library account").performScrollTo().performClick()
        compose.waitUntil(10_000) { container.previewServices.library.value.connected }
        capture("14-account-cards")
        device.pressBack()
        device.pressBack()
        compose.onNodeWithTag("section-loans").performClick()
        capture("15-library-loans")
        board("mensa")
        compose.waitUntil(10_000) { container.previewServices.mensa.value.menu != null }
        capture("16-mensa")
        board("study")
        compose.onNodeWithContentDescription("Settings").performClick()
        capture("17-settings")
        compose.runOnIdle { container.setSettings(container.settings.value.copy(theme = ThemeMode.DARK, language = AppLanguage.GERMAN)) }
        capture("18-settings-dark-german")
    }
    @Test fun reviewNarrowGermanLayoutsWithLargeText() {
        compose.runOnIdle {
            container.setSettings(AppSettings(theme = ThemeMode.DARK, language = AppLanguage.GERMAN))
            container.signOut()
        }
        compose.waitUntil(10_000) { container.ready.value && container.repository.value == null }
        compose.runOnIdle { container.preview() }
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courses?.size == 4 }
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current.density
                val repo by container.repository.collectAsState()
                val settings by container.settings.collectAsState()
                CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag("audit-shell")) {
                        ZenApp(repo, settings, NetworkConditions(), true, "de", {}, container::signOut,
                            container::setSettings, {}, services = container.previewServices, appVersion = BuildConfig.VERSION_NAME)
                    }
                }
            }
        }
        capture("19-narrow-today-dark-german-large-text", narrow = true)
        compose.onNodeWithTag("section-courses").performClick()
        capture("20-narrow-courses-large-text", narrow = true)
        compose.onNodeWithTag("section-calendar").performClick()
        compose.onNodeWithTag("planner-view-week").performClick()
        compose.onNodeWithTag("planner-calendar").performScrollToNode(hasTestTag("planner-week-grid"))
        capture("21-narrow-calendar-large-text", narrow = true)
        board("mensa")
        compose.waitUntil(10_000) { container.previewServices.mensa.value.menu != null }
        capture("22-narrow-mensa-large-text", narrow = true)
        board("library")
        capture("23-narrow-library-large-text", narrow = true)
    }

}
