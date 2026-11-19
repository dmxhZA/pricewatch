package app.pricewatch.data.source

import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.Candle
import app.pricewatch.data.model.ChartRange
import app.pricewatch.data.model.Provider
import app.pricewatch.data.model.Quote
import app.pricewatch.data.model.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One source of market data. All failures surface as
 * [app.pricewatch.data.model.DataException] so the UI can explain them.
 */
interface MarketDataSource {
  val provider: Provider

  /**
   * Set while a request is held back by the provider's rate limit: the time (epoch millis) at which
   * it will go out. Null when nothing is waiting.
   */
  val waitingUntil: StateFlow<Long?>
    get() = NOT_WAITING

  /** Instruments matching [query]. A blank query returns a few suggestions, or nothing. */
  suspend fun search(query: String): SearchResult

  /**
   * Fetches a quote for each asset. Results arrive as they become available, so a rate-limited
   * provider can deliver a long list in several batches. Every asset gets exactly one result.
   */
  fun quotes(assets: List<Asset>): Flow<Pair<Asset, Result<Quote>>>

  /** Price history for the chart, oldest first. Empty when the provider has none for the range. */
  suspend fun history(asset: Asset, range: ChartRange): List<Candle>

  private companion object {
    val NOT_WAITING: StateFlow<Long?> = MutableStateFlow(null)
  }
}
