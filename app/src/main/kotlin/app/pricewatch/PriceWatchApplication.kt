package app.pricewatch

import android.app.Application
import app.pricewatch.data.MarketRepository
import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.Quote
import app.pricewatch.data.source.CoinbaseSource
import app.pricewatch.data.source.Http
import app.pricewatch.data.source.OkHttpHttp
import app.pricewatch.data.source.TwelveDataSource
import app.pricewatch.data.store.JsonStore
import app.pricewatch.data.store.Settings
import app.pricewatch.data.store.SettingsStore
import app.pricewatch.data.store.WatchlistStore
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The app's few long-lived objects, wired by hand. Everything the user configures lives in JSON
 * files under [storageDir] (the app's private storage); nothing is sent anywhere except the price
 * requests to the two data providers.
 */
class AppContainer(
  storageDir: File,
  http: Http = OkHttpHttp(),
  clock: () -> Long = System::currentTimeMillis,
  scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  val settingsStore =
    SettingsStore(
      JsonStore(File(storageDir, "settings.json"), Settings.serializer(), Settings(), scope, ioDispatcher)
    )

  val watchlistStore =
    WatchlistStore(
      JsonStore(File(storageDir, "watchlist.json"), ListSerializer(Asset.serializer()), emptyList(), scope, ioDispatcher)
    )

  val twelveData = TwelveDataSource(http, apiKey = { settingsStore.settings.value.twelveDataApiKey }, clock = clock)

  val coinbase = CoinbaseSource(http, clock)

  val repository =
    MarketRepository(
      sources = listOf(twelveData, coinbase),
      quoteStore =
        JsonStore(
          File(storageDir, "quotes.json"),
          MapSerializer(String.serializer(), Quote.serializer()),
          emptyMap(),
          scope,
          ioDispatcher,
          writeDelayMillis = 500,
        ),
      clock = clock,
    )

  init {
    repository.retainOnly(watchlistStore.assets.value.map { it.id }.toSet())
  }
}

open class PriceWatchApplication : Application() {
  /** Created on first use; tests substitute a container with canned network responses. */
  open val container: AppContainer by lazy { AppContainer(filesDir) }
}
