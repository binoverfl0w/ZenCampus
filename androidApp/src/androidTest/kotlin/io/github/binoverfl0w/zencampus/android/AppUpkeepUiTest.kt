package io.github.binoverfl0w.zencampus.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.binoverfl0w.zencampus.model.*
import java.io.File
import org.junit.*
import org.junit.runner.RunWith

/** The local log and the Settings section for updates and diagnostics. */
@RunWith(AndroidJUnit4::class)
class AppUpkeepUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as ZenApplication).container

    @Before fun reset() {
        compose.runOnIdle {
            container.setSettings(AppSettings(theme = ThemeMode.LIGHT, language = AppLanguage.ENGLISH))
            container.signOut()
        }
        compose.waitUntil(10_000) { container.ready.value && container.repository.value == null }
    }

    @Test fun theLogIsWrittenScrubbedAndSettingsOffersToShareIt() {
        compose.runOnIdle { container.preview() }
        compose.waitUntil(10_000) { container.repository.value?.snapshot?.value?.courses?.isNotEmpty() == true }
        val log = File(compose.activity.filesDir, "logs/zencampus.log")
        compose.waitUntil(5_000) { log.isFile && "started ${BuildConfig.VERSION_NAME}" in log.readText() }
        Assert.assertFalse("the log never holds the sample account's name", "Alex Morgan" in log.readText())

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("release-settings"))
        compose.onNodeWithText("Share diagnostic log").assertExists()
        // Development builds have no release repository or crash endpoint, so those rows stay hidden.
        if (BuildConfig.UPDATE_REPO.isBlank()) compose.onNodeWithTag("update-action").assertDoesNotExist()
        if (BuildConfig.CRASH_DSN.isBlank()) compose.onNodeWithText("Send crash reports").assertDoesNotExist()
    }
}
