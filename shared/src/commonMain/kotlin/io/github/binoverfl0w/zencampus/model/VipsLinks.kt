package io.github.binoverfl0w.zencampus.model

import io.ktor.http.*

/** Only published, user-selected file links are resolved; exercise folders are never enumerated. */
data class VipsAttachment(val id: String, val url: String, val name: String)

/**
 * Where a Vips attachment goes in its course folder: Vips/<sheet title> when it was opened from a listed sheet's page
 * ([page] carries its assignment_id), otherwise Vips. A file opened from another DigiCampus page (a forum post, a wiki
 * page) goes in the course folder itself.
 */
fun vipsAttachmentFolders(page: String, sheets: List<VipsSheet>): List<String> {
    val path = page.takeIf { it.isNotBlank() }?.let { runCatching { Url(it).encodedPath }.getOrNull() }
    if (path != null && path != "/dispatch.php/vips" && !path.startsWith("/dispatch.php/vips/")) return emptyList()
    fun assignment(url: String) = runCatching { Url(url).parameters.getAll("assignment_id")?.singleOrNull() }.getOrNull()
    val id = assignment(page)
    val title = id?.let { sheets.firstOrNull { sheet -> sheet.id == id || assignment(sheet.sourceUrl) == id }?.title?.takeIf(String::isNotBlank) }
    return listOfNotNull("Vips", title?.let(::materialFolderName))
}

object VipsLinks {
    private val identity = Regex("[A-Za-z0-9_-]{1,100}")
    private fun trusted(value: String): Url? = runCatching { Url(value) }.getOrNull()?.takeIf {
        it.protocol == URLProtocol.HTTPS && it.host == "digicampus.uni-augsburg.de" && it.port == 443 &&
            it.user.isNullOrEmpty() && it.password.isNullOrEmpty() && it.fragment.isEmpty()
    }
    private fun context(url: Url, courseId: String, required: Boolean = false): Boolean {
        val ids = url.parameters.getAll("cid")
        return identity.matches(courseId) && (ids == listOf(courseId) || (!required && ids == null))
    }

    /** Entry points cannot be an exercise, print route, or any GET which records an attempt. */
    fun entry(value: String, courseId: String): String? {
        val url = trusted(value) ?: return null
        if (!context(url, courseId, required = true)) return null
        return when (url.encodedPath.trimEnd('/')) {
            "/dispatch.php/vips/sheets" -> vipsSheetsUrl(courseId)
            "/dispatch.php/vips/solutions", "/dispatch.php/vips/solutions/assignments" -> vipsResultsUrl(courseId)
            "/dispatch.php/vips/solutions/student_assignment_solutions" -> {
                val id = url.parameters.getAll("assignment_id")?.singleOrNull()?.takeIf { it.matches(Regex("[0-9]+")) } ?: return null
                URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/vips/solutions/student_assignment_solutions").apply {
                    parameters.append("assignment_id", id); parameters.append("cid", courseId)
                }.buildString()
            }
            "/dispatch.php/vips/sheets/show_assignment" -> {
                val id = url.parameters.getAll("assignment_id")?.singleOrNull()?.takeIf { it.matches(Regex("[0-9]+")) } ?: return null
                URLBuilder("$DIGICAMPUS_ORIGIN/dispatch.php/vips/sheets/show_assignment").apply {
                    parameters.append("assignment_id", id); parameters.append("cid", courseId)
                }.buildString()
            }
            else -> null
        }
    }

    fun page(value: String, courseId: String): Boolean {
        // Fragments are normal for interactive question navigation.
        val url = trusted(value.substringBefore('#')) ?: return false
        return context(url, courseId) && (url.encodedPath == "/dispatch.php/vips" || url.encodedPath.startsWith("/dispatch.php/vips/"))
    }

    fun attachment(value: String, courseId: String): VipsAttachment? {
        val url = trusted(value) ?: return null
        if (!context(url, courseId)) return null
        val id: String
        val name: String
        val canonical: String
        if (url.encodedPath == "/sendfile.php") {
            id = url.parameters.getAll("file_id")?.singleOrNull()?.takeIf(identity::matches) ?: return null
            if (url.parameters.getAll("type")?.let { it != listOf("0") } == true || url.parameters["zip"] != null) return null
            if (url.parameters.getAll("file_name")?.size?.let { it != 1 } == true) return null
            name = url.parameters["file_name"].orEmpty()
            canonical = URLBuilder("$DIGICAMPUS_ORIGIN/sendfile.php").apply {
                parameters.append("type", "0"); parameters.append("file_id", id)
                if (name.isNotBlank()) parameters.append("file_name", name)
                if (url.parameters["force_download"] == "1") parameters.append("force_download", "1")
                parameters.append("cid", courseId)
            }.buildString()
        } else {
            val match = Regex("/(?:dispatch\\.php/file/)?download/(normal|force_download)/0/([A-Za-z0-9_-]{1,100})/([^/]+)").matchEntire(url.encodedPath)
            val api = Regex("/jsonapi\\.php/v1/file-refs/([A-Za-z0-9_-]{1,100})/content").matchEntire(url.encodedPath)
            if (match == null && api == null) return null
            id = match?.groupValues?.get(2) ?: api!!.groupValues[1]
            name = match?.groupValues?.get(3)?.decodeURLQueryComponent(plusIsSpace = true).orEmpty()
            // The rewrite route's path contains the complete identity and original filename.
            canonical = "$DIGICAMPUS_ORIGIN${url.encodedPath}"
        }
        return VipsAttachment(id, canonical, materialFileName(name.ifBlank { "Attachment" }))
    }
}
