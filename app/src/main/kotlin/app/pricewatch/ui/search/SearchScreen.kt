package app.pricewatch.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pricewatch.data.model.Asset
import app.pricewatch.ui.common.appContainer
import app.pricewatch.ui.common.details
import app.pricewatch.ui.common.shortMessage
import app.pricewatch.ui.preview.PreviewData
import app.pricewatch.ui.theme.PriceWatchTheme

@Composable
fun SearchRoute(onBack: () -> Unit, onSettings: () -> Unit) {
  val container = appContainer()
  val viewModel = viewModel {
    SearchViewModel(container.repository, container.watchlistStore, container.settingsStore)
  }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  SearchScreen(
    state = state,
    onQueryChange = viewModel::setQuery,
    onToggle = viewModel::toggle,
    onBack = onBack,
    onSettings = onSettings,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
  state: SearchUiState,
  onQueryChange: (String) -> Unit,
  onToggle: (Asset) -> Unit,
  onBack: () -> Unit,
  onSettings: () -> Unit,
) {
  // The field owns its text so typing never waits on the view model; the view model is told
  // about every change.
  var text by rememberSaveable { mutableStateOf(state.query) }
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  Scaffold(
    topBar = {
      TopAppBar(
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        title = {
          TextField(
            value = text,
            onValueChange = {
              text = it
              onQueryChange(it)
            },
            placeholder = { Text("Name or symbol") },
            singleLine = true,
            keyboardOptions =
              KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
            trailingIcon = {
              if (text.isNotEmpty()) {
                IconButton(
                  onClick = {
                    text = ""
                    onQueryChange("")
                  }
                ) {
                  Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
              }
            },
            colors =
              TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
              ),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).semantics { contentDescription = "Search assets" },
          )
        },
      )
    }
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.padding(innerPadding).fillMaxSize().imePadding(),
      contentPadding = PaddingValues(bottom = 24.dp),
    ) {
      section(
        title = "Crypto",
        source = "Coinbase · no key needed",
        state = state.crypto,
        emptyText = "No crypto pair matches.",
        addedIds = state.addedIds,
        onToggle = onToggle,
        onSettings = onSettings,
      )
      section(
        title = "Stocks, ETFs, forex",
        source = "Twelve Data",
        state = state.market,
        emptyText = "No match on the markets the free plan covers.",
        idleText = "Type a name or ticker, for example “Apple” or “EUR/USD”.",
        addedIds = state.addedIds,
        onToggle = onToggle,
        onSettings = onSettings,
      )
    }
  }
}

private fun LazyListScope.section(
  title: String,
  source: String,
  state: SectionState,
  emptyText: String,
  addedIds: Set<String>,
  onToggle: (Asset) -> Unit,
  onSettings: () -> Unit,
  idleText: String? = null,
) {
  // An idle section with nothing to say is left out entirely.
  if (state is SectionState.Idle && idleText == null) return
  item(key = "header-$title") { SectionHeader(title, source) }
  when (state) {
    SectionState.Idle -> item(key = "idle-$title") { SectionMessage(idleText.orEmpty()) }
    SectionState.Loading ->
      item(key = "loading-$title") {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
      }
    SectionState.NeedsApiKey ->
      item(key = "key-$title") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
          Text(
            "Searching stocks, ETFs and forex needs a free Twelve Data API key.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          TextButton(onClick = onSettings, contentPadding = PaddingValues(0.dp)) { Text("Add the key in Settings") }
        }
      }
    is SectionState.Failed ->
      item(key = "failed-$title") {
        SectionMessage("${state.kind.shortMessage()}. Could not search $title.", isError = true)
      }
    is SectionState.Results -> {
      if (state.assets.isEmpty()) {
        item(key = "empty-$title") { SectionMessage(emptyText) }
      }
      items(state.assets, key = { it.id }) { asset ->
        ResultRow(asset = asset, added = asset.id in addedIds, onToggle = { onToggle(asset) })
        HorizontalDivider()
      }
      if (state.hiddenCount > 0) {
        item(key = "hidden-$title") {
          val listings = if (state.hiddenCount == 1) "1 listing" else "${state.hiddenCount} listings"
          SectionMessage("$listings on exchanges the free plan does not cover are not shown.")
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(title: String, source: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    verticalAlignment = Alignment.Bottom,
  ) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
    Text(source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun SectionMessage(text: String, isError: Boolean = false) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
  )
}

@Composable
private fun ResultRow(asset: Asset, added: Boolean, onToggle: () -> Unit) {
  val action = if (added) "Remove ${asset.symbol} from watchlist" else "Add ${asset.symbol} to watchlist"
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClickLabel = action, onClick = onToggle)
        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        asset.symbol,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(asset.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        asset.details(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    IconButton(onClick = onToggle) {
      if (added) {
        Icon(Icons.Default.Check, contentDescription = action, tint = MaterialTheme.colorScheme.primary)
      } else {
        Icon(Icons.Default.Add, contentDescription = action)
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun SearchPreview() {
  PriceWatchTheme { SearchScreen(PreviewData.search, {}, {}, {}, {}) }
}

@Preview(showBackground = true)
@Composable
private fun SearchNeedsKeyPreview() {
  PriceWatchTheme(darkTheme = true) {
    SearchScreen(PreviewData.search.copy(query = "", market = SectionState.NeedsApiKey), {}, {}, {}, {})
  }
}
