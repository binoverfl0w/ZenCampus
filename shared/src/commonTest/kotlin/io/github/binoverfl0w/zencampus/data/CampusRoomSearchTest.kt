package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.digicampus.DigiCampusProvider
import io.github.binoverfl0w.zencampus.data.study.PlannerRepository
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class CampusRoomSearchTest {
    private val room = CampusRoom("n2013", "N · 2.013", "Seminar room", 40,
        "$DIGICAMPUS_ORIGIN/dispatch.php/resources/room_planning/booking_plan/n2013")
    private fun row(room: CampusRoom) = """<tr><td data-range_type="Room" data-id="${room.id}"></td>
        <td><a href="${room.bookingUrl}">${room.name}</a></td><td>Seminar room</td><td>40</td><td></td></tr>"""
    private fun page(query: String, rooms: List<CampusRoom> = emptyList()): String =
        """<input name="special__room_name" value="$query"><table>${rooms.joinToString("") { row(it) }}</table>""" +
            if (rooms.isEmpty()) "<div class='messagebox_info'>No rooms could be found that match the specified search criteria!</div>" else ""

    @Test fun roomNumberAndBuildingMatchInEitherOrderAcrossPunctuationWithoutOtherBuildings() {
        val variants = listOf("2013 N", "N-2013", "N2013", "N 2.013", "2013\u00a0n", "N · 20/13")
        for (query in variants) {
            for (name in variants) assertTrue(campusRoomNameMatches(query, name), "$query -> $name")
            assertFalse(campusRoomNameMatches(query, "W · 2013"))
            assertFalse(campusRoomNameMatches(query, "2013 Seminarraum"), "A letter inside a word is not building N")
            assertFalse(campusRoomNameMatches(query, "N · 2014"))
        }
        assertTrue(campusRoomNameMatches("2013", "W · 2.013"))
        assertTrue(campusRoomNameMatches("201", room.name), "Partial numeric queries remain useful")
        assertTrue(campusRoomNameMatches("", room.name), "Empty local lists retain all rooms")
    }

    @Test fun equivalentSpellingsShareTheSearchCacheButDifferentBuildingsDoNot() {
        val key = campusRoomSearchKey("2013 N")
        for (query in listOf("N-2013", "N2013", "N · 2.013", "2013\u00a0n")) assertEquals(key, campusRoomSearchKey(query))
        assertNotEquals(key, campusRoomSearchKey("2013 W"))
        assertNotEquals(key, campusRoomSearchKey("2013"))
        assertNotEquals(campusRoomSearchKey("PC A"), campusRoomSearchKey("A PC"), "Short literal searches retain their own cache identity")
        assertEquals(CampusRoomQuery("2013 N 1013").searchTerms, CampusRoomQuery("1013 N 2013").searchTerms)
    }

    @Test fun aCanonicalNumberSearchMatchesTheBuildingOnServerListedRooms() = runTest {
        val queries = mutableListOf<String>()
        val other = room.copy(id = "w2013", name = "W · 2013", bookingUrl = room.bookingUrl.replace("n2013", "w2013"))
        val client = HttpClient(MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method)
            assertEquals("digicampus.uni-augsburg.de", req.url.host)
            assertEquals("/dispatch.php/resources/search/rooms", req.url.encodedPath)
            assertEquals("session=test", req.headers[HttpHeaders.Cookie])
            val query = req.url.parameters["special__room_name"]!!; queries += query
            respond(page(query, if (query == "2013") listOf(room, other) else emptyList()), headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        try {
            val result = DigiCampusProvider(client, { "session=test" }).searchCampusRooms("2013 N")
            assertEquals(listOf(room.id), result.rooms.map { it.id })
            assertEquals("2013 N", result.query)
            assertEquals(CAMPUS_ROOM_SEARCH_VERSION, result.version)
            assertEquals(listOf("2013"), queries)
        } finally { client.close() }
    }

    @Test fun aBareNumberAlsoFindsFloorSeparatedDigitsWithOneBoundedWildcardFallback() = runTest {
        val queries = mutableListOf<String>()
        val client = HttpClient(MockEngine { req ->
            val query = req.url.parameters["special__room_name"]!!; queries += query
            respond(page(query, if (query == "2%0%1%3") listOf(room) else emptyList()), headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        try {
            val result = DigiCampusProvider(client, { "session=test" }).searchCampusRooms("2013")
            assertEquals(listOf(room), result.rooms)
            assertEquals(listOf("2013", "2%0%1%3"), queries)
        } finally { client.close() }
    }

    @Test fun aSuccessfulNumberSearchMakesNoExtraReadsAndEmptySearchNeverInventsARoom() = runTest {
        var calls = 0
        val plain = room.copy(name = "N · 2013")
        val client = HttpClient(MockEngine { req ->
            calls++
            val query = req.url.parameters["special__room_name"]!!
            respond(page(query, if (query == "2013") listOf(plain) else emptyList()), headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        try {
            val provider = DigiCampusProvider(client, { "session=test" })
            assertEquals(listOf(plain), provider.searchCampusRooms("2013 N").rooms)
            assertEquals(1, calls)
            assertTrue(provider.searchCampusRooms("2014 N").rooms.isEmpty())
            assertEquals(3, calls, "A miss has at most two requests, without scanning the directory")
        } finally { client.close() }
    }

    @Test fun errorsDuringFallbackRemainErrorsAndNeverBecomeAVerifiedEmptyDirectory() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError)) {
            var calls = 0
            val client = HttpClient(MockEngine { req ->
                calls++
                val query = req.url.parameters["special__room_name"]!!
                if (calls == 1) respond(page(query), headers = headersOf(HttpHeaders.ContentType, "text/html"))
                else respond("Unavailable", status, headersOf(HttpHeaders.ContentType, "text/html"))
            })
            try {
                val failure = assertFailsWith<CampusException> { DigiCampusProvider(client, { "session=test" }).searchCampusRooms("2013 N") }
                assertEquals(if (status == HttpStatusCode.Forbidden) FailureKind.PERMISSION else FailureKind.SERVER, failure.kind)
                assertEquals(2, calls)
            } finally { client.close() }
        }
    }

    @Test fun equivalentQueriesReturnTheSameResourceSetRegardlessOfTheirLiteralWordOrder() = runTest {
        val main = room.copy(id = "main", name = "2013 N Main", bookingUrl = room.bookingUrl.replace("n2013", "main"))
        val annex = room.copy(id = "annex", name = "N2013 Annex", bookingUrl = room.bookingUrl.replace("n2013", "annex"))
        val client = HttpClient(MockEngine { req ->
            val query = req.url.parameters["special__room_name"]!!
            val found = when (query) { "2013 N" -> listOf(main); "N2013" -> listOf(annex); "2013" -> listOf(main, annex); else -> emptyList() }
            respond(page(query, found), headers = headersOf(HttpHeaders.ContentType, "text/html"))
        })
        try {
            val provider = DigiCampusProvider(client, { "session=test" })
            for (query in listOf("2013 N", "N2013", "N-2.013"))
                assertEquals(listOf(main.id, annex.id), provider.searchCampusRooms(query).rooms.map { it.id })
        } finally { client.close() }
    }

    @Test fun oldEmptyCachesRevalidateImmediatelyAndEquivalentQueriesReuseTheNewResult() = runTest {
        val account = Account("a", "A", "a"); val store = MemoryStore(); var calls = 0
        val key = campusRoomSearchKey("2013")
        store.put(account.cacheScope, key, """{"query":"2013","rooms":[]}""", 1_000L)
        val source = object : CampusProvider by TestProvider() {
            override suspend fun searchCampusRooms(query: String): CampusRoomResults {
                calls++
                return CampusRoomResults(query, listOf(room), CAMPUS_ROOM_SEARCH_VERSION)
            }
        }
        val repo = PlannerRepository(account, source, store, backgroundScope, { NetworkConditions() }, { 1_001L }, StandardTestDispatcher(testScheduler))
        repo.searchCampusRooms("2013"); runCurrent()
        assertEquals(listOf(room), repo.snapshot.value.campusRooms[key]?.rooms)
        assertEquals(1, calls)
        repo.searchCampusRooms("2013"); runCurrent()
        assertEquals(1, calls)
        repo.searchCampusRooms("2013 N"); runCurrent()
        repo.searchCampusRooms("N-2.013"); runCurrent()
        assertEquals(2, calls, "Aliases share the cached query instead of another network request")
        repo.close()
    }

    @Test fun oldCachesRemainReadableOfflineAndLegacyVersionIsRecognizable() = runTest {
        val account = Account("a", "A", "a"); val store = MemoryStore()
        val legacy = """{"query":"2013","rooms":[]}"""
        assertEquals(0, Json.decodeFromString<CampusRoomResults>(legacy).version)
        val key = campusRoomSearchKey("2013")
        store.put(account.cacheScope, key, legacy, 1_000L)
        val repo = PlannerRepository(account, TestProvider(), store, backgroundScope, { NetworkConditions(connected = false) }, { 1_001L }, StandardTestDispatcher(testScheduler))
        repo.searchCampusRooms("2013"); runCurrent()
        assertNotNull(repo.snapshot.value.campusRooms[key])
        assertEquals(FailureKind.OFFLINE, repo.sync.value.errors[key])
        repo.close()
    }
}
