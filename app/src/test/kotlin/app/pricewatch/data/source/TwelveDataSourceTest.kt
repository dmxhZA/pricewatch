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
import app.pricewatch.testing.twelveDataError
import app.pricewatch.testing.twelveDataQuote
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Parsing is checked against responses recorded from the live API (see `fixtures/`). */
@OptIn(ExperimentalCoroutinesApi::class)
class TwelveDataSourceTest {

  private fun TestScope.source(key: String = "test-key", respond: (Request) -> HttpResponse): Pair<TwelveDataSource, FakeHttp> {
    val http = FakeHttp(respond)
    // Virtual time drives both the clock and the rate limiter's waits.
    return TwelveDataSource(http, apiKey = { key }, clock = { currentTime }) to http
  }

  @Test
  fun `single quote is parsed from the recorded response`() = runTest {
    val (source, http) = source { ok(fixture("td_quote_aapl.json")) }

    val (asset, result) = source.quotes(listOf(Assets.apple)).toList().single()
    val quote = result.getOrThrow()

    assertEquals(Assets.apple, asset)
    assertEquals(356.26999, quote.price, 1e-9)
    assertEquals(354.88, quote.previousClose!!, 1e-9)
    assertEquals(1.38998, quote.change, 1e-9)
    assertEquals(0.39167722, quote.changePercent, 1e-9)
    assertEquals(ChangeBasis.PREVIOUS_CLOSE, quote.changeBasis)
    assertEquals(243.42, quote.yearLow!!, 1e-9)
    assertEquals(360.82999, quote.yearHigh!!, 1e-9)
    assertEquals(39_099_985.0, quote.volume!!, 0.0)
    assertEquals("USD", quote.currency)
    assertEquals(false, quote.marketOpen)
    assertEquals(1_794_603_540_000L, quote.asOfMillis)

    val request = http.requests.single()
    assertEquals("https://api.twelvedata.com/quote", request.url)
    // Stocks are pinned to their exchange; the key travels with every request.
    assertEquals("AAPL:NASDAQ", request.query["symbol"])
    assertEquals("test-key", request.query["apikey"])
  }

  @Test
  fun `several quotes go out as one request and are matched back by symbol`() = runTest {
    val (source, http) = source { ok(fixture("td_quote_batch_qualified.json")) }

    val results = source.quotes(listOf(Assets.euro, Assets.apple)).toList().toMap()

    assertEquals(1, http.requests.size)
    assertEquals("EUR/USD,AAPL:NASDAQ", http.requests.single().query["symbol"])
    assertEquals(356.26999, results.getValue(Assets.apple).getOrThrow().price, 1e-9)
    val euro = results.getValue(Assets.euro).getOrThrow()
    assertEquals(1.10212, euro.price, 1e-9)
    assertEquals(-0.031746896, euro.changePercent, 1e-9)
    // The forex quote carries no currency; it falls back to the asset's.
    assertEquals("USD", euro.currency)
    assertEquals(true, euro.marketOpen)
  }

  @Test
  fun `a symbol the provider rejects fails alone`() = runTest {
    val good = Assets.stock("GOOD")
    val bad = Assets.stock("BAD")
    val (source, _) = source {
      ok("""{"GOOD:NYSE":${twelveDataQuote("GOOD", 10.0)},"BAD:NYSE":${twelveDataError(404, "symbol not found")}}""")
    }

    val results = source.quotes(listOf(good, bad)).toList().toMap()

    assertEquals(10.0, results.getValue(good).getOrThrow().price, 0.0)
    assertEquals(ErrorKind.NOT_FOUND, (results.getValue(bad).exceptionOrNull() as DataException).kind)
  }

  @Test
  fun `without an api key nothing is requested`() = runTest {
    val (source, http) = source(key = "  ") { fail("no request expected"); ok("") }

    val result = source.quotes(listOf(Assets.apple)).toList().single().second

    assertEquals(ErrorKind.NO_API_KEY, (result.exceptionOrNull() as DataException).kind)
    assertTrue(http.requests.isEmpty())
    assertEquals(ErrorKind.NO_API_KEY, runCatching { source.search("apple") }.exceptionOrNull().kind())
  }

  @Test
  fun `a rejected key fails every asset without further requests`() = runTest {
    val (source, http) = source { HttpResponse(401, fixture("td_badkey.json")) }
    val assets = (1..10).map { Assets.stock("S$it") }

    val results = source.quotes(assets).toList()

    assertEquals(10, results.size)
    assertTrue(results.all { (it.second.exceptionOrNull() as DataException).kind == ErrorKind.BAD_API_KEY })
    assertEquals(1, http.requests.size)
  }

  @Test
  fun `a long list is spread over minutes to respect the free plan`() = runTest {
    val (source, http) = source { request ->
      val symbols = request.query.getValue("symbol").split(',')
      ok(
        if (symbols.size == 1) twelveDataQuote(symbols[0].substringBefore(':'), 1.0)
        else symbols.joinToString(",", "{", "}") { "\"$it\":${twelveDataQuote(it.substringBefore(':'), 1.0)}" }
      )
    }
    val assets = (1..14).map { Assets.stock("S$it") }

    val arrivals = mutableListOf<Long>()
    source.quotes(assets).collect { (_, result) ->
      result.getOrThrow()
      arrivals += currentTime
    }

    // 8 credits a minute, 2 held back for charts and searches: 6 now, 6 after a minute, 2 after two.
    assertEquals(listOf(6, 6, 2), http.requests.map { it.query.getValue("symbol").split(',').size })
    assertEquals(List(6) { 0L } + List(6) { 60_000L } + List(2) { 120_000L }, arrivals)
    assertNull(source.waitingUntil.value)
  }

