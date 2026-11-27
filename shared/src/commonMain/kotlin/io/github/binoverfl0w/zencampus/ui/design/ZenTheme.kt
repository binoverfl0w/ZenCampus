package io.github.binoverfl0w.zencampus.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.binoverfl0w.zencampus.model.Course
import io.github.binoverfl0w.zencampus.model.ThemeMode
import io.github.binoverfl0w.zencampus.resources.Res
import io.github.binoverfl0w.zencampus.resources.manrope_medium
import io.github.binoverfl0w.zencampus.resources.manrope_regular
import io.github.binoverfl0w.zencampus.resources.manrope_semibold
import io.github.binoverfl0w.zencampus.ui.CampusBoard
import io.github.binoverfl0w.zencampus.ui.Words
import org.jetbrains.compose.resources.Font

val LocalWords = staticCompositionLocalOf { Words(false) }
val LocalNow = staticCompositionLocalOf { 0L }
val LocalReducedMotion = staticCompositionLocalOf { false }
private val LocalZenDark = staticCompositionLocalOf { false }

private val Light = lightColorScheme(
    primary = Color(0xFF355F7A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE8EF), onPrimaryContainer = Color(0xFF1C3A4D),
    secondary = Color(0xFF756880), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFECE6F0), onSecondaryContainer = Color(0xFF4D4058),
    tertiary = Color(0xFF82684E), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEEE5D8), onTertiaryContainer = Color(0xFF51402D),
    background = Color(0xFFF3F2EE), onBackground = Color(0xFF1D2428),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1D2428),
    surfaceVariant = Color(0xFFE6E9EA), onSurfaceVariant = Color(0xFF56616A),
    surfaceDim = Color(0xFFDCDDD8), surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F7F4),
    surfaceContainer = Color(0xFFEFEEEA), surfaceContainerHigh = Color(0xFFEAE9E4),
    surfaceContainerHighest = Color(0xFFE3E3DE),
    outline = Color(0xFF7A858C), outlineVariant = Color(0xFFE0E0DA),
    inverseSurface = Color(0xFF2B3237), inverseOnSurface = Color(0xFFF2F3F2),
    inversePrimary = Color(0xFF9EC4DB), surfaceTint = Color(0xFF355F7A),
    error = Color(0xFF9B514B), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF5E3DF), onErrorContainer = Color(0xFF652C27),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9EC4DB), onPrimary = Color(0xFF10202B),
    primaryContainer = Color(0xFF22384A), onPrimaryContainer = Color(0xFFD5E7F2),
    secondary = Color(0xFFCDC0D8), onSecondary = Color(0xFF3B3046),
    secondaryContainer = Color(0xFF43394D), onSecondaryContainer = Color(0xFFEAE2F0),
    tertiary = Color(0xFFD8C1A6), onTertiary = Color(0xFF403224),
    tertiaryContainer = Color(0xFF493E32), onTertiaryContainer = Color(0xFFF0E5D6),
    background = Color(0xFF111518), onBackground = Color(0xFFE7ECEF),
    surface = Color(0xFF1A2025), onSurface = Color(0xFFE7ECEF),
    surfaceVariant = Color(0xFF2B343B), onSurfaceVariant = Color(0xFFADB9C0),
    surfaceDim = Color(0xFF111518), surfaceBright = Color(0xFF30383F),
    surfaceContainerLowest = Color(0xFF0C0F12), surfaceContainerLow = Color(0xFF161B1F),
    surfaceContainer = Color(0xFF1E252A), surfaceContainerHigh = Color(0xFF252D33),
    surfaceContainerHighest = Color(0xFF2E373E),
    outline = Color(0xFF85939D), outlineVariant = Color(0xFF283037),
    inverseSurface = Color(0xFFE7ECEF), inverseOnSurface = Color(0xFF2B3237),
    inversePrimary = Color(0xFF355F7A), surfaceTint = Color(0xFF9EC4DB),
    error = Color(0xFFE7B7AF), onError = Color(0xFF542923),
    errorContainer = Color(0xFF51332F), onErrorContainer = Color(0xFFF5DDD7),
)

