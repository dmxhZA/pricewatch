package app.pricewatch.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pricewatch.data.MarketRepository
import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.Candle
import app.pricewatch.data.model.ChartRange
import app.pricewatch.data.model.DataException
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.data.model.Quote
import app.pricewatch.data.store.Settings
import app.pricewatch.data.store.SettingsStore
import app.pricewatch.data.store.WatchlistStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChartUiState(
  val candles: List<Candle> = emptyList(),
  /** The range [candles] cover; null until the first chart has loaded. */
  val range: ChartRange? = null,
  val loading: Boolean = true,
  val error: ErrorKind? = null,
)

data class DetailUiState(
  /** Null once the asset has been removed from the watchlist. */
  val asset: Asset? = null,
  val quote: Quote? = null,
  val quoteError: ErrorKind? = null,
  val selectedRange: ChartRange = ChartRange.DAY,
  val chart: ChartUiState = ChartUiState(),
  /** Set while the free plan's rate limit holds back a request: when it will be sent. */
  val waitingUntilMillis: Long? = null,
  val autoRefreshSeconds: Int = Settings.DEFAULT_AUTO_REFRESH_SECONDS,
)

class DetailViewModel(
  private val assetId: String,
  private val watchlist: WatchlistStore,
  private val repository: MarketRepository,
  settings: SettingsStore,
) : ViewModel() {

  private val selectedRange = MutableStateFlow(ChartRange.DAY)
  private val chart = MutableStateFlow(ChartUiState())
  private val reloads = MutableStateFlow(0)

  private val asset: Asset?
    get() = watchlist.assets.value.firstOrNull { it.id == assetId }

  val uiState: StateFlow<DetailUiState> =
    combine(
        combine(watchlist.assets, repository.quotes, repository.errors) { assets, quotes, errors ->
          Triple(assets.firstOrNull { it.id == assetId }, quotes[assetId], errors[assetId]?.kind)
        },
        selectedRange,
        chart,
        repository.waitingUntil,
        settings.settings,
      ) { (asset, quote, quoteError), range, chart, waitingUntil, settings ->
        DetailUiState(asset, quote, quoteError, range, chart, waitingUntil, settings.autoRefreshSeconds)
      }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DetailUiState(asset = asset, quote = repository.quotes.value[assetId]),
      )

  init {
    viewModelScope.launch {
      combine(selectedRange, reloads) { range, _ -> range }.collectLatest { loadChart(it) }
    }
  }

  fun selectRange(range: ChartRange) {
    selectedRange.value = range
  }

  fun retryChart() = reloads.update { it + 1 }

  fun refreshQuote() {
    val asset = asset ?: return
    viewModelScope.launch { repository.refresh(listOf(asset)) }
  }

  private suspend fun loadChart(range: ChartRange) {
    val asset = asset ?: return
    // Keep the previous chart on screen while the new one loads.
    chart.update { it.copy(loading = true, error = null) }
    try {
      chart.value = ChartUiState(repository.history(asset, range), range, loading = false)
    } catch (e: DataException) {
      chart.update { it.copy(loading = false, error = e.kind) }
    }
  }
}
