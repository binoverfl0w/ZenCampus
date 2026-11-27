package io.github.binoverfl0w.zencampus.data.services

import io.github.binoverfl0w.zencampus.model.*
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

interface LibraryProvider {
    suspend fun account(): LibraryAccount
    suspend fun renew(loanId: String): LibraryRenewal
}

/** The official OPAC owns login. This adapter receives only an origin-scoped verified session cookie. */
class TouchPointLibraryProvider(
    private val client: () -> HttpClient, initialCookie: String,
    private val onCookieChanged: suspend (String) -> Unit = {}, private val clock: () -> Long = ::nowMillis,
) : LibraryProvider {
    private val mutex = Mutex()
    private val cookies = initialCookie.split(';').mapNotNull {
        val p = it.trim().split('=', limit = 2); if (p.size == 2) p[0] to p[1] else null
    }.toMap().toMutableMap()

    private data class LoanAction(val loan: LibraryLoan, val page: String, val renewal: String?)
    private data class AccountRead(val account: LibraryAccount, val actions: List<LoanAction>)
    private data class Page(val loans: List<Pair<LibraryLoan, String?>>, val next: List<String>)

    override suspend fun account(): LibraryAccount = withContext(Dispatchers.Default) { mutex.withLock { readAccount().account } }

    override suspend fun renew(loanId: String): LibraryRenewal = withContext(Dispatchers.Default) { mutex.withLock {
        val before = readAccount()
        val action = before.actions.singleOrNull { it.loan.id == loanId }
            ?: return@withLock LibraryRenewal(RenewalStatus.DENIED, before.account)
        // TouchPoint renewal links are positional: reload the originating page and match the stable item identity again.
        val freshPage = parsePage(fetch(action.page), action.page)
        val target = freshPage.loans.singleOrNull { it.first.id == loanId }
        val renewal = target?.second
            ?: return@withLock LibraryRenewal(RenewalStatus.DENIED, before.account)
        requireUrl(renewal, renewal = true)
        var confirmed = false
        var denied = false
        var message = ""
        try {
            val response = fetch(renewal, renewal = true)
            confirmed = response.select(".message-confirm").isNotEmpty()
            denied = response.select(".message-error, .alert-danger, .alert").isNotEmpty()
            message = response.select(".message-confirm, .message-error, .alert-danger, .alert").text().take(1200)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* An uncertain write is reconciled by reading; it is never automatically sent twice. */ }
        val after = try { readAccount().account } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val changed = after?.loans?.singleOrNull { it.id == loanId }?.dueDate?.let { due ->
            action.loan.dueDate?.let { due > it } == true
        } == true
        LibraryRenewal(when { confirmed || changed -> RenewalStatus.SUCCESS; denied -> RenewalStatus.DENIED; else -> RenewalStatus.UNCERTAIN }, after, message)
    } }

    private suspend fun readAccount(): AccountRead {
        fetch("${LIBRARY_BASE}userAccount.do?methodToCall=start")
        val actions = mutableListOf<LoanAction>()
        val queue = ArrayDeque<String>().apply { add(LIBRARY_ACCOUNT) }
        val visited = mutableSetOf<Int>()
        while (queue.isNotEmpty()) {
            if (visited.size >= 30) throw CampusException(FailureKind.INVALID_RESPONSE)
            val url = queue.removeFirst()
            if (!visited.add(position(url))) continue
            val page = parsePage(fetch(url), url)
            actions += page.loans.map { LoanAction(it.first, url, it.second) }
            page.next.filterNot { position(it) in visited || queue.any { waiting -> position(waiting) == position(it) } }.forEach { queue.add(it) }
        }
        // Multiple copies without a distinct identifier cannot safely use positional renewal.
        val duplicates = actions.groupBy { it.loan.id }.filterValues { it.size > 1 }.keys
        val loans = actions.mapIndexed { index, action ->
            if (action.loan.id in duplicates) action.loan.copy(id = "${action.loan.id}#copy:$index", renewable = false) else action.loan
        }
        return AccountRead(LibraryAccount(loans = loans, fetchedAt = clock(), holds = readHolds(), feesCents = readFees()), actions)
    }

    /** Reservations and orders share the loan list's markup. Any unreadable page makes holds unknown rather than empty. */
    private suspend fun readHolds(): List<LibraryHold>? = try {
        listOf("requested", "ordered").flatMap { kind ->
            val url = "${LIBRARY_BASE}userAccount.do?methodToCall=showAccount&accountTyp=$kind"
            parsePage(fetch(url), url).loans.map { (item, _) ->
                val pickup = Regex("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b").findAll(item.status).mapNotNull {
                    runCatching { LocalDate(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull()
                }.lastOrNull()
                LibraryHold(item.id, item.title, item.details, item.status.trim(), holdIsReady(item.status), if (holdIsReady(item.status)) pickup else null, kind)
            }
        }
    } catch (e: CancellationException) { throw e } catch (e: CampusException) {
        if (e.kind == FailureKind.AUTHENTICATION) throw e else null
    } catch (_: Exception) { null }

    /** The fee overview: an explicit "no fees" state is zero; otherwise every amount on the page is summed. */
    private suspend fun readFees(): Int? = try {
        val doc = fetch("${LIBRARY_BASE}userAccount.do?methodToCall=showAccount&accountTyp=fees")
        val data = doc.select(".data, #account-data")
        val text = data.text()
        when {
            data.isEmpty() -> null
            text.contains("keine Gebühren", true) || text.contains("keine Daten", true) || text.contains("no fees", true) -> 0
            else -> Regex("(\\d+)[,.](\\d{2})\\s*(€|EUR)").findAll(doc.select(".account-display-title, .account-display-state, td").text())
                .map { it.groupValues[1].toInt() * 100 + it.groupValues[2].toInt() }.toList().takeIf { it.isNotEmpty() }?.sum()
        }
    } catch (e: CancellationException) { throw e } catch (e: CampusException) {
        if (e.kind == FailureKind.AUTHENTICATION) throw e else null
    } catch (_: Exception) { null }

    fun sessionCookie(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    private fun position(url: String): Int = Url(url).parameters["anzPos"]?.toIntOrNull() ?: 1

    private suspend fun fetch(url: String, renewal: Boolean = false, redirects: Int = 0): Document {
        requireUrl(url, renewal)
        val response = client().get(url) {
            header(HttpHeaders.Cookie, cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            header(HttpHeaders.Accept, "text/html")
        }
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { header ->
            val pair = header.substringBefore(';').split('=', limit = 2)
            if (pair.size == 2 && pair[0].matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
                if (pair[1].isBlank() || header.contains("Max-Age=0", true)) cookies.remove(pair[0]) else cookies[pair[0]] = pair[1]
            }
        }
        currentCoroutineContext().ensureActive()
        onCookieChanged(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        if (response.status.value in 300..399) {
            if (renewal || redirects >= 3) throw CampusException(FailureKind.AUTHENTICATION)
            val location = response.headers[HttpHeaders.Location].orEmpty()
            val next = when {
                location.startsWith("https://") -> location
                location.startsWith('/') -> LIBRARY_ORIGIN + location
                location.startsWith('?') -> url.substringBefore('?') + location
                else -> LIBRARY_BASE + location
            }
            if (!isAllowed(next, false)) throw CampusException(FailureKind.AUTHENTICATION)
            return fetch(next, false, redirects + 1)
        }
        val doc = Ksoup.parse(response.campusHtml(), url)
        if (doc.select("form#LoginBean, input[type=password]").isNotEmpty()) throw CampusException(FailureKind.AUTHENTICATION)
        return doc
    }

    private fun parsePage(doc: Document, url: String): Page {
        val titleNodes = doc.select(".account-display-title:not(th)")
        val rows = titleNodes.mapNotNull { cell ->
            var row = cell.parent()
            while (row != null && row.tagName() != "tr" && !row.hasClass("row")) row = row.parent()
            row
        }.distinct()
        val noLoans = doc.select(".data, #account-data").text().let {
            it.contains("keine Daten", true) || it.contains("keine Ausleihen", true) || it.contains("keine Medien", true)
        }
        val authenticatedEmpty = doc.select(".data, #account-data").isNotEmpty() && doc.select("a[href]").any { link ->
            val destination = runCatching { Url(link.absUrl("href")) }.getOrNull()
            destination?.host == "opac.bibliothek.uni-augsburg.de" &&
                (destination.encodedPath.substringBefore(';') == "/TouchPoint/logout.do" ||
                    destination.encodedPath.substringBefore(';') == "/TouchPoint/login.do" && destination.parameters["methodToCall"] == "logout")
        }
        if (rows.isEmpty() && !noLoans && !authenticatedEmpty) throw CampusException(FailureKind.INVALID_RESPONSE)
        val loans = rows.map { row ->
            val cell = row.selectFirst(".account-display-title") ?: throw CampusException(FailureKind.INVALID_RESPONSE)
            val title = cell.select("b, strong").text().ifBlank { cell.ownText() }
            if (title.isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
            val details = cell.clone().apply { select("b, strong, a[href*=renewal.do]").remove() }.text().trim()
            val record = row.select("a[href]").mapNotNull { link -> runCatching { Url(link.absUrl("href")).parameters["q"] }.getOrNull() }.firstOrNull().orEmpty()
            val id = if (record.isNotBlank()) "record:$record" else "$title|$details"
            val state = row.select(".account-display-state").text()
            val deadlineLine = Ksoup.parse(row.selectFirst(".account-display-state")?.html()?.substringBefore("<br").orEmpty()).text()
            val dates = Regex("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b").findAll(deadlineLine).mapNotNull {
                runCatching { LocalDate(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull()
            }.toList()
            val renewal = row.select("a[href]").map { it.absUrl("href") }.firstOrNull { isAllowed(it, true) }
            LibraryLoan(id, title, details, dates.lastOrNull(), renewal != null, state) to renewal
        }
        val next = doc.select(".pagination a[href]").filterNot { it.attr("href").startsWith('#') }
            .map { it.absUrl("href").also { link -> requireUrl(link, false) } }.filter { position(it) > 1 }.distinctBy { position(it) }
        return Page(loans, next)
    }

    private fun requireUrl(url: String, renewal: Boolean) {
        if (!isAllowed(url, renewal)) throw CampusException(FailureKind.INVALID_RESPONSE)
    }
    private fun isAllowed(value: String, renewal: Boolean): Boolean = runCatching {
        val url = Url(value)
        val path = url.encodedPath.substringBefore(';')
        url.protocol == URLProtocol.HTTPS && url.host == "opac.bibliothek.uni-augsburg.de" && url.port == 443 &&
            url.user.isNullOrBlank() && url.password.isNullOrBlank() && if (renewal) {
                path == "/TouchPoint/renewal.do" && url.parameters["methodToCall"] == "renewal"
            } else path == "/TouchPoint/userAccount.do" && url.parameters["methodToCall"] in setOf("start", "showAccount", "pos") &&
                url.parameters["accountTyp"].let { it == null || it in setOf("loaned", "requested", "ordered", "fees") }
    }.getOrDefault(false)
}
