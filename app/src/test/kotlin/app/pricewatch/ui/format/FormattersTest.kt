package app.pricewatch.ui.format

import app.pricewatch.data.model.AssetType
import app.pricewatch.data.model.ChartRange
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
  private val us = Locale.US
  private val newYork = ZoneId.of("America/New_York")

  @Test
  fun `prices get the precision their size calls for`() {
    assertEquals("68,161.53", Formatters.price(68_161.53, AssetType.CRYPTO, us))
    assertEquals("356.27", Formatters.price(356.26999, AssetType.STOCK, us))
    assertEquals("0.5123", Formatters.price(0.51234, AssetType.CRYPTO, us))
    assertEquals("0.001235", Formatters.price(0.0012346, AssetType.CRYPTO, us))
    assertEquals("0.00002345", Formatters.price(0.00002345, AssetType.CRYPTO, us))
    assertEquals("0.00", Formatters.price(0.0, null, us))
  }

  @Test
  fun `currency pairs keep their conventional decimals`() {
    assertEquals("1.10214", Formatters.price(1.10214, AssetType.FOREX, us))
    assertEquals("151.234", Formatters.price(151.2341, AssetType.FOREX, us))
  }

  @Test
  fun `numbers follow the locale`() {
    assertEquals("68.161,53", Formatters.price(68_161.53, null, Locale.GERMANY))
  }

  @Test
  fun `moves are shown unsigned at the precision of the price`() {
    assertEquals("1.39", Formatters.changeAmount(-1.38998, 356.27, AssetType.STOCK, us))
    assertEquals("0.00033", Formatters.changeAmount(-0.00033, 1.10214, AssetType.FOREX, us))
    assertEquals("0.39%", Formatters.changePercent(0.39167722, us))
    assertEquals("2.87%", Formatters.changePercent(-2.868, us))
  }

  @Test
  fun `large counts are abbreviated`() {
    assertEquals("39.10M", Formatters.compact(39_099_985.0, us))
    assertEquals("152.3M", Formatters.compact(152_300_000.0, us))
    assertEquals("15.14K", Formatters.compact(15_141.07, us))
    assertEquals("9,876", Formatters.compact(9_876.0, us))
    assertEquals("12.50", Formatters.compact(12.5, us))
    assertEquals("1.20B", Formatters.compact(1.2e9, us))
    assertEquals("3.40T", Formatters.compact(3.4e12, us))
  }

  @Test
  fun `a moment today shows the time, another day adds the date`() {
    val now = Instant.parse("2026-11-13T21:30:00Z").toEpochMilli()
    val earlierToday = Instant.parse("2026-11-13T20:59:00Z").toEpochMilli()
    val yesterday = Instant.parse("2026-11-12T20:59:00Z").toEpochMilli()

    assertEquals("3:59 PM", Formatters.moment(earlierToday, now, newYork, us).normalizeSpaces())
    assertEquals("Nov 12, 3:59 PM", Formatters.moment(yesterday, now, newYork, us).normalizeSpaces())
  }

  @Test
  fun `chart labels get coarser with the range`() {
    val time = Instant.parse("2026-11-13T20:55:00Z").toEpochMilli()

    assertEquals("3:55 PM", Formatters.axisTime(time, ChartRange.DAY, newYork, us).normalizeSpaces())
    assertEquals("Fri 13", Formatters.axisTime(time, ChartRange.WEEK, newYork, us))
    assertEquals("Nov 13", Formatters.axisTime(time, ChartRange.MONTH, newYork, us))
    assertEquals("Fri, Nov 13, 3:55 PM", Formatters.pointTime(time, ChartRange.DAY, newYork, us).normalizeSpaces())

    val day = Instant.parse("2026-11-13T00:00:00Z").toEpochMilli()
    assertEquals("Nov 13", Formatters.axisTime(day, ChartRange.QUARTER, newYork, us))
    assertEquals("Nov 2026", Formatters.axisTime(day, ChartRange.YEAR, newYork, us))
    assertEquals("2026", Formatters.axisTime(day, ChartRange.FIVE_YEARS, newYork, us))
    assertEquals("Nov 13, 2026", Formatters.pointTime(day, ChartRange.YEAR, newYork, us))
  }

  @Test
  fun `a daily bar keeps its date wherever it is viewed`() {
    // Daily bars are stamped at 00:00 UTC of their calendar date.
    val bar = Instant.parse("2026-11-13T00:00:00Z").toEpochMilli()

    listOf("Pacific/Honolulu", "America/New_York", "Europe/Berlin", "Asia/Tokyo", "Pacific/Auckland").forEach {
      assertEquals(it, "Nov 13, 2026", Formatters.pointTime(bar, ChartRange.YEAR, ZoneId.of(it), us))
      assertEquals(it, "Nov 13", Formatters.axisTime(bar, ChartRange.QUARTER, ZoneId.of(it), us))
    }
  }

  /** Newer JDKs put a narrow no-break space before AM/PM. */
  private fun String.normalizeSpaces() = replace(' ', ' ').replace(' ', ' ')
}
