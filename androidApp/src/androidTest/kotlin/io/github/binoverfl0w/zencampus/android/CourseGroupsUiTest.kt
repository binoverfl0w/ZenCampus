package io.github.binoverfl0w.zencampus.android

import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.*
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import io.github.binoverfl0w.zencampus.ui.study.CoursesScreen
import java.io.File
import org.junit.*
import org.junit.runner.RunWith

/** Isolated debug fixtures. Synthetic class IDs never leave the local preferences/UI. */
@RunWith(AndroidJUnit4::class)
class CourseGroupsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as ZenApplication).container
    private var german by mutableStateOf(false)
    private var opened = ""

    @Before fun start() {
        compose.runOnIdle {
            container.setSettings(AppSettings(theme = ThemeMode.LIGHT, language = AppLanguage.ENGLISH))
            container.signOut()
        }
        compose.waitUntil(10_000) { container.ready.value && container.repository.value == null }
        compose.runOnIdle { container.preview() }
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courses?.size == 4 }
        val repository = container.repository.value!!
        compose.runOnUiThread { compose.activity.setContent {
            val snapshot by repository.snapshot.collectAsState()
            val sync by repository.sync.collectAsState()
            val lecture = snapshot.courses.first { it.id == "algorithms" }
            val courses = snapshot.courses + listOf(lecture.copy(id = "exercise", title = "Übung zu ${lecture.title}",
                subtitle = "Exercise class", typeName = "Übung", number = "INF-1003"))
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalWords provides Words(german), LocalDensity provides Density(density, if (german) 1.5f else 1f)) {
                ZenTheme(if (german) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Surface(Modifier.fillMaxSize()) {
                        CoursesScreen(snapshot.copy(courses = courses), repository, sync, { opened = it })
                    }
                }
            }
        } }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val file = File(compose.activity.cacheDir, "$name.png")
        Assert.assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/ZenCampus-course-groups-${BuildConfig.VERSION_NAME}")
        }
        val resolver = compose.activity.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
        file.delete()
    }

    @Test fun automaticGroupsKeepDirectOpeningAndSelectedPins() {
        compose.onNodeWithTag("course-member-algorithms").assertExists()
        compose.onNodeWithTag("courses-scroll").performScrollToNode(hasTestTag("course-pin-exercise"))
        compose.onNodeWithTag("course-pin-exercise").assertIsOff().performClick()
        compose.waitUntil(5_000) { "exercise" in container.repository.value!!.snapshot.value.preferences.pinnedCourses }
        compose.onNodeWithTag("course-pin-exercise").assertIsOn()
        Assert.assertEquals("", opened)
        compose.onNodeWithTag("courses-scroll").performScrollToIndex(0)
        capture("grouped-selected-pin")
        compose.onNodeWithTag("courses-scroll").performScrollToNode(hasTestTag("course-member-exercise"))
        compose.onNodeWithTag("course-member-exercise").performClick()
        Assert.assertEquals("exercise", opened)
        compose.onNodeWithTag("courses-scroll").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Edit group").performClick()
        compose.onNodeWithTag("course-group-name").performClick().performTextReplacement("Algorithms")
        compose.onNodeWithTag("course-group-save").assertIsDisplayed()
        compose.onNodeWithTag("course-group-save").performClick()
        compose.waitUntil(5_000) { container.repository.value!!.snapshot.value.preferences.courseGroups.singleOrNull()?.title == "Algorithms" }
        compose.onNodeWithTag("course-groups-dialog").assertDoesNotExist()
        capture("custom-course-group")
    }

    @Test fun germanLargeTextKeepsSemesterAndGroupEditingAccessible() {
        compose.runOnIdle { german = true }
        compose.onNodeWithContentDescription("Kursgruppen").assertIsDisplayed()
        compose.onNodeWithText("Wintersemester 2026/27", substring = true).assertIsDisplayed()
        capture("german-large-text")
        compose.onNodeWithContentDescription("Gruppe bearbeiten").performScrollTo().performClick()
        compose.onNodeWithTag("course-group-save").assertIsDisplayed().assertIsEnabled()
        capture("german-group-editor")
        compose.onNodeWithTag("course-groups-editor").performScrollToNode(hasTestTag("course-group-separate"))
        compose.onNodeWithTag("course-group-separate").performClick()
        compose.waitUntil(5_000) { container.repository.value!!.snapshot.value.preferences.separateCourseIds.size == 2 }
        compose.onNodeWithTag("course-member-exercise").assertDoesNotExist()
        compose.onNodeWithTag("courses-scroll").performScrollToNode(hasTestTag("course-card-exercise"))
        compose.onNodeWithTag("course-card-exercise").assertExists()
    }
}
