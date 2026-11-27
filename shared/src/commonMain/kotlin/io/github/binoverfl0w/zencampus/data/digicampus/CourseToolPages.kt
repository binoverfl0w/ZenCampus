package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.model.*
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.http.*

/** Only navigation and the student listing are read. Assignment/exercise endpoints are never warmed. */
internal object CourseToolPages {
    fun tools(html: String, courseId: String): CourseTools {
        val page = coursePage(html, courseId)
        val sections = page.select("#tabs > li[id^=nav_course_]").filter {
            !it.hasAttr("hidden") && it.attr("aria-hidden") != "true"
        }.mapNotNull { tab ->
            val id = tab.id().removePrefix("nav_course_")
            if (id.isBlank() || id.length > 200 || id.any { it.isISOControl() }) invalid()
            val title = tab.selectFirst(".tab-title")?.text()?.trim().orEmpty()
                .ifBlank { tab.selectFirst("a")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val href = tab.selectFirst("a[href]")?.attr("href").orEmpty()
            CourseTool(id, title.take(300), navigationUrl(href, courseId).orEmpty())
        }.distinctBy { it.id }
        if (sections.none { it.id == "main" }) invalid()
        return CourseTools(sections)
    }

    fun vips(html: String, courseId: String): VipsSheets {
        val page = coursePage(html, courseId)
        val vips = page.select("#tabs > li.current a[href]").any {
            navigationUrl(it.attr("href"), courseId)?.let { url -> CourseTool("vips", "", url).vips } == true
        }
        if (!vips) invalid()
        val sheets = mutableListOf<VipsSheet>()
        var tables = 0
        for (table in page.select("table.default")) {
            val headers = table.select("thead th")
            fun column(key: String) = headers.indexOfFirst { th -> th.select("a[href]").any { a ->
                navigationUrl(a.attr("href"), courseId)?.let { Url(it).parameters["sort"] == key } == true
            } }
            val title = column("title"); val start = column("start"); val end = column("end"); val mode = column("type")
            if (title < 0 || start < 0 || end < 0 || mode < 0) continue
            tables++
            // The status column follows mode; the final column contains actions which are never followed.
            if (headers.size != 6 || listOf(title, start, end, mode) != listOf(0, 1, 2, 3)) invalid()
            for (row in table.select("tbody > tr")) {
                val cells = row.select("> td")
                if (cells.size != headers.size) invalid()
                val link = cells[title].select("a[href]").singleOrNull() ?: invalid()
                val url = navigationUrl(link.attr("href"), courseId)?.let(::Url) ?: invalid()
                if (url.encodedPath.trimEnd('/') != "/dispatch.php/vips/sheets/show_assignment") invalid()
                val id = url.parameters.getAll("assignment_id")?.singleOrNull()?.takeIf { Regex("[0-9]{1,20}").matches(it) } ?: invalid()
                val name = link.text().trim().takeIf { it.isNotBlank() } ?: invalid()
                // Store a canonical display link, dropping access codes and unrelated query parameters.
                val source = URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment").apply {
                    parameters.append("assignment_id", id); parameters.append("cid", courseId)
                }.buildString()
                val kind = link.select("img[src]").mapNotNull { icon ->
                    when (icon.attr("src").substringBefore('?').substringBefore('#').substringAfterLast('/').substringBeforeLast('.')) {
                        "assignment-practice" -> VipsKind.PRACTICE
                        "assignment-quiz" -> VipsKind.SELFTEST
                        "assignment-exam" -> VipsKind.EXAM
                        else -> null
                    }
                }.distinct().singleOrNull() ?: VipsKind.UNKNOWN
                sheets += VipsSheet(id, courseId, name.take(1000), source, cells[start].text().trim(),
                    cells[end].text().trim(), cells[mode].text().trim(), cells[4].text().trim(),
                    table.selectFirst("caption")?.text()?.trim().orEmpty(), kind)
            }
        }
        val empty = page.select(".messagebox_info, .messagebox.info").any {
            it.text().trim().trimEnd('.').lowercase() in setOf(
                "es gibt aktuell keine laufenden aufgabenblätter", "there are currently no active assignments",
                "there are currently no active worksheets")
        }
        if (tables == 0 && !empty) invalid()
        if (sheets.isEmpty() && !empty) invalid()
        if (sheets.map { it.id }.distinct().size != sheets.size) invalid()
        return VipsSheets(sheets)
    }

    fun vipsResults(html: String, courseId: String): List<VipsResult> {
        val page = coursePage(html, courseId)
        val table = page.select("table").firstOrNull { it.selectFirst("caption")?.text()?.trim() in setOf("Freigegebene Ergebnisse", "Released results", "Published results") }
        if (table == null) {
            if (page.select(".messagebox_info, .messagebox.info").any { it.text().trim().trimEnd('.').lowercase() in
                    setOf("es ist kein beendetes aufgabenblatt vorhanden", "there are no completed assignments", "there is no finished task collection") }) return emptyList()
            invalid()
        }
        val results = table.select("tbody tr").mapNotNull { row ->
            val link = row.selectFirst("td a[href]") ?: return@mapNotNull null
            val safe = navigationUrl(link.attr("href"), courseId) ?: invalid()
            val entry = VipsLinks.entry(safe, courseId) ?: invalid()
            val url = Url(entry)
            if (url.encodedPath != "/dispatch.php/vips/solutions/student_assignment_solutions") invalid()
            val id = url.parameters["assignment_id"] ?: invalid()
            val cells = row.select("td")
            if (cells.size < 7 || link.text().isBlank()) invalid()
            VipsResult(id, courseId, link.text().trim(), entry, cells[1].text(), cells[2].text(),
                "${cells[3].text()} / ${cells[5].text()}")
        }
        if (results.map { it.id }.distinct().size != results.size) invalid()
        return results
    }

    private fun coursePage(html: String, courseId: String): Document {
        val page = Ksoup.parse(html)
        if (page.selectFirst("input[type=password]") != null) throw CampusException(FailureKind.AUTHENTICATION)
        val tabs = page.selectFirst("#tabs") ?: invalid()
        // Confirm context from server navigation, never from user-authored course content or a <base> tag.
        if (tabs.select("li[id^=nav_course_] > a[href]").none {
            navigationUrl(it.attr("href"), courseId, requireContext = true) != null
        }) invalid()
        return page
    }

    internal fun navigationUrl(href: String, courseId: String, requireContext: Boolean = false): String? {
        if (href.isBlank() || href.startsWith("//") || href.any { it.isISOControl() }) return null
        val resolved = when {
            href.startsWith("/") -> DIGICAMPUS_ORIGIN + href
            href.startsWith("dispatch.php/") || href.startsWith("plugins.php/") -> "$DIGICAMPUS_ORIGIN/$href"
            else -> href
        }
        val url = runCatching { Url(resolved) }.getOrNull() ?: return null
        if (url.protocol != URLProtocol.HTTPS || url.host != "digicampus.uni-augsburg.de" || url.port != 443 ||
            !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() ||
            !(url.encodedPath.startsWith("/dispatch.php/") || url.encodedPath.startsWith("/plugins.php/"))) return null
        val contexts = url.parameters.getAll("cid")
        if (requireContext && contexts != listOf(courseId)) return null
        if (contexts != null && contexts != listOf(courseId)) return null
        return URLBuilder(url).apply { parameters["cid"] = courseId; fragment = "" }.buildString()
    }

    private fun invalid(): Nothing = throw CampusException(FailureKind.INVALID_RESPONSE)
}
