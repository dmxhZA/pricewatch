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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What the provider reports about the API key's consumption. */
data class ApiUsage(
  val minuteUsed: Int?,
  val minuteLimit: Int?,
  val dayUsed: Int?,
  val dayLimit: Int?,
  val plan: String?,
)

/**
 * Twelve Data (https://twelvedata.com): quotes, history and symbol search for US stocks, ETFs and
 * forex on its free plan. Every request costs API credits; the free plan grants 8 per minute and
 * 800 per day, one per symbol quoted. [limiter] keeps the app inside the per-minute budget.
 */
class TwelveDataSource(
  private val http: Http,
  /** The user's API key; blank until they have entered one in Settings. */
  private val apiKey: () -> String,
  private val clock: () -> Long = System::currentTimeMillis,
  private val limiter: CreditLimiter = CreditLimiter(CREDITS_PER_MINUTE, 60_000L, clock),
) : MarketDataSource {

  override val provider = Provider.TWELVE_DATA

  override val waitingUntil: StateFlow<Long?>
    get() = limiter.waitingUntil

  override suspend fun search(query: String): SearchResult {
    val text = query.trim()
    if (text.isEmpty()) return SearchResult(emptyList())
    val root =
      fetch("symbol_search", listOf("symbol" to text, "outputsize" to "30", "show_plan" to "true"))
    errorOf(root)?.let { throw it }
    val items =
      root["data"]?.let { decode<List<SearchItemDto>>(it) }
        ?: throw DataException(ErrorKind.SERVER, "Unexpected search response")
    // Only instruments the free plan can quote are useful here; say how many were left out.
    val (usable, restricted) = items.partition { it.access == null || it.access.plan.equals("Basic", true) }
    val assets = usable.mapNotNull(::toAsset).distinctBy { it.id }
    return SearchResult(assets, hiddenCount = restricted.size)
  }

  override fun quotes(assets: List<Asset>): Flow<Pair<Asset, Result<Quote>>> = flow {
    if (apiKey().isBlank()) {
      val missingKey = DataException(ErrorKind.NO_API_KEY)
      assets.forEach { emit(it to Result.failure(missingKey)) }
      return@flow
    }
    var remaining = assets
    var retriedAfterRateLimit = false
    while (remaining.isNotEmpty()) {
      // One credit per symbol. Take what the minute still allows and keep a little back so that
      // opening a chart or searching does not have to queue behind a long watchlist.
      val count = limiter.acquireUpTo(max = remaining.size, reserve = INTERACTIVE_RESERVE)
      val batch = remaining.take(count)
      val results =
        try {
          fetchQuotes(batch)
        } catch (e: DataException) {
          if (e.kind == ErrorKind.RATE_LIMITED && !retriedAfterRateLimit) {
            // The limiter has been drained, so the next pass waits for a fresh minute.
            retriedAfterRateLimit = true
            continue
          }
          // Network down, key rejected, quota gone: the rest of the list would fail the same way.
          remaining.forEach { emit(it to Result.failure(e)) }
          return@flow
        }
      remaining = remaining.drop(count)
      batch.forEach { emit(it to results.getValue(it.id)) }
    }
  }

  override suspend fun history(asset: Asset, range: ChartRange): List<Candle> {
    val spec = seriesSpec(range)
    val startDate = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate().minusDays(spec.lookbackDays)
    val root =
      fetch(
        "time_series",
        listOf(
          "symbol" to requestSymbol(asset),
          "interval" to spec.interval,
          "start_date" to startDate.toString(),
          "outputsize" to "5000",
          "order" to "asc",
          // Without this, bar times come in each market's own zone (Sydney time for forex).
          "timezone" to "UTC",
        ),
      )
    errorOf(root)?.let { error ->
      // "No data for these dates" is a normal answer for a quiet range, not a failure.
      if (error.kind == ErrorKind.NOT_FOUND) return emptyList()
      throw error
    }
    val series = decode<TimeSeriesDto>(root)
    val candles = series.values.mapNotNull { it.toCandle() }.sortedBy { it.timeMillis }
    if (range != ChartRange.DAY) return candles
    // A trading day is a calendar day where the exchange is, not in UTC.
    val exchangeZone =
      series.meta?.exchangeTimezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
    return latestSession(candles, asset.type, exchangeZone)
  }

  /** Asks the provider how much of the key's allowance is used. Costs one credit. */
  suspend fun usage(): ApiUsage {
    val root = fetch("api_usage", emptyList())
    errorOf(root)?.let { throw it }
    fun int(name: String) = root[name]?.jsonPrimitive?.intOrNull
    return ApiUsage(
      minuteUsed = int("current_usage"),
      minuteLimit = int("plan_limit"),
      dayUsed = int("daily_usage"),
      dayLimit = int("plan_daily_limit"),
      plan = root["plan_category"]?.jsonPrimitive?.content,
    )
  }

  private suspend fun fetchQuotes(batch: List<Asset>): Map<String, Result<Quote>> {
    // quotes() has already taken one credit per symbol from the limiter.
    val root = fetch("quote", listOf("symbol" to batch.joinToString(",", transform = ::requestSymbol)), credits = 0)
    val now = clock()
    errorOf(root)?.let { error ->
      if (error.kind in REQUEST_WIDE_ERRORS) throw error
      // Otherwise the complaint is about the symbol itself (unknown, or not on this plan).
      return batch.associate { it.id to Result.failure(error) }
    }
    return batch.associate { asset ->
      // Several symbols come back keyed by the symbol as requested; a single one comes back bare.
      val node = (root[requestSymbol(asset)] as? JsonObject) ?: root.takeIf { batch.size == 1 }
      asset.id to
        when {
          node == null -> Result.failure(DataException(ErrorKind.NOT_FOUND, "No quote returned"))
          else -> errorOf(node)?.let { Result.failure(it) } ?: runCatching { parseQuote(node, asset, now) }
        }
    }
  }

  private fun parseQuote(node: JsonObject, asset: Asset, now: Long): Quote {
    val dto = decode<QuoteDto>(node)
    val price = dto.close.number() ?: throw DataException(ErrorKind.SERVER, "Quote has no price")
    val previousClose = dto.previousClose.number()
    val change = dto.change.number() ?: previousClose?.let { price - it } ?: 0.0
    val changePercent =
      dto.percentChange.number()
        ?: previousClose?.takeIf { it != 0.0 }?.let { (price - it) / it * 100 }
        ?: 0.0
    return Quote(
      price = price,
      change = change,
      changePercent = changePercent,
      changeBasis = ChangeBasis.PREVIOUS_CLOSE,
      open = dto.open.number(),
      high = dto.high.number(),
      low = dto.low.number(),
      previousClose = previousClose,
      volume = dto.volume.number(),
      yearLow = dto.fiftyTwoWeek?.low.number(),
      yearHigh = dto.fiftyTwoWeek?.high.number(),
      currency = dto.currency ?: asset.currency,
      marketOpen = dto.isMarketOpen,
      asOfMillis = (dto.lastQuoteAt ?: dto.timestamp)?.let { it * 1000 },
      fetchedAtMillis = now,
    )
  }

  /**
   * One API call. Spends [credits] first; pass 0 when the caller has already taken them from
   * [limiter]. A missing key fails before anything is spent.
   */
  private suspend fun fetch(path: String, query: List<Pair<String, String>>, credits: Int = 1): JsonObject {
    val key = apiKey().trim()
    if (key.isEmpty()) throw DataException(ErrorKind.NO_API_KEY)
    if (credits > 0) limiter.acquire(credits)
    val response = http.get("$BASE_URL/$path", query + ("apikey" to key))
    val root =
      try {
        LenientJson.parseToJsonElement(response.body) as? JsonObject
      } catch (_: SerializationException) {
        null
      } ?: throw DataException(ErrorKind.SERVER, "Twelve Data answered HTTP ${response.code}")
    if (errorOf(root)?.kind == ErrorKind.RATE_LIMITED) limiter.exhaust()
    return root
  }

  /** Twelve Data reports problems in the body as `{"code": .., "message": .., "status": "error"}`. */
  private fun errorOf(node: JsonObject): DataException? {
    if (node["status"]?.jsonPrimitive?.content != "error") return null
    val message = node["message"]?.jsonPrimitive?.content.orEmpty()
    val kind =
      when (node["code"]?.jsonPrimitive?.intOrNull) {
        401 -> ErrorKind.BAD_API_KEY
        403 -> ErrorKind.PLAN_RESTRICTED
        400,
        404 -> ErrorKind.NOT_FOUND
        429 -> if (message.contains("for the day", ignoreCase = true)) ErrorKind.QUOTA_EXHAUSTED else ErrorKind.RATE_LIMITED
        else -> ErrorKind.SERVER
      }
    return DataException(kind, message)
  }

  private fun toAsset(item: SearchItemDto): Asset? {
    val symbol = item.symbol?.takeIf { it.isNotBlank() } ?: return null
    val type = assetType(item.instrumentType)
    return Asset(
      provider = Provider.TWELVE_DATA,
      symbol = symbol,
      name = item.instrumentName?.takeIf { it.isNotBlank() } ?: symbol,
      type = type,
      // A currency pair is the same instrument whichever venue reports it.
      exchange = item.exchange?.takeIf { type.hasSessions && it.isNotBlank() },
      currency = item.currency?.takeIf { it.isNotBlank() } ?: symbol.substringAfter('/', "").ifEmpty { null },
    )
  }

  private inline fun <reified T> decode(element: kotlinx.serialization.json.JsonElement): T =
    try {
      LenientJson.decodeFromJsonElement<T>(element)
    } catch (e: SerializationException) {
      throw DataException(ErrorKind.SERVER, "Unexpected response from Twelve Data", e)
    }

  private class SeriesSpec(val interval: String, val lookbackDays: Long)

  private fun seriesSpec(range: ChartRange): SeriesSpec =
    when (range) {
      // Five days back so that "today" still finds the last session after a long weekend.
      ChartRange.DAY -> SeriesSpec("5min", 5)
      ChartRange.WEEK -> SeriesSpec("15min", 7)
      ChartRange.MONTH -> SeriesSpec("1h", 31)
      ChartRange.QUARTER -> SeriesSpec("1day", 92)
      ChartRange.YEAR -> SeriesSpec("1day", 366)
      ChartRange.FIVE_YEARS -> SeriesSpec("1week", 5 * 366)
    }

  @Serializable
  private class QuoteDto(
    val currency: String? = null,
    val timestamp: Long? = null,
    @SerialName("last_quote_at") val lastQuoteAt: Long? = null,
    val open: String? = null,
    val high: String? = null,
    val low: String? = null,
    val close: String? = null,
    val volume: String? = null,
    @SerialName("previous_close") val previousClose: String? = null,
    val change: String? = null,
    @SerialName("percent_change") val percentChange: String? = null,
    @SerialName("is_market_open") val isMarketOpen: Boolean? = null,
    @SerialName("fifty_two_week") val fiftyTwoWeek: YearRangeDto? = null,
  )

  @Serializable private class YearRangeDto(val low: String? = null, val high: String? = null)

  @Serializable
  private class TimeSeriesDto(val meta: SeriesMetaDto? = null, val values: List<BarDto> = emptyList())

  @Serializable
  private class SeriesMetaDto(@SerialName("exchange_timezone") val exchangeTimezone: String? = null)

  @Serializable
  private class BarDto(
    val datetime: String? = null,
    val open: String? = null,
    val high: String? = null,
    val low: String? = null,
    val close: String? = null,
    val volume: String? = null,
  ) {
    fun toCandle(): Candle? {
      val time = parseBarTime(datetime ?: return null) ?: return null
      val close = close.number() ?: return null
      return Candle(
        timeMillis = time,
        open = open.number() ?: close,
        high = high.number() ?: close,
        low = low.number() ?: close,
        close = close,
        volume = volume.number(),
      )
    }
  }

  @Serializable
  private class SearchItemDto(
    val symbol: String? = null,
    @SerialName("instrument_name") val instrumentName: String? = null,
    val exchange: String? = null,
    @SerialName("instrument_type") val instrumentType: String? = null,
    val currency: String? = null,
    val access: AccessDto? = null,
  )

  @Serializable private class AccessDto(val plan: String? = null)

  companion object {
    const val BASE_URL = "https://api.twelvedata.com"
    const val SIGN_UP_URL = "https://twelvedata.com/pricing"

    /** Free ("Basic") plan allowance. */
    const val CREDITS_PER_MINUTE = 8

    /** Credits a watchlist refresh leaves free each minute for charts and searches. */
    const val INTERACTIVE_RESERVE = 2

    private val REQUEST_WIDE_ERRORS =
      setOf(ErrorKind.BAD_API_KEY, ErrorKind.RATE_LIMITED, ErrorKind.QUOTA_EXHAUSTED, ErrorKind.SERVER)

    private val BAR_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Stocks are qualified with their exchange so a ticker that exists on several markets cannot
     * silently resolve to a different company.
     */
    fun requestSymbol(asset: Asset): String =
      if (asset.type.hasSessions && !asset.exchange.isNullOrBlank()) "${asset.symbol}:${asset.exchange}"
      else asset.symbol

    internal fun assetType(instrumentType: String?): AssetType =
      when (instrumentType?.lowercase()) {
        "common stock",
        "preferred stock",
        "depositary receipt",
        "american depositary receipt",
        "global depositary receipt",
        "reit",
        "limited partnership",
        "unit",
        "right",
        "warrant" -> AssetType.STOCK
        "etf",
        "exchange-traded note" -> AssetType.ETF
        "mutual fund",
        "closed-end fund",
        "trust" -> AssetType.FUND
        "index" -> AssetType.INDEX
        "physical currency" -> AssetType.FOREX
        "digital currency" -> AssetType.CRYPTO
        "precious metal",
        "commodity" -> AssetType.COMMODITY
        "bond",
        "bond fund" -> AssetType.BOND
        else -> AssetType.OTHER
      }

    /** Bars are requested in UTC and stamped `2026-11-13 20:55:00` intraday, `2026-11-13` daily. */
    internal fun parseBarTime(text: String): Long? =
      runCatching {
          val utc = if (text.length > 10) LocalDateTime.parse(text, BAR_TIME) else LocalDate.parse(text).atStartOfDay()
          utc.toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        .getOrNull()

    /** Narrows several days of intraday bars to the most recent session (or 24 hours). */
    internal fun latestSession(candles: List<Candle>, type: AssetType, zone: ZoneId): List<Candle> {
      val last = candles.lastOrNull() ?: return candles
      return if (type.hasSessions) {
        val day = Instant.ofEpochMilli(last.timeMillis).atZone(zone).toLocalDate()
        candles.filter { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() == day }
      } else {
        candles.filter { it.timeMillis >= last.timeMillis - 24 * 60 * 60 * 1000L }
      }
    }
  }
}

internal val LenientJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
  coerceInputValues = true
  explicitNulls = false
}

internal fun String?.number(): Double? = this?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
