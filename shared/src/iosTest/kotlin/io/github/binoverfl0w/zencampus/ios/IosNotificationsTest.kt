package io.github.binoverfl0w.zencampus

import io.github.binoverfl0w.zencampus.ui.Words
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.services.*
import io.github.binoverfl0w.zencampus.db.CampusDatabase
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import platform.Foundation.NSUUID
import kotlin.test.*

private class AlertPlatform : IosPlatform {
    var permission = true
    var postingWorks = true
    val sent = mutableListOf<String>()
    override fun readSecret(service: String): String? = null
    override fun writeSecret(service: String, value: String) = true
    override fun deleteSecret(service: String) = true
    override fun showLogin(kind: SignInKind, savedSignIn: SavedSignInPopup?, words: Words,
                           verifyCookie: (String, (Boolean) -> Unit) -> Unit, onCookie: (String) -> Unit) = Unit
    override fun openMaterial(path: String) = true
    override fun shareMaterial(path: String) = true
    override fun writeDiagnosticLine(line: String) = Unit
    override fun shareDiagnosticLog() = true
    override fun exportMaterial(path: String, onComplete: (String?) -> Unit) = onComplete(null)
    override fun selectUpload(onFile: (String?, String?, String?) -> Unit) = Unit
    override fun selectMaterialFolder(onFolder: (String?, String?) -> Unit) = Unit
    override fun beginMaterialFolder(bookmark: String): String? = null
    override fun endMaterialFolder(lease: String) = Unit
    override fun commitMaterialFolder(lease: String, stagedPath: String, folders: List<String>, name: String): String? = null
    override fun openVips(url: String, title: String, cookie: String, classify: (String, String) -> String, onAttachment: (String, String) -> Unit, onCookie: (String) -> Unit) = Unit
    override fun openVipsPreview(url: String, title: String, classify: (String) -> String, onAttachment: (String, String) -> Unit) = Unit
    override fun scheduleBackground(enabled: Boolean) = true
    override fun canRunBackground() = true
    override fun requestNotifications(onResult: (Boolean) -> Unit) = onResult(permission)
    override fun notificationsEnabled(onResult: (Boolean) -> Unit) = onResult(permission)
    override fun sendNotification(kind: String, title: String, body: String, onResult: (Boolean) -> Unit) { sent += kind; onResult(postingWorks) }
    override fun clearNotification(kind: String) = Unit
    override fun closePrivateReader() = Unit
}
private class AvailableCatalogue : CatalogueProvider {
    override suspend fun search(query: CatalogueQuery, page: Int) = CatalogueResults(query, page, emptyList(), fetchedAt = nowMillis())
    override suspend fun record(book: CatalogueBook) = CatalogueRecord(book, copies = listOf(CatalogueCopy(status = "available")), fetchedAt = nowMillis())
}

class IosNotificationsTest {
    @Test fun availableBookIsKeptWatchedIfPostingFailsAndRemovedAfterSuccess() = runBlocking {
        val driver = NativeSqliteDriver(CampusDatabase.Schema, ":memory:")
        val store = SqlCampusStore(CampusDatabase(driver))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val platform = AlertPlatform()
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) })
        val services = CampusServicesRepository(store, DemoMensaProvider(), scope, catalogueProvider = AvailableCatalogue())
        val accounts = IosAccounts(platform, store, client, scope, services, { AppSettings() }, Dispatchers.Default)
        try {
            services.awaitPreferences(); accounts.awaitReady()
            val book = CatalogueBook("book-1", "Algorithms")
            services.watchBook(book, true)
            withTimeout(5_000) { services.preferences.first { it.watchedBooks.any { item -> item.id == book.id } } }
            val alerts = IosNotifications(platform, store, services, accounts, { AppSettings() }, { "en" })
            platform.permission = false; assertFalse(alerts.enabled())
            platform.permission = true; assertTrue(alerts.enabled())
            platform.postingWorks = false; alerts.campusServices()
            assertTrue(services.preferences.value.watchedBooks.any { it.id == book.id })
            platform.postingWorks = true; alerts.campusServices()
            withTimeout(5_000) { services.preferences.first { it.watchedBooks.none { item -> item.id == book.id } } }
            assertEquals(listOf("books", "books"), platform.sent)
            val checkpointScope = "fixture-" + NSUUID().UUIDString
            platform.postingWorks = false
            alerts.once(checkpointScope, "checkpoint", "revision-1", "updates", "title", "body")
            assertNull(store.read(checkpointScope, "checkpoint"), "Failed delivery must not advance the checkpoint")
            platform.postingWorks = true
            alerts.once(checkpointScope, "checkpoint", "revision-1", "updates", "title", "body")
            alerts.once(checkpointScope, "checkpoint", "revision-1", "updates", "title", "body")
            assertEquals(2, platform.sent.count { it == "updates" }, "A successful identical alert is sent only once")
            assertEquals("revision-1", store.read(checkpointScope, "checkpoint")?.payload)
            store.purge(checkpointScope)
        } finally { scope.coroutineContext.job.cancelAndJoin(); client.close(); driver.close() }
    }
}
