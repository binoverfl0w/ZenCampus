package io.github.binoverfl0w.zencampus.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.binoverfl0w.zencampus.ui.design.boardColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZenBoardThemeTest {
    @Test fun eachBoardHasItsOwnBackgroundAndAccentInBothAppearances() {
        for (dark in listOf(false, true)) {
            val palettes = CampusBoard.entries.map { boardColorScheme(it, dark) }
            assertEquals(3, palettes.map { it.background }.distinct().size)
            assertEquals(3, palettes.map { it.primary }.distinct().size)
            assertEquals(3, palettes.map { it.primaryContainer }.distinct().size)
        }
    }

    @Test fun boardPalettesKeepReadableTextOnTheirSurfaces() {
        for (dark in listOf(false, true)) for (board in CampusBoard.entries) {
            val p = boardColorScheme(board, dark)
            val pairs = listOf(p.onPrimary to p.primary, p.onPrimaryContainer to p.primaryContainer,
                p.onSecondary to p.secondary, p.onSecondaryContainer to p.secondaryContainer,
                p.onTertiary to p.tertiary, p.onTertiaryContainer to p.tertiaryContainer,
                p.onBackground to p.background, p.onSurface to p.surface,
                p.onSurfaceVariant to p.surfaceVariant, p.onSurfaceVariant to p.background,
                p.onSurfaceVariant to p.surface, p.onSurface to p.surfaceContainerHigh,
                p.onSurface to p.surfaceContainerHighest, p.inverseOnSurface to p.inverseSurface,
                p.onError to p.error, p.onErrorContainer to p.errorContainer)
            for ((foreground, background) in pairs) assertTrue(contrast(foreground, background) >= 4.5,
                "$board / dark=$dark: $foreground on $background must reach 4.5:1 text contrast")
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val light = maxOf(a.luminance(), b.luminance()).toDouble()
        val dark = minOf(a.luminance(), b.luminance()).toDouble()
        return (light + .05) / (dark + .05)
    }
}
