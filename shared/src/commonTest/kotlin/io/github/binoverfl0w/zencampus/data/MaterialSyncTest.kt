package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MaterialSyncTest {
    private val folders = listOf(
        CampusFolder("root", "c", "Course root"),
        CampusFolder("week1", "c", "Week 1: Basics", parentId = "root"),
        CampusFolder("slides", "c", "Slides", parentId = "week1"),
        CampusFolder("loop", "c", "Loop", parentId = "loop"),
    )
    private fun doc(id: String, folder: String, name: String, modified: Long = 1, size: Long = 10, created: Long = 0,
        downloadable: Boolean = true, link: String = "") =
        Document(id, "c", folder, name, size, modified = modified, created = created, downloadable = downloadable, externalUrl = link)

    @Test fun planMirrorsFoldersBelowTheRootAndKeepsNamesUnique() {
        val materials = CourseMaterials("c", folders, listOf(
            doc("a", "slides", "intro.pdf", created = 1), doc("b", "slides", "Intro.pdf", created = 2),
            doc("c", "root", "syllabus.pdf"), doc("d", "loop", "odd.txt"),
            // Every DigiCampus file carries its own address; that alone must not exclude it.
            doc("addressed", "root", "notes.pdf", link = "/sendfile.php?type=0&file_id=abc"), doc("locked", "root", "x.pdf", downloadable = false),
            doc("huge", "root", "video.mp4", size = DigiCampusProvider.MAX_FILE_BYTES + 1),
        ))
        val plan = materialSyncPlan(materials, null, "", DigiCampusProvider.MAX_FILE_BYTES)
        val byId = plan.targets.associateBy { it.document.id }
        assertEquals(listOf("Week 1_ Basics", "Slides"), byId.getValue("a").folders)
        assertEquals("intro.pdf", byId.getValue("a").name)
        assertEquals("Intro (2).pdf", byId.getValue("b").name)
        assertEquals(emptyList(), byId.getValue("c").folders)
        assertEquals(listOf("Loop"), byId.getValue("d").folders)
        assertEquals(setOf("locked", "huge"), plan.skipped.map { it.id }.toSet())
        assertTrue("addressed" in byId)
        assertEquals(5, plan.pending.size); assertEquals(50, plan.bytes)
    }

    @Test fun onlyNewChangedOrMovedFilesArePendingAndANewFolderResyncsEverything() {
        val materials = CourseMaterials("c", folders, listOf(doc("a", "slides", "a.pdf", modified = 5), doc("b", "root", "b.pdf", modified = 5),
            doc("n", "root", "new.pdf")))
        val record = MaterialSyncRecord("c", "", listOf(
            SyncedMaterial("a", listOf("Week 1_ Basics", "Slides"), "a.pdf", 5, 10),
            SyncedMaterial("b", emptyList(), "b.pdf", 4, 10),
        ))
        assertEquals(setOf("b", "n"), materialSyncPlan(materials, record, "", Long.MAX_VALUE).pending.map { it.document.id }.toSet())
        assertEquals(3, materialSyncPlan(materials, record, "content://elsewhere", Long.MAX_VALUE).pending.size)
        val moved = materials.copy(documents = listOf(doc("a", "root", "a.pdf", modified = 5)))
        assertEquals(listOf("a"), materialSyncPlan(moved, record, "", Long.MAX_VALUE).pending.map { it.document.id })
    }

    private fun provider(): CampusProvider = object : CampusProvider by TestProvider() {
        override suspend fun courseMaterials(courseId: String, force: Boolean) = CourseMaterials(courseId, folders,
            listOf(doc("a", "slides", "intro.pdf"), doc("b", "week1", "sheet.pdf"), doc("c", "root", "syllabus.pdf")))
    }

    @Test fun syncWritesEveryFileIntoTheCourseFolderAndASecondRunOnlyFetchesChanges() = runTest {
        val account = Account("alice", "Alice", "alice")
        val materials = MemoryMaterials()
        val repo = CampusRepository(account, provider(), MemoryStore(), materials, backgroundScope, clock = { 1_000L })
        repo.refreshToday(); advanceUntilIdle()
        val plans = repo.planMaterialSync(listOf("c"))
        val pending = plans.single().pending.size
        assertEquals(3, pending)
        repo.startMaterialSync(plans, automatic = true); advanceUntilIdle(); runCurrent()
        val progress = repo.materialSync.value!!
        assertFalse(progress.running); assertEquals(pending, progress.done); assertTrue(progress.failed.isEmpty())
        // The default folder groups files by course title.
        val course = repo.snapshot.value.courses.firstOrNull { it.id == "c" }?.title ?: "c"
        assertEquals(setOf("Downloads/ZenCampus/$course/Week 1_ Basics/Slides/intro.pdf", "Downloads/ZenCampus/$course/Week 1_ Basics/sheet.pdf",
            "Downloads/ZenCampus/$course/syllabus.pdf"), materials.synced.keys)
        assertEquals(pending, materials.synced.size)
        val record = repo.snapshot.value.materialSync.getValue("c")
        assertTrue(record.automatic); assertEquals(pending, record.files.size); assertTrue(record.lastSync > 0)
        assertTrue(repo.planMaterialSync(listOf("c")).single().pending.isEmpty())
        repo.close(false)
    }

    @Test fun withoutADefaultFolderEachFileFailsWithStorageInsteadOfWritingSomewhereElse() = runTest {
        val materials = MemoryMaterials().apply { syncsDefault = false }
        val repo = CampusRepository(Account("alice", "Alice", "alice"), provider(), MemoryStore(), materials, backgroundScope, clock = { 1_000L })
        repo.refreshToday(); advanceUntilIdle()
        val plans = repo.planMaterialSync(listOf("c"))
        repo.startMaterialSync(plans); advanceUntilIdle(); runCurrent()
        val progress = repo.materialSync.value!!
        assertTrue(materials.synced.isEmpty())
        assertEquals(plans.single().pending.size, progress.failed.size)
        assertTrue(progress.failed.values.all { it == FailureKind.STORAGE })
        repo.close(false)
    }
}
