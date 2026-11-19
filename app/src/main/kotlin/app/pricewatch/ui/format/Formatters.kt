package app.pricewatch.ui.format

import app.pricewatch.data.model.AssetType
import app.pricewatch.data.model.ChartRange
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/** Turns numbers and times into the text shown on screen. Pure functions, in the given locale. */
object Formatters {

  /** A price with as many decimals as its magnitude calls for: `68,161.53`, `1.10214`, `0.00002345`. */
  fun price(value: Double, type: AssetType? = null, locale: Locale = Locale.getDefault()): String =
    fixed(value, priceDecimals(value, type), locale)

  /** The size of a move, without sign, with the precision of the price it belongs to. */
  fun changeAmount(change: Double, price: Double, type: AssetType? = null, locale: Locale = Locale.getDefault()): String =
    fixed(abs(change), priceDecimals(price, type), locale)

  /** The size of a move in percent, without sign: `0.39%`. */
  fun changePercent(percent: Double, locale: Locale = Locale.getDefault()): String =
    fixed(abs(percent), 2, locale) + "%"

  /** Large counts such as traded volume: `39.1M`, `15,141`. */
  fun compact(value: Double, locale: Locale = Locale.getDefault()): String {
    val magnitude = abs(value)
    val (scaled, suffix) =
      when {
        magnitude >= 1e12 -> value / 1e12 to "T"
        magnitude >= 1e9 -> value / 1e9 to "B"
        magnitude >= 1e6 -> value / 1e6 to "M"
        magnitude >= 1e4 -> value / 1e3 to "K"
        else -> value to ""
      }
    val decimals =
      when {
        suffix.isEmpty() -> if (magnitude >= 100 || magnitude == 0.0) 0 else 2
        abs(scaled) >= 100 -> 1
        else -> 2
      }
    return fixed(scaled, decimals, locale) + suffix
  }

  /** A moment as the user would say it: just the time if it is today, otherwise date and time. */
  fun moment(
    millis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
  ): String {
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)
    return if (time.toLocalDate() == today) clock
    else DateTimeFormatter.ofPattern("MMM d", locale).format(time) + ", " + clock
  }

  /** Label under the chart's time axis; coarser for longer ranges. */
  fun axisTime(millis: Long, range: ChartRange, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val time = Instant.ofEpochMilli(millis).atZone(barZone(range, zone))
    val formatter =
      when (range) {
        ChartRange.DAY -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        ChartRange.WEEK -> DateTimeFormatter.ofPattern("EEE d", locale)
        ChartRange.MONTH,
        ChartRange.QUARTER -> DateTimeFormatter.ofPattern("MMM d", locale)
        ChartRange.YEAR -> DateTimeFormatter.ofPattern("MMM yyyy", locale)
        ChartRange.FIVE_YEARS -> DateTimeFormatter.ofPattern("yyyy", locale)
      }
    return formatter.format(time)
  }

  /** The time of the chart point under the user's finger, as precise as the range's bars. */
  fun pointTime(millis: Long, range: ChartRange, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val time = Instant.ofEpochMilli(millis).atZone(barZone(range, zone))
    return when (range) {
      ChartRange.DAY,
      ChartRange.WEEK,
      ChartRange.MONTH ->
        DateTimeFormatter.ofPattern("EEE, MMM d", locale).format(time) +
          ", " +
          DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)
      else -> DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(time)
    }
  }

  /**
   * Intraday bars are shown in the user's own time. Whole-day bars carry a calendar date (stamped
   * at 00:00 UTC), which must not shift to the day before or after in zones far from UTC.
   */
  private fun barZone(range: ChartRange, zone: ZoneId): ZoneId = if (range.intraday) zone else ZoneOffset.UTC

  /** Decimals needed to show [value] meaningfully. Currency pairs conventionally get more. */
  fun priceDecimals(value: Double, type: AssetType? = null): Int {
    val magnitude = abs(value)
    return when {
      type == AssetType.FOREX -> if (magnitude >= 20) 3 else 5
      magnitude >= 1 || magnitude == 0.0 -> 2
      magnitude >= 0.01 -> 4
      magnitude >= 0.0001 -> 6
      else -> 8
    }
  }

  fun fixed(value: Double, decimals: Int, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale)
      .apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
        roundingMode = RoundingMode.HALF_UP
      }
      .format(value)
}
