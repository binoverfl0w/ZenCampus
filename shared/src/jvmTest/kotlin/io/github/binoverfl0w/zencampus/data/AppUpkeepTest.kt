package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpkeepTest {
    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private val apkDigest = sha256(apk)
    private val releasePage = "https://github.com/example/zencampus/releases/tag/v0.12.0"
    private val apkUrl = "https://github.com/example/zencampus/releases/download/v0.12.0/zencampus-0.12.0.apk"

    /** Downloads run on I/O threads; wait for the outcome rather than for the test scheduler to idle. */
    private suspend fun AppUpdates.settle() = state.first { it is UpdateState.Ready || it is UpdateState.Failed }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun releaseJson(digest: String? = apkDigest, sums: Boolean = false) = """{
        "tag_name": "v0.12.0", "name": "0.12.0", "html_url": "$releasePage", "body": "Notes",
        "assets": [
          {"name": "zencampus-0.12.0.apk", "size": ${apk.size}, "browser_download_url": "$apkUrl"
           ${if (digest != null) ", \"digest\": \"sha256:$digest\"" else ""}}
          ${if (sums) ", {\"name\": \"SHA256SUMS\", \"size\": 100, \"browser_download_url\": \"https://github.com/example/zencampus/releases/download/v0.12.0/SHA256SUMS\"}" else ""}
        ]}"""

    private class FakePlatform(private val folder: File) : UpdatePlatform {
        var installed = 0
        var opened = mutableListOf<String>()
        var state: String? = null
        override fun installable(assets: List<ReleaseAsset>) = assets.firstOrNull { it.name.endsWith(".apk") }
        override suspend fun writer(asset: ReleaseAsset): UpdateWriter = HashingFileWriter(File(folder, "update.apk"))
        override suspend fun install(asset: ReleaseAsset): UpdateInstall { installed++; return UpdateInstall.STARTED }
        override fun openPage(url: String) { opened += url }
        override fun readState() = state
        override fun writeState(value: String) { state = value }
    }

    private fun TestScope.updates(platform: FakePlatform, requests: MutableList<String> = mutableListOf(), version: String = "0.11.2",
        respondTo: (String) -> Pair<HttpStatusCode, Any>?): AppUpdates {
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            requests += url
            assertFalse(request.headers.contains(HttpHeaders.Cookie), "update requests never carry a cookie")
            val (status, body) = respondTo(url) ?: (HttpStatusCode.NotFound to "")
            when (body) {
                is ByteArray -> respond(body, status, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                is Headers -> respond("", status, body)
                else -> respond(body.toString(), status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }) { followRedirects = false; expectSuccess = false; install(HttpTimeout) }
        return AppUpdates({ client }, "example/zencampus", version, platform, backgroundScope, automatic = { true }, clock = { 1_000_000L })
    }

    @Test fun aNewerReleaseIsDownloadedThroughRedirectsVerifiedAndInstalled() = runTest {
        val folder = createTempDirectory().toFile()
        val platform = FakePlatform(folder)
        val requests = mutableListOf<String>()
        val updates = updates(platform, requests) { url ->
            when {
                url == "https://api.github.com/repos/example/zencampus/releases/latest" -> HttpStatusCode.OK to releaseJson()
                url == apkUrl -> HttpStatusCode.Found to headersOf(HttpHeaders.Location, "https://objects.example.com/apk?signature=1")
                url.startsWith("https://objects.example.com/apk") -> HttpStatusCode.OK to apk
                else -> null
            }
        }
        updates.check()
        val available = assertIs<UpdateState.Available>(updates.state.value)
        assertEquals("0.12.0", available.release.version)
        assertEquals("zencampus-0.12.0.apk", available.asset?.name)
        assertNotNull(platform.state, "the check time is remembered")
        updates.update(); updates.settle()
        assertIs<UpdateState.Ready>(updates.state.value)
        assertEquals(1, platform.installed)
        assertContentEquals(apk, File(folder, "update.apk").readBytes())
        assertFalse(File(folder, "update.apk.part").exists())
        assertTrue(requests.any { it.startsWith("https://objects.example.com/") })
    }

    @Test fun aDownloadThatDoesNotMatchItsChecksumIsNeverInstalled() = runTest {
        val folder = createTempDirectory().toFile()
        val platform = FakePlatform(folder)
        val updates = updates(platform) { url ->
            when (url) {
                "https://api.github.com/repos/example/zencampus/releases/latest" -> HttpStatusCode.OK to releaseJson(digest = "0".repeat(64))
                apkUrl -> HttpStatusCode.OK to apk
                else -> null
            }
        }
        updates.check(); updates.update(); updates.settle()
        assertEquals(UpdateFailure.UNVERIFIED, assertIs<UpdateState.Failed>(updates.state.value).failure)
        assertEquals(0, platform.installed)
    }

    @Test fun releasesWithoutAssetDigestsUseTheirChecksumFileAndRefuseWithoutOne() = runTest {
        val folder = createTempDirectory().toFile()
        val platform = FakePlatform(folder)
        var sums = true
        val updates = updates(platform) { url ->
            when {
                url.endsWith("/releases/latest") -> HttpStatusCode.OK to releaseJson(digest = null, sums = sums)
                url.endsWith("/SHA256SUMS") -> HttpStatusCode.OK to "$apkDigest  zencampus-0.12.0.apk\n"
                url == apkUrl -> HttpStatusCode.OK to apk
                else -> null
            }
        }
        updates.check(); updates.update(); updates.settle()
        assertIs<UpdateState.Ready>(updates.state.value)
        assertEquals(1, platform.installed)

        sums = false
        val second = updates(FakePlatform(folder)) { url -> if (url.endsWith("/releases/latest")) HttpStatusCode.OK to releaseJson(digest = null) else null }
        second.check(); second.update(); second.settle()
        assertEquals(UpdateFailure.UNVERIFIED, assertIs<UpdateState.Failed>(second.state.value).failure)
    }

    @Test fun theSameOrAnOlderVersionAndNoReleaseAtAllAreUpToDate() = runTest {
        val folder = createTempDirectory().toFile()
        val same = updates(FakePlatform(folder), version = "0.12.0") { HttpStatusCode.OK to releaseJson() }
        same.check()
        assertIs<UpdateState.Current>(same.state.value)
        val none = updates(FakePlatform(folder)) { null }
        none.check()
        assertIs<UpdateState.Current>(none.state.value)
        val down = updates(FakePlatform(folder)) { HttpStatusCode.ServiceUnavailable to "" }
        down.check()
        assertEquals(UpdateFailure.OFFLINE, assertIs<UpdateState.Failed>(down.state.value).failure)
    }

    @Test fun aDismissedVersionIsRememberedAndBlankRepositoriesDisableUpdates() = runTest {
        val folder = createTempDirectory().toFile()
        val platform = FakePlatform(folder)
        val updates = updates(platform) { HttpStatusCode.OK to releaseJson() }
        updates.check()
        updates.dismiss((updates.state.value as UpdateState.Available).release)
        assertEquals("0.12.0", updates.dismissed.value)
        val reopened = updates(platform) { null }
        assertEquals("0.12.0", reopened.dismissed.value)
        assertFalse(AppUpdates({ error("no requests") }, "", "0.11.2", platform, backgroundScope, { true }).configured)
    }

    @Test fun installationsThatCannotUpdateThemselvesOpenTheReleasePage() = runTest {
        val folder = createTempDirectory().toFile()
        val opened = CompletableDeferred<String>()
        val platform = object : UpdatePlatform by FakePlatform(folder) {
            override fun installable(assets: List<ReleaseAsset>): ReleaseAsset? = null
            override fun openPage(url: String) { opened.complete(url) }
        }
        val client = HttpClient(MockEngine { respond(releaseJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        val updates = AppUpdates({ client }, "example/zencampus", "0.11.2", platform, backgroundScope, { true })
        updates.check()
        assertNull(assertIs<UpdateState.Available>(updates.state.value).asset)
        updates.update()
        assertEquals(releasePage, opened.await())
    }

    private class MemoryCrashStore(reports: List<CrashReport>) : CrashStore {
        val reports = reports.toMutableList()
        override suspend fun pending() = reports.toList()
        override suspend fun remove(id: String) { reports.removeAll { it.id == id } }
    }

    private fun report(id: String) = crashReport(IllegalStateException("broke at https://example.org/x?token=1"),
        RuntimeInfo("0.12.0", "linux", "Linux", "6.0 amd64"), time = id.length.toLong()).copy(id = id)

    @Test fun crashReportsAreSentOnlyWithConsentAndKeptWhileTheServerIsDown() = runTest {
        var status = HttpStatusCode.InternalServerError
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("https://sentry.example.org/api/42/envelope/", request.url.toString())
            assertTrue(request.headers["X-Sentry-Auth"]!!.contains("sentry_key=public"))
            bodies += String(request.body.toByteArray())
            respond("", status)
        })
        val dsn = CrashDsn.parse("https://public@sentry.example.org/42")
        var enabled = false
        val store = MemoryCrashStore(listOf(report("a"), report("bb")))
        val reports = CrashReports({ client }, dsn, store, "0.12.0") { enabled }
        reports.start()
        assertEquals(2, reports.pending.value.size)
        assertTrue(bodies.isEmpty(), "nothing is sent without consent")

        enabled = true
        assertFalse(reports.send())
        assertEquals(2, store.reports.size, "a failing server keeps the reports for later")
        status = HttpStatusCode.OK
        assertTrue(reports.send())
        assertTrue(store.reports.isEmpty())
        assertTrue(reports.pending.value.isEmpty())
        assertFalse(bodies.any { "token=1" in it }, "messages are scrubbed")
    }

    @Test fun declinedOrUnconfiguredCrashReportsAreDeleted() = runTest {
        val store = MemoryCrashStore(listOf(report("a")))
        val declined = CrashReports({ error("no requests") }, CrashDsn.parse("https://public@sentry.example.org/42"), store, "0.12.0") { false }
        declined.start(); declined.discard()
        assertTrue(store.reports.isEmpty())
        val unconfigured = MemoryCrashStore(listOf(report("b")))
        val reports = CrashReports({ error("no requests") }, null, unconfigured, "0.12.0") { true }
        reports.start()
        assertTrue(unconfigured.reports.isEmpty())
        assertFalse(reports.configured)
    }

    @Test fun aCrashReportKeepsTheCauseChainAndRecentLogWithoutDetails() {
        ZenLog.info("sync", "study sync for student@example.org")
        val error = RuntimeException("outer /home/someone/file", IllegalArgumentException("inner"))
        val report = crashReport(error, RuntimeInfo("0.12.0", "linux", "Linux", "6.0"))
        assertEquals(listOf("java.lang.RuntimeException", "java.lang.IllegalArgumentException"), report.exceptions.map { it.type })
        assertEquals("outer /home/~/file", report.exceptions[0].message)
        assertTrue(report.exceptions[0].frames.isNotEmpty())
        assertEquals(32, report.id.length)
        assertTrue(report.log.any { it.message == "study sync for <email>" })
    }

    @Test fun theLogFileRotatesAndExportsBothParts() {
        val folder = createTempDirectory().toFile()
        val sink = FileLogSink(folder, limit = 200)
        repeat(20) { sink.write(LogLine(it.toLong(), LogLevel.INFO, "test", "line $it")) }
        assertTrue(File(folder, "zencampus.1.log").isFile)
        assertTrue(File(folder, "zencampus.log").length() <= 400)
        val exported = sink.export("ZenCampus test")
        assertTrue(exported.startsWith("ZenCampus test\n\n"))
        assertTrue("line 19" in exported)
        assertFalse("line 0\n" in exported, "the oldest lines fall away after two files")
    }
}
