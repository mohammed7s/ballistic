// Raw greyscale theme (docs/UX_SYNTHESIS.md §5–§6, approved by the owner on 8 Nov 2026). Neutral
// greys that follow the phone's light/dark setting; no dynamic colour. The single accent (mint)
// is deliberately not a Material colour role, so no stock component can pick it up: it is read
// only by LiveMarker and means exactly one thing, "Ballistic is acting on this phone right now".
// Warnings are carried by inversion, words and rules, never by colour (§5.3).

package app.ballistic.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFE6E6E6), onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF2E2E2E), onPrimaryContainer = Color(0xFFE6E6E6),
    secondary = Color(0xFF969696), onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF202020), onSecondaryContainer = Color(0xFFE6E6E6),
    tertiary = Color(0xFF969696), onTertiary = Color(0xFF111111),
    tertiaryContainer = Color(0xFF202020), onTertiaryContainer = Color(0xFFE6E6E6),
    background = Color(0xFF111111), onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF111111), onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF202020), onSurfaceVariant = Color(0xFF969696),
    surfaceTint = Color(0xFF111111),
    surfaceDim = Color(0xFF111111), surfaceBright = Color(0xFF262626),
    surfaceContainerLowest = Color(0xFF0B0B0B), surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF181818), surfaceContainerHigh = Color(0xFF202020),
    surfaceContainerHighest = Color(0xFF262626),
    inverseSurface = Color(0xFFE6E6E6), inverseOnSurface = Color(0xFF111111),
    inversePrimary = Color(0xFF111111),
    outline = Color(0xFF6B6B6B), outlineVariant = Color(0xFF2E2E2E),
    error = Color(0xFFE6E6E6), onError = Color(0xFF111111),
    errorContainer = Color(0xFFE6E6E6), onErrorContainer = Color(0xFF111111),
    scrim = Color(0xFF000000),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF151515), onPrimary = Color(0xFFE4E4E4),
    primaryContainer = Color(0xFFC9C9C9), onPrimaryContainer = Color(0xFF151515),
    secondary = Color(0xFF545454), onSecondary = Color(0xFFE4E4E4),
    secondaryContainer = Color(0xFFF6F6F6), onSecondaryContainer = Color(0xFF151515),
    tertiary = Color(0xFF545454), onTertiary = Color(0xFFE4E4E4),
    tertiaryContainer = Color(0xFFF6F6F6), onTertiaryContainer = Color(0xFF151515),
    background = Color(0xFFE4E4E4), onBackground = Color(0xFF151515),
    surface = Color(0xFFE4E4E4), onSurface = Color(0xFF151515),
    surfaceVariant = Color(0xFFF6F6F6), onSurfaceVariant = Color(0xFF545454),
    surfaceTint = Color(0xFFE4E4E4),
    surfaceDim = Color(0xFFDADADA), surfaceBright = Color(0xFFF6F6F6),
    surfaceContainerLowest = Color(0xFFF6F6F6), surfaceContainerLow = Color(0xFFEFEFEF),
    surfaceContainer = Color(0xFFECECEC), surfaceContainerHigh = Color(0xFFF6F6F6),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF151515), inverseOnSurface = Color(0xFFE4E4E4),
    inversePrimary = Color(0xFFE4E4E4),
    outline = Color(0xFF7A7A7A), outlineVariant = Color(0xFFC9C9C9),
    error = Color(0xFF151515), onError = Color(0xFFE4E4E4),
    errorContainer = Color(0xFF151515), onErrorContainer = Color(0xFFE4E4E4),
    scrim = Color(0xFF000000),
)

/** Radius 0 on surfaces, boxes, sheets and dialogs; 2dp on buttons and fields. No pills. */
val BallisticShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

val Mono: FontFamily = FontFamily.Monospace

private val BallisticTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = t.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
        labelLarge = t.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.copy(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 0.06.em, fontFeatureSettings = "tnum"),
    )
}

/** Outside the Material scheme on purpose: the accent and the monospace value styles. */
@Immutable
data class BallisticExtras(
    /** The live marker only. */
    val live: Color,
    /** Values, times, sizes, hosts. */
    val mono: TextStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum"),
    /** Meta lines and raw records. */
    val monoSmall: TextStyle = TextStyle(fontFamily = Mono, fontSize = 10.5.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum"),
)

/** Dark-mode mint, sampled from the logo chevron and softened; the light value keeps 3:1. */
val LiveDark = Color(0xFF2EF5A0)
val LiveLight = Color(0xFF0A8F58)

val LocalBallisticExtras = staticCompositionLocalOf { BallisticExtras(live = LiveDark) }

object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val hairline = 1.dp
    val emphasis = 2.dp
    val warnRule = 3.dp
    val liveMarker = 8.dp
    val touch = 48.dp
}

@Composable
fun BallisticTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalBallisticExtras provides BallisticExtras(live = if (dark) LiveDark else LiveLight)) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            shapes = BallisticShapes,
            typography = BallisticTypography,
            content = content,
        )
    }
}
