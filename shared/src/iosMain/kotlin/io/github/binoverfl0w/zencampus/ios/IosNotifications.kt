package io.github.binoverfl0w.zencampus

import io.github.binoverfl0w.zencampus.data.CampusStore
import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.action
import io.github.binoverfl0w.zencampus.ui.service
import io.github.binoverfl0w.zencampus.ui.mealName
import io.github.binoverfl0w.zencampus.ui.ui
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.resume
import kotlin.time.Instant

/** Uses the same freshness and delta rules as Android, with checkpoints scoped to the account. */
internal class IosNotifications(
    private val platform: IosPlatform,
    private val store: CampusStore,
    private val services: CampusServicesRepository,
    private val accounts: IosAccounts,
    private val settings: () -> AppSettings,
    private val language: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private suspend fun awaitBoolean(action: ((Boolean) -> Unit) -> Unit): Boolean = suspendCancellableCoroutine { continuation ->
        action { result -> if (continuation.isActive) continuation.resume(result) }
    }
    suspend fun enabled() = awaitBoolean(platform::notificationsEnabled)
    private suspend fun send(kind: String, title: String, body: String) =
        awaitBoolean { done -> platform.sendNotification(kind, title, body, done) }
    private fun words(): Words {
        val setting = settings().language
        return Words(setting == AppLanguage.GERMAN || setting == AppLanguage.SYSTEM && language().startsWith("de"))
    }
    internal suspend fun once(scope: String, key: String, signature: String, kind: String, title: String, body: String) {
        if (store.read(scope, key)?.payload == signature) return
        currentCoroutineContext().ensureActive()
        if (send(kind, title, body)) store.put(scope, key, signature, nowMillis())
    }
    suspend fun account(repo: CampusRepository) {
        val options = settings()
        if (options.dataSaver || repo.account.demo) return
        val now = nowMillis()
        val words = words()
        if (options.courseworkReminders) {
            repo.refreshToday()
            repo.refreshAllCoursework(force = true)
            val due = repo.courseworkReminders(now)
            if (due.isEmpty()) {
                platform.clearNotification("coursework")
                store.remove(repo.account.cacheScope, "ios-notice-coursework")
            } else once(repo.account.cacheScope, "ios-notice-coursework",
                "${campusToday(now)}:" + due.joinToString { "${it.id}:${it.due}" },
                "coursework", words.action("notice_title"), due.take(2).joinToString(" · ") { it.title })
        }
        if (options.updateNotifications) {
            repo.refreshToday()
            repo.refreshStudyUpdates(force = true)
            val observed = repo.updateNotificationSources(now)
            val previous = store.read(repo.account.cacheScope, "ios-notice-updates")?.payload?.let {
                runCatching { json.decodeFromString<Map<String, Set<String>>>(it) }.getOrNull()
            }.orEmpty()
            val delta = updateNotificationDelta(previous, observed)
            val sent = delta.newCount == 0 || send("updates", if (words.german) "Neue Aktivitäten" else "New activity",
                if (words.german) "Neue Nachrichten oder Kursaktualisierungen" else "New messages or course updates")
            if (sent) store.put(repo.account.cacheScope, "ios-notice-updates", json.encodeToString(delta.checkpoints), now)
        }
    }
    suspend fun library() {
        if (!services.preferences.value.libraryReminders || settings().dataSaver) return
        val id = services.library.value.connectionId ?: return
        // The provider may rotate cookies under the library session lock; do not hold that lock across refresh.
        services.refreshLibrary(force = true)
        accounts.withCurrentLibrary(id) {
            val state = services.library.value
            val account = state.account
            if (state.error != null || account == null || !isFresh(account.fetchedAt, nowMillis(), CampusServicesRepository.LOAN_TTL)) return@withCurrentLibrary
            val today = campusToday()
            val due = dueSoonLoans(account, today)
            val scope = CampusServicesRepository.libraryScope(id)
            val words = words()
            if (due.isEmpty()) { platform.clearNotification("library"); store.remove(scope, "ios-notice-loans") }
            else once(scope, "ios-notice-loans", "$today:" + due.joinToString { "${it.id}:${it.dueDate}" },
                "library", words.service("books_due"), due.take(2).joinToString(" · ") { it.title })
            val ready = account.holds?.filter { it.ready }
            if (ready != null) {
                if (ready.isEmpty()) { platform.clearNotification("holds"); store.remove(scope, "ios-notice-holds") }
                else once(scope, "ios-notice-holds", ready.map { it.id }.sorted().joinToString(),
                    "holds", words.ui("pickup_ready"), ready.take(2).joinToString(" · ") { it.title })
            }
        }
    }
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun campusServices() {
        if (settings().dataSaver) return
        val prefs = services.preferences.value
        val now = nowMillis()
        val today = campusToday(now)
        val words = words()
        val hour = Instant.fromEpochMilliseconds(now).toLocalDateTime(CAMPUS_ZONE).hour
        if (prefs.favoriteAlerts && prefs.favoriteMeals.isNotEmpty() && hour in 6..13) {
            services.refreshMensa(background = true)
            if (services.mensa.value.error == null) {
                if (!words.german) services.translateMenu()
                val meals = favoriteMealsOn(services.mensa.value.menu, today, services.preferences.value)
                if (meals.isNotEmpty()) once(services.publicScope, "ios-notice-favorites", today.toString(),
                    "favorites", words.ui("favorite_today"), meals.take(2).joinToString(" · ") { words.mealName(it, services.preferences.value) })
            }
        }
        if (prefs.watchedBooks.isNotEmpty()) {
            val books = services.availableWatchedBooks()
            if (books.isNotEmpty() && send("books", words.ui("book_available"), books.take(2).joinToString(" · ") { it.title }))
                services.forgetWatchedBooks(books)
        }
    }
}
