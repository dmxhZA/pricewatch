package app.pricewatch.ui.detail

import app.pricewatch.ui.format.Formatters
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceScaleTest {

  private fun labels(scale: PriceScale) = scale.ticks.map { Formatters.fixed(it, scale.decimals, Locale.US) }

  @Test
  fun `gridlines fall on round prices inside the padded range`() {
    val scale = PriceScale.of(listOf(352.0, 355.4, 358.0, 353.2))

    assertEquals(listOf("352", "354", "356", "358"), labels(scale))
    assertTrue(scale.low < 352.0 && scale.high > 358.0)
  }

  @Test
  fun `small moves of a currency pair get enough decimals`() {
    val scale = PriceScale.of(listOf(1.1018, 1.1023, 1.1028))

    assertEquals(listOf("1.1018", "1.1020", "1.1022", "1.1024", "1.1026", "1.1028"), labels(scale))
  }

  @Test
  fun `large prices use whole numbers`() {
    val scale = PriceScale.of(listOf(67_555.0, 70_245.69, 68_161.53))

    assertEquals(listOf("68,000", "69,000", "70,000"), labels(scale))
  }

  @Test
  fun `tiny prices are still distinguishable`() {
    val scale = PriceScale.of(listOf(0.00002310, 0.00002345, 0.00002392))

    assertEquals(scale.ticks.size, labels(scale).toSet().size)
    assertTrue(scale.ticks.size in 2..7)
  }

  @Test
  fun `a flat line gets a band around it instead of a zero-height axis`() {
    val scale = PriceScale.of(listOf(100.0, 100.0, 100.0))

    assertTrue(scale.low < 100.0 && scale.high > 100.0)
    assertTrue(scale.ticks.isNotEmpty())
    assertTrue(scale.ticks.all { it in scale.low..scale.high })
  }

  @Test
  fun `the number of gridlines stays readable across magnitudes`() {
    listOf(0.0004, 0.37, 1.9, 42.0, 730.0, 15_000.0, 2.4e6).forEach { span ->
      val scale = PriceScale.of(listOf(1000.0, 1000.0 + span))
      assertTrue("span $span gave ${scale.ticks.size} gridlines", scale.ticks.size in 2..7)
      assertEquals("labels for span $span are distinct", scale.ticks.size, labels(scale).toSet().size)
    }
  }
}
