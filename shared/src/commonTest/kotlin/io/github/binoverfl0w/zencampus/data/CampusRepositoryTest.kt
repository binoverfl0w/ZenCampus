package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class CampusRepositoryTest {
    private val account = Account("alice", "Alice Student", "alice")
    private val clockStart = 1_760_482_800_000L

    @Test fun groupCorrectionsAndPinsSurviveOfflineRecreationAndStayAccountScoped() = runTest {
        val store = MemoryStore()
        val provider = TestProvider()
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, clock = { clockStart })
        repo.saveCourseGroup(null, "Algorithms", setOf("lecture", "exercise"), setOf("lecture", "exercise", "tutorial"))
        repo.toggleCoursePin("exercise")
        repo.close(false)
        val restored = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope,
            conditions = { NetworkConditions(connected = false) }, clock = { clockStart })
        runCurrent()
        assertEquals("Algorithms", restored.snapshot.value.preferences.courseGroups.single().title)
        assertEquals(setOf("tutorial"), restored.snapshot.value.preferences.separateCourseIds)
        assertEquals(setOf("exercise"), restored.snapshot.value.preferences.pinnedCourses)
        assertEquals(0, provider.courseCalls)
        restored.clearCache(); runCurrent()
        assertEquals(1, restored.snapshot.value.preferences.courseGroups.size)
        val stranger = CampusRepository(account.copy(id = "bob"), provider, store, MemoryMaterials(), backgroundScope)
        runCurrent(); assertTrue(stranger.snapshot.value.preferences.courseGroups.isEmpty())
        stranger.close(false)
        restored.separateCourses(setOf("lecture", "exercise")); runCurrent()
        assertTrue(restored.snapshot.value.preferences.courseGroups.isEmpty())
        restored.close(true)
        assertNull(store.read(account.cacheScope, "preferences"))
    }

    @Test fun freshLegacyFolderCacheIsRevalidatedAfterTheHierarchyUpgrade() = runTest {
        val store = MemoryStore()
        val key = CampusRepository.folderKey("c", null)
        store.put(account.cacheScope, key, """{"folders":[{"id":"old","courseId":"c","name":"Old flattened folder"}]}""", clockStart)
        val repo = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope, clock = { clockStart })
        repo.refreshFolder("c", null)
        runCurrent()
        assertEquals("child", repo.snapshot.value.folderContents[key]?.folders?.single()?.id)
        assertEquals(2, Json.decodeFromString<FolderContent>(store.read(account.cacheScope, key)!!.payload).version)
        repo.close(false)
    }

    @Test fun oldEmptyFolderPreferenceDoesNotDiscardFoldersAfterTheFilterIsRemoved() = runTest {
        val store = MemoryStore()
        store.put(account.cacheScope, "preferences", """{"hideEmptyFolders":true}""", clockStart)
        val repo = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope, clock = { clockStart })
        repo.refreshFolder("c", null)
        repo.close(false)
        val restored = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope, clock = { clockStart })
        runCurrent()
        assertEquals(1, restored.snapshot.value.folderContents[CampusRepository.folderKey("c", null)]?.folders?.size)
        restored.close(false)
    }

    @Test fun simultaneousReadersShareOneRequestAndFreshNavigationUsesTheCache() = runTest {
        val provider = TestProvider()
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { clockStart })
        coroutineScope { List(20) { launch { repo.refreshCourses(force = true) } }.joinAll() }
        assertEquals(1, provider.courseCalls)
        repo.refreshCourses()
        assertEquals(1, provider.courseCalls)
        runCurrent()
        assertEquals("Algorithms", repo.snapshot.value.courses.single().title)
        repo.close(false)
    }

    @Test fun aFailedRefreshPreservesTheLastSuccessfulContent() = runTest {
        var now = clockStart
        val provider = TestProvider()
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { now })
        repo.refreshCourses()
        provider.courseFailure = FailureKind.SERVER
        now += CampusRepository.COURSE_TTL + 1
        repo.refreshCourses()
        runCurrent()
        assertEquals("Algorithms", repo.snapshot.value.courses.single().title)
        assertEquals(clockStart, repo.snapshot.value.updatedAt["courses:"])
        // A temporary failure keeps the content quietly: no error, marked stale and retried by itself.
        assertNull(repo.sync.value.errors["courses:"]); assertTrue("courses:" in repo.sync.value.stale)
        repo.close(false)
    }

    @Test fun offlineRefreshRetainsTheCacheWithoutContactingTheProvider() = runTest {
        var network = NetworkConditions()
        var now = clockStart
        val provider = TestProvider()
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope,
            conditions = { network }, clock = { now })
        repo.refreshCourses()
        now += CampusRepository.COURSE_TTL + 1
        network = NetworkConditions(connected = false)
        repo.refreshCourses()
        runCurrent()
        assertEquals(1, provider.courseCalls)
        assertEquals(1, repo.snapshot.value.courses.size)
        assertNull(repo.sync.value.errors["courses:"]); assertTrue("courses:" in repo.sync.value.stale)
        repo.close(false)
    }

    @Test fun logoutRejectsLateResultsEvenIfTheProviderIgnoresCancellation() = runTest {
        val store = MemoryStore()
        val gate = CompletableDeferred<Unit>()
        val provider = TestProvider().apply { courseGate = gate }
        val repo = CampusRepository(account, provider, store, MemoryMaterials(), backgroundScope, clock = { clockStart })
        val request = launch { repo.refreshCourses(true) }
        runCurrent()
        assertEquals(1, provider.courseCalls)
        val close = launch { repo.close(purge = true) }
        runCurrent()
        gate.complete(Unit)
        close.join()
        request.join()
        assertNull(store.read(account.cacheScope, "courses:"))
        assertTrue(store.observe(account.cacheScope).first().isEmpty())
    }

    @Test fun clearingOneAccountDoesNotChangeAnotherAccountsCache() = runTest {
        val store = MemoryStore()
        val alice = CampusRepository(account, TestProvider(), store, MemoryMaterials(), backgroundScope, clock = { clockStart })
        val bobAccount = Account("bob", "Bob Student", "bob")
        val bobProvider = TestProvider().apply { offeredCourses = listOf(Course("b", "Physics")) }
        val bob = CampusRepository(bobAccount, bobProvider, store, MemoryMaterials(), backgroundScope, clock = { clockStart })
        alice.refreshCourses(); bob.refreshCourses()
        alice.close(purge = true)
        runCurrent()
        assertEquals("Physics", bob.snapshot.value.courses.single().title)
        assertNotNull(store.read(bobAccount.cacheScope, "courses:"))
        assertNull(store.read(account.cacheScope, "courses:"))
        bob.close(false)
    }

    @Test fun changingSemesterDoesNotMixTheTwoCourseLists() = runTest {
        val provider = TestProvider()
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { clockStart })
        repo.selectSemester("first")
        provider.offeredCourses = listOf(Course("other", "Physics"))
        repo.selectSemester("second")
        runCurrent()
        assertEquals("Physics", repo.snapshot.value.courses.single().title)
        repo.selectSemester("first")
        runCurrent()
        assertEquals("Algorithms", repo.snapshot.value.courses.single().title)
        assertEquals(2, provider.courseCalls)
        repo.close(false)
    }

    @Test fun downloadedMaterialReopensOfflineWithoutAnotherDownload() = runTest {
        val provider = TestProvider()
        val files = MemoryMaterials()
        var network = NetworkConditions()
        val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope, conditions = { network }, clock = { clockStart })
        val doc = Document("notes", "c", "f", "notes.pdf", 4, "application/pdf", 10)
        repo.download(doc, pin = true, open = false)
        network = NetworkConditions(connected = false)
        repo.download(doc)
        runCurrent()
        assertEquals(1, provider.downloadCalls)
        assertEquals(1, files.opened.size)
        assertTrue(repo.snapshot.value.localDocuments.single().pinned)
        repo.close(purge = true)
        assertTrue(files.files.isEmpty())
    }

    @Test fun failedDownloadIsAbortedAndIsNeverListedAsOffline() = runTest {
        val provider = TestProvider().apply { failDownload = true }
        val files = MemoryMaterials()
        val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope, clock = { clockStart })
        repo.download(Document("notes", "c", "f", "notes.pdf"))
        runCurrent()
        assertTrue(repo.snapshot.value.localDocuments.isEmpty())
        assertEquals(1, files.aborted)
        assertTrue(files.files.isEmpty())
        repo.close(false)
    }

    @Test fun permissionLossRemovesCachedFolderContentAndItsOfflineMaterial() = runTest {
        val provider = TestProvider()
        val files = MemoryMaterials()
        val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope, clock = { clockStart })
        val doc = Document("notes", "c", "f", "notes.pdf")
        repo.refreshFolder("c", "f")
        repo.download(doc, pin = true, open = false)
        provider.folderFailure = FailureKind.PERMISSION
        repo.refreshFolder("c", "f", true)
        runCurrent()
        assertFalse(repo.snapshot.value.folderContents.containsKey(CampusRepository.folderKey("c", "f")))
        assertTrue(repo.snapshot.value.localDocuments.isEmpty())
        assertTrue(files.files.isEmpty())
        repo.close(false)
    }

    @Test fun freshnessHandlesExpiryAndClockMovingBackwards() {
        assertTrue(isFresh(100, 200, 1000))
        assertFalse(isFresh(100, 1100, 1000))
        assertFalse(isFresh(200, 100, 1000))
        assertFalse(isFresh(null, 100, 1000))
    }

    @Test fun newerCompleteCalendarWindowsDoNotResurrectRemovedCourseEvents() = runTest {
        var now = clockStart
        val provider = TestProvider()
        val event = CampusEvent("removed", "Lecture", now + 60_000, now + 120_000, "c")
        val lastDay = weekDates(campusToday(now)).first().plus(14, DateTimeUnit.DAY)
            .atTime(10, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
        val boundaryEvent = CampusEvent("last-day", "Last-day lecture", lastDay, lastDay + 60_000, "c")
        provider.offeredCourseEvents = listOf(event, boundaryEvent)
        provider.offeredPersonalEvents = listOf(event, boundaryEvent)
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { now })
        repo.refreshCourses()
        repo.visitCourse("c")
        repo.ensureWeek(campusToday(now))
        runCurrent()
        assertEquals(2, repo.snapshot.value.events.size)
        provider.offeredPersonalEvents = emptyList()
        now += 60_000
        repo.ensureWeek(campusToday(now), force = true)
        runCurrent()
        assertTrue(repo.snapshot.value.events.isEmpty())
        repo.close(false)
    }

    @Test fun deniedDownloadInvalidatesAnOlderSavedCopy() = runTest {
        val provider = TestProvider()
        val files = MemoryMaterials()
        val repo = CampusRepository(account, provider, MemoryStore(), files, backgroundScope, clock = { clockStart })
        val original = Document("notes", "c", "f", "notes.pdf", modified = 1)
        repo.refreshFolder("c", "f")
        repo.download(original, pin = true, open = false)
        provider.downloadFailure = FailureKind.PERMISSION
        repo.download(original.copy(modified = 2))
        runCurrent()
        assertTrue(repo.snapshot.value.localDocuments.isEmpty())
        assertTrue(repo.snapshot.value.folderContents.isEmpty())
        assertTrue(files.files.isEmpty())
        repo.close(false)
    }

    @Test fun emptyPersonalCalendarDoesNotHideWeeklyTimetableClasses() = runTest {
        val event = CampusEvent("schedule:c", "Weekly lecture", clockStart + 60_000, clockStart + 120_000, "c")
        val provider = TestProvider().apply { offeredScheduledEvents = listOf(event) }
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { clockStart })
        repo.ensureWeek(campusToday(clockStart))
        runCurrent()
        assertEquals(listOf(event), repo.snapshot.value.events)
        repo.close(false)
    }

    @Test fun realDatedCancellationsAndRoomChangesOverrideMatchingTimetableSlots() = runTest {
        val planned = CampusEvent("schedule:c", "Weekly lecture", clockStart + 60_000, clockStart + 120_000, "c", "Planned room")
        val dated = planned.copy(id = "actual", location = "Changed room", cancelled = true)
        val provider = TestProvider().apply {
            offeredScheduledEvents = listOf(planned)
            offeredPersonalEvents = listOf(dated)
        }
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { clockStart })
        repo.ensureWeek(campusToday(clockStart))
        runCurrent()
        assertEquals(listOf(dated), repo.snapshot.value.events)
        assertNull(nextUpcomingEvent(repo.snapshot.value.events, clockStart))
        repo.close(false)
    }

    @Test fun freshOverlappingTimetableWindowsRemoveDeletedSlots() = runTest {
        var now = clockStart
        val day = weekDates(campusToday(now)).first().plus(8, DateTimeUnit.DAY)
        val start = day.atTime(10, 0).toInstant(CAMPUS_ZONE).toEpochMilliseconds()
        val provider = TestProvider().apply { offeredScheduledEvents = listOf(CampusEvent("schedule:c", "Removed slot", start, start + 3_600_000, "c")) }
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { now })
        repo.ensureWeek(campusToday(now))
        runCurrent()
        assertEquals(1, repo.snapshot.value.events.size)
        provider.offeredScheduledEvents = emptyList()
        now += 60_000
        repo.ensureWeek(day)
        runCurrent()
        assertTrue(repo.snapshot.value.events.isEmpty())
        repo.close(false)
    }

    @Test fun timetableStillLoadsWhenThePersonalEventRequestFails() = runTest {
        val provider = TestProvider().apply {
            offeredScheduledEvents = listOf(CampusEvent("schedule:c", "Class", clockStart + 60_000, clockStart + 120_000, "c"))
            personalFailure = FailureKind.INVALID_RESPONSE
        }
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { clockStart })
        repo.ensureWeek(campusToday(clockStart))
        runCurrent()
        assertEquals(1, repo.snapshot.value.events.size)
        assertTrue(repo.sync.value.errors.values.contains(FailureKind.INVALID_RESPONSE))
        repo.close(false)
    }

    @Test fun reopeningAFreshWeekUsesBothCachedSources() = runTest {
        val provider = TestProvider()
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { clockStart })
        repo.ensureWeek(campusToday(clockStart))
        repo.ensureWeek(campusToday(clockStart))
        assertEquals(1, provider.personalCalls)
        assertEquals(1, provider.scheduleCalls)
        repo.close(false)
    }

    @Test fun refreshedProfileUpdatesTheGreetingWithoutChangingAccountScope() = runTest {
        val provider = TestProvider().apply { offeredAccount = account.copy(name = "Dr. Alice Student", givenName = "Alice") }
        val repo = CampusRepository(account, provider, MemoryStore(), MemoryMaterials(), backgroundScope, clock = { clockStart })
        repo.refreshProfile()
        runCurrent()
        assertEquals("Alice", repo.snapshot.value.account.firstName)
        assertEquals(account.cacheScope, repo.snapshot.value.account.cacheScope)
        provider.offeredAccount = Account("another-user", "Another person", "another")
        repo.refreshProfile(force = true)
        runCurrent()
        assertEquals(account.id, repo.snapshot.value.account.id)
        assertTrue(repo.sync.value.sessionExpired)
        repo.close(false)
    }

    @Test fun weeksStartOnMondayAcrossTheYearBoundary() {
        assertEquals("2026-12-28", weekDates(kotlinx.datetime.LocalDate(2027, 1, 1)).first().toString())
        assertEquals("2027-01-03", weekDates(kotlinx.datetime.LocalDate(2027, 1, 1)).last().toString())
    }
}
