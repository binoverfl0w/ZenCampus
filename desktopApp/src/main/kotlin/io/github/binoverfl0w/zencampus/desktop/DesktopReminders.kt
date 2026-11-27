package io.github.binoverfl0w.zencampus.desktop

import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.model.courseworkReminders
import io.github.binoverfl0w.zencampus.ui.mealName
import io.github.binoverfl0w.zencampus.ui.ui
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.datetime.toLocalDateTime

/** Opt-in checks live with the desktop process, including when its window is minimized. */
internal class DesktopReminders(private val container: DesktopContainer) {
    suspend fun run() {
        delay(20_000)
        while (currentCoroutineContext().isActive) {
            try { check() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            delay(60 * 60_000L)
        }
    }
    private suspend fun check() {
        val settings = container.settings.value
        if (!container.network.value.canPrefetch(settings)) return
        val repo = container.repository.value
        if (repo != null && !repo.account.demo && (settings.backgroundRefresh || settings.courseworkReminders)) repo.syncStudy()
        if (repo != null && !repo.account.demo && settings.backgroundRefresh && container.repository.value === repo) repo.autoSyncMaterials()
        if (repo != null && !repo.account.demo && settings.courseworkReminders && container.repository.value === repo) {
            repo.refreshAllCoursework(force = true)
            val due = repo.courseworkReminders(nowMillis())
            if (due.isNotEmpty() && container.repository.value === repo && container.settings.value.courseworkReminders)
                publish("coursework", repo.account.cacheScope, due.joinToString { "${it.id}:${it.due}" },
                    "Coursework due soon", due.take(2).joinToString(" · ") { it.title })
        }
        val services = container.services
        val words = desktopWords(container.settings.value)
        if (services.preferences.value.libraryReminders && services.library.value.connected) libraryReminders(services, words)
        val preferences = services.preferences.value
        val now = nowMillis()
        val hour = kotlin.time.Instant.fromEpochMilliseconds(now).toLocalDateTime(CAMPUS_ZONE).hour
        if (preferences.favoriteAlerts && preferences.favoriteMeals.isNotEmpty() && hour in 6..13) {
            services.refreshMensa()
            if (!words.german) services.translateMenu()
            val meals = favoriteMealsOn(services.mensa.value.menu, campusToday(now), services.preferences.value)
            if (meals.isNotEmpty()) publish("mensa-favorites", "mensa", meals.joinToString { it.id },
                words.ui("favorite_today"), meals.take(2).joinToString(" · ") { words.mealName(it, services.preferences.value) })
        }
        if (preferences.watchedBooks.isNotEmpty()) {
            val available = services.checkWatchedBooks()
            if (available.isNotEmpty()) publish("library-watch", "catalogue", available.joinToString { it.id },
                words.ui("book_available"), available.take(2).joinToString(" · ") { it.title })
        }
    }

    private suspend fun libraryReminders(services: CampusServicesRepository, words: io.github.binoverfl0w.zencampus.ui.Words) {
        services.refreshLibrary(force = true)
        val state = services.library.value; val account = state.account ?: return
        if (state.error != null || !isFresh(account.fetchedAt, nowMillis(), CampusServicesRepository.LOAN_TTL)) return
        val due = dueSoonLoans(account, campusToday())
        if (due.isNotEmpty() && services.library.value.connectionId == state.connectionId && services.preferences.value.libraryReminders)
            publish("library", state.connectionId.orEmpty(), due.joinToString { "${it.id}:${it.dueDate}" },
                "Library books due soon", due.take(2).joinToString(" · ") { it.title })
        val ready = account.holds.orEmpty().filter { it.ready }
        if (ready.isNotEmpty() && services.library.value.connectionId == state.connectionId && services.preferences.value.libraryReminders)
            publish("library-holds", state.connectionId.orEmpty(), ready.map { it.id }.sorted().joinToString(),
                words.ui("pickup_ready"), ready.take(2).joinToString(" · ") { it.title })
    }
    private fun publish(kind: String, owner: String, value: String, title: String, body: String) {
        val signature = digest("$owner:${campusToday()}:$value")
        if (container.preferences.read().reminderSignatures[kind] == signature) return
        if (isWindows) {
            if (windowsNotify(title, body)) container.preferences.update { it.copy(reminderSignatures = it.reminderSignatures + (kind to signature)) }
            return
        }
        val mac = System.getProperty("os.name").startsWith("Mac")
        val args = if (mac) listOf("/usr/bin/osascript", "-") else listOf("notify-send", "--app-name=ZenCampus", "--icon=appointment-soon", "--urgency=low", "--", title, body)
        val process = ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.bufferedWriter().use { writer ->
            if (mac) writer.write("display notification ${appleScriptString(body)} with title ${appleScriptString(title)}\n")
        }
        if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0)
            container.preferences.update { it.copy(reminderSignatures = it.reminderSignatures + (kind to signature)) }
        else process.destroyForcibly()
    }
}

internal fun appleScriptString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ") + "\""
