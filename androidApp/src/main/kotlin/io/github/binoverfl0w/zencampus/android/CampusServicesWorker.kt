package io.github.binoverfl0w.zencampus.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.mealName
import io.github.binoverfl0w.zencampus.ui.ui
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
/** Favourite-dish alerts and "notify me when available" checks for watched catalogue books. */
class CampusServicesWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @SuppressLint("MissingPermission") // Runtime permission is checked below before publishing a notification.
    override suspend fun doWork(): Result {
        val container = (applicationContext as ZenApplication).container
        container.ready.first { it }
        val services = container.campusServices
        // Preferences load from the local store; give them a moment before deciding there is nothing to do.
        val preferences = withTimeoutOrNull(3_000) {
            services.preferences.first { it.favoriteAlerts && it.favoriteMeals.isNotEmpty() || it.watchedBooks.isNotEmpty() }
        } ?: return Result.success()
        if (container.settings.value.dataSaver) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val words = container.words()
        val stored = applicationContext.getSharedPreferences("campus_services_alerts", Context.MODE_PRIVATE)
        val now = nowMillis()
        val today = campusToday(now)
        val hour = Instant.fromEpochMilliseconds(now).toLocalDateTime(CAMPUS_ZONE).hour

        // Favourites: once a day, only before lunch is over.
        if (preferences.favoriteAlerts && preferences.favoriteMeals.isNotEmpty() && hour in 6..13 && stored.getString("favorites", null) != today.toString()) {
            services.refreshMensa()
            if (!words.german) services.translateMenu()
            val meals = favoriteMealsOn(services.mensa.value.menu, today, services.preferences.value)
            if (meals.isNotEmpty()) {
                manager.createNotificationChannel(NotificationChannel("mensa-favorites", words.ui("favorite_alerts"), NotificationManager.IMPORTANCE_DEFAULT))
                manager.notify(1047, NotificationCompat.Builder(applicationContext, "mensa-favorites").setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(words.ui("favorite_today")).setContentText(meals.take(2).joinToString(" · ") { words.mealName(it, services.preferences.value) })
                    .setContentIntent(open("mensa", 1047)).setAutoCancel(true).setOnlyAlertOnce(true).build())
                stored.edit(commit = true) { putString("favorites", today.toString()) }
            }
        }

        // Watched books: an available copy notifies once and ends the watch.
        if (services.preferences.value.watchedBooks.isNotEmpty()) {
            val available = services.checkWatchedBooks()
            if (available.isNotEmpty()) {
                manager.createNotificationChannel(NotificationChannel("library-watch", words.ui("watched_books"), NotificationManager.IMPORTANCE_DEFAULT))
                manager.notify(1048, NotificationCompat.Builder(applicationContext, "library-watch").setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(words.ui("book_available")).setContentText(available.take(2).joinToString(" · ") { it.title })
                    .setContentIntent(open("catalogue", 1048)).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            }
        }
        return Result.success()
    }

    private fun open(route: String, code: Int): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).putExtra("openService", route)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(applicationContext, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
