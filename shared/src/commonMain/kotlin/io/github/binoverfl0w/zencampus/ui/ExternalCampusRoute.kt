package io.github.binoverfl0w.zencampus.ui

private val externalCampusRoutes = setOf(
    "today", "calendar", "mensa", "search", "officehours", "coursework",
    "library", "accounts", "catalogue", "updates", "inbox",
)

internal fun externalCampusRoute(value: String): String? = value.takeIf { it in externalCampusRoutes }

/** Widget URLs use the host's build-specific scheme and a single, allowlisted path segment. */
internal fun widgetCampusRoute(url: String, scheme: String): String? =
    url.removePrefix("$scheme://open/").takeIf { it != url }?.let(::externalCampusRoute)
