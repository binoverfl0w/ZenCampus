package io.github.binoverfl0w.zencampus.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.db.CampusDatabase
import io.github.binoverfl0w.zencampus.model.*
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test

class MaterialTransportTest {
    @Test fun aTruncatedRealHttpResponseRecoversIntoOneCompleteDiskFile(): Unit = runBlocking {
        val root = Files.createTempDirectory("zen-material-transport-").toFile()
        val calls = AtomicInteger()
        val complete = "%PDF-fixture-only\n".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/attachment") { exchange ->
            assertEquals("session=fixture-only", exchange.requestHeaders.getFirst("Cookie"))
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            exchange.sendResponseHeaders(200, complete.size.toLong())
            try {
                exchange.responseBody.write(if (calls.incrementAndGet() == 1) complete.copyOf(4) else complete)
            } finally { runCatching { exchange.close() } }
        }
        server.start()
        val client = HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 5000; socketTimeoutMillis = 1000 }
            engine { config {
                retryOnConnectionFailure(false)
                // Only this isolated test client reroutes the fixed trusted URL to our loopback server.
                addInterceptor { chain ->
                    assertEquals("digicampus.uni-augsburg.de", chain.request().url.host)
                    chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.address.port}/attachment").build())
                }
            } }
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CampusDatabase.Schema.create(driver)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val account = Account("transport-fixture", "Student", "fixture")
        val materials = DesktopMaterials(DesktopPaths(root))
        val repo = CampusRepository(account, DigiCampusProvider(client, { "session=fixture-only" }), SqlCampusStore(CampusDatabase(driver)), materials, scope)
        val document = Document("attachment", "course", "folder", "Lecture notes.pdf", complete.size.toLong(), "application/pdf", 1)
        try {
            withTimeout(15_000) { repo.download(document, pin = true, open = false) }
            assertEquals(2, calls.get())
            val saved = withTimeout(5000) { repo.snapshot.first { it.localDocuments.singleOrNull()?.pinned == true }.localDocuments.single() }
            assertContentEquals(complete, File(saved.path).readBytes())
            assertEquals(document.name, File(saved.path).name)
            assertTrue(saved.pinned); assertTrue(repo.sync.value.errors.isEmpty())
            assertFalse(root.walk().any { it.name == ".download.part" })
            assertEquals(1, root.walk().count { it.isFile && it.name == document.name })
            repo.download(document, open = false)
            assertEquals(2, calls.get(), "A complete cached file should not require another request")
        } finally {
            repo.close(false); scope.cancel(); client.close(); server.stop(0); driver.close(); root.deleteRecursively()
        }
    }
}
