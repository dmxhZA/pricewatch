package app.pricewatch.ui.watchlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.AssetGroup
import app.pricewatch.data.model.ErrorKind
import app.pricewatch.data.source.TwelveDataSource
import app.pricewatch.ui.common.ChangeLabel
import app.pricewatch.ui.common.appContainer
import app.pricewatch.ui.common.secondsUntil
import app.pricewatch.ui.common.shortMessage
import app.pricewatch.ui.common.tabular
import app.pricewatch.ui.format.Formatters
import app.pricewatch.ui.preview.PreviewData
import app.pricewatch.ui.theme.PriceWatchTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WatchlistRoute(onAdd: () -> Unit, onOpen: (Asset) -> Unit, onSettings: () -> Unit) {
  val container = appContainer()
  val viewModel = viewModel {
    WatchlistViewModel(container.watchlistStore, container.repository, container.settingsStore)
  }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()

  // Refresh when the list comes on screen, then periodically for as long as it stays there.
  val lifecycleOwner = LocalLifecycleOwner.current
  val autoRefreshSeconds = state.autoRefreshSeconds
  LaunchedEffect(lifecycleOwner, autoRefreshSeconds) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        viewModel.refresh()
        if (autoRefreshSeconds <= 0) break
        delay(autoRefreshSeconds * 1000L)
      }
    }
  }

  WatchlistScreen(
    state = state,
    onRefresh = { viewModel.refresh(byUser = true) },
    onAdd = onAdd,
    onOpen = onOpen,
    onSettings = onSettings,
    onMove = viewModel::move,
    onEdit = viewModel::edit,
    onRemove = { asset ->
      val index = viewModel.remove(asset)
      scope.launch {
        snackbarHostState.currentSnackbarData?.dismiss()
        val result =
          snackbarHostState.showSnackbar(
            message = "Removed ${asset.symbol}",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
          )
        if (result == SnackbarResult.ActionPerformed) viewModel.restore(asset, index)
      }
    },
    snackbarHostState = snackbarHostState,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
  state: WatchlistUiState,
  onRefresh: () -> Unit,
  onAdd: () -> Unit,
  onOpen: (Asset) -> Unit,
  onSettings: () -> Unit,
  onMove: (Asset, Int) -> Unit,
  onEdit: (Asset, String, AssetGroup) -> Unit,
  onRemove: (Asset) -> Unit,
  snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
  initiallyEditing: Boolean = false,
) {
  var editing by rememberSaveable { mutableStateOf(initiallyEditing) }
  var editingId by rememberSaveable { mutableStateOf<String?>(null) }
  val isEmpty = state.rows.isEmpty()
  LaunchedEffect(isEmpty) { if (isEmpty) editing = false }
  BackHandler(enabled = editing) { editing = false }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(if (editing) "Edit watchlist" else "Watchlist")
            state.updatedAtMillis
              ?.takeIf { !editing }
              ?.let {
                Text(
                  "Updated ${Formatters.moment(it)}",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
          }
        },
        actions = {
          if (editing) {
            IconButton(onClick = { editing = false }) { Icon(Icons.Default.Check, contentDescription = "Done editing") }
          } else {
            if (!isEmpty) {
              IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, contentDescription = "Edit watchlist") }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
          }
        },
      )
    },
    floatingActionButton = {
      if (!editing && !isEmpty) {
        FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add asset") }
      }
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    if (isEmpty) {
      EmptyWatchlist(onAdd = onAdd, onSettings = onSettings, modifier = Modifier.padding(innerPadding))
    } else {
      Column(Modifier.padding(innerPadding).fillMaxSize()) {
        // Always reserve the indicator's height so the list does not jump when it appears.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
          if (state.refreshing && !state.userRefreshing && state.waitingUntilMillis == null) {
            LinearProgressIndicator(Modifier.fillMaxSize())
          }
        }
        PullToRefreshBox(isRefreshing = state.userRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
          LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            if (state.needsApiKey) {
              item(key = "notice-key") {
                Notice(
                  text = "Stocks, ETFs and forex need a free Twelve Data API key.",
                  actionLabel = "Set up",
                  onAction = onSettings,
                )
              }
            }
            state.waitingUntilMillis?.let { until ->
              item(key = "notice-wait") { RateLimitNotice(until) }
            }
            state.sections.forEach { section ->
              item(key = "section-${section.group.name}", contentType = "section") { SectionHeader(section.group) }
              itemsIndexed(section.rows, key = { _, row -> row.asset.id }, contentType = { _, _ -> "row" }) { index, row ->
                if (editing) {
                  EditRow(
                    asset = row.asset,
                    // Entries move within their section; the edit dialog moves them to another.
                    canMoveUp = index > 0,
                    canMoveDown = index < section.rows.lastIndex,
                    onMove = { offset -> onMove(row.asset, offset) },
                    onEdit = { editingId = row.asset.id },
                    onRemove = { onRemove(row.asset) },
                  )
                } else {
                  QuoteRow(row = row, onClick = { onOpen(row.asset) })
                }
                if (index < section.rows.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp))
              }
            }
          }
        }
      }
    }
  }

  state.rows
    .firstOrNull { it.asset.id == editingId }
    ?.asset
    ?.let { asset ->
      EditDialog(
        asset = asset,
        onDismiss = { editingId = null },
        onSave = { label, group ->
          onEdit(asset, label, group)
          editingId = null
        },
      )
    }
}