private val LibraryLight = Light.copy(
    primary = Color(0xFF46664F), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEADF), onPrimaryContainer = Color(0xFF223F2C),
    secondary = Color(0xFF6B6B5B), secondaryContainer = Color(0xFFECE8DB), onSecondaryContainer = Color(0xFF454638),
    tertiary = Color(0xFF576D75), tertiaryContainer = Color(0xFFDFE9EB), onTertiaryContainer = Color(0xFF344B53),
    background = Color(0xFFF1F4EF), onBackground = Color(0xFF28312B),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF28312B),
    surfaceVariant = Color(0xFFE4EAE2), onSurfaceVariant = Color(0xFF5F6B60),
    surfaceDim = Color(0xFFDDE3DA), surfaceBright = Color(0xFFFBFCF9),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F8F2),
    surfaceContainer = Color(0xFFEBF0E8), surfaceContainerHigh = Color(0xFFE6ECE3), surfaceContainerHighest = Color(0xFFDFE7DC),
    outline = Color(0xFF7F8B80), outlineVariant = Color(0xFFDDE4DA),
    inverseSurface = Color(0xFF2D3730), inverseOnSurface = Color(0xFFF0F4ED),
    inversePrimary = Color(0xFFB8CEBA), surfaceTint = Color(0xFF506B5B),
)
private val LibraryDark = Dark.copy(
    primary = Color(0xFFB8CEBA), onPrimary = Color(0xFF233B2B),
    primaryContainer = Color(0xFF344B3A), onPrimaryContainer = Color(0xFFE6F0E3),
    secondary = Color(0xFFCDCDBA), onSecondary = Color(0xFF35372B),
    secondaryContainer = Color(0xFF46483A), onSecondaryContainer = Color(0xFFECECDD),
    tertiary = Color(0xFFBCCED3), onTertiary = Color(0xFF2B3E45),
    tertiaryContainer = Color(0xFF3D5057), onTertiaryContainer = Color(0xFFE1EBEF),
    background = Color(0xFF111613), onBackground = Color(0xFFE5EDE3),
    surface = Color(0xFF19211C), onSurface = Color(0xFFE5EDE3),
    surfaceVariant = Color(0xFF303D33), onSurfaceVariant = Color(0xFFB8C4B7),
    surfaceDim = Color(0xFF171D19), surfaceBright = Color(0xFF354039),
    surfaceContainerLowest = Color(0xFF121813), surfaceContainerLow = Color(0xFF1C241E),
    surfaceContainer = Color(0xFF252F28), surfaceContainerHigh = Color(0xFF2C3830), surfaceContainerHighest = Color(0xFF344238),
    outline = Color(0xFF88998A), outlineVariant = Color(0xFF27322B),
    inverseSurface = Color(0xFFE5EDE3), inverseOnSurface = Color(0xFF2D3730),
    inversePrimary = Color(0xFF506B5B), surfaceTint = Color(0xFFB8CEBA),
)
private val MensaLight = Light.copy(
    primary = Color(0xFF7A5A39), onPrimary = Color.White,
    primaryContainer = Color(0xFFF2E4D0), onPrimaryContainer = Color(0xFF47321D),
    secondary = Color(0xFF5E705E), secondaryContainer = Color(0xFFE4EADD), onSecondaryContainer = Color(0xFF364A36),
    tertiary = Color(0xFF886258), tertiaryContainer = Color(0xFFF1E1DA), onTertiaryContainer = Color(0xFF573C33),
    background = Color(0xFFF7F1E9), onBackground = Color(0xFF342D25),
    surface = Color(0xFFFFFDFA), onSurface = Color(0xFF342D25),
    surfaceVariant = Color(0xFFEFE5D8), onSurfaceVariant = Color(0xFF706457),
    surfaceDim = Color(0xFFE5DCCD), surfaceBright = Color(0xFFFFFBF6),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFBF5EC),
    surfaceContainer = Color(0xFFF4EBDD), surfaceContainerHigh = Color(0xFFEFE4D5), surfaceContainerHighest = Color(0xFFE9DDCC),
    outline = Color(0xFF938575), outlineVariant = Color(0xFFE0D5C5),
    inverseSurface = Color(0xFF3A3026), inverseOnSurface = Color(0xFFF9F0E4),
    inversePrimary = Color(0xFFDDC3A2), surfaceTint = Color(0xFF806447),
)
private val MensaDark = Dark.copy(
    primary = Color(0xFFDDC3A2), onPrimary = Color(0xFF433222),
    primaryContainer = Color(0xFF51402E), onPrimaryContainer = Color(0xFFF4E8D7),
    secondary = Color(0xFFC3CDB8), onSecondary = Color(0xFF333F2D),
    secondaryContainer = Color(0xFF424C37), onSecondaryContainer = Color(0xFFE9EEDD),
    tertiary = Color(0xFFE0BAB0), onTertiary = Color(0xFF4F342E),
    tertiaryContainer = Color(0xFF563D36), onTertiaryContainer = Color(0xFFF6E3DD),
    background = Color(0xFF18140F), onBackground = Color(0xFFF0E7DC),
    surface = Color(0xFF221C16), onSurface = Color(0xFFF0E7DC),
    surfaceVariant = Color(0xFF40362D), onSurfaceVariant = Color(0xFFCFC0AF),
    surfaceDim = Color(0xFF201B17), surfaceBright = Color(0xFF44392F),
    surfaceContainerLowest = Color(0xFF191410), surfaceContainerLow = Color(0xFF261F19),
    surfaceContainer = Color(0xFF31281F), surfaceContainerHigh = Color(0xFF3A3026), surfaceContainerHighest = Color(0xFF44382C),
    outline = Color(0xFFA39380), outlineVariant = Color(0xFF3A3027),
    inverseSurface = Color(0xFFF0E7DC), inverseOnSurface = Color(0xFF3A3026),
    inversePrimary = Color(0xFF806447), surfaceTint = Color(0xFFDDC3A2),
)

