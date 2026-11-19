package app.pricewatch.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pricewatch.data.MarketRepository
import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.AssetGroup
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.data.model.Provider
import app.pricewatch.data.model.Quote
import app.pricewatch.data.store.Settings
import app.pricewatch.data.store.SettingsStore
import app.pricewatch.data.store.WatchlistStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WatchlistRow(
  val asset: Asset,
  val quote: Quote? = null,
  /** Why the latest attempt to update this row failed, if it did. */
  val error: ErrorKind? = null,
  val refreshing: Boolean = false,
)

data class WatchlistUiState(
  val rows: List<WatchlistRow> = emptyList(),
  /** Some quote is being fetched. */
  val refreshing: Boolean = false,
  /** The user pulled to refresh and data is on its way (not merely queued behind a rate limit). */
  val userRefreshing: Boolean = false,
  /** Set while the free plan's rate limit holds back the next request: when it will be sent. */
  val waitingUntilMillis: Long? = null,
  /** When the newest quote on screen was downloaded. */
  val updatedAtMillis: Long? = null,
  /** The list contains assets that cannot be priced until an API key is entered. */
  val needsApiKey: Boolean = false,
  val autoRefreshSeconds: Int = Settings.DEFAULT_AUTO_REFRESH_SECONDS,
) {
  /** The rows by section, in display order. Sections with nothing in them are left out. */
  val sections: List<WatchlistSection> =
    rows.groupBy { it.asset.group }.toSortedMap().map { (group, rows) -> WatchlistSection(group, rows) }
}

data class WatchlistSection(val group: AssetGroup, val rows: List<WatchlistRow>)

class WatchlistViewModel(
  private val watchlist: WatchlistStore,
  private val repository: MarketRepository,
  private val settings: SettingsStore,
) : ViewModel() {

  private val userRefreshes = MutableStateFlow(0)

  val uiState: StateFlow<WatchlistUiState> =
    combine(
        combine(watchlist.assets, repository.quotes, repository.errors, repository.refreshing, ::rowsOf),
        repository.refreshing,
        repository.waitingUntil,
        settings.settings,
        userRefreshes,
      ) { rows, refreshing, waitingUntil, settings, userRefreshes ->
        WatchlistUiState(
          rows = rows,
          refreshing = refreshing.isNotEmpty(),
          userRefreshing = userRefreshes > 0 && waitingUntil == null,
          // Only worth mentioning while something on this list is actually waiting for it.
          waitingUntilMillis = waitingUntil.takeIf { refreshing.isNotEmpty() },
          updatedAtMillis = rows.mapNotNull { it.quote?.fetchedAtMillis }.maxOrNull(),
          needsApiKey =
            settings.twelveDataApiKey.isBlank() && rows.any { it.asset.provider == Provider.TWELVE_DATA },
          autoRefreshSeconds = settings.autoRefreshSeconds,
        )
      }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        WatchlistUiState(
          rows = rowsOf(watchlist.assets.value, repository.quotes.value, emptyMap(), emptySet()),
          autoRefreshSeconds = settings.settings.value.autoRefreshSeconds,
        ),
      )

  init {
    // Price a newly added asset right away, wherever it was added from.
    viewModelScope.launch {
      watchlist.assets.map { list -> list.map { it.id }.toSet() }.distinctUntilChanged().drop(1).collect { refresh() }
    }
    // A new or corrected API key should take effect without the user having to ask.
    viewModelScope.launch {
      settings.settings.map { it.twelveDataApiKey }.distinctUntilChanged().drop(1).collect { refresh(force = true) }
    }
  }

  /** Updates prices that are due. With [byUser], re-fetches everything and shows the pull indicator. */
  fun refresh(force: Boolean = false, byUser: Boolean = false) {
    viewModelScope.launch {
      if (byUser) userRefreshes.update { it + 1 }
      try {
        repository.refresh(watchlist.assets.value, force = force || byUser)
      } finally {
        if (byUser) userRefreshes.update { it - 1 }
      }
    }
  }

  fun move(asset: Asset, offset: Int) = watchlist.move(asset.id, offset)

  /** Applies the edit dialog: the user's name for the entry and the section it belongs in. */
  fun edit(asset: Asset, label: String, group: AssetGroup) {
    watchlist.rename(asset.id, label)
    watchlist.setGroup(asset.id, group)
  }

  /** Removes [asset] and returns where it was, so the removal can be undone with [restore]. */
  fun remove(asset: Asset): Int {
    val index = watchlist.assets.value.indexOfFirst { it.id == asset.id }
    watchlist.remove(asset.id)
    return index
  }

  fun restore(asset: Asset, index: Int) = watchlist.add(asset, index.coerceAtLeast(0))

  private fun rowsOf(
    assets: List<Asset>,
    quotes: Map<String, Quote>,
    errors: Map<String, app.pricewatch.data.model.DataException>,
    refreshing: Set<String>,
  ): List<WatchlistRow> =
    assets.map { WatchlistRow(it, quotes[it.id], errors[it.id]?.kind, it.id in refreshing) }
}
