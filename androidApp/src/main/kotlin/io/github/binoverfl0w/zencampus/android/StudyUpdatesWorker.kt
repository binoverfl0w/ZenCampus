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
import io.github.binoverfl0w.zencampus.model.nowMillis
import io.github.binoverfl0w.zencampus.model.updateNotificationDelta
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.Locale

class StudyUpdatesWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val container = (applicationContext as ZenApplication).container
        container.ready.first { it }
        val repo = container.repository.value ?: return Result.success()
        val settings = container.settings.value
        if (!settings.updateNotifications || settings.dataSaver || repo.account.demo) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        repo.refreshToday()
        repo.refreshStudyUpdates(force = true)
        val sources = repo.updateNotificationSources(nowMillis())
        val prefs = applicationContext.getSharedPreferences("study_updates", Context.MODE_PRIVATE)
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val observed = sources.mapKeys { digest(repo.account.cacheScope + ":" + it.key) }
            .mapValues { (_, values) -> values.map(::digest).toSet() }
        container.withUpdatesNotification(repo) {
            val previous = observed.keys.filter { prefs.contains(it) }.associateWith { prefs.getStringSet(it, emptySet()).orEmpty().toSet() }
            val delta = updateNotificationDelta(previous, observed)
            prefs.edit(commit = true) { delta.checkpoints.forEach { (key, value) -> putStringSet(key, value) } }
            if (delta.newCount > 0) {
                val german = settings.language == io.github.binoverfl0w.zencampus.model.AppLanguage.GERMAN ||
                    settings.language == io.github.binoverfl0w.zencampus.model.AppLanguage.SYSTEM && Locale.getDefault().language.startsWith("de")
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("study-updates", if (german) "Kursaktualisierungen" else "Study updates", NotificationManager.IMPORTANCE_LOW))
                val intent = Intent(applicationContext, MainActivity::class.java).putExtra("openService", "updates")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pending = PendingIntent.getActivity(applicationContext, 1044, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(1044, NotificationCompat.Builder(applicationContext, "study-updates").setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(if (german) "Neue Aktivitäten" else "New activity")
                    .setContentText(if (german) "Neue Nachrichten oder Kursaktualisierungen" else "New messages or course updates")
                    .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            }
        }
        return Result.success()
    }
}
