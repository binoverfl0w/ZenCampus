package io.github.binoverfl0w.zencampus.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import io.github.binoverfl0w.zencampus.ui.HomeWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The home-screen widgets. Each shows part of [HomeWidgets], built from cached data in shared code; a provider only lays
 * it out. They are refreshed whenever that data changes, and by an alarm when their text goes stale (a class starts or
 * ends, a new day begins).
 */
abstract class CampusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context, fetchMenu = true)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // Resizing can switch layouts or show more rows.
        CampusWidgets.latest?.let { CampusWidgets.render(context, manager, javaClass, id, it) } ?: refresh(context, fetchMenu = false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CampusWidgets.ACTION_REFRESH) refresh(context, fetchMenu = false) else super.onReceive(context, intent)
    }

    private fun refresh(context: Context, fetchMenu: Boolean) {
        val pending = goAsync()
        val container = (context.applicationContext as ZenApplication).container
        scope.launch {
            try { withTimeoutOrNull(8_000) { container.refreshHomeWidget(fetchMenu) } } finally { pending.finish() }
        }
    }

    private companion object { val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}

/** "Your day": the date and weather, the next class, the next deadline and today's top dish. Keeps its original name so placed widgets stay. */
class HomeWidgetProvider : CampusWidget()
class NextClassWidget : CampusWidget()
class MensaWidget : CampusWidget()
class DueSoonWidget : CampusWidget()
class LibraryWidget : CampusWidget()

object CampusWidgets {
    const val ACTION_REFRESH = "io.github.binoverfl0w.zencampus.android.widgets.REFRESH"
    private val providers = listOf(HomeWidgetProvider::class.java, NextClassWidget::class.java, MensaWidget::class.java,
        DueSoonWidget::class.java, LibraryWidget::class.java)

    /** The last data shown, so a resize can redraw without reading the cache again. */
    @Volatile var latest: HomeWidgets? = null
        private set

    fun installed(context: Context): Boolean = providers.any { ids(context, it).isNotEmpty() }

