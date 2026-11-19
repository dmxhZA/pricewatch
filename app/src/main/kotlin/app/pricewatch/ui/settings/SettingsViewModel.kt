package app.pricewatch.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pricewatch.data.model.DataException
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.data.source.ApiUsage
import app.pricewatch.data.source.TwelveDataSource
import app.pricewatch.data.store.Settings
import app.pricewatch.data.store.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Outcome of asking Twelve Data whether the entered key works. */
sealed interface KeyCheck {
  data object NotChecked : KeyCheck

  data object Checking : KeyCheck

  data class Valid(val usage: ApiUsage) : KeyCheck

  data class Failed(val kind: ErrorKind) : KeyCheck
}

data class SettingsUiState(val settings: Settings = Settings(), val keyCheck: KeyCheck = KeyCheck.NotChecked)

class SettingsViewModel(private val store: SettingsStore, private val twelveData: TwelveDataSource) : ViewModel() {

  private val keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.NotChecked)
  private var checkJob: Job? = null

  val uiState: StateFlow<SettingsUiState> =
    combine(store.settings, keyCheck, ::SettingsUiState)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(store.settings.value))

  fun setApiKey(key: String) {
    store.setTwelveDataApiKey(key)
    // A result for the previous key says nothing about this one.
    checkJob?.cancel()
    keyCheck.value = KeyCheck.NotChecked
  }

  fun setAutoRefreshSeconds(seconds: Int) = store.setAutoRefreshSeconds(seconds)

  fun checkKey() {
    checkJob?.cancel()
    checkJob =
      viewModelScope.launch {
        keyCheck.value = KeyCheck.Checking
        keyCheck.value =
          try {
            KeyCheck.Valid(twelveData.usage())
          } catch (e: DataException) {
            KeyCheck.Failed(e.kind)
          }
      }
  }
}
