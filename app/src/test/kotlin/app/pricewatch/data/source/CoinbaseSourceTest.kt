package app.pricewatch.data.source

import app.pricewatch.data.model.AssetType
import app.pricewatch.data.model.ChangeBasis
import app.pricewatch.data.model.ChartRange
import app.pricewatch.data.model.DataException
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.data.model.Provider
import app.pricewatch.testing.Assets
import app.pricewatch.testing.FakeHttp
import app.pricewatch.testing.Request
import app.pricewatch.testing.fixture
import app.pricewatch.testing.ok
import java.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing is checked against responses recorded from the live API (see `fixtures/`). */
class CoinbaseSourceTest {

  private val now = Instant.parse("2026-11-14T02:00:00Z").toEpochMilli()

  private fun source(respond: (Request) -> HttpResponse): Pair<CoinbaseSource, FakeHttp> {
    val http = FakeHttp(respond)
    return CoinbaseSource(http, clock = { now }) to http
  }

  private fun catalog(request: Request): HttpResponse =
    when (request.path) {
      "products" -> ok(fixture("cb_products.json"))
      "currencies" -> ok(fixture("cb_currencies.json"))
      else -> HttpResponse(404, fixture("cb_bad.json"))
    }

  @Test
  fun `quote is the last price against the price 24 hours earlier`() = runTest {
    val (source, http) = source { ok(fixture("cb_stats.json")) }

    val quote = source.quotes(listOf(Assets.bitcoin)).toList().single().second.getOrThrow()

    assertEquals(68_161.53, quote.price, 1e-9)
    assertEquals(68_161.53 - 70_174.19, quote.change, 1e-6)
    assertEquals((68_161.53 - 70_174.19) / 70_174.19 * 100, quote.changePercent, 1e-9)
    assertEquals(ChangeBasis.ROLLING_24H, quote.changeBasis)
    assertEquals(70_245.69, quote.high!!, 1e-9)
    assertEquals(67_555.0, quote.low!!, 1e-9)
    assertEquals(15_141.07350592, quote.volume!!, 1e-9)
    assertEquals("USD", quote.currency)
    assertEquals(now, quote.fetchedAtMillis)
    assertEquals("https://api.exchange.coinbase.com/products/BTC-USD/stats", http.requests.single().url)
  }

  @Test
  fun `every asset gets its own answer even when one fails`() = runTest {
    val (source, _) = source { request ->
      if (request.path.startsWith("products/BTC-USD")) ok(fixture("cb_stats.json")) else HttpResponse(404, fixture("cb_bad.json"))
    }

    val results = source.quotes(listOf(Assets.bitcoin, Assets.ether)).toList().toMap()

    assertTrue(results.getValue(Assets.bitcoin).isSuccess)
    assertEquals(ErrorKind.NOT_FOUND, (results.getValue(Assets.ether).exceptionOrNull() as DataException).kind)
  }

  @Test
  fun `being offline is reported per asset`() = runTest {
    val (source, http) = source { ok(fixture("cb_stats.json")) }
    http.offline = true

    val result = source.quotes(listOf(Assets.bitcoin)).toList().single().second

    assertEquals(ErrorKind.NETWORK, (result.exceptionOrNull() as DataException).kind)
  }

  @Test
  fun `search finds pairs by ticker and by coin name, best match first`() = runTest {
    val (source, http) = source(::catalog)

    val byTicker = source.search("btc").assets
    assertEquals("BTC-USD", byTicker.first().symbol)
    // Familiar quote currencies come before the rest.
    assertEquals(listOf("BTC-USD", "BTC-EUR", "BTC-GBP", "BTC-USDT"), byTicker.take(4).map { it.symbol })
    // Pairs that no longer trade are not offered.
    assertFalse(byTicker.any { it.symbol == "BTC-USDC" })

    val bitcoin = byTicker.first()
    assertEquals(Provider.COINBASE, bitcoin.provider)
    assertEquals("Bitcoin", bitcoin.name)
    assertEquals(AssetType.CRYPTO, bitcoin.type)
    assertEquals("USD", bitcoin.currency)
    assertEquals(Assets.bitcoin.id, bitcoin.id)

    assertEquals("SOL-USD", source.search("solana").assets.first().symbol)
    assertEquals("ETH-EUR", source.search("eth/eur").assets.first().symbol)
    assertTrue(source.search("no such coin").assets.isEmpty())

    // The catalog is downloaded once and then searched locally.
    assertEquals(listOf("products", "currencies"), http.requests.map { it.path })
  }

  @Test
  fun `an empty query suggests popular pairs`() = runTest {
    val (source, _) = source(::catalog)

    assertEquals(
      listOf("BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "DOGE-USD", "ADA-USD"),
      source.search("  ").assets.map { it.symbol },
    )
  }

  @Test
  fun `search still works on tickers when coin names cannot be loaded`() = runTest {
    val (source, _) = source { request ->
      if (request.path == "products") ok(fixture("cb_products.json")) else HttpResponse(500, "oops")
    }

    val first = source.search("eth").assets.first()

    assertEquals("ETH-USD", first.symbol)
    assertEquals("ETH", first.name)
  }

  @Test
  fun `recorded candles are parsed oldest first`() = runTest {
    val (source, http) = source { ok(fixture("cb_candles.json")) }

    val candles = source.history(Assets.bitcoin, ChartRange.QUARTER)

    assertEquals(350, candles.size)
    assertTrue(candles.zipWithNext().all { (a, b) -> a.timeMillis < b.timeMillis })
    // Rows are [time, low, high, open, close, volume].
    val last = candles.last()
    assertEquals(1_794_614_400_000L, last.timeMillis)
    assertEquals(68_051.9, last.low, 1e-9)
    assertEquals(68_380.0, last.high, 1e-9)
    assertEquals(68_236.54, last.open, 1e-9)
    assertEquals(68_170.02, last.close, 1e-9)
    val query = http.requests.single().query
    assertEquals("86400", query["granularity"])
    assertEquals(Instant.ofEpochMilli(now).toString(), query["end"])
  }

  @Test
  fun `a long range is fetched in pages that cover it without gaps`() = runTest {
    val day = 86_400_000L
    val (source, http) = source { request ->
      // Answer like the real API: one row per day in the asked window, newest first.
      val start = Instant.parse(request.query.getValue("start")).toEpochMilli()
      val end = Instant.parse(request.query.getValue("end")).toEpochMilli()
      val days = generateSequence(end / day * day) { it - day }.takeWhile { it >= start }.toList()
      assertTrue("at most 300 candles may be asked for at once", days.size <= 300)
      ok(days.joinToString(",", "[", "]") { "[${it / 1000},1,3,2,2.5,10]" })
    }

    val candles = source.history(Assets.bitcoin, ChartRange.YEAR)

    assertEquals(2, http.requests.size)
    assertEquals(365, candles.size)
    assertTrue(candles.zipWithNext().all { (a, b) -> b.timeMillis - a.timeMillis == day })
    assertEquals(now / day * day, candles.last().timeMillis)
  }

  @Test
  fun `an unknown product has no history`() = runTest {
    val (source, _) = source { HttpResponse(404, fixture("cb_bad.json")) }

    val error = runCatching { source.history(Assets.bitcoin, ChartRange.DAY) }.exceptionOrNull()

    assertEquals(ErrorKind.NOT_FOUND, (error as DataException).kind)
  }
}
