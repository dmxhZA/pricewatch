package app.pricewatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Follows the system's light/dark setting. */
@Composable
fun PriceWatchTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalMarketColors provides if (darkTheme) DarkMarketColors else LightMarketColors) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = Typography, content = content)
  }
}

/** Market-specific colors of the current theme, alongside `MaterialTheme.colorScheme`. */
val MaterialTheme.marketColors: MarketColors
  @Composable @ReadOnlyComposable get() = LocalMarketColors.current
