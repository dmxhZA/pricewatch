package app.pricewatch.ui.preview

import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.AssetGroup
import app.pricewatch.data.model.AssetType
import app.pricewatch.data.model.Candle
import app.pricewatch.data.model.ChangeBasis
import app.pricewatch.data.model.ChartRange
import app.pricewatch.data.model.Provider
import app.pricewatch.data.model.Quote
import app.pricewatch.ui.detail.ChartUiState
import app.pricewatch.ui.detail.DetailUiState
import app.pricewatch.ui.search.SearchUiState
import app.pricewatch.ui.search.SectionState
import app.pricewatch.ui.watchlist.WatchlistRow
import app.pricewatch.ui.watchlist.WatchlistUiState
import kotlin.math.sin

/** Made-up but realistic values for the screens' design-time previews. */
internal object PreviewData {
  private const val NOW = 1_794_603_540_000L // 2026-11-13 20:59 UTC

  val apple = Asset(Provider.TWELVE_DATA, "AAPL", "Apple Inc.", AssetType.STOCK, "NASDAQ", "USD")
  val sp500 = Asset(Provider.TWELVE_DATA, "SPY", "SPDR S&P 500 ETF Trust", AssetType.ETF, "NYSE", "USD")
  val euro = Asset(Provider.TWELVE_DATA, "EUR/USD", "Euro / US Dollar", AssetType.FOREX, currency = "USD")
  val bitcoin = Asset(Provider.COINBASE, "BTC-USD", "Bitcoin", AssetType.CRYPTO, "Coinbase", "USD")
  val ether = Asset(Provider.COINBASE, "ETH-USD", "Ethereum", AssetType.CRYPTO, "Coinbase", "USD", label = "Ether (long-term)")

  private val appleQuote =
    Quote(
      price = 356.27,
      change = 1.39,
      changePercent = 0.39,
      changeBasis = ChangeBasis.PREVIOUS_CLOSE,
      open = 357.97,
      high = 360.83,
      low = 355.76,
      previousClose = 354.88,
      volume = 39_099_985.0,
      yearLow = 243.42,
      yearHigh = 360.83,
      currency = "USD",
      marketOpen = false,
      asOfMillis = NOW,
      fetchedAtMillis = NOW,
    )

  private val bitcoinQuote =
    Quote(
      price = 68_161.53,
      change = -2_012.66,
      changePercent = -2.87,
      changeBasis = ChangeBasis.ROLLING_24H,
      open = 70_174.19,
      high = 70_245.69,
      low = 67_555.0,
      volume = 15_141.07,
      currency = "USD",
      marketOpen = true,
      asOfMillis = NOW,
      fetchedAtMillis = NOW,
    )

  val watchlist =
    WatchlistUiState(
      rows =
        listOf(
          // In display order, as the store keeps them.
          WatchlistRow(bitcoin, bitcoinQuote),
          WatchlistRow(ether, bitcoinQuote.copy(price = 2_412.9, change = 0.0, changePercent = 0.0)),
          WatchlistRow(apple, appleQuote),
          WatchlistRow(sp500, appleQuote.copy(price = 688.12, change = -3.05, changePercent = -0.44)),
          WatchlistRow(euro, appleQuote.copy(price = 1.10214, change = -0.00033, changePercent = -0.03, marketOpen = true)),
        ),
      updatedAtMillis = NOW,
    )

  val search =
    SearchUiState(
      query = "a",
      crypto = SectionState.Results(listOf(bitcoin, ether.copy(label = null))),
      market = SectionState.Results(listOf(apple, sp500, euro), hiddenCount = 4),
      added = mapOf(apple.id to AssetGroup.STOCKS, bitcoin.id to AssetGroup.CRYPTO),
    )

  private fun candles(count: Int, start: Double, stepMillis: Long): List<Candle> =
    List(count) { i ->
      // A gentle upward drift with two overlaid waves, so the line looks like a market.
      val close = start * (1 + 0.012 * sin(i / 9.0) + 0.006 * sin(i / 2.3) + 0.0003 * i)
      Candle(NOW - (count - 1 - i) * stepMillis, open = close, high = close, low = close, close = close)
    }

  val detail =
    DetailUiState(
      asset = apple,
      quote = appleQuote,
      selectedRange = ChartRange.DAY,
      chart = ChartUiState(candles(78, 352.0, 5 * 60_000L), ChartRange.DAY, loading = false),
    )

  val detailCrypto =
    DetailUiState(
      asset = bitcoin,
      quote = bitcoinQuote,
      selectedRange = ChartRange.MONTH,
      chart = ChartUiState(candles(120, 71_000.0, 6 * 3_600_000L), ChartRange.MONTH, loading = false),
    )
}
