package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.services.CatalogueProvider
import io.github.binoverfl0w.zencampus.data.services.CatalogueRepository
import io.github.binoverfl0w.zencampus.data.services.DemoCatalogueProvider
import io.github.binoverfl0w.zencampus.data.services.TouchPointCatalogueProvider
import io.github.binoverfl0w.zencampus.model.*
import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueTest {
    private val now = 1_792_000_000_000L
    private val start = """<form id="AdvancedSearchForm"><input name="CSId" value="public_nonce"></form>"""
    private val shell = """<form id="RefineHitListForm"></form>"""
    private val results = """<div class="box-header"><h2>Treffer Katalog der UB Augsburg (21)</h2></div><table class="data"><tr>
        <td class="icon"><img alt="Buch"></td><td class="results"><span class="Z3988" title="rft.au=Cormen%2C+Thomas+H.&amp;rft.date=%5B2022%5D&amp;rft.isbn=9780262046305&amp;rft.edition=Fourth+edition"></span>
        <div class="title"><a href="/TouchPoint/singleHit.do?methodToCall=showHit&amp;curPos=1&amp;identifier=public_list">Introduction to algorithms</a></div>Cormen, Thomas H. [2022]</td></tr></table>"""
    private val detail = """<a id="permalink-link" href="/TouchPoint/perma.do?q=1035%3D%22BV0123%22%20IN%20%5B2%5D&amp;v=uba"></a><table class="titleinfo">
        <tr><td>Autor/Mitarbeiter:</td><td>Cormen, Thomas H.</td></tr><tr><td>Titel:</td><td>Introduction to algorithms</td></tr>
        <tr><td>Jahr:</td><td>[2022]</td></tr><tr><td>Ausgabe:</td><td>Fourth edition</td></tr><tr><td>ISBN:</td><td>9780262046305</td></tr>
        <tr><td>Volltext:</td><td><a href="https://doi.org/10.123/test">Publisher</a><a href="javascript:write()">Unsafe</a></td></tr></table>
        <div id="ajax_holdings_url_2" ajaxParameter="singleHit.do?methodToCall=showHoldings&amp;dbIdentifier=2&amp;curPos=1&amp;hitlistIdentifier=public_list&amp;ajax=true"></div>"""
    private val holdings = """<table class="data" id="holdings_2"><tr><th>Signatur Fußnoten</th><th>Standort</th><th>Zweigstelle / Abteilung</th><th>Leihstatus</th><th>QR-Code</th></tr>
        <tr><td>81/ST 130 C811(4)</td><td>Informatik</td><td>UB</td><td>Am Standort (Freihandbestand)<a href="https://vscout.bibliothek.uni-augsburg.de/">Wegweiser</a></td><td>QR</td></tr>
        <tr><td>40/ST 134 C811(4)</td><td>Wirtschaft</td><td>UB</td><td>entliehen (keine Vormerkung möglich)</td><td>QR</td></tr></table>"""
    private val headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
    private fun doc(html: String) = Ksoup.parse(html, LIBRARY_START)

    @Test fun sourceMetadataAndCopyStatesArePreservedWithoutGuessingAvailability() {
        val found = TouchPointCatalogueProvider.parseResults(doc(results), CatalogueQuery("Cormen"), 1, now)
        assertEquals(21, found.total); assertTrue(found.hasNext)
        val book = found.books.single()
        assertEquals("Cormen, Thomas H.", book.author); assertEquals("2022", book.year); assertEquals("Fourth edition", book.edition)
        val record = TouchPointCatalogueProvider.parseRecord(doc(detail), book, now)
        assertEquals("1035=\"BV0123\" IN [2]", record.book.recordQuery)
        assertEquals("Publisher", record.links.single().label)
        val copies = TouchPointCatalogueProvider.parseCopies(doc(holdings)).first
        assertEquals("Am Standort (Freihandbestand)", copies.first().status)
        assertEquals("entliehen (keine Vormerkung möglich)", copies.last().status)
        assertEquals("81/ST 130 C811(4)", copies.first().callNumber)
        assertEquals("Informatik", copies.first().location)
    }

    @Test fun emptyResultsAndExplicitNoCopiesDifferFromMaintenanceAndMalformedResponses() {
        val empty = TouchPointCatalogueProvider.parseResults(doc("<div class=nodata>Keine Treffer</div>"), CatalogueQuery("none"), 1, now)
        assertTrue(empty.books.isEmpty()); assertFalse(empty.hasNext)
        val (_, note) = TouchPointCatalogueProvider.parseCopies(doc("<table id=holdings_2><tr><th>Signatur</th></tr><tr><td class=errortext>Es sind keine Exemplare zu diesem Titel verfügbar.</td></tr></table>"))
        assertTrue(note.contains("keine Exemplare"))
        assertFailsWith<CampusException> { TouchPointCatalogueProvider.parseResults(doc("Maintenance"), CatalogueQuery("test"), 1, now) }
        assertFailsWith<CampusException> { TouchPointCatalogueProvider.parseCopies(doc("Maintenance")) }
        assertFailsWith<CampusException> { TouchPointCatalogueProvider.parseRecord(doc(detail.replace("Introduction to algorithms", "Other edition")), emptyBook(), now) }
    }

    @Test fun ajaxPaginationAndHoldingsUseOnlyAnAnonymousReadSession() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { req ->
            assertEquals("opac.bibliothek.uni-augsburg.de", req.url.host); assertNull(req.headers[HttpHeaders.Authorization])
            assertFalse(req.headers[HttpHeaders.Cookie].orEmpty().contains("library_secret"))
            requests += req.url.encodedPath + ":" + req.url.parameters["methodToCall"].orEmpty()
            when(req.url.encodedPath) {
                "/TouchPoint/start.do" -> respond(start, headers = headersOf(HttpHeaders.ContentType to listOf("text/html; charset=utf-8"), HttpHeaders.SetCookie to listOf("JSESSIONID=public_only; Secure; Path=/TouchPoint")))
                "/TouchPoint/search.do" -> { assertEquals("100", req.url.parameters["searchCategories[0]"]); respond(shell, headers = headers) }
                "/TouchPoint/speedHitList.do" -> respond(results, headers = headers)
                "/TouchPoint/hitList.do" -> { assertEquals("11", req.url.parameters["curPos"]); respond(shell, headers = headers) }
                "/TouchPoint/singleHit.do" -> respond(if (req.url.parameters["methodToCall"] == "showHoldings") holdings else detail, headers = headers)
                else -> error("Unexpected endpoint")
            }
        })
        try {
            val provider = TouchPointCatalogueProvider({ client }, { now })
            val found = provider.search(CatalogueQuery("Cormen", CatalogueField.AUTHOR), 2)
            assertEquals(2, found.page); assertEquals(2, requests.count { it.startsWith("/TouchPoint/speedHitList.do") })
            assertEquals(2, provider.record(found.books.single()).copies.size)
            assertTrue(requests.none { it.contains("renewal") || it.contains("requestItem") || it.contains("userAccount") })
        } finally { client.close() }
    }

    @Test fun singleHitIsbnSearchAndProcessRestartReidentifyTheBookUsingItsStableRecord() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { req ->
            requests += req.url.encodedPath
            respond(when(req.url.encodedPath) {
                "/TouchPoint/start.do" -> start; "/TouchPoint/search.do", "/TouchPoint/perma.do" -> detail
                "/TouchPoint/singleHit.do" -> holdings; else -> error("Unexpected endpoint")
            }, headers = headers)
        })
        try {
            val original = TouchPointCatalogueProvider.parseResults(doc(results), CatalogueQuery("Cormen"), 1, now).books.single()
            val fresh = TouchPointCatalogueProvider({ client }, { now })
            val record = fresh.record(original)
            assertEquals(2, record.copies.size)
            assertTrue(requests.contains("/TouchPoint/perma.do"))
            requests.clear()
            assertEquals(2, TouchPointCatalogueProvider({ client }, { now }).record(record.book).copies.size)
            assertFalse(requests.contains("/TouchPoint/search.do"))
        } finally { client.close() }
    }

    @Test fun foreignAndMutationUrlsAreRejectedAndHoldingsFailureKeepsBookDetails() = runTest {
        for (url in listOf("https://evil.example/TouchPoint/singleHit.do?methodToCall=showHit", "${LIBRARY_BASE}renewal.do?methodToCall=renewal", "${LIBRARY_BASE}singleHit.do?methodToCall=requestItem", "http://opac.bibliothek.uni-augsburg.de/TouchPoint/start.do"))
            assertFailsWith<CampusException> { TouchPointCatalogueProvider.safeUrl(url) }
        val client = HttpClient(MockEngine { req ->
            when(req.url.encodedPath) {
                "/TouchPoint/perma.do" -> respond(detail.replace("singleHit.do?methodToCall=showHoldings", "https://evil.example/singleHit.do?methodToCall=showHoldings"), headers = headers)
                else -> error("A foreign holdings request must never be sent")
            }
        })
        try {
            val book = TouchPointCatalogueProvider.parseResults(doc(results), CatalogueQuery("Cormen"), 1, now).books.single().copy(recordQuery = "test")
            val record = TouchPointCatalogueProvider({ client }, { now }).record(book)
            assertTrue(record.holdingsUnavailable); assertTrue(record.properties.isNotEmpty()); assertTrue(record.copies.isEmpty())
        } finally { client.close() }
    }

    @Test fun cachesRestoreOfflineAndFailuresKeepResultsDetailsAndSavedBooks() = runTest {
        val store = MemoryStore(); var fail = false; var calls = 0
        val demo = DemoCatalogueProvider { now }
        val provider = object : CatalogueProvider {
            override suspend fun search(query: CatalogueQuery, page: Int): CatalogueResults { calls++; if (fail) throw CampusException(FailureKind.SERVER); return demo.search(query, page) }
            override suspend fun record(book: CatalogueBook): CatalogueRecord { if (fail) throw CampusException(FailureKind.SERVER); return demo.record(book) }
        }
        val repo = CatalogueRepository(store, "public", provider, backgroundScope, clock = { now })
        val query = CatalogueQuery("Cormen", CatalogueField.AUTHOR)
        repo.search(query); val book = repo.search.value.results!!.books.single()
        repo.openRecord(book.id); repo.toggleSaved(book); runCurrent()
        repo.search(query); assertEquals(1, calls)
        fail = true; repo.search(query, force = true); repo.openRecord(book.id, true)
        assertEquals(FailureKind.SERVER, repo.search.value.error); assertEquals(2, repo.record.value.record!!.copies.size)
        val restored = CatalogueRepository(store, "public", provider, backgroundScope, conditions = { NetworkConditions(connected = false) }, clock = { now + 600_000 })
        runCurrent(); restored.search(query); restored.openRecord(book.id); runCurrent()
        assertEquals(1, restored.saved.value.size); assertEquals(query, restored.recent.value.first())
        assertEquals(2, restored.record.value.record!!.copies.size); assertEquals(FailureKind.OFFLINE, restored.record.value.error)
        assertEquals(2, calls)
    }

    @Test fun overlappingSearchesShareRequestsAndOlderQueriesCannotReplaceNewerResults() = runTest {
        val store = MemoryStore(); val gate = CompletableDeferred<Unit>(); var calls = 0
        val provider = object : CatalogueProvider {
            override suspend fun search(query: CatalogueQuery, page: Int): CatalogueResults {
                calls++; if (query.text == "old") gate.await() else delay(20)
                return CatalogueResults(query, page, listOf(emptyBook().copy(title = query.text)), fetchedAt = now)
            }
            override suspend fun record(book: CatalogueBook) = CatalogueRecord(book, fetchedAt = now)
        }
        val repo = CatalogueRepository(store, "public", provider, backgroundScope, clock = { now })
        val first = launch { repo.search(CatalogueQuery("old")) }; runCurrent()
        coroutineScope { List(4) { launch { repo.search(CatalogueQuery("new")) } }.joinAll() }
        gate.complete(Unit); first.join()
        assertEquals("new", repo.search.value.results!!.books.single().title)
        assertEquals(2, calls)
    }
    @Test fun cachedQueriesIgnoreCaseAndFailedCopyRefreshKeepsItsOriginalUpdateTime() = runTest {
        val store = MemoryStore(); var time = now; var failCopies = false; var calls = 0
        val demo = DemoCatalogueProvider { time }
        val provider = object : CatalogueProvider {
            override suspend fun search(query: CatalogueQuery, page: Int): CatalogueResults { calls++; return demo.search(query, page) }
            override suspend fun record(book: CatalogueBook) = if (failCopies) CatalogueRecord(book, holdingsUnavailable = true, fetchedAt = time) else demo.record(book)
        }
        val repo = CatalogueRepository(store, "public", provider, backgroundScope, clock = { time })
        repo.search(CatalogueQuery("Cormen")); val book = repo.search.value.results!!.books.single()
        repo.search(CatalogueQuery("cormen")); assertEquals(1, calls); assertEquals("cormen", repo.search.value.results!!.query.text)
        repo.openRecord(book.id); time += 600_000; failCopies = true; repo.openRecord(book.id, true)
        val record = repo.record.value.record!!
        assertEquals(2, record.copies.size); assertTrue(record.holdingsUnavailable)
        assertEquals(now, record.holdingsFetchedAt); assertEquals(time, record.fetchedAt)
    }
    private fun emptyBook() = CatalogueBook("fixture", "Introduction to algorithms")
}
