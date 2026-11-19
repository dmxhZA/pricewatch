package app.pricewatch.data.store

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class Settings(
  /** Twelve Data API key; blank until the user enters one. */
  val twelveDataApiKey: String = "",
  /** Seconds between automatic refreshes while the watchlist is on screen; 0 turns them off. */
  val autoRefreshSeconds: Int = DEFAULT_AUTO_REFRESH_SECONDS,
) {
  companion object {
    const val DEFAULT_AUTO_REFRESH_SECONDS = 300

    /** Choices offered in Settings, in seconds. */
    val AUTO_REFRESH_CHOICES = listOf(0, 60, 300, 900)
  }
}

/** App preferences, persisted in `settings.json`. */
class SettingsStore(private val store: JsonStore<Settings>) {

  val settings: StateFlow<Settings>
    get() = store.value

  fun setTwelveDataApiKey(key: String) = store.update { it.copy(twelveDataApiKey = key.trim()) }

  fun setAutoRefreshSeconds(seconds: Int) = store.update { it.copy(autoRefreshSeconds = seconds) }
}
