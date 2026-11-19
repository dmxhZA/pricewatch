package app.pricewatch.data

import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.Candle
import app.pricewatch.data.model.ChartRange
import app.pricewatch.data.model.DataException
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.data.model.Provider
import app.pricewatch.data.model.Quote
import app.pricewatch.data.model.SearchResult
import app.pricewatch.data.source.MarketDataSource
import app.pricewatch.data.store.JsonStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single place the UI gets market data from. Keeps the latest quote of every asset (also on disk,
 * so the app opens with the last known prices), decides which quotes are worth re-fetching, and
 * routes each asset to the source that provides it.
 */
class MarketRepository(
  sources: List<MarketDataSource>,
  private val quoteStore: JsonStore<Map<String, Quote>>,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val sources: Map<Provider, MarketDataSource> = sources.associateBy { it.provider }

  /** Latest known quote per asset id. */
  val quotes: StateFlow<Map<String, Quote>>
    get() = quoteStore.value

  private val _errors = MutableStateFlow<Map<String, DataException>>(emptyMap())

  /** Why the most recent refresh of an asset failed; cleared when a refresh succeeds. */
  val errors: StateFlow<Map<String, DataException>> = _errors.asStateFlow()

  private val _refreshing = MutableStateFlow<Set<String>>(emptySet())

  /** Ids of assets whose quote is being fetched right now. */
  val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

  /** When a rate-limited source will send its next request, or null if none is held back. */
  val waitingUntil: Flow<Long?> =
    combine(sources.map { it.waitingUntil }) { times -> times.filterNotNull().minOrNull() }

  private class CachedHistory(val candles: List<Candle>, val fetchedAt: Long)

  private val historyCache = LinkedHashMap<Pair<String, ChartRange>, CachedHistory>()

  /**
   * Updates the quotes of [assets] that are due, and returns once all of them have an answer.
   * Safe to call repeatedly and concurrently: assets already being fetched are skipped.
   *
   * @param force the user asked for fresh data, so re-fetch even quotes that are still recent.
   */
  suspend fun refresh(assets: List<Asset>, force: Boolean = false) {
    val now = clock()
    val known = quotes.value
    var due = emptyList<Asset>()
    _refreshing.update { inFlight ->
      due = assets.distinctBy { it.id }.filter { it.id !in inFlight && isDue(known[it.id], now, force) }
      inFlight + due.map { it.id }
    }
    if (due.isEmpty()) return
    // Never-fetched first, then oldest first, so a rate-limited source spends its budget where
    // it matters most.
    val ordered = due.sortedBy { known[it.id]?.fetchedAtMillis ?: Long.MIN_VALUE }
    try {
      coroutineScope {
        for ((provider, group) in ordered.groupBy { it.provider }) {
          launch { refreshFrom(provider, group) }
        }
      }
    } finally {
      // Covers cancellation: whatever is still marked in flight will not get an answer.
      val ids = due.map { it.id }.toSet()
      _refreshing.update { it - ids }
    }
  }

  suspend fun search(provider: Provider, query: String): SearchResult = source(provider).search(query)

  /** Chart data, oldest first. Recent answers are reused so flipping between ranges is free. */
  suspend fun history(asset: Asset, range: ChartRange): List<Candle> {
    val key = asset.id to range
    synchronized(historyCache) {
      historyCache[key]?.takeIf { clock() - it.fetchedAt < historyTtlMillis(range) }?.let { return it.candles }
    }
    val candles = source(asset.provider).history(asset, range)
    synchronized(historyCache) {
      historyCache.remove(key)
      historyCache[key] = CachedHistory(candles, clock())
      while (historyCache.size > MAX_CACHED_HISTORIES) historyCache.remove(historyCache.keys.first())
    }
    return candles
  }

  /** Drops stored quotes of assets that are no longer on the watchlist. */
  fun retainOnly(assetIds: Set<String>) {
    if (quotes.value.keys.any { it !in assetIds }) quoteStore.update { it.filterKeys(assetIds::contains) }
    _errors.update { it.filterKeys(assetIds::contains) }
  }

  private suspend fun refreshFrom(provider: Provider, assets: List<Asset>) {
    val unanswered = assets.associateByTo(LinkedHashMap()) { it.id }
    val failure: DataException? =
      try {
        source(provider).quotes(assets).collect { (asset, result) ->
          unanswered.remove(asset.id)
          record(asset.id, result)
        }
        null
      } catch (e: CancellationException) {
        throw e
      } catch (e: DataException) {
        e
      } catch (e: Exception) {
        DataException(ErrorKind.SERVER, e.message, e)
      }
    // A source that stopped early still owes these an answer.
    val reason = failure ?: DataException(ErrorKind.SERVER, "No answer from ${provider.label}")
    unanswered.keys.toList().forEach { record(it, Result.failure(reason)) }
  }

  private fun record(assetId: String, result: Result<Quote>) {
    result
      .onSuccess { quote ->
        quoteStore.update { it + (assetId to quote) }
        _errors.update { it - assetId }
      }
      .onFailure { error ->
        val known = error as? DataException ?: DataException(ErrorKind.SERVER, error.message, error)
        _errors.update { it + (assetId to known) }
      }
    _refreshing.update { it - assetId }
  }

  private fun isDue(quote: Quote?, now: Long, force: Boolean): Boolean {
    if (quote == null) return true
    val age = now - quote.fetchedAtMillis
    val minimumAge =
      when {
        force -> FORCED_MIN_AGE_MILLIS
        quote.marketOpen == false -> CLOSED_MARKET_MIN_AGE_MILLIS
        else -> MIN_AGE_MILLIS
      }
    // A negative age means the device clock was set back; refresh rather than trust it.
    return age < 0 || age >= minimumAge
  }

  private fun source(provider: Provider): MarketDataSource =
    sources[provider] ?: throw DataException(ErrorKind.SERVER, "No data source for ${provider.label}")

  private fun historyTtlMillis(range: ChartRange): Long =
    when (range) {
      ChartRange.DAY -> 60_000L
      ChartRange.WEEK -> 5 * 60_000L
      ChartRange.MONTH -> 15 * 60_000L
      else -> 60 * 60_000L
    }

  companion object {
    /** A quote younger than this is not fetched again by automatic refreshes. */
    const val MIN_AGE_MILLIS = 20_000L

    /** Even a manual refresh does not re-fetch something that just arrived. */
    const val FORCED_MIN_AGE_MILLIS = 5_000L

    /** While its market is closed a price cannot change, so check back only occasionally. */
    const val CLOSED_MARKET_MIN_AGE_MILLIS = 15 * 60_000L

    private const val MAX_CACHED_HISTORIES = 24
  }
}
