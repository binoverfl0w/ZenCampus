package io.github.binoverfl0w.zencampus.android

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.binoverfl0w.zencampus.model.AppLanguage
import io.github.binoverfl0w.zencampus.model.AppSettings
import io.github.binoverfl0w.zencampus.model.ThemeMode
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hosts every home-screen widget the way a launcher does (its own AppWidgetHost, launcher-like sizes) with the sample
 * account, and checks each one inflates and shows that account's day. A screenshot of the set is saved for review.
 */
@RunWith(AndroidJUnit4::class)
class WidgetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as ZenApplication).container
    private val host = AppWidgetHost(context, 7171)

    @After fun tearDown() {
        host.stopListening(); host.deleteHost()
        instrumentation.runOnMainSync { container.signOut() }
    }

    @Test fun everyWidgetInflatesInAHostAndShowsTheSampleDay() {
        instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0").close()
        instrumentation.runOnMainSync { container.setSettings(AppSettings(theme = ThemeMode.SYSTEM, language = AppLanguage.ENGLISH)) }
        // A fresh install creates its database and keys first, which takes a while on an emulator.
        waitFor(60_000) { container.ready.value }
        instrumentation.runOnMainSync { container.preview() }
        waitFor { container.repository.value?.snapshot?.value?.courses?.isNotEmpty() == true }

        val manager = AppWidgetManager.getInstance(context)
        // Launcher-like sizes in dp: 4×2, 2×2, 2×2, 4×2, 2×1 and the 2×1 form of Next class.
        val widgets = listOf(
            Triple(HomeWidgetProvider::class.java, 330, 200), Triple(NextClassWidget::class.java, 160, 160),
            Triple(MensaWidget::class.java, 160, 160), Triple(DueSoonWidget::class.java, 330, 170),
            Triple(LibraryWidget::class.java, 160, 76), Triple(NextClassWidget::class.java, 160, 76),
        ).map { (provider, width, height) ->
            val id = host.allocateAppWidgetId()
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            }
            assertTrue("bind ${provider.simpleName}", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, provider), options))
            Triple(id, width, height)
        }
        host.startListening()

        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val views = mutableListOf<AppWidgetHostView>()
        scenario.onActivity { activity ->
            val grid = GridLayout(activity).apply { columnCount = 2; setPadding(dp(12), dp(12), dp(12), dp(12)) }
            for ((id, width, height) in widgets) {
                val view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                views += view
                grid.addView(view, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, if (width > 200) 2 else 1)).apply {
                    this.width = dp(width); this.height = dp(height); setMargins(dp(5), dp(5), dp(5), dp(5))
                })
            }
            activity.setContentView(FrameLayout(activity).apply {
                setBackgroundColor(if (night(activity)) Color.rgb(0x16, 0x1d, 0x22) else Color.rgb(0xbf, 0xcc, 0xc8))
                addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
        kotlinx.coroutines.runBlocking { container.refreshHomeWidget() }

        val next = container.repository.value!!.snapshot.value.let { snapshot ->
            io.github.binoverfl0w.zencampus.model.nextUpcomingEvent(snapshot.events, System.currentTimeMillis())?.let { event ->
                snapshot.courses.firstOrNull { it.id == event.courseId }?.title ?: event.title
            }
        }
        waitFor { views.all { it.findViewById<View>(R.id.widget_root) != null } }
        instrumentation.waitForIdleSync()
        // The sample account always has a next class, coursework and a library account.
        val dayTitle = views[0].findViewById<TextView>(R.id.day_title_0)
        waitFor { dayTitle.text.toString() == next }
        assertEquals(next, views[1].findViewById<TextView>(R.id.next_title).text.toString())
        assertNotNull(views[3].findViewById<TextView>(R.id.due_label))
        Thread.sleep(800)

        val night = night(context)
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            val out = File(context.getExternalFilesDir(null), "widgets-${if (night) "dark" else "light"}.png")
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        scenario.close()
    }

    private fun night(context: android.content.Context) =
        context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (true) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            check(System.currentTimeMillis() < end) { "Timed out waiting for the widgets" }
            Thread.sleep(100)
        }
    }
}
