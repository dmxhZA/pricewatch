package app.pricewatch.ui.common

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import app.pricewatch.AppContainer
import app.pricewatch.PriceWatchApplication
import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.AssetType
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.ui.format.Formatters
import app.pricewatch.ui.theme.marketColors
import kotlinx.coroutines.delay

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as PriceWatchApplication).container

/** Digits of equal width, so prices in a column line up. */
val TextStyle.tabular: TextStyle
  get() = copy(fontFeatureSettings = "tnum")

/**
 * A price move such as `▲ 1.39 (0.39%)`. Direction is carried by the arrow as well as the color,
 * so it reads the same for people who cannot tell the two colors apart.
 */
@Composable
fun ChangeLabel(
  change: Double,
  percent: Double,
  price: Double,
  type: AssetType?,
  modifier: Modifier = Modifier,
  style: TextStyle = LocalTextStyle.current,
  suffix: String? = null,
) {
  val amount = Formatters.changeAmount(change, price, type)
  val share = Formatters.changePercent(percent)
  // Judge direction on what is displayed: a move too small to show should not get an arrow.
  val flat = amount.none { it in '1'..'9' } && share.none { it in '1'..'9' }
  val (arrow, word, color) =
    when {
      flat -> Triple("", "unchanged", MaterialTheme.colorScheme.onSurfaceVariant)
      change > 0 -> Triple("▲ ", "up", MaterialTheme.marketColors.gain)
      else -> Triple("▼ ", "down", MaterialTheme.marketColors.loss)
    }
  val tail = suffix?.let { " $it" }.orEmpty()
  Text(
    text = "$arrow$amount ($share)$tail",
    color = color,
    style = style.tabular,
    maxLines = 1,
    modifier = modifier.semantics { contentDescription = "$word $amount, $share$tail" },
  )
}

/** One line telling the user why a price could not be updated. */
fun ErrorKind.shortMessage(): String =
  when (this) {
    ErrorKind.NETWORK -> "No connection"
    ErrorKind.NO_API_KEY -> "Needs API key"
    ErrorKind.BAD_API_KEY -> "API key rejected"
    ErrorKind.RATE_LIMITED -> "Rate limit reached"
    ErrorKind.QUOTA_EXHAUSTED -> "Daily free quota used up"
    ErrorKind.NOT_FOUND -> "Not available"
    ErrorKind.PLAN_RESTRICTED -> "Not on the free plan"
    ErrorKind.SERVER -> "Data source error"
  }

/** For example `NASDAQ · Stock · USD`. */
fun Asset.details(): String = listOfNotNull(exchange, type.label, currency).joinToString(" · ")

/** Seconds left until [untilMillis], counting down once a second; 0 when the moment has passed. */
@Composable
fun secondsUntil(untilMillis: Long, nowMillis: () -> Long = System::currentTimeMillis): Int {
  val seconds by
    produceState(initialValue = secondsBetween(nowMillis(), untilMillis), untilMillis) {
      while (true) {
        value = secondsBetween(nowMillis(), untilMillis)
        if (value <= 0) break
        delay(1000)
      }
    }
  return seconds
}

private fun secondsBetween(fromMillis: Long, toMillis: Long): Int =
  ((toMillis - fromMillis + 999) / 1000).coerceAtLeast(0).toInt()
