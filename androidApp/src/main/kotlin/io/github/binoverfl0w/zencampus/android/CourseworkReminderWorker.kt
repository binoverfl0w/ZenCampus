package io.github.binoverfl0w.zencampus.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.*
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.model.courseworkReminders
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.action
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.first

class CourseworkReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @SuppressLint("MissingPermission") // Permission is checked immediately before posting.
    override suspend fun doWork(): Result {
        val container = (applicationContext as ZenApplication).container
        container.ready.first { it }
        val repo = container.repository.value ?: return Result.success()
        val settings = container.settings.value
        if (!settings.courseworkReminders || settings.dataSaver || repo.account.demo) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        repo.refreshToday() // Wait for initial profile/semester/course setup before selecting reminder sources.
        repo.refreshAllCoursework(force = true)
        val now = nowMillis()
        val due = repo.courseworkReminders(now)
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val prefs = applicationContext.getSharedPreferences("coursework_reminders", Context.MODE_PRIVATE)
        val signature = MessageDigest.getInstance("SHA-256").digest((repo.account.cacheScope + campusToday(now) + due.joinToString { it.id + ":" + it.due }).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val words = Words(settings.language == AppLanguage.GERMAN || settings.language == AppLanguage.SYSTEM && Locale.getDefault().language.startsWith("de"))
        container.withCourseworkNotification(repo) {
            if (due.isEmpty()) { manager.cancel(1043); prefs.edit(commit = true) { remove("notice") } }
            else if (prefs.getString("notice", null) != signature) {
                manager.createNotificationChannel(NotificationChannel("coursework-due", words.action("notifications"), NotificationManager.IMPORTANCE_LOW))
                val intent = Intent(applicationContext, MainActivity::class.java).putExtra("openService", "coursework")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pending = PendingIntent.getActivity(applicationContext, 1043, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(1043, NotificationCompat.Builder(applicationContext, "coursework-due").setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(words.action("notice_title")).setContentText(due.take(2).joinToString(" · ") { it.title })
                    .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
                prefs.edit(commit = true) { putString("notice", signature) }
            }
        }
        return Result.success()
    }
}