internal fun boardColorScheme(board: CampusBoard, dark: Boolean): ColorScheme = when (board) {
    CampusBoard.STUDY -> if (dark) Dark else Light
    CampusBoard.LIBRARY -> if (dark) LibraryDark else LibraryLight
    CampusBoard.MENSA -> if (dark) MensaDark else MensaLight
}

/** Corner radii every Material component inherits: text fields and menus 12, chips 10, cards 14/20, sheets and pickers 24. */
private val ZenShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun ZenBoardTheme(board: CampusBoard, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = boardColorScheme(board, LocalZenDark.current),
        typography = MaterialTheme.typography, shapes = ZenShapes, content = content)
}

@Composable
fun ZenTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    CompositionLocalProvider(LocalZenDark provides dark) { MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = zenTypography(),
        shapes = ZenShapes,
        content = content,
    ) }
}

@Composable
private fun zenTypography(): Typography {
    val regular = Font(Res.font.manrope_regular, FontWeight.Normal)
    val medium = Font(Res.font.manrope_medium, FontWeight.Medium)
    val semibold = Font(Res.font.manrope_semibold, FontWeight.SemiBold)
    return remember(regular, medium, semibold) {
        val family = FontFamily(regular, medium, semibold)
        fun style(size: Int, height: Int, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) = TextStyle(
            fontFamily = family, fontWeight = weight, fontSize = size.sp,
            lineHeight = height.sp, letterSpacing = spacing.sp,
        )
        Typography(
            displayLarge = style(52, 60, spacing = -1.5f),
            displayMedium = style(44, 52, spacing = -1f),
            displaySmall = style(34, 42, FontWeight.Medium, -.8f),
            headlineLarge = style(30, 38, FontWeight.Medium, -.6f),
            headlineMedium = style(25, 33, FontWeight.Medium, -.4f),
            headlineSmall = style(23, 30, FontWeight.Medium, -.3f),
            titleLarge = style(20, 28, FontWeight.Medium, -.2f),
            titleMedium = style(16, 23, FontWeight.SemiBold, -.1f),
            titleSmall = style(14, 20, FontWeight.SemiBold),
            bodyLarge = style(16, 25),
            bodyMedium = style(14, 22, spacing = .05f),
            bodySmall = style(12, 18, spacing = .1f),
            labelLarge = style(14, 20, FontWeight.SemiBold, .05f),
            labelMedium = style(12, 18, FontWeight.Medium, .2f),
            labelSmall = style(11, 16, FontWeight.Medium, .65f),
        )
    }
}

/** Spacing and shape tokens shared by every screen. */
object Zen {
    val gutter = 20.dp
    val gap = 24.dp
    val radiusSmall = 10.dp
    val radiusMedium = 14.dp
    val radiusLarge = 20.dp
    val radiusHero = 24.dp
    val railWidth = 248.dp
    /** Window width from which the side rail replaces the bottom bar and the edge roller. */
    val wideBreakpoint = 840.dp
    val readingWidth = 760.dp
    val wideContentWidth = 1160.dp
}

/** Desktop provides true so the rail can show its keyboard shortcuts (Ctrl+1/2/3, Ctrl+K). */
val LocalKeyboardShortcuts = staticCompositionLocalOf { false }
val LocalKeyboardShortcutModifier = staticCompositionLocalOf<String?> { null }

/** True when the shell shows the side rail (desktop and large tablets). */
val LocalWideLayout = staticCompositionLocalOf { false }

@Composable
fun isZenDark(): Boolean = LocalZenDark.current

/** A foreground colour and its tinted container, used for course and board identity. */
data class ZenTint(val ink: Color, val soft: Color)