@Composable
private fun SectionHeader(group: AssetGroup) {
  Text(
    group.label,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
  )
}

@Composable
private fun QuoteRow(row: WatchlistRow, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        row.asset.symbol,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        row.asset.displayName,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      row.error?.let { error ->
        Text(
          // A price shown next to an error is the last one that was downloaded.
          if (row.quote != null) "${error.shortMessage()} · showing last known price" else error.shortMessage(),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(horizontalAlignment = Alignment.End) {
      val quote = row.quote
      when {
        quote != null -> {
          Text(
            Formatters.price(quote.price, row.asset.type),
            style = MaterialTheme.typography.titleMedium.tabular,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
          )
          ChangeLabel(
            change = quote.change,
            percent = quote.changePercent,
            price = quote.price,
            type = row.asset.type,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        row.refreshing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else ->
          Text("—", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun EditRow(
  asset: Asset,
  canMoveUp: Boolean,
  canMoveDown: Boolean,
  onMove: (Int) -> Unit,
  onEdit: () -> Unit,
  onRemove: () -> Unit,
) {
  Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
      Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${asset.symbol} up")
    }
    IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
      Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${asset.symbol} down")
    }
    Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp)) {
      Text(
        asset.symbol,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        asset.displayName,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit ${asset.symbol}") }
    IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove ${asset.symbol}") }
  }
}

/** The entry's own name and the section it is listed in. */
@Composable
private fun EditDialog(asset: Asset, onDismiss: () -> Unit, onSave: (String, AssetGroup) -> Unit) {
  var text by rememberSaveable(asset.id) { mutableStateOf(asset.displayName) }
  var group by rememberSaveable(asset.id) { mutableStateOf(asset.group) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Edit ${asset.symbol}") },
    text = { EditFields(asset, text, onTextChange = { text = it }, group, onGroupChange = { group = it }) },
    confirmButton = {
      // Saving the provider's own name means "no custom name".
      TextButton(onClick = { onSave(if (text.trim() == asset.name) "" else text, group) }) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun EditFields(
  asset: Asset,
  text: String,
  onTextChange: (String) -> Unit,
  group: AssetGroup,
  onGroupChange: (AssetGroup) -> Unit,
) {
  Column(Modifier.verticalScroll(rememberScrollState())) {
    OutlinedTextField(value = text, onValueChange = onTextChange, singleLine = true, label = { Text("Name") })
    Text(
      "Leave empty to go back to “${asset.name}”.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )
    Text("Section", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
    GroupChoices(selected = group, suggested = asset.type.suggestedGroup, onSelect = onGroupChange)
  }
}

@Composable
private fun GroupChoices(selected: AssetGroup, suggested: AssetGroup, onSelect: (AssetGroup) -> Unit) {
  Column(Modifier.selectableGroup()) {
    AssetGroup.entries.forEach { group ->
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = group == selected, role = Role.RadioButton, onClick = { onSelect(group) }),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // The whole row is the control; the button only shows its state.
        RadioButton(selected = group == selected, onClick = null)
        Text(group.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
        if (group == suggested) {
          Text("Suggested", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

@Composable
private fun EmptyWatchlist(onAdd: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Nothing on your watchlist yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(
      "Crypto works right away. For stocks, ETFs and forex, add a free Twelve Data API key in Settings first.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    Button(onClick = onAdd) { Text("Add an asset") }
    TextButton(onClick = onSettings) { Text("Open Settings") }
  }
}

@Composable
private fun Notice(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Row(
      modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
      if (actionLabel != null && onAction != null) {
        TextButton(onClick = onAction) { Text(actionLabel) }
      }
    }
  }
}

@Composable
private fun RateLimitNotice(waitingUntilMillis: Long) {
  val seconds = secondsUntil(waitingUntilMillis)
  val whenText = if (seconds > 0) "in $seconds s" else "in a moment"
  Notice(
    "The free data plan allows ${TwelveDataSource.CREDITS_PER_MINUTE} requests a minute. " +
      "The remaining prices update $whenText."
  )
}

@Preview(showBackground = true)
@Composable
private fun WatchlistPreview() {
  PriceWatchTheme {
    WatchlistScreen(PreviewData.watchlist, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, {})
  }
}

@Preview(showBackground = true)
@Composable
private fun WatchlistDarkPreview() {
  PriceWatchTheme(darkTheme = true) {
    WatchlistScreen(
      PreviewData.watchlist.copy(
        needsApiKey = true,
        rows =
          PreviewData.watchlist.rows.map { row ->
            if (row.asset == PreviewData.sp500) row.copy(quote = null, error = ErrorKind.NO_API_KEY) else row
          },
      ),
      {},
      {},
      {},
      {},
      { _, _ -> },
      { _, _, _ -> },
      {},
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun WatchlistEditingPreview() {
  PriceWatchTheme {
    WatchlistScreen(PreviewData.watchlist, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, {}, initiallyEditing = true)
  }
}

@Preview(showBackground = true)
@Composable
private fun WatchlistEmptyPreview() {
  PriceWatchTheme { WatchlistScreen(WatchlistUiState(), {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, {}) }
}

@Preview(showBackground = true)
@Composable
private fun EditDialogPreview() {
  // A dialog opens in its own window, which previews cannot show; draw its fields in place instead.
  PriceWatchTheme {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(312.dp)) {
      Box(Modifier.padding(24.dp)) {
        EditFields(PreviewData.sp500, "My index fund", {}, AssetGroup.INDICES, {})
      }
    }
  }
}
