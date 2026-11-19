package app.pricewatch.data.model

import kotlinx.serialization.Serializable

/** Where an asset's prices come from. Serialized by name into the local watchlist file. */
@Serializable
enum class Provider(val label: String) {
  /** Stocks, ETFs, forex and more. Needs the user's free API key. */
  TWELVE_DATA("Twelve Data"),

  /** Crypto pairs from Coinbase's public market-data API. No key needed. */
  COINBASE("Coinbase"),
}

@Serializable
enum class AssetType(val label: String) {
  STOCK("Stock"),
  ETF("ETF"),
  FUND("Fund"),
  INDEX("Index"),
  FOREX("Forex"),
  CRYPTO("Crypto"),
  COMMODITY("Commodity"),
  BOND("Bond"),
  OTHER("Other");

  /** Exchange-traded instruments have daily sessions; crypto and forex trade around the clock. */
  val hasSessions: Boolean
    get() = this != CRYPTO && this != FOREX
}

/** One entry of the watchlist. Everything needed to request and display it is stored locally. */
@Serializable
data class Asset(
  val provider: Provider,
  /** The provider's own symbol, for example `AAPL`, `EUR/USD` or `BTC-USD`. */
  val symbol: String,
  val name: String,
  val type: AssetType,
  val exchange: String? = null,
  val currency: String? = null,
  /** Optional name the user gave this entry; shown instead of [name]. */
  val label: String? = null,
) {
  /** Stable key: the same ticker can be listed by several providers and exchanges. */
  val id: String
    get() = "${provider.name}|$symbol|${exchange.orEmpty()}"

  val displayName: String
    get() = label?.takeIf { it.isNotBlank() } ?: name
}

/** What the day-change figures of a quote are measured against. */
@Serializable
enum class ChangeBasis {
  /** Change since the previous session's close (stocks, ETFs, forex). */
  PREVIOUS_CLOSE,

  /** Change over the trailing 24 hours (crypto). */
  ROLLING_24H,
}

@Serializable
data class Quote(
  val price: Double,
  val change: Double,
  val changePercent: Double,
  val changeBasis: ChangeBasis,
  val open: Double? = null,
  val high: Double? = null,
  val low: Double? = null,
  val previousClose: Double? = null,
  val volume: Double? = null,
  val yearLow: Double? = null,
  val yearHigh: Double? = null,
  val currency: String? = null,
  val marketOpen: Boolean? = null,
  /** When the provider says this price is from; null if it does not say. */
  val asOfMillis: Long? = null,
  /** When this device downloaded the quote. */
  val fetchedAtMillis: Long,
)

data class Candle(
  val timeMillis: Long,
  val open: Double,
  val high: Double,
  val low: Double,
  val close: Double,
  val volume: Double? = null,
)

/**
 * Time spans offered on the chart.
 *
 * @property intraday whether the span is drawn from bars shorter than a day, whose time of day
 *   matters. Otherwise each bar stands for a whole day (or week) and is stamped at 00:00 UTC of its
 *   calendar date, so that the date reads the same in every time zone.
 */
enum class ChartRange(val label: String, val description: String, val intraday: Boolean) {
  DAY("1D", "today", intraday = true),
  WEEK("1W", "past week", intraday = true),
  MONTH("1M", "past month", intraday = true),
  QUARTER("3M", "past 3 months", intraday = false),
  YEAR("1Y", "past year", intraday = false),
  FIVE_YEARS("5Y", "past 5 years", intraday = false),
}

data class SearchResult(
  val assets: List<Asset>,
  /** Matches left out because the provider's free plan cannot quote them. */
  val hiddenCount: Int = 0,
)

enum class ErrorKind {
  NETWORK,
  NO_API_KEY,
  BAD_API_KEY,
  RATE_LIMITED,
  QUOTA_EXHAUSTED,
  NOT_FOUND,
  PLAN_RESTRICTED,
  SERVER,
}

class DataException(val kind: ErrorKind, message: String? = null, cause: Throwable? = null) :
  Exception(message ?: kind.name, cause)
