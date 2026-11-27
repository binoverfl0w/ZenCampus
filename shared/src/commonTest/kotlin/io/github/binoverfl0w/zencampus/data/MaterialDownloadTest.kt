package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MaterialDownloadTest {
    private val account = Account("transfer", "Student", "transfer")
    private val document = Document("file", "course", "folder", "Notes.pdf", 4, "application/pdf", 1)
    private val content = byteArrayOf(1, 2, 3, 4)
    private val headers = headersOf(HttpHeaders.ContentType to listOf("application/pdf"), HttpHeaders.ContentLength to listOf("4"))

    private fun TestScope.clientForTest(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        })) {
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 20_000; socketTimeoutMillis = 20_000 }
        }

    @Test fun aFailedConnectionRecoversFromOneTapAndRepeatedTapsDoNotDuplicateDelivery() = runTest {
        var calls = 0
        val client = clientForTest { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("session=fixture", request.headers[HttpHeaders.Cookie])
            if (++calls == 1) throw IOException("Connection reset")
            respond(content, headers = headers)
        }
        val files = MemoryMaterials()
        val repo = CampusRepository(account, DigiCampusProvider(client, { "session=fixture" }), MemoryStore(), files, backgroundScope)
        try {
            repo.requestMaterial(document); runCurrent()
            assertEquals(1, calls); assertEquals(1, files.aborted)
            assertTrue(repo.sync.value.errors.isEmpty()); assertTrue(repo.sync.value.active.isNotEmpty())
            repeat(10) { repo.requestMaterial(document) }; runCurrent()
            advanceTimeBy(300); runCurrent()
            assertEquals(2, calls); assertEquals(1, files.opened.size)
            assertContentEquals(content, files.files.values.single())
            assertTrue(repo.sync.value.errors.isEmpty()); assertTrue(repo.sync.value.materialProgress.isEmpty())
            repo.requestMaterial(document); runCurrent()
            assertEquals(2, calls); assertEquals(2, files.opened.size)
        } finally { repo.close(false); client.close() }
    }

    @Test fun interruptedBodiesDiscardPartialBytesAndRestartWithAFreshWriter() = runTest {
        var calls = 0
        val broken = CompletableDeferred<Unit>()
        val client = clientForTest {
            if (++calls == 1) {
                val channel = ByteChannel(autoFlush = true)
                backgroundScope.launch {
                    channel.writeFully(byteArrayOf(9, 9)); broken.await()
                    channel.close(IOException("Socket closed mid-transfer"))
                }
                respond(channel, headers = headers)
            } else respond(content, headers = headers)
        }
        val files = MemoryMaterials()
        val repo = CampusRepository(account, DigiCampusProvider(client, { "session=fixture" }), MemoryStore(), files, backgroundScope)
        try {
            repo.requestMaterial(document); runCurrent()
            assertEquals(2L, repo.sync.value.materialProgress[document.id]?.bytes)
            broken.complete(Unit); runCurrent(); advanceTimeBy(300); runCurrent()
            assertEquals(2, calls); assertEquals(1, files.aborted)
            assertContentEquals(content, files.files.values.single())
            assertEquals(4L, repo.snapshot.value.localDocuments.single().size)
            assertEquals(1, files.opened.size)
        } finally { repo.close(false); client.close() }
    }

    @Test fun truncatedSuccessfulResponsesAreRetriedWithoutConcatenatingTheirBodies() = runTest {
        var calls = 0
        val client = clientForTest { respond(if (++calls == 1) byteArrayOf(9) else content, headers = headers) }
        val files = MemoryMaterials()
        val repo = CampusRepository(account, DigiCampusProvider(client, { "session=fixture" }), MemoryStore(), files, backgroundScope)
        try {
            repo.requestMaterial(document); runCurrent(); advanceTimeBy(300); runCurrent()
            assertEquals(2, calls); assertEquals(1, files.aborted)
            assertContentEquals(content, files.files.values.single()); assertEquals(1, files.opened.size)
        } finally { repo.close(false); client.close() }
    }

    @Test fun temporaryServerErrorsRecoverAndRespectRetryAfter() = runTest {
        var calls = 0
        val client = clientForTest {
            when (++calls) {
                1 -> respond("", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, "2"))
                2 -> respond("", HttpStatusCode.TooManyRequests)
                else -> respond(content, headers = headers)
            }
        }
        val files = MemoryMaterials()
        val repo = CampusRepository(account, DigiCampusProvider(client, { "session=fixture" }), MemoryStore(), files, backgroundScope)
        try {
            repo.requestMaterial(document); runCurrent(); advanceTimeBy(1999); runCurrent()
            assertEquals(1, calls)
            advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
            advanceTimeBy(600); runCurrent(); assertEquals(3, calls)
            assertEquals(1, files.opened.size); assertTrue(repo.sync.value.errors.isEmpty())
        } finally { repo.close(false); client.close() }
    }

    @Test fun persistentFailuresStopAfterThreeAttemptsAndLeaveNoSavedPartialFile() = runTest {
        var calls = 0; var fail = true
        val client = clientForTest { calls++; if (fail) throw IOException("Connection reset"); respond(content, headers = headers) }
        val files = MemoryMaterials()
        val repo = CampusRepository(account, DigiCampusProvider(client, { "session=fixture" }), MemoryStore(), files, backgroundScope)
        try {
            repo.requestMaterial(document); runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(3, calls); assertEquals(3, files.aborted)
            assertTrue(files.files.isEmpty()); assertTrue(files.opened.isEmpty()); assertTrue(repo.snapshot.value.localDocuments.isEmpty())
            assertEquals(FailureKind.OFFLINE, repo.sync.value.errors["download:${document.id}"])
            assertTrue(repo.sync.value.active.isEmpty()); assertTrue(repo.sync.value.materialProgress.isEmpty())
            fail = false; repo.retryMaterial(document); runCurrent()
            assertEquals(4, calls); assertEquals(1, files.opened.size); assertTrue(repo.sync.value.errors.isEmpty())
        } finally { repo.close(false); client.close() }
    }

    @Test fun permissionLoginRedirectAndInvalidResponsesAreNotRetried() = runTest {
        for ((status, type, failure) in listOf(
            Triple(HttpStatusCode.Unauthorized, "application/pdf", FailureKind.AUTHENTICATION),
            Triple(HttpStatusCode.Forbidden, "application/pdf", FailureKind.PERMISSION),
            Triple(HttpStatusCode.NotFound, "application/pdf", FailureKind.NOT_FOUND),
            Triple(HttpStatusCode.Found, "text/html", FailureKind.EXTERNAL_LINK),
            Triple(HttpStatusCode.OK, "text/html", FailureKind.AUTHENTICATION),
            Triple(HttpStatusCode.PartialContent, "application/pdf", FailureKind.INVALID_RESPONSE),
        )) {
            var calls = 0
            val client = clientForTest { calls++; respond("", status, headersOf(HttpHeaders.ContentType, type)) }
            val files = MemoryMaterials()
            val repo = CampusRepository(account, DigiCampusProvider(client, { "fixture" }), MemoryStore(), files, backgroundScope)
            try {
                repo.requestMaterial(document); runCurrent(); advanceTimeBy(2000); runCurrent()
                assertEquals(1, calls, status.toString()); assertEquals(failure, repo.sync.value.errors["download:${document.id}"])
                assertTrue(files.files.isEmpty()); assertTrue(files.opened.isEmpty())
            } finally { repo.close(false); client.close() }
        }
    }

    @Test fun longAndUnparsedRetryAfterValuesDoNotCauseEarlyRetries() = runTest {
        for (value in listOf("60", "Wed, 28 Oct 2026 20:00:00 GMT", "9223372036854775807")) {
            var calls = 0
            val client = clientForTest { calls++; respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, value)) }
            val repo = CampusRepository(account, DigiCampusProvider(client, { "fixture" }), MemoryStore(), MemoryMaterials(), backgroundScope)
            try {
                repo.requestMaterial(document); runCurrent(); advanceTimeBy(20_000); runCurrent()
                assertEquals(1, calls); assertEquals(FailureKind.SERVER, repo.sync.value.errors["download:${document.id}"])
            } finally { repo.close(false); client.close() }
        }
    }

    @Test fun diskWriteFailuresAreStorageErrorsAndDoNotRepeatTheTransfer() = runTest {
        var calls = 0
        val client = clientForTest { calls++; respond(content, headers = headers) }
        val memory = MemoryMaterials()
        val files = object : MaterialStorage by memory {
            override suspend fun create(scope: String, document: Document): MaterialWriter {
                val writer = memory.create(scope, document)
                return object : MaterialWriter by writer {
                    override suspend fun write(bytes: ByteArray) { throw IOException("Disk full") }
                }
            }
        }
        val repo = CampusRepository(account, DigiCampusProvider(client, { "fixture" }), MemoryStore(), files, backgroundScope)
        try {
            repo.requestMaterial(document); runCurrent(); advanceTimeBy(2000); runCurrent()
            assertEquals(1, calls); assertEquals(1, memory.aborted)
            assertEquals(FailureKind.STORAGE, repo.sync.value.errors["download:${document.id}"])
            assertTrue(memory.files.isEmpty())
        } finally { repo.close(false); client.close() }
    }

    @Test fun removingFilesOrSigningOutDuringBackoffCancelsRecovery() = runTest {
        for (signOut in listOf(false, true)) {
            var calls = 0
            val client = clientForTest { calls++; throw IOException("Connection reset") }
            val files = MemoryMaterials(); val store = MemoryStore()
            val repo = CampusRepository(account, DigiCampusProvider(client, { "fixture" }), store, files, backgroundScope)
            try {
                repo.requestMaterial(document); runCurrent(); assertEquals(1, calls)
                if (signOut) repo.close(true) else repo.clearDownloads()
                advanceTimeBy(2000); runCurrent()
                assertEquals(1, calls); assertTrue(files.files.isEmpty()); assertTrue(files.opened.isEmpty())
                assertTrue(repo.sync.value.materialProgress.isEmpty())
            } finally { if (!signOut) repo.close(false); client.close() }
        }
    }

    @Test fun downloadsOverrideTheShortApiSocketTimeout() = runTest {
        val client = clientForTest { request ->
            val timeout = request.getCapabilityOrNull(HttpTimeoutCapability)
            assertEquals(120_000L, timeout?.requestTimeoutMillis)
            assertEquals(45_000L, timeout?.socketTimeoutMillis)
            respond(content, headers = headers)
        }
        try {
            assertEquals(4L, DigiCampusProvider(client, { "fixture" }).download(document) {})
        } finally { client.close() }
    }
}
