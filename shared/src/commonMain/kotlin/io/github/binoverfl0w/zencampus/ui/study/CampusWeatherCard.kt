package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.data.study.CampusRepository
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.WeatherIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenPanel
import kotlin.math.roundToInt

@Composable
fun CampusWeatherCard(weather: CampusWeather?, loading: Boolean, preview: Boolean, onOpenUrl: (String) -> Unit) {
    val words = LocalWords.current
    val now = LocalNow.current
    ZenPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(words.text("campus_weather"), Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.titleMedium)
            if (weather != null) {
                WeatherIcon(weather.weatherCode, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                Text("${weather.temperatureC.roundToInt()}°C", Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.headlineSmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZenIcon(Symbol.LOCATION, Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary)
            Text(words.text("weather_location"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        if (weather != null) {
            Text("${words.weatherCondition(weather.weatherCode)} · ${words.text("wind")} ${weather.windKmh.roundToInt()} km/h",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(words.text(if (loading) "weather_loading" else "weather_unavailable"),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val today = campusToday(now)
        val hours = weather?.hoursOn(today).orEmpty()
        if (hours.isNotEmpty()) key(today) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Spacer(Modifier.height(10.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(words.text("through_the_day"), Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SmallLabel("${words.text("precipitation_chance")} %")
            }
            Spacer(Modifier.height(8.dp))
            val currentHour = hours.indexOfLast { it.start <= now }.coerceAtLeast(0)
            val state = rememberLazyListState(initialFirstVisibleItemIndex = currentHour)
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val labelStyle = MaterialTheme.typography.labelSmall
            val temperatureStyle = MaterialTheme.typography.titleSmall
            val labelWidth = listOf("23:00", words.text("weather_now"), "100%")
                .maxOf { measurer.measure(it, labelStyle, maxLines = 1).size.width }
            val temperatureWidth = measurer.measure("−40°", temperatureStyle, maxLines = 1).size.width
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val spacing = with(density) { 6.dp.roundToPx() }
                val minimum = maxOf(with(density) { 58.dp.roundToPx() },
                    maxOf(labelWidth, temperatureWidth) + with(density) { 12.dp.roundToPx() })
                val columns = ((constraints.maxWidth + spacing) / (minimum + spacing)).coerceIn(1, 8)
                val available = constraints.maxWidth - spacing * (columns - 1)
                LazyRow(Modifier.fillMaxWidth().testTag("hourly-forecast"), state = state,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(hours, key = { _, hour -> hour.start }) { index, hour ->
                        // Distribute rounding pixels so a complete set of columns ends at the card's inset.
                        val width = with(density) { (available / columns + if (index % columns < available % columns) 1 else 0).toDp() }
                        val current = now >= hour.start && now < hour.start + 3_600_000L
                        val ink = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        Surface(color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.width(width).testTag("hourly-forecast-cell").semantics(mergeDescendants = true) {
                                contentDescription = "${words.time(hour.start)}, ${words.weatherCondition(hour.weatherCode)}, ${hour.temperatureC.roundToInt()}°C" +
                                    hour.precipitationProbability?.let { ", ${words.text("precipitation_chance")} $it%" }.orEmpty()
                            }) {
                            Column(Modifier.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (current) words.text("weather_now") else words.time(hour.start), style = labelStyle, maxLines = 1)
                                WeatherIcon(hour.weatherCode, Modifier.size(20.dp), ink, hour.isDay)
                                Text("${hour.temperatureC.roundToInt()}°", style = temperatureStyle, maxLines = 1)
                                Text(hour.precipitationProbability?.let { "$it%" } ?: "—", style = labelStyle, maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        } else if (weather != null) {
            Spacer(Modifier.height(8.dp))
            SmallLabel(words.text(if (loading) "weather_loading" else "forecast_unavailable"))
        }
        WeatherFooter(weather, preview, onOpenUrl)
    }
}

@Composable
fun WeatherFooter(weather: CampusWeather?, preview: Boolean, onOpenUrl: (String) -> Unit) {
    val words = LocalWords.current
    val now = LocalNow.current
    FlowRow(Modifier.fillMaxWidth().testTag("weather-footer"), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val age = when {
            preview -> words.text("sample_weather")
            weather == null -> words.text("weather_retry")
            !isFresh(weather.observedAt, now, CampusRepository.WEATHER_TTL) -> "${words.text("saved_weather")} · ${words.age(weather.observedAt, now)}"
            else -> "${words.text("last_updated")} ${words.time(weather.observedAt)}"
        }
        Text(age, Modifier.padding(end = 8.dp).testTag("weather-age"), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(buildAnnotatedString {
            withLink(LinkAnnotation.Clickable("source", TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary)),
                linkInteractionListener = { onOpenUrl("https://open-meteo.com/") })) { append("Open-Meteo") }
            append(" · ")
            withLink(LinkAnnotation.Clickable("license", TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary)),
                linkInteractionListener = { onOpenUrl("https://creativecommons.org/licenses/by/4.0/") })) { append("CC BY 4.0") }
        }, Modifier.testTag("weather-source"), style = MaterialTheme.typography.labelSmall)
    }
}