    private fun ids(context: Context, provider: Class<out CampusWidget>) =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, provider))

    fun publish(context: Context, data: HomeWidgets) {
        latest = data
        val manager = AppWidgetManager.getInstance(context)
        var any = false
        for (provider in providers) for (id in ids(context, provider)) { any = true; render(context, manager, provider, id, data) }
        if (any) scheduleRefresh(context, data.refreshAt)
    }

    fun render(context: Context, manager: AppWidgetManager, provider: Class<out CampusWidget>, id: Int, data: HomeWidgets) {
        val options = manager.getAppWidgetOptions(id)
        // The launcher reports the smallest size the widget has in either orientation.
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val views = when (provider) {
            HomeWidgetProvider::class.java -> day(context, data, height)
            NextClassWidget::class.java -> next(context, data, height)
            MensaWidget::class.java -> mensa(context, data, height)
            DueSoonWidget::class.java -> due(context, data, height)
            else -> library(context, data, height)
        }
        manager.updateAppWidget(id, views)
    }

    private fun day(context: Context, data: HomeWidgets, height: Int) = RemoteViews(context.packageName, R.layout.widget_day).apply {
        setOnClickPendingIntent(R.id.widget_root, open(context, "today"))
        // A short widget drops the date line so the three rows keep their size.
        setViewVisibility(R.id.day_header, if (height in 1 until 185) View.GONE else View.VISIBLE)
        setTextViewText(R.id.day_date, data.date)
        show(R.id.day_weather, data.weather)

        val next = data.nextClass
        setTextViewText(R.id.day_avatar_0, next?.initials ?: "·")
        setTextViewText(R.id.day_title_0, next?.title ?: if (data.signedIn) data.labels.noClasses else data.labels.signIn)
        show(R.id.day_detail_0, next?.let { listOf(it.range, it.location).filter(String::isNotBlank).joinToString(" · ") })
        show(R.id.day_badge_0, next?.day)
        setOnClickPendingIntent(R.id.day_row_0, open(context, if (data.signedIn) "calendar" else "accounts"))

        val due = data.due.firstOrNull()
        setViewVisibility(R.id.day_row_1, if (data.signedIn) View.VISIBLE else View.GONE)
        setInt(R.id.day_row_1, "setBackgroundResource", if (due?.urgent == true) R.drawable.widget_row_alert else R.drawable.widget_row_study)
        setInt(R.id.day_avatar_1, "setBackgroundResource", if (due?.urgent == true) R.drawable.widget_avatar_alert else R.drawable.widget_avatar_study)
        setTextViewText(R.id.day_avatar_1, due?.initials ?: "✓")
        setTextViewText(R.id.day_title_1, due?.title ?: data.labels.nothingDue)
        show(R.id.day_detail_1, due?.let { listOf(it.due, data.more(data.dueCount - 1)).filter(String::isNotBlank).joinToString(" · ") })
        setOnClickPendingIntent(R.id.day_row_1, open(context, "coursework"))

        val dish = data.mensa.meals.firstOrNull()
        setTextViewText(R.id.day_title_2, dish?.let { (if (it.favorite) "★ " else "") + it.name } ?: data.mensa.status.orEmpty())
        show(R.id.day_detail_2, dish?.let { listOf(it.price, data.more(data.mensa.total - 1)).filter(String::isNotBlank).joinToString(" · ") }
            ?: data.labels.mensa)
        setOnClickPendingIntent(R.id.day_row_2, open(context, "mensa"))
    }

    private fun next(context: Context, data: HomeWidgets, height: Int): RemoteViews {
        val small = height in 1 until 100
        val next = data.nextClass
        return RemoteViews(context.packageName, if (small) R.layout.widget_next_small else R.layout.widget_next).apply {
            setOnClickPendingIntent(R.id.widget_root, open(context, if (data.signedIn) "calendar" else "accounts"))
            val label = if (next?.running == true) data.labels.now else data.labels.nextClass
            if (small) {
                // One short line: when, or "Now · until 11:30" while it is on.
                setTextViewText(R.id.next_label, next?.let { if (it.running) "${data.labels.now} · ${data.labels.until} ${it.range.substringAfter("– ")}" else "${it.day.substringBefore(' ')} · ${it.start}" } ?: label)
                setTextViewText(R.id.next_title, next?.title ?: if (data.signedIn) data.labels.noClasses else data.labels.signIn)
                // A 2×1 widget has room for the label and one line; the room shows once it is taller.
                show(R.id.next_detail, next?.location?.takeIf { it.isNotBlank() && height >= 80 })
            } else {
                setTextViewText(R.id.next_label, label)
                show(R.id.next_time, next?.let { if (it.running) it.range.substringAfter("– ") else it.start })
                // The room shares the date line, so it stays visible under a two-line course name.
                show(R.id.next_day, next?.let { listOf(if (it.running) "${data.labels.until} ${it.range.substringAfter("– ")}" else it.day, it.location).filter(String::isNotBlank).joinToString(" · ") })
                setTextViewText(R.id.next_title, next?.title ?: if (data.signedIn) data.labels.noClasses else data.labels.signIn)
                show(R.id.next_detail, next?.takeIf { !it.running }?.range)
            }
        }
    }

    private val mealRows = listOf(
        Mealrow(R.id.meal_row_0, R.id.meal_line_0, R.id.meal_name_0, R.id.meal_diet_0, R.id.meal_price_0),
        Mealrow(R.id.meal_row_1, R.id.meal_line_1, R.id.meal_name_1, R.id.meal_diet_1, R.id.meal_price_1),
        Mealrow(R.id.meal_row_2, R.id.meal_line_2, R.id.meal_name_2, R.id.meal_diet_2, R.id.meal_price_2),
        Mealrow(R.id.meal_row_3, R.id.meal_line_3, R.id.meal_name_3, R.id.meal_diet_3, R.id.meal_price_3),
        Mealrow(R.id.meal_row_4, R.id.meal_line_4, R.id.meal_name_4, R.id.meal_diet_4, R.id.meal_price_4),
        Mealrow(R.id.meal_row_5, R.id.meal_line_5, R.id.meal_name_5, R.id.meal_diet_5, R.id.meal_price_5),
    )
    private class Mealrow(val row: Int, val line: Int, val name: Int, val diet: Int, val price: Int)

    private fun mensa(context: Context, data: HomeWidgets, height: Int) = RemoteViews(context.packageName, R.layout.widget_mensa).apply {
        setOnClickPendingIntent(R.id.widget_root, open(context, "mensa"))
        setTextViewText(R.id.mensa_label, data.labels.mensa)
        // About 23 dp per dish after the heading and the "more" line; four on a 2×2 widget.
        val fits = if (height <= 0) 4 else ((height - 58) / 23).coerceIn(2, mealRows.size)
        val shown = data.mensa.meals.take(fits)
        mealRows.forEachIndexed { index, row ->
            val meal = shown.getOrNull(index)
            setViewVisibility(row.row, if (meal == null) View.GONE else View.VISIBLE)
            if (meal == null) return@forEachIndexed
            setViewVisibility(row.line, if (index == 0) View.GONE else View.VISIBLE)
            setTextViewText(row.name, (if (meal.favorite) "★ " else "") + meal.name)
            setViewVisibility(row.diet, if (meal.diet == null) View.INVISIBLE else View.VISIBLE)
            meal.diet?.let { setContentDescription(row.diet, it) }
            setTextViewText(row.price, meal.price)
        }
        show(R.id.mensa_status, data.mensa.status)
        show(R.id.mensa_more, data.more(data.mensa.total - shown.size).takeIf(String::isNotBlank))
    }

    private val dueRows = listOf(
        Duerow(R.id.due_row_0, R.id.due_avatar_0, R.id.due_title_0, R.id.due_course_0, R.id.due_when_0),
        Duerow(R.id.due_row_1, R.id.due_avatar_1, R.id.due_title_1, R.id.due_course_1, R.id.due_when_1),
        Duerow(R.id.due_row_2, R.id.due_avatar_2, R.id.due_title_2, R.id.due_course_2, R.id.due_when_2),
        Duerow(R.id.due_row_3, R.id.due_avatar_3, R.id.due_title_3, R.id.due_course_3, R.id.due_when_3),
        Duerow(R.id.due_row_4, R.id.due_avatar_4, R.id.due_title_4, R.id.due_course_4, R.id.due_when_4),
    )
    private class Duerow(val row: Int, val avatar: Int, val title: Int, val course: Int, val chip: Int)

    private fun due(context: Context, data: HomeWidgets, height: Int) = RemoteViews(context.packageName, R.layout.widget_due).apply {
        setOnClickPendingIntent(R.id.widget_root, open(context, if (data.signedIn) "coursework" else "accounts"))
        setTextViewText(R.id.due_label, if (data.dueCount == 0) data.labels.due else "${data.labels.due} · ${data.dueCount}")
        // About 46 dp per deadline below the heading; two on the smallest size, three on 4×2.
        val fits = if (height <= 0) 3 else ((height - 40) / 46).coerceIn(1, dueRows.size)
        val shown = data.due.take(fits)
        setViewVisibility(R.id.due_list, if (shown.isEmpty()) View.GONE else View.VISIBLE)
        show(R.id.due_empty, if (shown.isNotEmpty()) null else if (data.signedIn) data.labels.nothingDue else data.labels.signIn)
        dueRows.forEachIndexed { index, row ->
            val item = shown.getOrNull(index)
            setViewVisibility(row.row, if (item == null) View.GONE else View.VISIBLE)
            if (item == null) return@forEachIndexed
            val alert = item.urgent
            setTextViewText(row.avatar, item.initials)
            setInt(row.avatar, "setBackgroundResource", if (alert) R.drawable.widget_avatar_soft_alert else R.drawable.widget_avatar_soft_study)
            setTextColor(row.avatar, context.getColor(if (alert) R.color.widget_alert else R.color.widget_study))
            setTextViewText(row.title, item.title)
            show(row.course, item.course.takeIf(String::isNotBlank))
            setTextViewText(row.chip, item.due)
            setInt(row.chip, "setBackgroundResource", if (alert) R.drawable.widget_chip_alert else R.drawable.widget_chip_study)
            setTextColor(row.chip, context.getColor(if (alert) R.color.widget_alert else R.color.widget_study))
        }
    }

    private fun library(context: Context, data: HomeWidgets, height: Int) = RemoteViews(context.packageName, R.layout.widget_library).apply {
        setOnClickPendingIntent(R.id.widget_root, open(context, if (data.library.connected) "library" else "accounts"))
        setTextViewText(R.id.library_headline, data.library.headline)
        // A 2×1 widget has room for the label and one line; pickups and overdue loans then join the label.
        val roomy = height <= 0 || height >= 80
        show(R.id.library_detail, data.library.detail.takeIf { it.isNotBlank() && roomy })
        setTextViewText(R.id.library_label, if (roomy || data.library.detail.isBlank()) data.labels.library else "${data.labels.library} · ${data.library.detail}")
        setTextColor(R.id.library_detail, context.getColor(if (data.library.attention) R.color.widget_alert else R.color.widget_muted))
    }

    /** "+2 more" in the app's language, or "" for none. */
    private fun HomeWidgets.more(count: Int): String = if (count <= 0) "" else labels.more.replace("%d", count.toString())

    private fun RemoteViews.show(id: Int, text: String?) {
        setViewVisibility(id, if (text.isNullOrBlank()) View.GONE else View.VISIBLE)
        if (!text.isNullOrBlank()) setTextViewText(id, text)
    }

    private fun open(context: Context, route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).putExtra("openService", route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // One request code per route: PendingIntents that differ only in extras would replace each other.
        return PendingIntent.getActivity(context, 2000 + route.hashCode() % 1000, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** An inexact alarm (no permission needed) at the moment the widgets' text goes stale; a later publish replaces it. */
    private fun scheduleRefresh(context: Context, at: Long) {
        val intent = Intent(context, HomeWidgetProvider::class.java).setAction(ACTION_REFRESH)
        val pending = PendingIntent.getBroadcast(context, 2100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at + 1_000, pending)
    }
}
