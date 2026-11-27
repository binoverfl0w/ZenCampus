package io.github.binoverfl0w.zencampus.data.services

import io.github.binoverfl0w.zencampus.model.*
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface CatalogueProvider {
    suspend fun search(query: CatalogueQuery, page: Int = 1): CatalogueResults
    suspend fun record(book: CatalogueBook): CatalogueRecord
}

/** An anonymous cookie jar and serialized search context, independent of the authenticated loan session. */
class TouchPointCatalogueProvider(private val client: () -> HttpClient, private val clock: () -> Long = ::nowMillis) : CatalogueProvider {
    private val mutex = Mutex()
    private val cookies = mutableMapOf<String, String>()
    private val hits = mutableMapOf<String, String>()

    override suspend fun search(query: CatalogueQuery, page: Int): CatalogueResults = withContext(Dispatchers.Default) {
        mutex.withLock { searchLocked(query, page) }
    }

    private suspend fun searchLocked(query: CatalogueQuery, page: Int): CatalogueResults {
        if (query.text.isBlank() || query.text.length > 180 || page !in 1..500) throw CampusException(FailureKind.INVALID_RESPONSE)
        val start = fetch(LIBRARY_START)
        val form = start.selectFirst("#AdvancedSearchForm") ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        val nonce = form.selectFirst("input[name=CSId]")?.attr("value").orEmpty()
        if (nonce.isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
        var doc = fetch(url("search.do", mapOf("View" to "uba", "methodToCall" to "submitButtonCall", "CSId" to nonce,
            "methodToCallParameter" to "submitSearch", "refine" to "false", "tab" to "tab1", "numberOfHits" to "10",
            "searchCategories[0]" to query.field.category, "searchString[0]" to query.text, "submitButtonCall_submitSearch" to "Suchen")))
        doc = resultsDocument(doc)
        val single = bookFromDetail(doc)
        if (single != null) {
            hits.clear()
            if (page != 1) throw CampusException(FailureKind.NOT_FOUND)
            return CatalogueResults(query, page, listOf(single), 1, fetchedAt = clock())
        }
        if (page > 1) {
            val first = doc.selectFirst("a[href*=singleHit.do]") ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            val identifier = Url(safeUrl(first.absUrl("href"))).parameters["identifier"]
                ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            doc = resultsDocument(fetch(url("hitList.do", mapOf("methodToCall" to "pos", "identifier" to identifier,
                "curPos" to ((page - 1) * 10 + 1).toString()))))
        }
        // Only the current public search context's positional links are kept, never serialized into the cache.
        hits.clear()
        val parsed = parseResults(doc, query, page, clock())
        doc.select("table.data tr:has(.results), table.data tr:has(.hitlistMetadata)").forEach { row ->
            val link = row.selectFirst(".title a[href], .hitlistTitle a[href]") ?: return@forEach
            val book = bookFromRow(row) ?: return@forEach
            val target = safeUrl(link.absUrl("href"))
            if (Url(target).encodedPath.substringBefore(';') != "/TouchPoint/singleHit.do") throw CampusException(FailureKind.INVALID_RESPONSE)
            hits[book.id] = target
        }
        return parsed
    }

    private suspend fun resultsDocument(doc: Document): Document = if (doc.select("#RefineHitListForm").isNotEmpty())
        fetch(url("speedHitList.do", mapOf("databaseId" to "2"))) else doc

    override suspend fun record(book: CatalogueBook): CatalogueRecord = withContext(Dispatchers.Default) { mutex.withLock {
        var target = if (book.recordQuery.isNotBlank()) catalogueUrl(book) else hits[book.id]
        if (target == null) {
            val query = if (book.isbn.isNotBlank()) CatalogueQuery(book.isbn, CatalogueField.ISBN)
                else CatalogueQuery(book.title, CatalogueField.TITLE)
            val found = searchLocked(query, 1).books
            // A positional result must be reidentified after process death; never silently open a different edition.
            val match = found.singleOrNull { sameBook(it, book) } ?: throw CampusException(FailureKind.NOT_FOUND)
            target = if (match.recordQuery.isNotBlank()) catalogueUrl(match) else hits[match.id]
                ?: throw CampusException(FailureKind.INVALID_RESPONSE)
        }
        val doc = fetch(target)
        var record = parseRecord(doc, book, clock())
        val holdings = doc.selectFirst("[id^=ajax_holdings_url]")
            ?.let { it.attr("ajaxParameter").ifBlank { it.attr("data-ajaxParameter") } }.orEmpty()
        if (holdings.isNotBlank()) {
            try {
                val node = doc.selectFirst("[id^=ajax_holdings_url]")!!
                node.attr("data-read-url", holdings)
                val copyDoc = fetch(safeUrl(node.absUrl("data-read-url")))
                val (copies, note) = parseCopies(copyDoc)
                record = record.copy(copies = copies, holdingsNote = note, holdingsFetchedAt = clock())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { record = record.copy(holdingsUnavailable = true) }
        } else {
            val (copies, note) = parseCopies(doc, allowMissing = true)
            record = record.copy(copies = copies, holdingsNote = note, holdingsUnavailable = copies.isEmpty() && note.isBlank(),
                holdingsFetchedAt = if (copies.isNotEmpty() || note.isNotBlank()) clock() else null)
        }
        record
    } }

    private suspend fun fetch(value: String, redirects: Int = 0): Document {
        val target = safeUrl(value)
        val response = client().get(target) {
            header(HttpHeaders.Accept, "text/html")
            if (cookies.isNotEmpty()) header(HttpHeaders.Cookie, cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { cookie ->
            val pair = cookie.substringBefore(';').split('=', limit = 2)
            if (pair.size == 2 && pair[0].matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
                if (pair[1].isBlank() || cookie.contains("Max-Age=0", true)) cookies.remove(pair[0]) else cookies[pair[0]] = pair[1]
            }
        }
        if (response.status.value in 300..399) {
            if (redirects >= 3) throw CampusException(FailureKind.INVALID_RESPONSE)
            val next = Ksoup.parse("<a href=\"${response.headers[HttpHeaders.Location].orEmpty().replace("\"", "&quot;")}\"></a>", target)
                .selectFirst("a")!!.absUrl("href")
            return fetch(safeUrl(next), redirects + 1)
        }
        val doc = Ksoup.parse(response.campusHtml(), target)
        if (doc.select("input[type=password]").isNotEmpty()) throw CampusException(FailureKind.AUTHENTICATION)
        return doc
    }

    companion object {
        internal fun url(path: String, parameters: Map<String, String>) = URLBuilder(LIBRARY_BASE + path).apply {
            parameters.forEach { (key, value) -> this.parameters.append(key, value) }
        }.buildString()

        internal fun safeUrl(value: String): String {
            val u = runCatching { Url(value) }.getOrNull() ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            val path = u.encodedPath.substringBefore(';')
            val method = u.parameters["methodToCall"]
            val allowed = when (path) {
                "/TouchPoint/start.do", "/TouchPoint/perma.do", "/TouchPoint/speedHitList.do" -> method == null
                "/TouchPoint/search.do" -> method == "submitButtonCall" && u.parameters["methodToCallParameter"] == "submitSearch"
                "/TouchPoint/hitList.do" -> method == "pos"
                "/TouchPoint/singleHit.do" -> method in setOf("showHit", "showHoldings", "showDocument")
                else -> false
            }
            if (u.protocol != URLProtocol.HTTPS || u.host != "opac.bibliothek.uni-augsburg.de" || u.port != 443 ||
                !u.user.isNullOrBlank() || !u.password.isNullOrBlank() || !allowed) throw CampusException(FailureKind.INVALID_RESPONSE)
            return value
        }

        internal fun parseResults(doc: Document, query: CatalogueQuery, page: Int, now: Long): CatalogueResults {
            val heading = doc.select(".box-header h1, .box-header h2").text()
            val total = Regex("\\(([\\d.,]+)\\)").find(heading)?.groupValues?.get(1)?.filter { it.isDigit() }?.toIntOrNull()
            val rows = doc.select("table.data tr:has(.results), table.data tr:has(.hitlistMetadata)")
            val books = rows.map { bookFromRow(it) ?: throw CampusException(FailureKind.INVALID_RESPONSE) }
            if (books.isEmpty() && total != 0 && doc.select(".nodata").isEmpty()) throw CampusException(FailureKind.INVALID_RESPONSE)
            return CatalogueResults(query, page, books.distinctBy { it.id }, total, total?.let { page * 10 < it } ?: (books.size == 10), now)
        }

        private fun bookFromRow(row: com.fleeksoft.ksoup.nodes.Element): CatalogueBook? {
            val title = row.select(".title, .hitlistTitle").text().trim()
            if (title.isBlank()) return null
            val coins = row.selectFirst(".Z3988")?.attr("title").orEmpty()
            val meta = runCatching { parseQueryString(coins) }.getOrNull()
            val own = row.selectFirst(".results, .hitlistMetadata")?.ownText().orEmpty().trim()
            val year = meta?.get("rft.date").orEmpty().ifBlank { Regex("\\b(1[5-9]\\d{2}|20\\d{2})\\b").findAll(own).lastOrNull()?.value.orEmpty() }.trim('[', ']')
            val author = meta?.get("rft.au").orEmpty().ifBlank { own.removeSuffix(year).trim() }
            val isbn = meta?.get("rft.isbn").orEmpty()
            val edition = meta?.get("rft.edition").orEmpty()
            val id = bookId(title, author, year, isbn, edition)
            return CatalogueBook(id, title, author, year, edition, row.selectFirst(".icon img, .icn")?.attr("alt").orEmpty(), isbn)
        }

        private fun bookId(title: String, author: String, year: String, isbn: String, edition: String): String =
            listOf(title, author, year, isbn, edition).joinToString("|").fold(14695981039346656037uL) { hash, c ->
                (hash xor c.code.toULong()) * 1099511628211uL
            }.toString(16)

        private fun bookFromDetail(doc: Document): CatalogueBook? {
            val fields = doc.select(".titleinfo tr").mapNotNull { row ->
                val cells = row.select("th, td")
                if (cells.size < 2) null else cells.first()!!.text().trim().removeSuffix(":") to cells.last()!!.text().trim()
            }
            fun value(label: String) = fields.firstOrNull { it.first == label }?.second.orEmpty()
            val title = value("Titel")
            if (title.isBlank()) return null
            val author = value("Autor/Mitarbeiter")
            val year = value("Jahr").trim('[', ']')
            val isbn = value("ISBN").replace("-", "").substringBefore(' ')
            val edition = value("Ausgabe")
            val perma = doc.selectFirst("#permalink-link")?.absUrl("href").orEmpty()
            val recordQuery = if (perma.isNotBlank()) Url(safeUrl(perma)).parameters["q"].orEmpty() else ""
            if (recordQuery.isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
            return CatalogueBook(bookId(title, author, year, isbn, edition), title, author, year, edition, isbn = isbn, recordQuery = recordQuery)
        }

        private fun sameBook(a: CatalogueBook, b: CatalogueBook) = a.title.equals(b.title, true) &&
            (b.author.isBlank() || a.author == b.author) && (b.year.isBlank() || a.year == b.year) &&
            (b.edition.isBlank() || a.edition == b.edition) && (b.isbn.isBlank() || a.isbn == b.isbn)

        internal fun parseRecord(doc: Document, book: CatalogueBook, now: Long): CatalogueRecord {
            val fields = doc.select(".titleinfo tr").mapNotNull { row ->
                val cells = row.select("th, td")
                if (cells.size < 2) null else CatalogueProperty(cells.first()!!.text().trim().removeSuffix(":"), cells.last()!!.text().trim())
            }.filter { it.value.isNotBlank() }
            if (fields.none { it.label.equals("Titel", true) }) throw CampusException(FailureKind.INVALID_RESPONSE)
            if (!fields.first { it.label.equals("Titel", true) }.value.equals(book.title, true)) throw CampusException(FailureKind.INVALID_RESPONSE)
            val perma = doc.selectFirst("#permalink-link")?.absUrl("href").orEmpty()
            val recordQuery = if (perma.isNotBlank()) Url(safeUrl(perma)).parameters["q"].orEmpty() else book.recordQuery
            val links = doc.select(".titleinfo a[href]").mapNotNull { a ->
                val target = a.absUrl("href")
                val u = runCatching { Url(target) }.getOrNull()
                if (u?.protocol == URLProtocol.HTTPS && u.user.isNullOrBlank() && u.password.isNullOrBlank())
                    CatalogueLink(a.text().ifBlank { u.host }, target) else null
            }.distinctBy { it.url }
            return CatalogueRecord(book.copy(recordQuery = recordQuery), fields, links = links, fetchedAt = now)
        }

        internal fun parseCopies(doc: Document, allowMissing: Boolean = false): Pair<List<CatalogueCopy>, String> {
            val table = doc.selectFirst("table[id^=holdings]") ?: doc.selectFirst("table.data:has(th)")
            if (table == null) {
                if (allowMissing) return emptyList<CatalogueCopy>() to ""
                throw CampusException(FailureKind.INVALID_RESPONSE)
            }
            val headers = table.select("tr th").map { it.text().lowercase() }
            val note = table.select(".errortext").text()
            val copies = table.select("tr:has(td)").filter { it.select(".errortext").isEmpty() }.map { row ->
                val cells = row.select("td")
                fun value(word: String): String = headers.indexOfFirst { it.contains(word) }.takeIf { it >= 0 }?.let {
                    cells.getOrNull(it)?.clone()?.apply { select("a[href*=vscout], a.qrcode").remove() }?.text()
                }.orEmpty()
                CatalogueCopy(value("signatur"), value("standort"), value("zweigstelle"), value("status"))
            }.filter { it.callNumber.isNotBlank() || it.status.isNotBlank() || it.location.isNotBlank() }
            if (copies.isEmpty() && note.isBlank() && !allowMissing) throw CampusException(FailureKind.INVALID_RESPONSE)
            return copies to note
        }
    }
}

fun catalogueUrl(book: CatalogueBook): String = if (book.recordQuery.isNotBlank())
    TouchPointCatalogueProvider.url("perma.do", mapOf("q" to book.recordQuery, "v" to "uba", "l" to "de"))
else LIBRARY_START
