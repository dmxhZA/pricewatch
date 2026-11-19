package app.pricewatch

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The list of followed assets; the app's home. */
@Serializable data object Watchlist : NavKey

/** Finding assets and adding them to the watchlist. */
@Serializable data object Search : NavKey

/** One asset's price, statistics and chart. */
@Serializable data class Detail(val assetId: String) : NavKey

@Serializable data object AppSettings : NavKey
