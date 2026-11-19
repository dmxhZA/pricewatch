package app.pricewatch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Neutral, slightly warm grays with a single blue accent. Light and dark are chosen separately
// rather than derived from each other, so both keep readable contrast.

internal val LightColors: ColorScheme =
  lightColorScheme(
    primary = Color(0xFF256ABF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE2FB),
    onPrimaryContainer = Color(0xFF0D366B),
    inversePrimary = Color(0xFF86B6EF),
    secondary = Color(0xFF52514E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE2FB),
    onSecondaryContainer = Color(0xFF0D366B),
    tertiary = Color(0xFF6250D6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4E0FA),
    onTertiaryContainer = Color(0xFF2A1F7A),
    background = Color(0xFFF9F9F7),
    onBackground = Color(0xFF0B0B0B),
    surface = Color(0xFFF9F9F7),
    onSurface = Color(0xFF0B0B0B),
    surfaceVariant = Color(0xFFECEBE6),
    onSurfaceVariant = Color(0xFF52514E),
    surfaceTint = Color(0xFF256ABF),
    inverseSurface = Color(0xFF2C2C2A),
    inverseOnSurface = Color(0xFFF0EFEC),
    error = Color(0xFFD03B3B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDCD9),
    onErrorContainer = Color(0xFF7A1717),
    outline = Color(0xFF898781),
    outlineVariant = Color(0xFFE1E0D9),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCFCFB),
    surfaceDim = Color(0xFFE5E4DF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFCFB),
    surfaceContainer = Color(0xFFF3F3F0),
    surfaceContainerHigh = Color(0xFFECEBE6),
    surfaceContainerHighest = Color(0xFFE5E4DF),
  )

internal val DarkColors: ColorScheme =
  darkColorScheme(
    primary = Color(0xFF6DA7EC),
    onPrimary = Color(0xFF0D366B),
    primaryContainer = Color(0xFF184F95),
    onPrimaryContainer = Color(0xFFCDE2FB),
    inversePrimary = Color(0xFF256ABF),
    secondary = Color(0xFFC3C2B7),
    onSecondary = Color(0xFF1A1A19),
    secondaryContainer = Color(0xFF173A63),
    onSecondaryContainer = Color(0xFFCDE2FB),
    tertiary = Color(0xFF9085E9),
    onTertiary = Color(0xFF1B1452),
    tertiaryContainer = Color(0xFF34298A),
    onTertiaryContainer = Color(0xFFE4E0FA),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFF0EFEC),
    surface = Color(0xFF0D0D0D),
    onSurface = Color(0xFFF0EFEC),
    surfaceVariant = Color(0xFF2A2A28),
    onSurfaceVariant = Color(0xFFC3C2B7),
    surfaceTint = Color(0xFF6DA7EC),
    inverseSurface = Color(0xFFF0EFEC),
    inverseOnSurface = Color(0xFF1A1A19),
    error = Color(0xFFE66767),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFBDCD9),
    outline = Color(0xFF898781),
    outlineVariant = Color(0xFF2C2C2A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353533),
    surfaceDim = Color(0xFF0D0D0D),
    surfaceContainerLowest = Color(0xFF080808),
    surfaceContainerLow = Color(0xFF1A1A19),
    surfaceContainer = Color(0xFF1F1F1E),
    surfaceContainerHigh = Color(0xFF2A2A28),
    surfaceContainerHighest = Color(0xFF353533),
  )

/** Colors with a market meaning, which Material's scheme has no roles for. */
@Immutable
data class MarketColors(
  /** Text and arrows for a price that went up. Always paired with an arrow, never color alone. */
  val gain: Color,
  /** Text and arrows for a price that went down. */
  val loss: Color,
  val chartLine: Color,
  val chartGrid: Color,
  val chartAxis: Color,
  /** Axis labels and other de-emphasized chart text. */
  val chartLabel: Color,
)

internal val LightMarketColors =
  MarketColors(
    gain = Color(0xFF006300),
    loss = Color(0xFFD03B3B),
    chartLine = Color(0xFF2A78D6),
    chartGrid = Color(0xFFE1E0D9),
    chartAxis = Color(0xFFC3C2B7),
    chartLabel = Color(0xFF6F6D68),
  )

internal val DarkMarketColors =
  MarketColors(
    gain = Color(0xFF0CA30C),
    loss = Color(0xFFE66767),
    chartLine = Color(0xFF3987E5),
    chartGrid = Color(0xFF2C2C2A),
    chartAxis = Color(0xFF383835),
    chartLabel = Color(0xFF9E9C95),
  )

val LocalMarketColors = staticCompositionLocalOf { LightMarketColors }