  @Test
  fun `a rate limit answer is retried once after the minute has passed`() = runTest {
    var calls = 0
    val (source, _) = source {
      if (calls++ == 0) HttpResponse(429, twelveDataError(429, "You have run out of API credits for the current minute."))
      else ok(twelveDataQuote("AAPL", 5.0))
    }

    val result = source.quotes(listOf(Assets.apple)).toList().single().second

    assertEquals(5.0, result.getOrThrow().price, 0.0)
    assertEquals(2, calls)
    assertEquals(60_000L, currentTime)
  }

  @Test
  fun `an exhausted daily quota is reported as such`() = runTest {
    val (source, http) = source { HttpResponse(429, twelveDataError(429, "You have run out of API credits for the day.")) }

    val result = source.quotes(listOf(Assets.apple)).toList().single().second

    assertEquals(ErrorKind.QUOTA_EXHAUSTED, (result.exceptionOrNull() as DataException).kind)
    assertEquals(1, http.requests.size)
  }

  @Test
  fun `daily history is parsed oldest first`() = runTest {
    val (source, http) = source { ok(fixture("td_ts_aapl.json")) }

    val candles = source.history(Assets.apple, ChartRange.QUARTER)

    assertEquals(10, candles.size)
    assertTrue(candles.zipWithNext().all { (a, b) -> a.timeMillis < b.timeMillis })
    val last = candles.last()
    // A daily bar is stamped at 00:00 UTC of its date.
    assertEquals(Instant.parse("2026-11-13T00:00:00Z").toEpochMilli(), last.timeMillis)
    assertEquals(356.26999, last.close, 1e-9)
    assertEquals(360.82999, last.high, 1e-9)
    val query = http.requests.single().query
    assertEquals("1day", query["interval"])
    assertEquals("UTC", query["timezone"])
    assertEquals("AAPL:NASDAQ", query["symbol"])
  }

  @Test
  fun `the one-day chart of a stock shows only the latest session`() = runTest {
    val (source, _) = source { ok(fixture("td_ts_aapl_5min.json")) }

    val candles = source.history(Assets.apple, ChartRange.DAY)

    // The recording holds five sessions of 78 five-minute bars each.
    assertEquals(78, candles.size)
    val newYork = ZoneId.of("America/New_York")
    assertEquals("2026-11-13T09:30", Instant.ofEpochMilli(candles.first().timeMillis).atZone(newYork).toLocalDateTime().toString())
    assertEquals("2026-11-13T15:55", Instant.ofEpochMilli(candles.last().timeMillis).atZone(newYork).toLocalDateTime().toString())
  }

  @Test
  fun `the one-day chart of a currency pair covers the last 24 hours`() = runTest {
    val (source, _) = source { ok(fixture("td_ts_eurusd_5min.json")) }

    val candles = source.history(Assets.euro, ChartRange.DAY)

    val last = candles.last().timeMillis
    assertEquals(Instant.parse("2026-11-14T02:40:00Z").toEpochMilli(), last)
    assertEquals(last - 24 * 3_600_000L, candles.first().timeMillis)
    assertEquals(289, candles.size)
  }

  @Test
  fun `no data for a period is an empty chart, not an error`() = runTest {
    val (source, _) = source { HttpResponse(400, fixture("td_ts_nodata.json")) }

    assertTrue(source.history(Assets.apple, ChartRange.WEEK).isEmpty())
  }

  @Test
  fun `search keeps what the free plan can quote and counts the rest`() = runTest {
    val (source, http) = source { ok(fixture("td_search_aapl.json")) }

    val result = source.search(" AAPL ")

    val apple = result.assets.first()
    assertEquals(Provider.TWELVE_DATA, apple.provider)
    assertEquals("AAPL", apple.symbol)
    assertEquals("Apple Inc.", apple.name)
    assertEquals(AssetType.STOCK, apple.type)
    assertEquals("NASDAQ", apple.exchange)
    assertEquals("USD", apple.currency)
    assertTrue("listings on paid-plan exchanges are counted", result.hiddenCount > 0)
    assertFalse("and not offered", result.assets.any { it.currency == "ARS" })
    assertEquals(result.assets.size, result.assets.map { it.id }.toSet().size)
    assertEquals("AAPL", http.requests.single().query["symbol"])
    assertEquals("true", http.requests.single().query["show_plan"])
  }

  @Test
  fun `a currency pair found by search is venue-independent`() = runTest {
    val (source, _) = source { ok(fixture("td_search_eurusd.json")) }

    val euro = source.search("EUR/USD").assets.first()

    assertEquals(AssetType.FOREX, euro.type)
    assertNull(euro.exchange)
    assertEquals("USD", euro.currency)
    assertEquals("EUR/USD", TwelveDataSource.requestSymbol(euro))
  }

  @Test
  fun `key usage is read from the provider`() = runTest {
    val (source, _) = source {
      ok("""{"timestamp":"2026-11-14 02:14:56","current_usage":3,"plan_limit":8,"daily_usage":120,"plan_daily_limit":800,"plan_category":"basic"}""")
    }

    assertEquals(ApiUsage(3, 8, 120, 800, "basic"), source.usage())
  }

  @Test
  fun `a page that is not json is a server error`() = runTest {
    val (source, _) = source { HttpResponse(502, "<html>Bad gateway</html>") }

    assertEquals(ErrorKind.SERVER, runCatching { source.search("x") }.exceptionOrNull().kind())
  }

  private fun Throwable?.kind(): ErrorKind? = (this as? DataException)?.kind
}
