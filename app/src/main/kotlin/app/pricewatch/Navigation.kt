package app.pricewatch

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.pricewatch.ui.detail.DetailRoute
import app.pricewatch.ui.search.SearchRoute
import app.pricewatch.ui.settings.SettingsRoute
import app.pricewatch.ui.watchlist.WatchlistRoute

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Watchlist)

  fun open(key: NavKey) {
    // A second tap during the transition must not stack the same screen twice.
    if (backStack.lastOrNull() != key) backStack.add(key)
  }
  fun back() {
    if (backStack.size > 1) backStack.removeLastOrNull()
  }

  NavDisplay(
    backStack = backStack,
    onBack = ::back,
    // Give every screen its own saved state and view models, released when it leaves the stack.
    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
    entryProvider =
      entryProvider {
        entry<Watchlist> {
          WatchlistRoute(
            onAdd = { open(Search) },
            onOpen = { asset -> open(Detail(asset.id)) },
            onSettings = { open(AppSettings) },
          )
        }
        entry<Search> { SearchRoute(onBack = ::back, onSettings = { open(AppSettings) }) }
        entry<Detail> { key -> DetailRoute(assetId = key.assetId, onBack = ::back) }
        entry<AppSettings> { SettingsRoute(onBack = ::back) }
      },
  )
}
