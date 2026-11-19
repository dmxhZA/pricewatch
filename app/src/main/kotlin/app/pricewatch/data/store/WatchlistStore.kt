package app.pricewatch.data.store

import app.pricewatch.data.model.Asset
import kotlinx.coroutines.flow.StateFlow

/** The user's ordered list of assets, persisted in `watchlist.json`. */
class WatchlistStore(private val store: JsonStore<List<Asset>>) {

  val assets: StateFlow<List<Asset>>
    get() = store.value

  /** Appends [asset], or puts it at [index] (used to undo a removal). Ignored if already listed. */
  fun add(asset: Asset, index: Int? = null) =
    store.update { list ->
      if (list.any { it.id == asset.id }) list
      else list.toMutableList().apply { add((index ?: size).coerceIn(0, size), asset) }
    }

  fun remove(assetId: String) = store.update { list -> list.filterNot { it.id == assetId } }

  /** Moves the entry up (negative [offset]) or down the list; stops at either end. */
  fun move(assetId: String, offset: Int) =
    store.update { list ->
      val from = list.indexOfFirst { it.id == assetId }
      val to = (from + offset).coerceIn(0, list.lastIndex)
      if (from < 0 || from == to) list else list.toMutableList().apply { add(to, removeAt(from)) }
    }

  /** Sets the user's own name for the entry; blank restores the provider's name. */
  fun rename(assetId: String, label: String) =
    store.update { list ->
      list.map { if (it.id == assetId) it.copy(label = label.trim().ifEmpty { null }) else it }
    }
}
