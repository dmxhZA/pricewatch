package app.pricewatch.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.data.source.ApiUsage
import app.pricewatch.data.source.TwelveDataSource
import app.pricewatch.data.store.Settings
import app.pricewatch.ui.common.appContainer
import app.pricewatch.ui.common.shortMessage
import app.pricewatch.ui.theme.PriceWatchTheme

@Composable
fun SettingsRoute(onBack: () -> Unit) {
  val container = appContainer()
  val viewModel = viewModel { SettingsViewModel(container.settingsStore, container.twelveData) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  SettingsScreen(
    state = state,
    onApiKeyChange = viewModel::setApiKey,
    onCheckKey = viewModel::checkKey,
    onAutoRefreshChange = viewModel::setAutoRefreshSeconds,
    onGetKey = {
      try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TwelveDataSource.SIGN_UP_URL)))
      } catch (_: ActivityNotFoundException) {
        // No browser installed; the address is also written out on the screen.
      }
    },
    onBack = onBack,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  state: SettingsUiState,
  onApiKeyChange: (String) -> Unit,
  onCheckKey: () -> Unit,
  onAutoRefreshChange: (Int) -> Unit,
  onGetKey: () -> Unit,
  onBack: () -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        title = { Text("Settings") },
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier.padding(innerPadding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
    ) {
      SectionTitle("Stocks, ETFs and forex")
      ApiKeySection(state, onApiKeyChange, onCheckKey, onGetKey)

      HorizontalDivider(Modifier.padding(top = 16.dp))
      SectionTitle("Automatic refresh")
      Paragraph("How often prices update while the app is open. They always update when you open it or pull down on the list.")
      Column(Modifier.selectableGroup()) {
        Settings.AUTO_REFRESH_CHOICES.forEach { seconds ->
          val selected = seconds == state.settings.autoRefreshSeconds
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = { onAutoRefreshChange(seconds) })
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            // The row handles the tap, so the whole line is the target.
            RadioButton(selected = selected, onClick = null)
            Text(refreshLabel(seconds), modifier = Modifier.padding(start = 16.dp))
          }
        }
      }

      HorizontalDivider(Modifier.padding(top = 16.dp))
      SectionTitle("About the data")
      Paragraph(
        "Crypto prices come from Coinbase's public market data and need no setup. Everything else comes from " +
          "Twelve Data using your own free key."
      )
      Paragraph(
        "Your watchlist, settings and API key are stored only on this device. The app talks to those two " +
          "services and nothing else."
      )
      Paragraph("Prices can be delayed and are for information only, not for trading decisions.")
    }
  }
}

@Composable
private fun ApiKeySection(
  state: SettingsUiState,
  onApiKeyChange: (String) -> Unit,
  onCheckKey: () -> Unit,
  onGetKey: () -> Unit,
) {
  // The field owns its text so typing never waits on storage.
  var key by rememberSaveable { mutableStateOf(state.settings.twelveDataApiKey) }
  var visible by rememberSaveable { mutableStateOf(false) }

  Paragraph(
    "These prices come from Twelve Data, which is free for personal use but needs an API key. Signing up " +
      "takes an email address and no payment details. The free plan covers US-listed stocks and ETFs and " +
      "currency pairs, with ${TwelveDataSource.CREDITS_PER_MINUTE} requests a minute and 800 a day."
  )
  OutlinedTextField(
    value = key,
    onValueChange = {
      key = it.trim()
      onApiKeyChange(key)
    },
    label = { Text("Twelve Data API key") },
    singleLine = true,
    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
    keyboardOptions =
      KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
      ),
    trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  )
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedButton(onClick = onGetKey) { Text("Get a free key") }
    OutlinedButton(onClick = onCheckKey, enabled = key.isNotEmpty() && state.keyCheck != KeyCheck.Checking) {
      Text("Check key")
    }
    if (state.keyCheck == KeyCheck.Checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
  }
  when (val check = state.keyCheck) {
    is KeyCheck.Valid -> Paragraph("The key works. ${usageText(check.usage)}")
    is KeyCheck.Failed -> Paragraph(keyProblem(check.kind), isError = true)
    KeyCheck.Checking,
    KeyCheck.NotChecked -> Unit
  }
  Paragraph("Sign-up page: twelvedata.com/pricing")
}

private fun usageText(usage: ApiUsage): String {
  val parts =
    listOfNotNull(
      if (usage.minuteUsed != null && usage.minuteLimit != null) "${usage.minuteUsed} of ${usage.minuteLimit} requests used this minute" else null,
      if (usage.dayUsed != null && usage.dayLimit != null) "${usage.dayUsed} of ${usage.dayLimit} today" else null,
    )
  return if (parts.isEmpty()) "" else parts.joinToString(", ", postfix = ".").replaceFirstChar { it.uppercase() }
}

private fun keyProblem(kind: ErrorKind): String =
  when (kind) {
    ErrorKind.BAD_API_KEY -> "Twelve Data does not accept this key. Check that it was copied completely."
    ErrorKind.NETWORK -> "No connection, so the key could not be checked."
    ErrorKind.RATE_LIMITED,
    ErrorKind.QUOTA_EXHAUSTED -> "The key is valid, but its free allowance is used up for now."
    else -> "${kind.shortMessage()}. The key could not be checked."
  }

private fun refreshLabel(seconds: Int): String =
  when {
    seconds <= 0 -> "Only when I ask"
    seconds < 120 -> "Every minute"
    else -> "Every ${seconds / 60} minutes"
  }

@Composable
private fun SectionTitle(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
  )
}

@Composable
private fun Paragraph(text: String, isError: Boolean = false) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
  )
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun SettingsPreview() {
  PriceWatchTheme {
    SettingsScreen(
      SettingsUiState(
        Settings(twelveDataApiKey = "0123456789abcdef"),
        KeyCheck.Valid(ApiUsage(minuteUsed = 3, minuteLimit = 8, dayUsed = 120, dayLimit = 800, plan = "basic")),
      ),
      {},
      {},
      {},
      {},
      {},
    )
  }
}
