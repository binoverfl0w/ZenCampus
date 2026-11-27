package io.github.binoverfl0w.zencampus

import io.github.binoverfl0w.zencampus.ui.Words
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.digicampus.*
import io.github.binoverfl0w.zencampus.data.services.*
import io.github.binoverfl0w.zencampus.data.study.*
import io.github.binoverfl0w.zencampus.db.CampusDatabase
import io.github.binoverfl0w.zencampus.model.AppSettings
import io.github.binoverfl0w.zencampus.model.Account
import io.github.binoverfl0w.zencampus.model.vipsSheetsUrl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

private class TestPlatform : IosPlatform {
    override fun openMaterial(path: String) = true
    override fun shareMaterial(path: String) = true
    override fun writeDiagnosticLine(line: String) = Unit
    override fun shareDiagnosticLog() = true
    override fun exportMaterial(path: String, onComplete: (String?) -> Unit) = onComplete(null)
    override fun selectUpload(onFile: (String?, String?, String?) -> Unit) = Unit
    override fun selectMaterialFolder(onFolder: (String?, String?) -> Unit) = Unit
    override fun beginMaterialFolder(bookmark: String): String? = if (bookmark.startsWith("test:")) "test-lease\n" + bookmark.removePrefix("test:") else null
    override fun endMaterialFolder(lease: String) = Unit
    override fun commitMaterialFolder(lease: String, stagedPath: String, folders: List<String>, name: String): String? = null
    var vipsClassify: ((String, String) -> String)? = null
    var vipsCookie: ((String) -> Unit)? = null
    var closedReaders = 0
    override fun openVips(url: String, title: String, cookie: String, classify: (String, String) -> String, onAttachment: (String, String) -> Unit, onCookie: (String) -> Unit) {
        vipsClassify = classify; vipsCookie = onCookie
    }
    var previewCalls = 0
    override fun openVipsPreview(url: String, title: String, classify: (String) -> String, onAttachment: (String, String) -> Unit) {
        previewCalls++; vipsClassify = { target, _ -> classify(target) }
    }
    override fun scheduleBackground(enabled: Boolean) = true
    override fun canRunBackground() = true
    override fun requestNotifications(onResult: (Boolean) -> Unit) = onResult(true)
    override fun notificationsEnabled(onResult: (Boolean) -> Unit) = onResult(true)
    override fun sendNotification(kind: String, title: String, body: String, onResult: (Boolean) -> Unit) = onResult(true)
    override fun clearNotification(kind: String) = Unit
    override fun closePrivateReader() { closedReaders++ }
    val secrets = mutableMapOf<String, String>()
    var callback: ((String) -> Unit)? = null
    suspend fun nextLogin(): (String) -> Unit = withTimeout(5_000) {
        while (callback == null) delay(10)
        checkNotNull(callback).also { callback = null }
    }
    var canWrite = true
    var canDelete = true
    override fun readSecret(service: String) = secrets[service]
    override fun writeSecret(service: String, value: String): Boolean {
        if (!canWrite) return false
        secrets[service] = value
        return true
    }
    override fun deleteSecret(service: String): Boolean {
        if (!canDelete) return false
        secrets.remove(service)
        return true
    }
    override fun showLogin(kind: SignInKind, savedSignIn: SavedSignInPopup?, words: Words,
                           verifyCookie: (String, (Boolean) -> Unit) -> Unit, onCookie: (String) -> Unit) { callback = onCookie }
}

