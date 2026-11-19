package app.pricewatch.data.source

import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.AssetType
import app.pricewatch.data.model.Candle
import app.pricewatch.data.model.ChangeBasis
import app.pricewatch.data.model.ChartRange
import app.pricewatch.data.model.DataException
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.data.model.Provider
import app.pricewatch.data.model.Quote
import app.pricewatch.data.model.SearchResult
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Coinbase Exchange public market data (https://docs.cdp.coinbase.com/exchange): live crypto prices
 * with no account or key. Symbols are Coinbase product ids such as `BTC-USD`.
 */
class CoinbaseSource(
  private val http: Http,
  private val clock: () -> Long = System::currentTimeMillis,
) : MarketDataSource {

  override val provider = Provider.COINBASE

  private class Catalog(val assets: List<Asset>, val loadedAt: Long)

  private val catalogMutex = Mutex()
  private var catalog: Catalog? = null

  /** The public endpoints allow roughly ten requests a second per address; stay well below. */
  private val requestSlots = Semaphore(MAX_PARALLEL_REQUESTS)

  override suspend fun search(query: String): SearchResult {
    val all = catalog()
    val text = query.trim()
    if (text.isEmpty()) {
      return SearchResult(SUGGESTIONS.mapNotNull { id -> all.firstOrNull { it.symbol == id } })
    }
    val matches =
      all
        .mapNotNull { asset -> relevance(asset, text)?.let { asset to it } }
        .sortedWith(compareBy({ it.second }, { quoteRank(it.first) }, { it.first.symbol }))
        .take(MAX_RESULTS)
        .map { it.first }
    return SearchResult(matches)
  }

  override fun quotes(assets: List<Asset>): Flow<Pair<Asset, Result<Quote>>> = channelFlow {
    for (asset in assets) {
      launch {
        val result =
          try {
            Result.success(requestSlots.withPermit { fetchQuote(asset) })
          } catch (e: DataException) {
            Result.failure(e)
          }
        send(asset to result)
      }
    }
  }

  override suspend fun history(asset: Asset, range: ChartRange): List<Candle> {
    val spec = seriesSpec(range)
    val stepMillis = spec.granularitySeconds * 1000L
    val end = clock()
    val start = end - spec.spanMillis
    val byTime = sortedMapOf<Long, Candle>()
    // The API caps a response at about 300 candles, so long ranges are fetched in pages.
    var pageEnd = end
    while (pageEnd > start) {
      val pageStart = maxOf(start, pageEnd - MAX_CANDLES_PER_REQUEST * stepMillis)
      val body =
        requestSlots.withPermit {
          get(
            "products/${asset.symbol}/candles",
            listOf(
              "granularity" to spec.granularitySeconds.toString(),
              "start" to Instant.ofEpochMilli(pageStart).toString(),
              "end" to Instant.ofEpochMilli(pageEnd).toString(),
            ),
          )
        }
      val rows = body as? JsonArray ?: throw unexpected()
      rows.mapNotNull(::toCandle).forEach { byTime[it.timeMillis] = it }
      pageEnd = pageStart
    }
    return byTime.values.toList()
  }

  private suspend fun fetchQuote(asset: Asset): Quote {
    val stats = decode<StatsDto>(get("products/${asset.symbol}/stats", emptyList()))
    val price = stats.last.number() ?: throw DataException(ErrorKind.SERVER, "Quote has no price")
    // "open" is the price 24 hours ago: crypto has no daily close to compare against.
    val dayAgo = stats.open.number()
    val change = dayAgo?.let { price - it } ?: 0.0
    val now = clock()
    return Quote(
      price = price,
      change = change,
      changePercent = dayAgo?.takeIf { it != 0.0 }?.let { change / it * 100 } ?: 0.0,
      changeBasis = ChangeBasis.ROLLING_24H,
      open = dayAgo,
      high = stats.high.number(),
      low = stats.low.number(),
      volume = stats.volume.number(),
      currency = asset.currency,
      marketOpen = true,
      asOfMillis = now,
      fetchedAtMillis = now,
    )
  }

  /** All tradable pairs with their coin names. Fetched once and reused for [CATALOG_TTL_MILLIS]. */
  private suspend fun catalog(): List<Asset> =
    catalogMutex.withLock {
      catalog?.takeIf { clock() - it.loadedAt < CATALOG_TTL_MILLIS }?.let { return@withLock it.assets }
      val products = decode<List<ProductDto>>(get("products", emptyList()))
      // Coin names are a nicety; the search still works on tickers if this call fails.
      val names =
        try {
          decode<List<CurrencyDto>>(get("currencies", emptyList())).associate { it.id to it.name }
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          emptyMap()
        }
      val assets =
        products
          .filter { it.status == "online" && !it.tradingDisabled && it.id.isNotBlank() }
          .map { product ->
            Asset(
              provider = Provider.COINBASE,
              symbol = product.id,
              name = names[product.baseCurrency]?.takeIf { it.isNotBlank() } ?: product.baseCurrency,
              type = AssetType.CRYPTO,
              exchange = EXCHANGE_NAME,
              currency = product.quoteCurrency,
            )
          }
      catalog = Catalog(assets, clock())
      assets
    }

  private suspend fun get(path: String, query: List<Pair<String, String>>): JsonElement {
    val response = http.get("$BASE_URL/$path", query)
    val body =
      try {
        LenientJson.parseToJsonElement(response.body)
      } catch (_: SerializationException) {
        null
      }
    if (response.code in 200..299 && body != null) return body
    val message = (body as? JsonObject)?.get("message")?.jsonPrimitive?.content
    val kind =
      when (response.code) {
        404 -> ErrorKind.NOT_FOUND
        429 -> ErrorKind.RATE_LIMITED
        else -> ErrorKind.SERVER
      }
    throw DataException(kind, message ?: "Coinbase answered HTTP ${response.code}")
  }

  private inline fun <reified T> decode(element: JsonElement): T =
    try {
      LenientJson.decodeFromJsonElement<T>(element)
    } catch (e: SerializationException) {
      throw DataException(ErrorKind.SERVER, "Unexpected response from Coinbase", e)
    }

  private fun unexpected() = DataException(ErrorKind.SERVER, "Unexpected response from Coinbase")

  /** A row is `[time (s), low, high, open, close, volume]`. */
  private fun toCandle(row: JsonElement): Candle? {
    val cells = row as? JsonArray ?: return null
    if (cells.size < 5) return null
    fun cell(i: Int) = cells[i].jsonPrimitive.doubleOrNull
    return Candle(
      timeMillis = (cells[0].jsonPrimitive.longOrNull ?: return null) * 1000,
      low = cell(1) ?: return null,
      high = cell(2) ?: return null,
      open = cell(3) ?: return null,
      close = cell(4) ?: return null,
      volume = cells.getOrNull(5)?.jsonPrimitive?.doubleOrNull,
    )
  }

  /** Lower is better; null means no match. */
  private fun relevance(asset: Asset, text: String): Int? {
    val base = asset.symbol.substringBefore('-')
    val compact = text.replace("/", "-").replace(" ", "")
    return when {
      asset.symbol.equals(compact, ignoreCase = true) -> 0
      base.equals(text, ignoreCase = true) -> 1
      asset.name.equals(text, ignoreCase = true) -> 2
      asset.symbol.startsWith(compact, ignoreCase = true) -> 3
      asset.name.startsWith(text, ignoreCase = true) -> 4
      asset.name.contains(text, ignoreCase = true) -> 5
      else -> null
    }
  }

  /** Among pairs of the same coin, list the familiar quote currencies first. */
  private fun quoteRank(asset: Asset): Int =
    QUOTE_ORDER.indexOf(asset.currency).let { if (it < 0) QUOTE_ORDER.size else it }

  private class SeriesSpec(val granularitySeconds: Int, val spanMillis: Long)

  private fun seriesSpec(range: ChartRange): SeriesSpec =
    when (range) {
      ChartRange.DAY -> SeriesSpec(300, DAY_MILLIS)
      ChartRange.WEEK -> SeriesSpec(3_600, 7 * DAY_MILLIS)
      ChartRange.MONTH -> SeriesSpec(21_600, 30 * DAY_MILLIS)
      ChartRange.QUARTER -> SeriesSpec(86_400, 91 * DAY_MILLIS)
      ChartRange.YEAR -> SeriesSpec(86_400, 365 * DAY_MILLIS)
      ChartRange.FIVE_YEARS -> SeriesSpec(86_400, 5 * 365 * DAY_MILLIS)
    }

  @Serializable
  private class ProductDto(
    val id: String = "",
    @SerialName("base_currency") val baseCurrency: String = "",
    @SerialName("quote_currency") val quoteCurrency: String = "",
    val status: String = "",
    @SerialName("trading_disabled") val tradingDisabled: Boolean = false,
  )

  @Serializable private class CurrencyDto(val id: String = "", val name: String = "")

  @Serializable
  private class StatsDto(
    val open: String? = null,
    val high: String? = null,
    val low: String? = null,
    val last: String? = null,
    val volume: String? = null,
  )

  companion object {
    const val BASE_URL = "https://api.exchange.coinbase.com"
    const val EXCHANGE_NAME = "Coinbase"

    private const val MAX_PARALLEL_REQUESTS = 4
    private const val MAX_CANDLES_PER_REQUEST = 290
    private const val MAX_RESULTS = 40
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    private const val CATALOG_TTL_MILLIS = 12 * 60 * 60 * 1000L

    private val QUOTE_ORDER = listOf("USD", "EUR", "GBP", "USDT", "USDC", "BTC", "ETH")

    /** Shown before the user has typed anything. */
    private val SUGGESTIONS = listOf("BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "DOGE-USD", "ADA-USD")
  }
}
