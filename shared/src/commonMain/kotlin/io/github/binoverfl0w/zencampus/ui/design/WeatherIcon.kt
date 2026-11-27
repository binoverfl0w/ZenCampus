package io.github.binoverfl0w.zencampus.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Weather symbols belong to forecasts; the Today destination retains its agenda symbol. */
@Composable
fun WeatherIcon(code: Int, modifier: Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, isDay: Boolean? = true) {
    val symbol = when (code) {
        0, 1 -> if (isDay == false) Symbol.WEATHER_NIGHT else Symbol.WEATHER_CLEAR
        2, 3 -> Symbol.WEATHER_CLOUD
        45, 48 -> Symbol.WEATHER_FOG
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Symbol.WEATHER_RAIN
        71, 73, 75, 77, 85, 86 -> Symbol.WEATHER_SNOW
        95, 96, 99 -> Symbol.WEATHER_STORM
        else -> null
    }
    if (symbol != null) ZenIcon(symbol, modifier, color)
    else Text("—", modifier, style = MaterialTheme.typography.labelMedium, color = color)
}
