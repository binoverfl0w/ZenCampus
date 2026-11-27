package io.github.binoverfl0w.zencampus.data

import io.github.binoverfl0w.zencampus.data.services.AugsburgMensaProvider
import io.github.binoverfl0w.zencampus.data.services.CampusServicesRepository
import io.github.binoverfl0w.zencampus.data.services.DemoLibraryProvider
import io.github.binoverfl0w.zencampus.data.services.DemoMensaProvider
import io.github.binoverfl0w.zencampus.data.services.LibraryProvider
import io.github.binoverfl0w.zencampus.data.services.MensaProvider
import io.github.binoverfl0w.zencampus.data.services.TouchPointLibraryProvider
import io.github.binoverfl0w.zencampus.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class CampusServicesTest {
    private val now = 1_792_000_000_000L
    private val menuHtml = """<script>
        zusatzstoffe["22"] = JSON.parse('{"name":"Vegan"}');
        zusatzstoffe["25"] = JSON.parse('{"name":"Vegetarisch"}');
        zusatzstoffe["Mi"] = JSON.parse('{"name":"Milch"}');</script>
        <div class="essenliste" data-date2="2026-10-14"><h3 class="mensatitle">Mensa Uni Augsburg</h3>
        <li class="groupdivider" data-gid="main">Main dishes</li>
        <li class="conditional" data-gid="main" ref='["Mi","UNKNOWN"]'><a href="details.php#meal-a">
        <img data-type="22"><h3>Vegetable curry <sup>(Mi)</sup></h3><p>With rice <sup>(Mi)</sup></p>
        <p class="next"><strong>Preise je 100g:</strong> 0,95&nbsp;&euro; / 1,20&nbsp;&euro; / 1,90&nbsp;&euro;</p></a></li>
        <li class="conditional" ref='[]'><a href="details.php#meal-b"><h3>Fish</h3><p class="next">Preis auf Anfrage</p></a></li></div>
        <div class="essenliste" data-date2="2026-10-15">Geschlossen</div>"""
    private val headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")

    @Test fun publicMenuUsesSourceDatesPricesDietaryLabelsAndPreservesUnknownValues() {
        val result = AugsburgMensaProvider.parse(menuHtml, now)
        assertEquals(listOf(LocalDate(2026,10,14),LocalDate(2026,10,15)), result.days.map { it.date })
        val meal = result.days.first().meals.first()
        assertEquals("Vegetable curry", meal.name)
        assertEquals("With rice", meal.description)
        assertEquals(MealPrices(95,120,190,true), meal.prices)
        assertTrue(meal.vegan && meal.vegetarian)
        assertEquals("", meal.labels.single { it.code == "UNKNOWN" }.name)
        assertNull(result.days.first().meals.last().prices.studentCents)
        assertFalse(result.days.first().meals.last().matches(MealFilter.VEGETARIAN))
        assertEquals(MenuAvailability.CLOSED, result.days.last().availability)
    }

    @Test fun longLabelDescriptionsDoNotOverflowTheParser() {
        // The public menu includes rich label descriptions. Their size must not consume call-stack space.
        val label = buildJsonObject {
            put("name", "Vegan")
            put("description", "Menu information ".repeat(12_000))
        }.toString()
        val html = menuHtml.replace("JSON.parse('{\"name\":\"Vegan\"}')", "JSON.parse('$label')")
        val meal = AugsburgMensaProvider.parse(html, now).days.first().meals.first()
        assertTrue(meal.vegan)
        assertEquals("Vegan", meal.labels.single { it.code == "22" }.name)
    }

    @Test fun manyEscapedCharactersInLabelsDoNotOverflowOrChangeTheirText() {
        val name = "Ingredients: \"milk\", path C:\\labels ".repeat(3_000)
        val label = buildJsonObject { put("name", name) }.toString()
        val html = menuHtml.replace("JSON.parse('{\"name\":\"Milch\"}')", "JSON.parse('$label')")
        val meal = AugsburgMensaProvider.parse(html, now).days.first().meals.first()
        assertEquals(name, meal.labels.single { it.code == "Mi" }.name)
        assertTrue(meal.vegan)
    }

    @Test fun malformedLabelDoesNotHideLaterValidDietaryLabels() {
        val html = menuHtml.replace("<script>", "<script>zusatzstoffe[\"broken\"] = JSON.parse('invalid JSON');")
        val meal = AugsburgMensaProvider.parse(html, now).days.first().meals.first()
        assertTrue(meal.vegan)
        assertEquals("Milch", meal.labels.single { it.code == "Mi" }.name)
    }

    @Test fun malformedMenusAndForeignSourceLinksDoNotBecomeEmptyValidMenus() {
        for (body in listOf("<html>Maintenance</html>", menuHtml.replace("2026-10-14", "bad-date"),
            menuHtml.replace("details.php#meal-a", "https://evil.example/meal"))) {
            assertFailsWith<CampusException> { AugsburgMensaProvider.parse(body, now) }
        }
    }

    @Test fun mensaRequestsNeverReceiveAccountCredentials() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("augsburg.my-mensa.de", request.url.host)
            assertNull(request.headers[HttpHeaders.Cookie]); assertNull(request.headers[HttpHeaders.Authorization])
            respond(menuHtml, headers = headers)
        })
        try { assertEquals(2, AugsburgMensaProvider({ client }, { now }).menu().days.size) } finally { client.close() }
    }

    @Test fun concurrentMenuReadsShareTheCacheAndRestoreOfflineWithPreferences() = runTest {
        val store = MemoryStore()
        var calls = 0
        val provider = object : MensaProvider { override suspend fun menu(): MensaMenu { calls++; delay(50); return AugsburgMensaProvider.parse(menuHtml, now) } }
        val repo = CampusServicesRepository(store, provider, backgroundScope, clock = { now })
        coroutineScope { List(10) { launch { repo.refreshMensa() } }.joinAll() }
        assertEquals(1, calls)
        repo.setPreferences { it.copy(mealFilter = MealFilter.VEGAN, priceGroup = MealPriceGroup.GUEST) }
        val restored = CampusServicesRepository(store, provider, backgroundScope, conditions = { NetworkConditions(connected = false) }, clock = { now })
        restored.refreshMensa(); runCurrent()
        assertEquals(2, restored.mensa.value.menu!!.days.size)
        assertEquals(MealFilter.VEGAN, restored.preferences.value.mealFilter)
        assertEquals(MealPriceGroup.GUEST, restored.preferences.value.priceGroup)
        assertEquals(1, calls)
    }

    @Test fun dataSaverSkipsSpeculationButOpeningMensaCanLoadDataAndFailuresKeepIt() = runTest {
        val store = MemoryStore()
        var fail = false
        var calls = 0
        val provider = object : MensaProvider { override suspend fun menu(): MensaMenu {
            calls++; if (fail) throw CampusException(FailureKind.INVALID_RESPONSE)
            return AugsburgMensaProvider.parse(menuHtml, now)
        } }
        val repo = CampusServicesRepository(store, provider, backgroundScope, settings = { AppSettings(dataSaver = true) }, clock = { now })
        repo.refreshMensa(background = true); assertEquals(0, calls)
        repo.refreshMensa(); runCurrent(); fail = true
        repo.refreshMensa(force = true); runCurrent()
        assertEquals(2, repo.mensa.value.menu!!.days.size)
        assertEquals(FailureKind.INVALID_RESPONSE, repo.mensa.value.error)
    }

    @Test fun corruptFreshMenuCacheIsRevalidated() = runTest {
        val store = MemoryStore()
        store.put("campus:augsburg:public", "mensa", "{}", now)
        val repo = CampusServicesRepository(store, object : MensaProvider {
            override suspend fun menu() = AugsburgMensaProvider.parse(menuHtml, now)
        }, backgroundScope, clock = { now })
        repo.refreshMensa(); runCurrent()
        assertNotNull(repo.mensa.value.menu)
    }

    private fun loan(id: String, date: String = "26.10.2026", position: Int = 0, renew: Boolean = true): String = """
        <tr><td class="account-display-title"><a href="search.do?q=$id"><strong>Book $id</strong></a><br>Author $id<br>Shelf $id</td>
        <td class="account-display-state">Fälligkeitsdatum: $date<br>Central library</td><td>
        ${if (renew) "<a href=\"renewal.do?methodToCall=renewal&amp;item=$position\">Verlängern</a>" else ""}</td></tr>"""
    private fun page(rows: String, next: String = "") = "<div id='account-data'><table class='data'><tr><th class='account-display-title'>Loans</th></tr>$rows</table>$next</div>"
    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine(handler)) { followRedirects = false }

    @Test fun libraryReadsAllLoanPagesAndLimitsItsCookieToTheOfficialOrigin() = runTest {
        var rotated = ""
        val http = client { request ->
            assertEquals("opac.bibliothek.uni-augsburg.de", request.url.host)
            assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("JSESSIONID="))
            val body = when (request.url.parameters["methodToCall"]) {
                "start" -> page("")
                "pos" -> page(loan("second", renew = false))
                else -> page(loan("first"), "<div class='pagination'><a href='userAccount.do?methodToCall=pos&amp;accountTyp=loaned&amp;anzPos=11'>2</a></div>")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType to listOf("text/html"), HttpHeaders.SetCookie to listOf("JSESSIONID=rotated; Path=/TouchPoint; Secure; HttpOnly")))
        }
        try {
            val provider = TouchPointLibraryProvider({ http }, "JSESSIONID=initial", { rotated = it }, { now })
            val result = provider.account()
            assertEquals(listOf("Book first", "Book second"), result.loans.map { it.title })
            assertEquals(LocalDate(2026,10,26), result.loans.first().dueDate)
            assertTrue(result.loans.first().renewable); assertFalse(result.loans.last().renewable)
            assertEquals("JSESSIONID=rotated", rotated)
        } finally { http.close() }
    }

    @Test fun expiredLibrarySessionsAndIncompletePagesAreRejected() = runTest {
        for (body in listOf("<form id='LoginBean'><input type='password'></form>", "<html>Maintenance</html>")) {
            val http = client { respond(body, headers = headers) }
            try { assertFailsWith<CampusException> { TouchPointLibraryProvider({ http }, "JSESSIONID=fixture").account() } }
            finally { http.close() }
        }
    }

    @Test fun libraryPaginationCannotSendCookiesToAnotherServiceOrInvokeWrites() = runTest {
        for (link in listOf("https://evil.example/userAccount.do?methodToCall=pos", "renewal.do?methodToCall=renewal&item=1")) {
            var calls = 0
            val http = client { request ->
                calls++; assertEquals("opac.bibliothek.uni-augsburg.de", request.url.host)
                respond(page(loan("a"), "<div class='pagination'><a href='$link'>2</a></div>"), headers = headers)
            }
            try {
                assertEquals(FailureKind.INVALID_RESPONSE, assertFailsWith<CampusException> { TouchPointLibraryProvider({ http }, "JSESSIONID=fixture").account() }.kind)
                assertEquals(2, calls)
            } finally { http.close() }
        }
    }

    @Test fun aVerifiedEmptyLoanListIsAccepted() = runTest {
        val http = client { respond(page("<tr><td>Keine Daten</td></tr>"), headers = headers) }
        try { assertTrue(TouchPointLibraryProvider({ http }, "JSESSIONID=fixture").account().loans.isEmpty()) } finally { http.close() }
    }

    @Test fun renewalMatchesTheBookAfterReorderingAndReadsTheConfirmedDueDate() = runTest {
        var reads = 0
        var renewed = false
        var writeCount = 0
        val http = client { request ->
            val body = when {
                request.url.encodedPath.endsWith("renewal.do") -> {
                    writeCount++; assertEquals("1", request.url.parameters["item"]); renewed = true; "<div class='message-confirm'>Renewed</div>"
                }
                request.url.parameters["methodToCall"] == "start" -> page("")
                else -> { reads++; page(if (reads == 1) loan("a", position = 0) + loan("b", position = 1)
                    else loan("b", position = 0) + loan("a", if (renewed) "23.11.2026" else "26.10.2026", 1)) }
            }
            respond(body, headers = headers)
        }
        try {
            val provider = TouchPointLibraryProvider({ http }, "JSESSIONID=fixture", clock = { now })
            val account = provider.account()
            val result = provider.renew(account.loans.first().id)
            assertEquals(1, writeCount)
            assertEquals(RenewalStatus.SUCCESS, result.status)
            assertEquals(LocalDate(2026,11,23), result.account!!.loans.single { it.title == "Book a" }.dueDate)
        } finally { http.close() }
    }

    @Test fun uncertainRenewalsAreReconciledWithoutRepeatingTheWrite() = runTest {
        var renewed = false
        var writeCount = 0
        val http = client { request ->
            if (request.url.encodedPath.endsWith("renewal.do")) { writeCount++; renewed = true; throw RuntimeException("Connection lost") }
            respond(if (request.url.parameters["methodToCall"] == "start") page("") else page(loan("a", if (renewed) "23.11.2026" else "26.10.2026")), headers = headers)
        }
        try {
            val provider = TouchPointLibraryProvider({ http }, "JSESSIONID=fixture", clock = { now })
            val id = provider.account().loans.single().id
            assertEquals(RenewalStatus.SUCCESS, provider.renew(id).status)
            assertEquals(1, writeCount)
        } finally { http.close() }
    }

    @Test fun ambiguousCopiesDoNotOfferNativeRenewal() = runTest {
        val http = client { respond(page(loan("a") + loan("a", position = 1)), headers = headers) }
        try {
            val result = TouchPointLibraryProvider({ http }, "JSESSIONID=fixture").account()
            assertEquals(2, result.loans.map { it.id }.distinct().size)
            assertTrue(result.loans.none { it.renewable })
        } finally { http.close() }
    }

    @Test fun libraryDisconnectKeepsMenusAndCoursesAndDiscardsLatePersonalReads() = runTest {
        val store = MemoryStore()
        store.put("digicampus:augsburg:alice", "profile", "saved course profile", now)
        val repo = CampusServicesRepository(store, DemoMensaProvider { now }, backgroundScope, clock = { now })
        repo.refreshMensa()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val provider = object : LibraryProvider {
            override suspend fun account(): LibraryAccount { started.complete(Unit); withContext(NonCancellable) { release.await() }; return LibraryAccount(loans = listOf(LibraryLoan("a","Private book")), fetchedAt = now) }
            override suspend fun renew(loanId: String) = error("No write expected")
        }
        repo.attachLibrary("alice", provider)
        val read = launch { repo.refreshLibrary() }
        started.await(); repo.disconnectLibrary(); release.complete(Unit); read.join(); runCurrent()
        assertFalse(repo.library.value.connected)
        assertNull(store.read(CampusServicesRepository.libraryScope("alice"), "account"))
        assertNotNull(repo.mensa.value.menu)
        assertNotNull(store.read("digicampus:augsburg:alice", "profile"))
    }

    @Test fun libraryAccountSwitchAndOfflineRestoreRemainIsolated() = runTest {
        val store = MemoryStore()
        val repo = CampusServicesRepository(store, DemoMensaProvider { now }, backgroundScope, clock = { now })
        repo.attachLibrary("alice", DemoLibraryProvider { now }); repo.refreshLibrary(); runCurrent()
        assertNotNull(repo.library.value.account)
        val offline = CampusServicesRepository(store, DemoMensaProvider { now }, backgroundScope,
            conditions = { NetworkConditions(connected = false) }, clock = { now })
        offline.attachLibrary("alice", DemoLibraryProvider { now }); offline.refreshLibrary(); runCurrent()
        assertEquals(2, offline.library.value.account!!.loans.size)
        repo.attachLibrary("bob", DemoLibraryProvider { now }); runCurrent()
        assertNull(store.read(CampusServicesRepository.libraryScope("alice"), "account"))
        assertNull(repo.library.value.account)
    }

    @Test fun dueSoonUsesCampusDatesIncludesOverdueAndDropsRenewedOrUnknownDates() {
        val today = LocalDate(2026,10,24)
        val account = LibraryAccount(loans = listOf(
            LibraryLoan("overdue","Overdue",dueDate = today.plus(-1,DateTimeUnit.DAY)),
            LibraryLoan("soon","Soon",dueDate = today.plus(2,DateTimeUnit.DAY)),
            LibraryLoan("later","Later",dueDate = today.plus(3,DateTimeUnit.DAY)),
            LibraryLoan("unknown","Unknown")), fetchedAt = now)
        assertEquals(listOf("overdue","soon"), dueSoonLoans(account,today).map { it.id })
        assertEquals(listOf("overdue"), dueSoonLoans(account.copy(loans = account.loans.map {
            if (it.id == "soon") it.copy(dueDate = today.plus(28,DateTimeUnit.DAY)) else it
        }),today).map { it.id })
    }

    @Test fun deniedRenewalKeepsTheLoanAndDoesNotRetryTheWrite() = runTest {
        var writes = 0
        val http = client { request ->
            val body = if (request.url.encodedPath.endsWith("renewal.do")) { writes++; "<div class='alert'>Not eligible</div>" }
                else if (request.url.parameters["methodToCall"] == "start") page("") else page(loan("a"))
            respond(body,headers = headers)
        }
        try {
            val provider = TouchPointLibraryProvider({http},"JSESSIONID=fixture")
            val id = provider.account().loans.single().id
            val result = provider.renew(id)
            assertEquals(RenewalStatus.DENIED,result.status)
            assertEquals(LocalDate(2026,10,26),result.account!!.loans.single().dueDate)
            assertEquals(1,writes)
        } finally { http.close() }
    }

    @Test fun libraryReadRedirectsRemainOnTheOriginAndPreserveTheRotatedSession() = runTest {
        var reads = 0
        val http = client { request ->
            assertEquals("opac.bibliothek.uni-augsburg.de",request.url.host)
            reads++
            if (reads == 1) respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location to listOf(LIBRARY_ACCOUNT),
                HttpHeaders.SetCookie to listOf("JSESSIONID=new; Path=/TouchPoint")))
            else { assertEquals("JSESSIONID=new",request.headers[HttpHeaders.Cookie]); respond(page(loan("a")),headers = headers) }
        }
        try { assertEquals(1,TouchPointLibraryProvider({http},"JSESSIONID=fixture").account().loans.size) } finally { http.close() }
    }

    @Test fun renewalWithoutAReadbackKeepsSavedLoansButInvalidatesTheirFreshness() = runTest {
        var calls = 0
        val old = LibraryAccount(loans = listOf(LibraryLoan("a","Book",dueDate = LocalDate(2026,10,26),renewable = true)),fetchedAt = now)
        val provider = object : LibraryProvider {
            override suspend fun account(): LibraryAccount { calls++; return old.copy(loans = old.loans.map { it.copy(dueDate = LocalDate(2026,11,23)) }) }
            override suspend fun renew(loanId: String) = LibraryRenewal(RenewalStatus.SUCCESS,null)
        }
        val store = MemoryStore()
        val repo = CampusServicesRepository(store,DemoMensaProvider { now },backgroundScope,clock = { now })
        repo.attachLibrary("a",provider,old); repo.renewLoan("a"); runCurrent()
        assertEquals(LocalDate(2026,10,26),repo.library.value.account!!.loans.single().dueDate)
        assertNotNull(repo.library.value.error)
        repo.refreshLibrary(); runCurrent()
        assertEquals(1,calls)
        assertEquals(LocalDate(2026,11,23),repo.library.value.account!!.loans.single().dueDate)
    }
}
