package io.github.binoverfl0w.zencampus.data.services

import io.github.binoverfl0w.zencampus.model.*
import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*

interface MensaProvider {
    suspend fun menu(): MensaMenu
    /** Another Studierendenwerk location; providers without locations only serve the default. */
    suspend fun menu(location: String): MensaMenu =
        if (location == MENSA_DEFAULT_LOCATION) menu() else throw CampusException(FailureKind.NOT_FOUND)
}

internal suspend fun io.ktor.client.statement.HttpResponse.campusHtml(): String {
    if (status.value !in 200..299) throw CampusException(
        if (status.value in setOf(401, 403)) FailureKind.AUTHENTICATION else FailureKind.SERVER, httpStatus = status.value)
    val bytes = bodyAsChannel().readRemaining(1_048_577).readByteArray()
    if (bytes.size > 1_048_576) throw CampusException(FailureKind.INVALID_RESPONSE)
    return bytes.decodeToString()
}

class AugsburgMensaProvider(private val client: () -> HttpClient, private val clock: () -> Long = ::nowMillis) : MensaProvider {
    override suspend fun menu(): MensaMenu = menu(MENSA_DEFAULT_LOCATION)
    override suspend fun menu(location: String): MensaMenu = withContext(Dispatchers.Default) {
        if (MENSA_LOCATIONS.none { it.key == location }) throw CampusException(FailureKind.NOT_FOUND)
        val url = mensaUrl(location)
        val name = MENSA_LOCATIONS.first { it.key == location }.name
        parse(client().get(url).campusHtml(), clock(), url, name).copy(location = location, sourceUrl = url)
    }

    companion object {
        internal fun parse(html: String, now: Long, url: String = MENSA_URL, fallbackName: String = "Mensa Uni Augsburg"): MensaMenu {
            val doc = Ksoup.parse(html, url)
            val json = Json { ignoreUnknownKeys = true }
            val labels = labelNames(html)
            val pages = doc.select("div.essenliste[data-date2]")
            if (pages.isEmpty()) throw CampusException(FailureKind.INVALID_RESPONSE)
            val days = pages.map { page ->
                val date = runCatching { LocalDate.parse(page.attr("data-date2")) }.getOrElse {
                    throw CampusException(FailureKind.INVALID_RESPONSE)
                }
                val categories = page.select("li.groupdivider[data-gid]").associate { it.attr("data-gid") to it.text() }
                val mealNodes = page.select("li.conditional")
                val meals = mealNodes.map { row ->
                    val title = row.selectFirst("h3")?.clone()?.apply { select("sup").remove() }?.text().orEmpty()
                    if (title.isBlank()) throw CampusException(FailureKind.INVALID_RESPONSE)
                    val source = row.selectFirst("a[href]")?.absUrl("href").orEmpty()
                    val url = runCatching { Url(source) }.getOrNull()
                    if (url?.protocol != URLProtocol.HTTPS || url.host != "augsburg.my-mensa.de") throw CampusException(FailureKind.INVALID_RESPONSE)
                    val icons = row.select("img[data-type]").map { it.attr("data-type") }
                    val refs = runCatching { json.decodeFromString<List<String>>(row.attr("ref")) }.getOrDefault(emptyList())
                    val codes = (refs + icons).distinct()
                    val description = row.select("p:not(.next)").map { it.clone().apply { select("sup").remove() }.text() }
                        .filter { it.isNotBlank() }.joinToString(" ")
                    val pricesText = row.select("p.next").text()
                    val amounts = Regex("(\\d+)[,.](\\d{2})\\s*€").findAll(pricesText).map {
                        it.groupValues[1].toInt() * 100 + it.groupValues[2].toInt()
                    }.toList()
                    // Three published columns are student/staff/guest. Unknown shapes keep their price unknown.
                    val prices = if (amounts.size == 3) MealPrices(amounts[0], amounts[1], amounts[2], pricesText.contains("100g", true))
                        else MealPrices(per100g = pricesText.contains("100g", true))
                    val vegan = icons.any { labels[it].equals("Vegan", true) }
                    val vegetarian = vegan || icons.any { labels[it].equals("Vegetarisch", true) }
                    val category = categories[row.attr("data-gid")].orEmpty().replace(Regex("\\s*-\\s*\\d+[,.]\\d{2}\\s*€?\\s*$"), "").trim()
                    MensaMeal(source.substringAfter('#'), title, description, category,
                        prices, vegan, vegetarian, codes.map { MealLabel(it, labels[it].orEmpty()) }, source)
                }.distinctBy { it.id }
                val closed = meals.isEmpty() && page.text().contains("geschlossen", true)
                if (meals.isEmpty() && !closed && !page.text().contains("keine", true)) throw CampusException(FailureKind.INVALID_RESPONSE)
                MensaDay(date, meals, if (closed) MenuAvailability.CLOSED else MenuAvailability.PUBLISHED)
            }.sortedBy { it.date }
            if (days.map { it.date }.distinct().size != days.size) throw CampusException(FailureKind.INVALID_RESPONSE)
            return MensaMenu(pages.firstOrNull()?.selectFirst(".mensatitle")?.text().orEmpty().ifBlank { fallbackName }, days, now)
        }

        private fun labelNames(html: String): Map<String, String> {
            val json = Json { ignoreUnknownKeys = true }
            val assignment = Regex("""zusatzstoffe\["([^"]+)"\]\s*=\s*JSON\.parse\('""")
            val labels = mutableMapOf<String, String>()
            var cursor = 0
            while (cursor < html.length) {
                val match = assignment.find(html, cursor) ?: break
                val start = match.range.last + 1
                cursor = start
                // Iteration keeps stack usage constant for long descriptions and escaped characters.
                // A repeated regex alternation here overflows Java's stack on the public menu.
                while (cursor < html.length && html[cursor] != '\'') {
                    cursor += if (html[cursor] == '\\') 2 else 1
                }
                if (cursor >= html.length) break
                val end = cursor
                cursor++
                if (html.getOrNull(cursor) != ')') continue
                runCatching {
                    json.parseToJsonElement(html.substring(start, end)).jsonObject.getValue("name").jsonPrimitive.content
                }.getOrNull()?.let { labels[match.groupValues[1]] = it }
            }
            return labels
        }
    }
}
