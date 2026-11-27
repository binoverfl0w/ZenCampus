package io.github.binoverfl0w.zencampus.data

import io.ktor.http.Url
import io.ktor.http.URLProtocol

private val microsoftSignInHosts = setOf("login.microsoftonline.com", "login.microsoft.com", "login.live.com", "login.windows.net")

private fun secureSignInHost(value: String): String? {
    if (!value.startsWith("https://", ignoreCase = true)) return null
    val url = runCatching { Url(value) }.getOrNull() ?: return null
    return url.host.lowercase().takeIf {
        url.protocol == URLProtocol.HTTPS && url.port == 443 && url.user == null && url.password == null
    }
}

/** University SSO plus its official Microsoft identity endpoints; API cookies remain origin-bound. */
fun isTrustedSignInUrl(value: String): Boolean {
    val host = secureSignInHost(value) ?: return false
    return host == "uni-augsburg.de" || host.endsWith(".uni-augsburg.de") || host in microsoftSignInHosts
}

internal fun microsoftSignInOrigin(value: String): String? =
    secureSignInHost(value)?.takeIf { it in microsoftSignInHosts }?.let { "https://$it" }

internal fun librarySignInOrigin(value: String): String? =
    io.github.binoverfl0w.zencampus.model.LIBRARY_ORIGIN.takeIf { secureSignInHost(value) == Url(it).host }