private val CourseTintsLight = listOf(
    ZenTint(Color(0xFF355F7A), Color(0xFFDDE8EF)), ZenTint(Color(0xFF7A5A8C), Color(0xFFECE3F1)),
    ZenTint(Color(0xFF2F7365), Color(0xFFDAEEEA)), ZenTint(Color(0xFF85560F), Color(0xFFF5E8D2)),
    ZenTint(Color(0xFF984656), Color(0xFFF5E0E4)), ZenTint(Color(0xFF55642A), Color(0xFFE8ECD6)),
    ZenTint(Color(0xFF4A5795), Color(0xFFE2E5F4)), ZenTint(Color(0xFF975233), Color(0xFFF4E2D8)),
)
private val CourseTintsDark = listOf(
    ZenTint(Color(0xFF9EC4DB), Color(0xFF22384A)), ZenTint(Color(0xFFCBB3DA), Color(0xFF352A3E)),
    ZenTint(Color(0xFF8FD0C3), Color(0xFF1E3833)), ZenTint(Color(0xFFE9BD7A), Color(0xFF45331A)),
    ZenTint(Color(0xFFEBA9B6), Color(0xFF45252D)), ZenTint(Color(0xFFC3D08F), Color(0xFF2E3519)),
    ZenTint(Color(0xFFAEB8EE), Color(0xFF272E4E)), ZenTint(Color(0xFFEDB194), Color(0xFF45291C)),
)
val COURSE_TINT_COUNT = CourseTintsLight.size

/** Course id → tint slot for the loaded courses. Collisions are resolved so loaded courses stay distinct. */
val LocalCourseHues = staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

fun courseHues(ids: Collection<String>): Map<String, Int> {
    val result = LinkedHashMap<String, Int>()
    val used = BooleanArray(COURSE_TINT_COUNT)
    ids.distinct().sorted().forEach { id ->
        var slot = preferredHue(id)
        if (ids.size <= COURSE_TINT_COUNT) while (used[slot]) slot = (slot + 1) % COURSE_TINT_COUNT
        used[slot] = true
        result[id] = slot
    }
    return result
}

private fun preferredHue(id: String): Int {
    var hash = 5381
    id.forEach { hash = hash * 33 + it.code }
    return (hash and Int.MAX_VALUE) % COURSE_TINT_COUNT
}

@Composable
fun courseTint(courseId: String?): ZenTint {
    val slot = courseId?.let { LocalCourseHues.current[it] ?: preferredHue(it) } ?: 0
    return (if (LocalZenDark.current) CourseTintsDark else CourseTintsLight)[slot]
}

@Composable
fun courseColor(course: Course?): Color = courseTint(course?.id).ink

/** Two-letter course monogram: initials of the meaningful words, or the start of a single word. */
fun courseInitials(title: String): String {
    val skip = setOf("und", "and", "of", "the", "der", "die", "das", "zu", "für", "for", "in", "im", "mit", "with", "von", "to", "a", "an", "&")
    val cleaned = io.github.binoverfl0w.zencampus.data.cleanCourseTitle(title)
    val words = cleaned.split(' ').filter { it.isNotBlank() && it.lowercase() !in skip }
    return when {
        words.isEmpty() -> "·"
        words.size == 1 -> words[0].take(2).replaceFirstChar { it.uppercase() }
        else -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
    }
}

/** Board identity colours, readable from any board's theme (cross-board tiles). */
@Composable
fun boardTint(board: CampusBoard): ZenTint {
    val scheme = boardColorScheme(board, LocalZenDark.current)
    return ZenTint(scheme.primary, scheme.primaryContainer)
}

enum class Tone { NEUTRAL, PRIMARY, WARN, DANGER, SUCCESS }

@Composable
fun toneColors(tone: Tone): ZenTint {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalZenDark.current
    return when (tone) {
        Tone.NEUTRAL -> ZenTint(scheme.onSurfaceVariant, scheme.surfaceContainerHigh)
        Tone.PRIMARY -> ZenTint(scheme.onPrimaryContainer, scheme.primaryContainer)
        Tone.WARN -> if (dark) ZenTint(Color(0xFFE9BD7A), Color(0xFF45331A)) else ZenTint(Color(0xFF85560F), Color(0xFFF5E8D2))
        Tone.DANGER -> ZenTint(scheme.onErrorContainer, scheme.errorContainer)
        Tone.SUCCESS -> if (dark) ZenTint(Color(0xFFA9C8B1), Color(0xFF1F3127)) else ZenTint(Color(0xFF3F6049), Color(0xFFE1EBE2))
    }
}
