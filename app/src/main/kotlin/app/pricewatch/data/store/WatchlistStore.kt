package app.pricewatch.data.store

import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.AssetGroup
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's ordered list of assets, persisted in `watchlist.json`.
 *
 * The list is kept in display order: entries of the same [AssetGroup] next to each other, groups in
 * the order they are shown, and the user's own order within each group. Prices are refreshed in
 * list order, so this also makes them arrive top to bottom.
 */
class WatchlistStore(private val store: JsonStore<List<Asset>>) {

  init {
    // A file from before sections existed is in the user's mixed order; sort it once.
    if (store.value.value != store.value.value.inDisplayOrder()) update { it }
  }

  val assets: StateFlow<List<Asset>>
    get() = store.value

  /**
   * Adds [asset] at the end of its group, or puts it at [index] (used to undo a removal). Ignored if
   * already listed.
   */
  fun add(asset: Asset, index: Int? = null) = update { list ->
    if (list.any { it.id == asset.id }) list
    else list.toMutableList().apply { add((index ?: size).coerceIn(0, size), asset) }
  }

  fun remove(assetId: String) = update { list -> list.filterNot { it.id == assetId } }

  /** Moves the entry up (negative [offset]) or down within its group; stops at either end. */
  fun move(assetId: String, offset: Int) = update { list ->
    val asset = list.firstOrNull { it.id == assetId } ?: return@update list
    val siblings = list.filter { it.group == asset.group }
    val from = siblings.indexOf(asset)
    val to = (from + offset).coerceIn(0, siblings.lastIndex)
    if (from == to) return@update list
    val target = siblings[to]
    list.toMutableList().apply {
      remove(asset)
      add(indexOf(target) + if (offset > 0) 1 else 0, asset)
    }
  }

  /** Moves the entry to the end of [group]. */
  fun setGroup(assetId: String, group: AssetGroup) = update { list ->
    val asset = list.firstOrNull { it.id == assetId }
    if (asset == null || asset.group == group) list else list.filterNot { it.id == assetId } + asset.copy(group = group)
  }

  /** Sets the user's own name for the entry; blank restores the provider's name. */
  fun rename(assetId: String, label: String) = update { list ->
    list.map { if (it.id == assetId) it.copy(label = label.trim().ifEmpty { null }) else it }
  }

  private fun update(transform: (List<Asset>) -> List<Asset>) = store.update { transform(it).inDisplayOrder() }

  private companion object {
    /** Stable, so the user's order within each group is kept. */
    fun List<Asset>.inDisplayOrder(): List<Asset> = sortedBy { it.group.ordinal }
  }
}
