package app.pricewatch.testing

import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.AssetType
import app.pricewatch.data.model.DataException
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.data.model.Provider
import app.pricewatch.data.source.Http
import app.pricewatch.data.source.HttpResponse

/** A recorded request: the URL without query string, and its parameters. */
data class Request(val url: String, val query: Map<String, String>) {
  val path: String
    get() = url.substringAfter("://").substringAfter('/')
}

/** Stands in for the network: answers from [respond] and remembers what was asked. */
class FakeHttp(private val respond: (Request) -> HttpResponse) : Http {
  val requests = mutableListOf<Request>()

  /** When set, every request fails as if the device were offline. */
  var offline = false

  override suspend fun get(url: String, query: List<Pair<String, String>>): HttpResponse {
    val request = Request(url, query.toMap())
    requests += request
    if (offline) throw DataException(ErrorKind.NETWORK, "offline")
    return respond(request)
  }
}

/** A response recorded from the real API, stored under `src/test/resources/fixtures`. */
fun fixture(name: String): String =
  checkNotNull(FakeHttp::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

fun ok(body: String) = HttpResponse(200, body)

object Assets {
  val apple = Asset(Provider.TWELVE_DATA, "AAPL", "Apple Inc.", AssetType.STOCK, "NASDAQ", "USD")
  val euro = Asset(Provider.TWELVE_DATA, "EUR/USD", "Euro / US Dollar", AssetType.FOREX, currency = "USD")
  val bitcoin = Asset(Provider.COINBASE, "BTC-USD", "Bitcoin", AssetType.CRYPTO, "Coinbase", "USD")
  val ether = Asset(Provider.COINBASE, "ETH-USD", "Ethereum", AssetType.CRYPTO, "Coinbase", "USD")

  fun stock(symbol: String) = Asset(Provider.TWELVE_DATA, symbol, "$symbol Corp", AssetType.STOCK, "NYSE", "USD")
}

/** Twelve Data's error body, as documented and as seen in the recorded fixtures. */
fun twelveDataError(code: Int, message: String) = """{"code":$code,"message":"$message","status":"error"}"""

/** A minimal but well-formed Twelve Data quote for [symbol]. */
fun twelveDataQuote(symbol: String, price: Double, previousClose: Double = price, marketOpen: Boolean = true) =
  """{"symbol":"$symbol","name":"$symbol Corp","exchange":"NYSE","currency":"USD","timestamp":1794580200,""" +
    """"last_quote_at":1794603540,"open":"$price","high":"$price","low":"$price","close":"$price","volume":"1000",""" +
    """"previous_close":"$previousClose","change":"${price - previousClose}",""" +
    """"percent_change":"${if (previousClose == 0.0) 0.0 else (price - previousClose) / previousClose * 100}",""" +
    """"is_market_open":$marketOpen}"""
