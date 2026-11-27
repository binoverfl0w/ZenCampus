package io.github.binoverfl0w.zencampus.android

import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import io.github.binoverfl0w.zencampus.model.ThemeMode

@Composable
internal fun ConfigureSystemBars(window: Window, mode: ThemeMode) {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !dark
    }
}
