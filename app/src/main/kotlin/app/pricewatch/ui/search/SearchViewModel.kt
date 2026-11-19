package app.pricewatch.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pricewatch.data.MarketRepository
import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.AssetType
import app.pricewatch.data.model.DataException
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.data.model.Provider
import app.pricewatch.data.store.SettingsStore
import app.pricewatch.data.store.WatchlistStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/** What one provider's part of the result list is showing. */
sealed interface SectionState {
  /** Nothing to show: the query is empty and the provider has no suggestions. */
  data object Idle : SectionState

  data object Loading : SectionState

  /** The provider cannot be searched until the user enters an API key. */
  data object NeedsApiKey : SectionState

  data class Results(val assets: List<Asset>, val hiddenCount: Int = 0) : SectionState

  data class Failed(val kind: ErrorKind) : SectionState
}

data class SearchUiState(
  val query: String = "",
  val crypto: SectionState = SectionState.Loading,
  val market: SectionState = SectionState.Idle,
  /** Ids already on the watchlist, to show which results are added. */
  val addedIds: Set<String> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
  private val repository: MarketRepository,
  private val watchlist: WatchlistStore,
  settings: SettingsStore,
) : ViewModel() {

  private val query = MutableStateFlow("")

  /** Coinbase's pair list is downloaded once and filtered locally, so it can follow every keystroke. */
  private val crypto: Flow<SectionState> =
    query
      .map { it.trim() }
      .distinctUntilChanged()
      .transformLatest { text ->
        emit(SectionState.Loading)
        emit(search(Provider.COINBASE, text))
      }

  /** Each Twelve Data search costs an API credit, so wait until the user pauses typing. */
  private val market: Flow<SectionState> =
    combine(query.map { it.trim() }, settings.settings.map { it.twelveDataApiKey.isNotBlank() }, ::Pair)
      .distinctUntilChanged()
      .transformLatest { (text, hasKey) ->
        when {
          !hasKey -> emit(SectionState.NeedsApiKey)
          text.isEmpty() -> emit(SectionState.Idle)
          else -> {
            emit(SectionState.Loading)
            delay(TYPING_PAUSE_MILLIS)
            emit(search(Provider.TWELVE_DATA, text))
          }
        }
      }

  val uiState: StateFlow<SearchUiState> =
    combine(query, crypto, market, watchlist.assets) { query, crypto, market, assets ->
        SearchUiState(
          query = query,
          crypto = crypto,
          market = withoutDuplicateCrypto(market, crypto),
          addedIds = assets.map { it.id }.toSet(),
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

  fun setQuery(text: String) {
    query.value = text
  }

  /** Adds the asset to the watchlist, or removes it if it is already there. */
  fun toggle(asset: Asset) {
    if (watchlist.assets.value.any { it.id == asset.id }) watchlist.remove(asset.id) else watchlist.add(asset)
  }

  private suspend fun search(provider: Provider, text: String): SectionState =
    try {
      val result = repository.search(provider, text)
      if (text.isEmpty() && result.assets.isEmpty()) SectionState.Idle
      else SectionState.Results(result.assets, result.hiddenCount)
    } catch (e: DataException) {
      if (e.kind == ErrorKind.NO_API_KEY) SectionState.NeedsApiKey else SectionState.Failed(e.kind)
    }

  /** Twelve Data lists crypto pairs too; hide those Coinbase already offers without a key. */
  private fun withoutDuplicateCrypto(market: SectionState, crypto: SectionState): SectionState {
    if (market !is SectionState.Results || crypto !is SectionState.Results) return market
    val offered = crypto.assets.map { it.symbol }.toSet()
    return market.copy(
      assets =
        market.assets.filterNot { it.type == AssetType.CRYPTO && it.symbol.replace('/', '-') in offered }
    )
  }

  private companion object {
    const val TYPING_PAUSE_MILLIS = 600L
  }
}
