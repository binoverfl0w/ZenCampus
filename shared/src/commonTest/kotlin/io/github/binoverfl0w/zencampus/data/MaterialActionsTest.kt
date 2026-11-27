package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.path
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class MaterialActionsTest {
    private val account = Account("materials", "Student", "materials")
    private val doc = Document("notes", "course", "folder", "Lecture notes.pdf", 4, "application/pdf", 1)

    @Test fun repeatedTapsShareTheTransferAndLaunchOnlyOneViewer() = runTest {
        val gate = CompletableDeferred<Unit>(); var transfers = 0
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun download(document: Document, write: suspend (ByteArray) -> Unit): Long {
                transfers++; write(byteArrayOf(1, 2)); gate.await(); write(byteArrayOf(3, 4)); return 4
            }
        }
        val files = MemoryMaterials()
        val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope)
        repeat(12) { repo.requestMaterial(doc) }
        runCurrent()
        assertEquals(1, transfers); assertTrue(files.opened.isEmpty())
        assertEquals(2L, repo.sync.value.materialProgress[doc.id]?.bytes)
        gate.complete(Unit); runCurrent()
        assertEquals(1, files.opened.size); assertEquals(1, repo.snapshot.value.localDocuments.size)
        assertTrue(repo.sync.value.materialProgress.isEmpty()); assertFalse(repo.sync.value.refreshing)
        repo.close(false)
    }

    @Test fun cancellingTheScreenWaiterDoesNotLoseThePendingOpenAction() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun download(document: Document, write: suspend (ByteArray) -> Unit): Long { gate.await(); write(byteArrayOf(1,2,3,4)); return 4 }
        }
        val files = MemoryMaterials(); val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope)
        val screen = launch { repo.download(doc) }; runCurrent(); screen.cancelAndJoin()
        gate.complete(Unit); runCurrent()
        assertEquals(1, files.opened.size)
        repo.close(false)
    }

    @Test fun tappingDuringAnOfflineSaveQueuesAnOpenAndRetainsThePin() = runTest {
        val gate = CompletableDeferred<Unit>(); var transfers = 0
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun download(document: Document, write: suspend (ByteArray) -> Unit): Long { transfers++; gate.await(); write(byteArrayOf(1,2,3,4)); return 4 }
        }
        val files = MemoryMaterials(); val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope)
        repo.requestMaterial(doc, MaterialAction.KEEP_OFFLINE); runCurrent()
        repo.requestMaterial(doc); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(1, transfers); assertEquals(1, files.opened.size)
        assertTrue(repo.snapshot.value.localDocuments.single().pinned)
        repo.close(false)
    }

    @Test fun twoFilesCanTransferTogetherAndAdditionalFilesWaitForASlot() = runTest {
        val gates = (0..4).associate { it.toString() to CompletableDeferred<Unit>() }; val started = mutableListOf<String>()
        var current = 0; var maximum = 0
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun download(document: Document, write: suspend (ByteArray) -> Unit): Long {
                started += document.id; current++; maximum = maxOf(maximum, current)
                try { gates.getValue(document.id).await(); write(byteArrayOf(1,2,3,4)); return 4 } finally { current-- }
            }
        }
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope)
        (0..4).forEach { repo.requestMaterial(doc.copy(id = it.toString()), MaterialAction.KEEP_OFFLINE) }; runCurrent()
        assertEquals(2, started.size)
        gates.getValue(started.first()).complete(Unit); runCurrent(); assertEquals(3, started.size)
        gates.values.forEach { it.complete(Unit) }; runCurrent()
        assertEquals(2, maximum); assertEquals(5, repo.snapshot.value.localDocuments.size)
        repo.close(false)
    }

    @Test fun removingDownloadsWhileBytesAreInFlightCannotRestoreTheFile() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun download(document: Document, write: suspend (ByteArray) -> Unit): Long { write(byteArrayOf(1,2)); gate.await(); write(byteArrayOf(3,4)); return 4 }
        }
        val files = MemoryMaterials(); val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope)
        repo.requestMaterial(doc); runCurrent(); repo.clearDownloads(); gate.complete(Unit); runCurrent()
        assertTrue(repo.snapshot.value.localDocuments.isEmpty()); assertTrue(files.opened.isEmpty()); assertTrue(files.files.isEmpty())
        repo.close(false)
    }

    @Test fun logoutCancelsTransfersAndNeverOpensOldAccountMaterial() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = object : CampusProvider by TestProvider() {
            override suspend fun download(document: Document, write: suspend (ByteArray) -> Unit): Long { write(byteArrayOf(1,2)); gate.await(); return 2 }
        }
        val files = MemoryMaterials(); val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope)
        repo.requestMaterial(doc); runCurrent(); repo.close(true); gate.complete(Unit); runCurrent()
        assertTrue(files.opened.isEmpty()); assertTrue(files.files.isEmpty()); assertEquals(1, files.aborted)
    }

    @Test fun cleanupFailureDoesNotAbortTheAlreadyCommittedReplacement() = runTest {
        val memory = MemoryMaterials(); var stale: String? = null
        val files = object : MaterialStorage by memory {
            override suspend fun remove(path: String) { if (path == stale) throw CampusException(FailureKind.STORAGE); memory.remove(path) }
        }
        val repo = CampusRepository(account, TestProvider(), MemoryStore(), files, backgroundScope)
        repo.download(doc, open = false); runCurrent(); stale = repo.snapshot.value.localDocuments.single().path
        repo.download(doc.copy(modified = 2)); runCurrent()
        val saved = repo.snapshot.value.localDocuments.single()
        assertNotEquals(stale, saved.path); assertTrue(memory.files.containsKey(saved.path)); assertEquals(0, memory.aborted)
        assertEquals(1, memory.opened.size); assertFalse(repo.sync.value.errors.containsKey("download:${doc.id}"))
        repo.close(false)
    }

    @Test fun failedExportCanRetryWithoutDownloadingAgainOrFalselyReportingSuccess() = runTest {
        // Without a chosen folder and no way to write to Downloads directly, saving falls back to the platform's export.
        val provider = TestProvider(); val memory = MemoryMaterials().apply { syncsDefault = false }; var exports = 0
        val files = object : MaterialStorage by memory {
            override suspend fun saveToDownloads(path: String, mimeType: String, displayName: String): String {
                exports++; if (exports == 1) throw CampusException(FailureKind.STORAGE)
                return "Downloads/ZenCampus/$displayName"
            }
        }
        val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope)
        repo.requestMaterial(doc, MaterialAction.SAVE_TO_DOWNLOADS); runCurrent()
        assertEquals(FailureKind.STORAGE, repo.sync.value.errors["download:${doc.id}"]); assertTrue(repo.sync.value.materialExports.isEmpty())
        repo.retryMaterial(doc); runCurrent()
        assertEquals(1, provider.downloadCalls); assertEquals(2, exports)
        assertEquals("Downloads/ZenCampus/${doc.name}", repo.sync.value.materialExports[doc.id])
        assertTrue(memory.opened.isEmpty()); assertTrue(repo.sync.value.errors.isEmpty())
        repo.close(false)
    }

    @Test fun exportsUseTheCourseFolderLayoutAndReturnToDownloadsAfterReset() = runTest {
        val files = MemoryMaterials()
        val store = MemoryStore()
        // The file's DigiCampus folder is known from the course's file list, so it is kept below the course folder.
        store.put(account.cacheScope, CacheKeys.courseFiles(doc.courseId), Json.encodeToString(CourseMaterials(doc.courseId,
            listOf(CampusFolder("root", doc.courseId, "Course"), CampusFolder(doc.folderId, doc.courseId, "Week 1", "root")), listOf(doc))), 1)
        val repo = CampusRepository(account, TestProvider(), store, files, backgroundScope)
        repo.setMaterialDestination(doc.courseId, MaterialDestination("folder-uri", "Course folder")); runCurrent()
        repo.requestMaterial(doc, MaterialAction.SAVE_TO_DOWNLOADS); runCurrent()
        assertEquals("Course folder/Week 1/${doc.name}", repo.sync.value.materialExports[doc.id])
        assertContentEquals(byteArrayOf(1, 2, 3, 4), files.synced["Course folder/Week 1/${doc.name}"])
        // Another course without a chosen folder goes to its own folder in Downloads/ZenCampus.
        val other = doc.copy(id = "another", courseId = "other-course")
        repo.requestMaterial(other, MaterialAction.SAVE_TO_DOWNLOADS); runCurrent()
        assertEquals("Downloads/ZenCampus/other-course/${doc.name}", repo.sync.value.materialExports[other.id])
        repo.close(false)
        val restored = CampusRepository(account, TestProvider(), store, files, backgroundScope); runCurrent()
        assertEquals("folder-uri", restored.snapshot.value.preferences.materialDestinations[doc.courseId]?.location)
        restored.setMaterialDestination(doc.courseId, null); runCurrent()
        restored.requestMaterial(doc, MaterialAction.SAVE_TO_DOWNLOADS); runCurrent()
        assertEquals("Downloads/ZenCampus/course/Week 1/${doc.name}", restored.sync.value.materialExports[doc.id])
        restored.close(false)
    }

    @Test fun externalOpenAndShareUseTheSavedDocumentWithoutAnotherTransfer() = runTest {
        val provider = TestProvider(); val memory = MemoryMaterials(); val actions = mutableListOf<String>()
        val files = object : MaterialStorage by memory {
            override suspend fun openWith(path: String, mimeType: String, displayName: String) { assertTrue(memory.files.containsKey(path)); actions += "open:$displayName:$mimeType" }
            override suspend fun share(path: String, mimeType: String, displayName: String) { assertTrue(memory.files.containsKey(path)); actions += "share:$displayName:$mimeType" }
        }
        val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope)
        repo.requestMaterial(doc, MaterialAction.OPEN_WITH); runCurrent()
        repo.requestMaterial(doc, MaterialAction.SHARE); runCurrent()
        assertEquals(listOf("open:${doc.name}:application/pdf", "share:${doc.name}:application/pdf"), actions)
        assertEquals(1, provider.downloadCalls); assertTrue(memory.opened.isEmpty())
        repo.close(false)
    }

    @Test fun viewerFailureKeepsTheCachedCopyAndRetriesOnlyTheOpen() = runTest {
        val provider = TestProvider(); val memory = MemoryMaterials(); var opens = 0
        val files = object : MaterialStorage by memory {
            override suspend fun open(path: String, mimeType: String, displayName: String) {
                opens++; if (opens == 1) throw CampusException(FailureKind.NO_VIEWER)
                memory.open(path, mimeType, displayName)
            }
        }
        val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope)
        repo.requestMaterial(doc); runCurrent()
        assertEquals(FailureKind.NO_VIEWER, repo.sync.value.errors["download:${doc.id}"])
        assertEquals(1, repo.snapshot.value.localDocuments.size)
        repo.retryMaterial(doc); runCurrent()
        assertEquals(1, provider.downloadCalls); assertEquals(2, opens); assertEquals(1, memory.opened.size)
        repo.close(false)
    }

    @Test fun aFailedManifestUpdateRetainsTheOldReadableCopy() = runTest {
        val memory = MemoryMaterials(); val originalStore = MemoryStore(); var fail = false
        val store = object : CampusStore by originalStore {
            override suspend fun put(scope: String, key: String, payload: String, updatedAt: Long) {
                if (fail && key == "downloads") throw CampusException(FailureKind.STORAGE)
                originalStore.put(scope, key, payload, updatedAt)
            }
        }
        val repo = CampusRepository(account, TestProvider(), store, memory, backgroundScope)
        repo.download(doc, open = false); runCurrent()
        val previous = repo.snapshot.value.localDocuments.single()
        fail = true; repo.download(doc.copy(modified = 2)); runCurrent()
        assertEquals(previous, repo.snapshot.value.localDocuments.single()); assertTrue(memory.files.containsKey(previous.path))
        assertEquals(1, memory.files.size); assertEquals(1, memory.aborted); assertTrue(memory.opened.isEmpty())
        repo.close(false)
    }

    @Test fun filenamesStayReadableBoundedAndCannotEscapeTheirDirectory() {
        assertEquals("01 · Übung.pdf", materialFileName("01 · Übung.pdf"))
        assertEquals("Document.pdf", materialFileName("..", "pdf"))
        assertEquals("notes.pdf", materialFileName("notes", "pdf"))
        val hostile = materialFileName("../../notes\n/evil.pdf")
        assertFalse(hostile.contains('/')); assertFalse(hostile.contains('\n')); assertFalse(hostile.startsWith('.')); assertTrue(hostile.endsWith(".pdf"))
        val unicode = materialFileName("Übung 📖".repeat(100) + ".pdf")
        assertTrue(unicode.encodeToByteArray().size <= 220); assertTrue(unicode.endsWith(".pdf")); assertFalse(unicode.contains('\uFFFD'))
    }
}
