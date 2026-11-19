package app.pricewatch.data

import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.Candle
import app.pricewatch.data.model.ChangeBasis
import app.pricewatch.data.model.ChartRange
import app.pricewatch.data.model.DataException
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.data.model.Provider
import app.pricewatch.data.model.Quote
import app.pricewatch.data.model.SearchResult
import app.pricewatch.data.source.MarketDataSource
import app.pricewatch.data.store.JsonStore
import app.pricewatch.testing.Assets
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MarketRepositoryTest {

  @get:Rule val folder = TemporaryFolder()

  /** A source whose answers the test scripts, and which records what it was asked for. */
  private class ScriptedSource(override val provider: Provider, private val now: () -> Long) : MarketDataSource {
    val quoteRequests = mutableListOf<List<String>>()
    var historyRequests = 0
    var marketOpen = true
    var price = 100.0
    var failure: DataException? = null
    var crashAfter: Int? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun search(query: String) = SearchResult(emptyList())

    override fun quotes(assets: List<Asset>): Flow<Pair<Asset, Result<Quote>>> = flow {
      quoteRequests += assets.map { it.symbol }
      gate?.await()
      assets.forEachIndexed { i, asset ->
        if (i == crashAfter) error("source broke down")
        val result =
          failure?.let { Result.failure(it) }
            ?: Result.success(Quote(price, 0.0, 0.0, ChangeBasis.PREVIOUS_CLOSE, marketOpen = marketOpen, fetchedAtMillis = now()))
        emit(asset to result)
      }
    }

    override suspend fun history(asset: Asset, range: ChartRange): List<Candle> {
      historyRequests++
      return listOf(Candle(now(), price, price, price, price))
    }
  }

  private class Fixture(val repository: MarketRepository, val stocks: ScriptedSource, val crypto: ScriptedSource)

  private fun TestScope.fixture(file: File = File(folder.root, "quotes.json")): Fixture {
    val stocks = ScriptedSource(Provider.TWELVE_DATA) { currentTime }
    val crypto = ScriptedSource(Provider.COINBASE) { currentTime }
    val store =
      JsonStore(
        file,
        MapSerializer(String.serializer(), Quote.serializer()),
        emptyMap(),
        backgroundScope,
        StandardTestDispatcher(testScheduler),
      )
    return Fixture(MarketRepository(listOf(stocks, crypto), store) { currentTime }, stocks, crypto)
  }

  private val all = listOf(Assets.apple, Assets.bitcoin, Assets.euro)

  @Test
  fun `refresh asks each provider for its own assets and stores the quotes`() = runTest {
    val f = fixture()

    f.repository.refresh(all)

    assertEquals(listOf(listOf("AAPL", "EUR/USD")), f.stocks.quoteRequests)
    assertEquals(listOf(listOf("BTC-USD")), f.crypto.quoteRequests)
    assertEquals(all.map { it.id }.toSet(), f.repository.quotes.value.keys)
    assertTrue(f.repository.refreshing.value.isEmpty())
    assertTrue(f.repository.errors.value.isEmpty())
  }

  @Test
  fun `quotes that just arrived are not fetched again`() = runTest {
    val f = fixture()
    f.repository.refresh(all)

    advanceTimeBy(10_000)
    f.repository.refresh(all)
    assertEquals(1, f.stocks.quoteRequests.size)

    // Unless the user asks, and then only once the previous answer is a few seconds old.
    f.repository.refresh(all, force = true)
    assertEquals(2, f.stocks.quoteRequests.size)
    f.repository.refresh(all, force = true)
    assertEquals(2, f.stocks.quoteRequests.size)

    advanceTimeBy(MarketRepository.MIN_AGE_MILLIS)
    f.repository.refresh(all)
    assertEquals(3, f.stocks.quoteRequests.size)
  }

  @Test
  fun `closed markets are checked only occasionally`() = runTest {
    val f = fixture()
    f.stocks.marketOpen = false
    f.repository.refresh(all)

    advanceTimeBy(5 * 60_000)
    f.repository.refresh(all)
    assertEquals("the closed stock market is left alone", 1, f.stocks.quoteRequests.size)
    assertEquals("crypto never closes", 2, f.crypto.quoteRequests.size)

    advanceTimeBy(MarketRepository.CLOSED_MARKET_MIN_AGE_MILLIS)
    f.repository.refresh(all)
    assertEquals(2, f.stocks.quoteRequests.size)
  }

  @Test
  fun `the stalest quotes are requested first`() = runTest {
    val f = fixture()
    val a = Assets.stock("A")
    val b = Assets.stock("B")
    val c = Assets.stock("C")
    f.repository.refresh(listOf(b))
    advanceTimeBy(60_000)
    f.repository.refresh(listOf(a))
    advanceTimeBy(60_000)

    f.repository.refresh(listOf(a, b, c))

    // C has never been fetched, B is older than A.
    assertEquals(listOf("C", "B", "A"), f.stocks.quoteRequests.last())
  }

  @Test
  fun `an overlapping refresh does not request the same asset twice`() = runTest {
    val f = fixture()
    val gate = CompletableDeferred<Unit>()
    f.stocks.gate = gate

    val first = launch { f.repository.refresh(listOf(Assets.apple)) }
    runCurrent()
    assertEquals(setOf(Assets.apple.id), f.repository.refreshing.value)

    // A second caller, for example the screen's timer, while the first is still under way.
    f.repository.refresh(listOf(Assets.apple, Assets.bitcoin))
    assertEquals(1, f.stocks.quoteRequests.size)
    assertEquals(1, f.crypto.quoteRequests.size)

    gate.complete(Unit)
    first.join()
    assertTrue(f.repository.refreshing.value.isEmpty())
    assertEquals(2, f.repository.quotes.value.size)
  }

  @Test
  fun `a failed refresh keeps the last known price and reports why`() = runTest {
    val f = fixture()
    f.repository.refresh(listOf(Assets.apple))
    advanceTimeBy(60_000)

    f.stocks.failure = DataException(ErrorKind.NETWORK)
    f.repository.refresh(listOf(Assets.apple))

    assertEquals(100.0, f.repository.quotes.value.getValue(Assets.apple.id).price, 0.0)
    assertEquals(ErrorKind.NETWORK, f.repository.errors.value.getValue(Assets.apple.id).kind)

    // The next success clears the error.
    advanceTimeBy(60_000)
    f.stocks.failure = null
    f.stocks.price = 101.0
    f.repository.refresh(listOf(Assets.apple))
    assertEquals(101.0, f.repository.quotes.value.getValue(Assets.apple.id).price, 0.0)
    assertNull(f.repository.errors.value[Assets.apple.id])
  }

  @Test
  fun `a source that breaks down midway fails only what it had not answered`() = runTest {
    val f = fixture()
    f.stocks.crashAfter = 1

    f.repository.refresh(listOf(Assets.apple, Assets.euro, Assets.bitcoin))

    assertEquals(setOf(Assets.apple.id, Assets.bitcoin.id), f.repository.quotes.value.keys)
    assertEquals(ErrorKind.SERVER, f.repository.errors.value.getValue(Assets.euro.id).kind)
    assertTrue(f.repository.refreshing.value.isEmpty())
  }

  @Test
  fun `a cancelled refresh leaves nothing marked as in progress`() = runTest {
    val f = fixture()
    f.stocks.gate = CompletableDeferred()

    val job = launch { f.repository.refresh(listOf(Assets.apple)) }
    runCurrent()
    job.cancel()
    job.join()

    assertTrue(f.repository.refreshing.value.isEmpty())
  }

  @Test
  fun `quotes are kept on disk for the next start`() = runTest {
    val file = File(folder.root, "quotes.json")
    fixture(file).repository.refresh(all)
    runCurrent() // lets the background write finish

    val reopened = fixture(file).repository

    assertEquals(all.map { it.id }.toSet(), reopened.quotes.value.keys)

    reopened.retainOnly(setOf(Assets.bitcoin.id))
    assertEquals(setOf(Assets.bitcoin.id), reopened.quotes.value.keys)
  }

  @Test
  fun `chart data is reused while it is recent`() = runTest {
    val f = fixture()

    f.repository.history(Assets.apple, ChartRange.DAY)
    f.repository.history(Assets.apple, ChartRange.DAY)
    assertEquals(1, f.stocks.historyRequests)

    f.repository.history(Assets.apple, ChartRange.YEAR)
    assertEquals(2, f.stocks.historyRequests)

    // Intraday data goes stale within a minute; a year of daily bars does not.
    advanceTimeBy(61_000)
    f.repository.history(Assets.apple, ChartRange.DAY)
    f.repository.history(Assets.apple, ChartRange.YEAR)
    assertEquals(3, f.stocks.historyRequests)
  }
}