class IosAccountsTest {
    @Test fun accountIsVerifiedBeforeSavingAndSignOutPurgesOnlyItsScope() = runBlocking {
        val driver = NativeSqliteDriver(CampusDatabase.Schema, ":memory:")
        val store = SqlCampusStore(CampusDatabase(driver))
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/users/me") && request.headers["Cookie"] in listOf("sid=valid", "sid=renewed"))
                respond("""{"data":{"type":"users","id":"student-a","attributes":{"formatted-name":"Example Student","username":"student"}}}""", headers = headersOf("Content-Type", "application/vnd.api+json"))
            else if (request.headers["Cookie"] == "sid=other")
                respond("""{"data":{"type":"users","id":"student-b","attributes":{"formatted-name":"Other Student","username":"other"}}}""", headers = headersOf("Content-Type", "application/vnd.api+json"))
            else respond("", HttpStatusCode.Unauthorized)
        }) { followRedirects = false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val platform = TestPlatform()
        val services = CampusServicesRepository(store, DemoMensaProvider(), scope)
        val accounts = IosAccounts(platform, store, client, scope, services, { AppSettings() }, Dispatchers.Default)
        try {
            withTimeout(5_000) { while (!accounts.ready) delay(10) }
            store.put("campus:augsburg:public", "mensa", "public", 1)
            accounts.signIn(); platform.nextLogin()("sid=invalid")
            withTimeout(5_000) { while (accounts.message == null) delay(10) }
            assertNull(accounts.repository)
            assertNull(platform.secrets["digicampus"])
            accounts.message = null
            accounts.signIn(); platform.nextLogin()("sid=valid")
            withTimeout(5_000) { while (accounts.repository == null) delay(10) }
            val repo = assertNotNull(accounts.repository)
            assertNotNull(platform.secrets["digicampus"])
            platform.canWrite = false
            accounts.signIn(); platform.nextLogin()("sid=other")
            withTimeout(5_000) { while (accounts.message == null) delay(10) }
            assertSame(repo, accounts.repository, "A failed Keychain write must not replace the active account")
            assertTrue(platform.secrets["digicampus"].orEmpty().contains("sid=valid"))
            platform.canWrite = true; accounts.message = null
            accounts.openVips("course1", "https://digicampus.uni-augsburg.de/dispatch.php/vips/sheets?cid=course1", "Vips")
            withTimeout(5_000) { while (platform.vipsClassify == null) delay(10) }
            val classify = assertNotNull(platform.vipsClassify)
            val sheets = "https://digicampus.uni-augsburg.de/dispatch.php/vips/sheets?cid=course1"
            assertEquals("page", classify(sheets, sheets))
            // The signed-in browser follows any DigiCampus page; other sites leave it (only when tapped, in Swift).
            assertEquals("page", classify("https://digicampus.uni-augsburg.de/dispatch.php/vips/sheets?cid=other", sheets))
            assertEquals("external", classify("https://evil.example/dispatch.php/vips/sheets?cid=course1", sheets))
            assertEquals("blocked", classify("https://digicampus.uni-augsburg.de/logout.php", sheets))
            assertEquals("attachment", classify("https://digicampus.uni-augsburg.de/sendfile.php?type=0&file_id=file1&cid=course1", sheets))
            platform.vipsCookie!!("sid=other")
            delay(200)
            assertTrue(platform.secrets["digicampus"].orEmpty().contains("sid=valid"), "A different account's cookie must be rejected")
            platform.vipsCookie!!("sid=renewed")
            withTimeout(5_000) { while (!platform.secrets["digicampus"].orEmpty().contains("sid=renewed")) delay(10) }
            val vault = checkNotNull(accounts.signInSession.savedSignIns)
            vault.save(SignInKind.STUDY, STUDY_SIGN_IN_ORIGIN, "student@example.test", "fixture", null)
            assertNotNull(platform.secrets["saved-sign-ins"])
            assertFalse(platform.secrets["digicampus"].orEmpty().contains("student@example.test"))
            store.put(repo.account.cacheScope, "private", "secret", 1)
            platform.canDelete = false
            accounts.signOut()
            withTimeout(5_000) { while (accounts.message == null) delay(10) }
            assertNotNull(accounts.repository, "Failed Keychain deletion must not pretend sign-out succeeded")
            platform.canDelete = true; accounts.message = null
            accounts.signOut()
            withTimeout(5_000) { while (accounts.repository != null) delay(10) }
            withTimeout(5_000) { while (store.read(repo.account.cacheScope, "private") != null) delay(10) }
            assertNull(platform.secrets["digicampus"])
            assertTrue(vault.entries.value.isEmpty())
            assertTrue(platform.closedReaders >= 1)
            platform.vipsCookie!!("sid=late")
            delay(100)
            assertNull(platform.secrets["digicampus"], "A closed reader must not restore the signed-out session")
            assertEquals("public", store.read("campus:augsburg:public", "mensa")?.payload)
        } finally {
            scope.coroutineContext.job.cancelAndJoin(); client.close(); driver.close()
        }
    }
    @Test fun libraryConnectionIsVerifiedAndDisconnectKeepsPublicCache() = runBlocking {
        val driver = NativeSqliteDriver(CampusDatabase.Schema, ":memory:")
        val store = SqlCampusStore(CampusDatabase(driver))
        val page = "<div id='account-data'><table class='data'><tr><th class='account-display-title'>Loans</th></tr><tr><td>Keine Daten</td></tr></table></div>"
        val client = HttpClient(MockEngine { request ->
            if (request.headers["Cookie"].orEmpty().contains("JSESSIONID=valid"))
                respond(page, headers = headersOf("Content-Type", "text/html"))
            else respond("<form id='LoginBean'><input type='password'></form>", headers = headersOf("Content-Type", "text/html"))
        }) { followRedirects = false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val platform = TestPlatform()
        val services = CampusServicesRepository(store, DemoMensaProvider(), scope)
        val accounts = IosAccounts(platform, store, client, scope, services, { AppSettings() }, Dispatchers.Default)
        try {
            withTimeout(5_000) { while (!accounts.ready) delay(10) }
            store.put(services.publicScope, "mensa", "public", 1)
            accounts.connectLibrary(); platform.nextLogin()("JSESSIONID=invalid")
            withTimeout(5_000) { while (accounts.message == null) delay(10) }
            assertFalse(services.library.value.connected)
            assertNull(platform.secrets["library"])
            accounts.message = null
            accounts.connectLibrary(); platform.nextLogin()("JSESSIONID=valid")
            withTimeout(5_000) { while (!services.library.value.connected) delay(10) }
            assertNotNull(platform.secrets["library"])
            services.setPreferences { it.copy(libraryReminders = true) }
            withTimeout(5_000) { services.preferences.first { it.libraryReminders } }
            val notifications = IosNotifications(platform, store, services, accounts, { AppSettings() }, { "en" })
            // The provider invokes the session cookie callback while refreshing. Holding the library lock
            // around that request would deadlock, even when the cookie has not changed.
            withTimeout(5_000) { notifications.library() }
            platform.canDelete = false
            accounts.disconnectLibrary()
            withTimeout(5_000) { while (accounts.message == null) delay(10) }
            assertTrue(services.library.value.connected, "Failed Keychain deletion must preserve the library connection")
            platform.canDelete = true; accounts.message = null
            accounts.disconnectLibrary()
            withTimeout(5_000) { while (services.library.value.connected) delay(10) }
            assertNull(platform.secrets["library"])
            assertEquals("public", store.read(services.publicScope, "mensa")?.payload)
        } finally { scope.coroutineContext.job.cancelAndJoin(); client.close(); driver.close() }
    }

    @Test fun restoresTheExistingKeychainFormats() = runBlocking {
        val driver = NativeSqliteDriver(CampusDatabase.Schema, ":memory:")
        val store = SqlCampusStore(CampusDatabase(driver))
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val platform = TestPlatform()
        val account = Account("student-a", "Example Student", "student")
        platform.secrets["digicampus"] = Json.encodeToString(SavedSession(account, "sid=existing"))
        platform.secrets["library"] = """{"id":"existing-library","cookie":"JSESSIONID=existing"}"""
        val services = CampusServicesRepository(store, DemoMensaProvider(), scope)
        try {
            val accounts = IosAccounts(platform, store, client, scope, services, { AppSettings() }, Dispatchers.Default)
            withTimeout(5_000) { while (!accounts.ready) delay(10) }
            assertEquals(account, accounts.repository?.account)
            assertTrue(services.library.value.connected)
            assertEquals("existing-library", services.library.value.connectionId)
            assertTrue(platform.secrets["digicampus"].orEmpty().contains("sid=existing"))
            assertTrue(platform.secrets["library"].orEmpty().contains("JSESSIONID=existing"))
        } finally { scope.coroutineContext.job.cancelAndJoin(); client.close(); driver.close() }
    }

    @Test fun previewUsesSeparateDataAndKeepsExistingKeychainEntry() = runBlocking {
        val driver = NativeSqliteDriver(CampusDatabase.Schema, ":memory:")
        val store = SqlCampusStore(CampusDatabase(driver))
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val platform = TestPlatform()
        platform.secrets["digicampus"] = "unreadable-existing-session"
        val services = CampusServicesRepository(store, DemoMensaProvider(), scope)
        try {
            val accounts = IosAccounts(platform, store, client, scope, services, { AppSettings() }, Dispatchers.Default)
            withTimeout(5_000) { while (!accounts.ready) delay(10) }
            accounts.usePreview()
            withTimeout(5_000) { while (accounts.repository?.account?.demo != true) delay(10) }
            assertNotEquals(services.publicScope, accounts.previewServices.publicScope)
            accounts.openVips("algorithms", vipsSheetsUrl("algorithms"), "Vips")
            assertEquals(1, platform.previewCalls)
            assertEquals("page", platform.vipsClassify!!(vipsSheetsUrl("algorithms"), ""))
            assertEquals("blocked", platform.vipsClassify!!("https://evil.example/dispatch.php/vips/sheets?cid=algorithms", ""))
            assertEquals("unreadable-existing-session", platform.secrets["digicampus"])
            accounts.signOut()
            withTimeout(5_000) { while (accounts.repository != null) delay(10) }
            assertEquals("unreadable-existing-session", platform.secrets["digicampus"])
        } finally { scope.coroutineContext.job.cancelAndJoin(); client.close(); driver.close() }
    }

}
