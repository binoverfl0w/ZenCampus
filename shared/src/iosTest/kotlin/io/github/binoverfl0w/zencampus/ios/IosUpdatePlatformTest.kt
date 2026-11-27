package io.github.binoverfl0w.zencampus

import io.github.binoverfl0w.zencampus.model.ReleaseAsset
import io.github.binoverfl0w.zencampus.data.AppUpdates
import io.github.binoverfl0w.zencampus.data.UpdateState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class IosUpdatePlatformTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun releaseCheckOffersPageWithoutAnInstallableAsset() = runTest {
        val adapter = IosUpdatePlatform()
        val original = adapter.readState()
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertEquals("https://api.github.com/repos/example/campus/releases/latest", request.url.toString())
            assertFalse(request.headers.contains(HttpHeaders.Cookie))
            respond("""{"tag_name":"v2.0.0","html_url":"https://github.com/example/campus/releases/tag/v2.0.0","assets":[{"name":"app.ipa","browser_download_url":"https://github.com/example/campus/app.ipa","size":1}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val updates = AppUpdates({ client }, "example/campus", "1.0.0", adapter, backgroundScope, automatic = { false })
            val worker = updates.start()
            runCurrent()
            assertEquals(0, requests, "Disabled automatic checks must not contact the release service")
            worker.cancel()
            updates.check()
            val state = assertIs<UpdateState.Available>(updates.state.value)
            assertEquals("2.0.0", state.release.version)
            assertNull(state.asset)
        } finally {
            client.close()
            if (original == null) platform.Foundation.NSUserDefaults.standardUserDefaults.removeObjectForKey("zen.release.checks")
            else adapter.writeState(original)
        }
    }

    @Test fun iosNeverOffersDirectInstallationAndPersistsCheckState() = runBlocking {
        val adapter = IosUpdatePlatform()
        val original = adapter.readState()
        try {
            val asset = ReleaseAsset("zencampus.ipa", "https://example.com/zencampus.ipa", 1)
            assertNull(adapter.installable(listOf(asset)))
            assertFailsWith<IllegalStateException> { adapter.writer(asset) }
            assertFailsWith<IllegalStateException> { adapter.install(asset) }
            adapter.writeState("test state")
            assertEquals("test state", adapter.readState())
        } finally {
            if (original == null) platform.Foundation.NSUserDefaults.standardUserDefaults.removeObjectForKey("zen.release.checks")
            else adapter.writeState(original)
        }
        Unit
    }
}

class IosDiagnosticsTest {
    @Test fun logSinkReceivesScrubbedText() {
        val seen = mutableListOf<io.github.binoverfl0w.zencampus.model.LogLine>()
        val sink = io.github.binoverfl0w.zencampus.data.LogSink { seen += it }
        io.github.binoverfl0w.zencampus.data.ZenLog.install(sink)
        try {
            io.github.binoverfl0w.zencampus.data.ZenLog.info("test", "cookie=secret student@example.edu https://example.edu/path?token=secret")
            val line = seen.last().message
            kotlin.test.assertFalse(line.contains("secret"))
            kotlin.test.assertFalse(line.contains("student@example.edu"))
            kotlin.test.assertTrue(line.contains("<redacted>"))
        } finally { io.github.binoverfl0w.zencampus.data.ZenLog.uninstall(sink) }
    }
}
