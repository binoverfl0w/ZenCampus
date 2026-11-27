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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.service
import io.github.binoverfl0w.zencampus.ui.ui
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus

class LibraryReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @SuppressLint("MissingPermission") // Runtime permission is checked below before publishing a notification.
    override suspend fun doWork(): Result {
        val container = (applicationContext as ZenApplication).container
        container.ready.first { it }
        val services = container.campusServices
        val enabled = withTimeoutOrNull(3_000) { services.preferences.first { it.libraryReminders } } ?: return Result.success()
        if (!enabled.libraryReminders || !services.library.value.connected || container.settings.value.dataSaver) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        services.refreshLibrary(force = true)
        val state = services.library.value
        val account = state.account ?: return Result.success()
        if (state.error != null || !isFresh(account.fetchedAt, nowMillis(), CampusServicesRepository.LOAN_TTL)) return Result.success()
        val today = campusToday()
        val loans = dueSoonLoans(account, today)
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        notifyReadyHolds(container, state.connectionId, account, manager)
        if (loans.isEmpty()) { manager.cancel(1042); return Result.success() }
        val signature = MessageDigest.getInstance("SHA-256").digest(
            (state.connectionId + ":" + today + ":" + loans.joinToString { it.id + it.dueDate }).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val prefs = applicationContext.getSharedPreferences("library_reminders", Context.MODE_PRIVATE)
        if (prefs.getString("notice", null) == signature) return Result.success()
        val settings = container.settings.value
        val words = Words(settings.language == AppLanguage.GERMAN || settings.language == AppLanguage.SYSTEM && Locale.getDefault().language.startsWith("de"))
        manager.createNotificationChannel(NotificationChannel("library-due", words.service("library_reminders"), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(applicationContext, MainActivity::class.java).putExtra("openService", "library")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(applicationContext, 1042, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        container.withLibraryNotification(state.connectionId) {
        manager.notify(1042, NotificationCompat.Builder(applicationContext, "library-due")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(words.service("books_due"))
            .setContentText(loans.take(2).joinToString(" · ") { it.title }).setContentIntent(pending)
            .setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        prefs.edit().putString("notice", signature).commit()
        }
        return Result.success()
    }

    /** Reservations waiting at the pickup desk; each set of ready holds notifies once. */
    @SuppressLint("MissingPermission")
    private suspend fun notifyReadyHolds(container: AppContainer, connectionId: String?, account: LibraryAccount, manager: NotificationManager) {
        val ready = account.holds?.filter { it.ready } ?: return
        val prefs = applicationContext.getSharedPreferences("library_reminders", Context.MODE_PRIVATE)
        if (ready.isEmpty()) { manager.cancel(1046); prefs.edit().remove("holds").commit(); return }
        val signature = ready.map { it.id }.sorted().joinToString()
        if (prefs.getString("holds", null) == signature) return
        val words = container.words()
        manager.createNotificationChannel(NotificationChannel("library-holds", words.ui("reservations"), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(applicationContext, MainActivity::class.java).putExtra("openService", "library")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(applicationContext, 1046, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        container.withLibraryNotification(connectionId) {
            manager.notify(1046, NotificationCompat.Builder(applicationContext, "library-holds").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(words.ui("pickup_ready")).setContentText(ready.take(2).joinToString(" · ") { hold ->
                    hold.title + (hold.pickupUntil?.let { " (${words.ui("pickup_until")} ${words.formatDate(it)})" } ?: "")
                }).setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            prefs.edit().putString("holds", signature).commit()
        }
    }
}
