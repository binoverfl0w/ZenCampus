package io.github.binoverfl0w.zencampus.model

import io.github.binoverfl0w.zencampus.data.isTrustedSignInUrl
import io.ktor.http.*

/** What the signed-in in-app browser does with a link it is asked to follow. */
enum class CampusRoute { PAGE, ATTACHMENT, RECONNECT, EXTERNAL, BLOCK }

/**
 * The signed-in in-app DigiCampus browser. It shows any DigiCampus page the student opens, so "Open in DigiCampus"
 * lands on the page it names instead of a signed-out browser. It never follows sign-out, which would end the app's own
 * session, and it opens Vips only at a sheet or result list, because opening an exercise can start an attempt.
 */
object CampusLinks {
    private val identity = Regex("[A-Za-z0-9_-]{1,100}")

    private fun trusted(value: String): Url? = runCatching { Url(value.substringBefore('#')) }.getOrNull()?.takeIf {
        it.protocol == URLProtocol.HTTPS && it.host == "digicampus.uni-augsburg.de" && it.port == 443 &&
            it.user.isNullOrEmpty() && it.password.isNullOrEmpty()
    }

    private fun Url.signOut() = encodedPath.contains("logout", ignoreCase = true)
    private fun Url.signIn() = encodedPath.contains("login", ignoreCase = true) || encodedPath == "/index.php"
    private fun Url.vips() = encodedPath == "/dispatch.php/vips" || encodedPath.startsWith("/dispatch.php/vips/")

    /** The course a DigiCampus address belongs to (its cid), when it names exactly one. */
    fun courseOf(value: String): String? =
        trusted(value)?.parameters?.getAll("cid")?.singleOrNull()?.takeIf(identity::matches)

    /**
     * Where the browser may start for [value], or null when it is not a DigiCampus page it shows. Vips addresses go
     * through [VipsLinks.entry], which only opens sheet and result lists.
     */
    fun entry(value: String, course: String? = null): String? {
        val url = trusted(value) ?: return null
        if (url.signOut() || url.signIn()) return null
        if (url.vips()) return VipsLinks.entry(value.substringBefore('#'), course ?: courseOf(value) ?: return null)
        return value
    }

    /**
     * The course a file link belongs to when the browser hands it to the app's own download: the link's cid, else the
     * course the browser was opened for, else the cid of the page it was on. Null when it is not such a file link.
     */
    fun attachmentCourse(value: String, course: String?, page: String): String? =
        listOfNotNull(courseOf(value), course, courseOf(page)).firstOrNull { VipsLinks.attachment(value, it) != null }

    /**
     * Decided before every top-level navigation. [course] is the course the browser was opened for, [page] the page it
     * is on. The preview account only shows the Vips sample, never the university's site.
     */
    fun navigation(value: String, course: String?, page: String, gesture: Boolean, preview: Boolean): CampusRoute {
        if (attachmentCourse(value, course, page) != null) return CampusRoute.ATTACHMENT
        val url = trusted(value)
        if (preview) return if (course != null && VipsLinks.page(value, course)) CampusRoute.PAGE else CampusRoute.BLOCK
        if (url != null) return when {
            url.signOut() -> CampusRoute.BLOCK
            url.signIn() -> CampusRoute.RECONNECT
            else -> CampusRoute.PAGE
        }
        if (isTrustedSignInUrl(value)) return CampusRoute.RECONNECT
        val external = runCatching { Url(value) }.getOrNull()?.let {
            it.protocol == URLProtocol.HTTPS && it.port == 443 && it.user.isNullOrEmpty() && it.password.isNullOrEmpty()
        } == true
        return if (gesture && external) CampusRoute.EXTERNAL else CampusRoute.BLOCK
    }
}
