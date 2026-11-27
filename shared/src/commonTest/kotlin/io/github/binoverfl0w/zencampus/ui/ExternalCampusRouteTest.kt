package io.github.binoverfl0w.zencampus.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalCampusRouteTest {
    @Test fun launcherAndWidgetDestinationsAreRecognized() {
        for (route in listOf("today", "calendar", "mensa", "search", "coursework", "library", "accounts")) {
            assertEquals(route, externalCampusRoute(route))
            assertEquals(route, widgetCampusRoute("io.github.binoverfl0w.zencampus.dev://open/$route", "io.github.binoverfl0w.zencampus.dev"))
        }
        assertEquals("library", widgetCampusRoute("io.github.binoverfl0w.zencampus://open/library", "io.github.binoverfl0w.zencampus"))
    }

    @Test fun widgetRoutesRequireTheCorrectSchemeHostAndPath() {
        for (url in listOf(
            "io.github.binoverfl0w.zencampus://open/today", "io.github.binoverfl0w.zencampus.dev://other/today",
            "io.github.binoverfl0w.zencampus.dev://open:80/today", "io.github.binoverfl0w.zencampus.dev://open/today/other",
            "io.github.binoverfl0w.zencampus.dev://open/today?extra=1", "io.github.binoverfl0w.zencampus.dev://open/today#extra",
            "io.github.binoverfl0w.zencampus.dev://open/%74oday", "io.github.binoverfl0w.zencampus.dev://open/settings",
            "today", "",
        )) assertNull(widgetCampusRoute(url, "io.github.binoverfl0w.zencampus.dev"), url)
        assertNull(externalCampusRoute("search#123"))
        assertNull(externalCampusRoute("Search"))
    }
}
