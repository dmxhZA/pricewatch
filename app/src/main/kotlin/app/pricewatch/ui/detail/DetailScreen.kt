package app.pricewatch.ui.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import app.pricewatch.data.model.ChangeBasis
import app.pricewatch.data.model.ChartRange
import app.pricewatch.data.model.Quote
import app.pricewatch.ui.common.ChangeLabel
import app.pricewatch.ui.common.appContainer
import app.pricewatch.ui.common.details
import app.pricewatch.ui.common.secondsUntil
import app.pricewatch.ui.common.shortMessage
import app.pricewatch.ui.common.tabular
import app.pricewatch.ui.format.Formatters
import app.pricewatch.ui.preview.PreviewData
import app.pricewatch.ui.theme.PriceWatchTheme
import kotlinx.coroutines.delay

@Composable
fun DetailRoute(assetId: String, onBack: () -> Unit) {
  val container = appContainer()
  val viewModel = viewModel {
    DetailViewModel(assetId, container.watchlistStore, container.repository, container.settingsStore)
  }
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  // Keep the price current while this screen is open.
  val lifecycleOwner = LocalLifecycleOwner.current
  val autoRefreshSeconds = state.autoRefreshSeconds
  LaunchedEffect(lifecycleOwner, autoRefreshSeconds) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        viewModel.refreshQuote()
        if (autoRefreshSeconds <= 0) break
        delay(autoRefreshSeconds * 1000L)
      }
    }
  }

  DetailScreen(state = state, onSelectRange = viewModel::selectRange, onRetryChart = viewModel::retryChart, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
  state: DetailUiState,
  onSelectRange: (ChartRange) -> Unit,
  onRetryChart: () -> Unit,
  onBack: () -> Unit,
) {
  val asset = state.asset
  Scaffold(
    topBar = {
      TopAppBar(
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        title = { Text(asset?.symbol.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
      )
    }
  ) { innerPadding ->
    if (asset == null) {
      Box(Modifier.padding(innerPadding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text("This asset is no longer on your watchlist.", textAlign = TextAlign.Center)
      }
      return@Scaffold
    }
    Column(Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
      Header(asset, state)
      Spacer(Modifier.height(20.dp))
      ChartSection(asset, state, onSelectRange, onRetryChart)
      Spacer(Modifier.height(24.dp))
      state.quote?.let { Statistics(asset, it) }
      Text(
        "Prices from ${asset.provider.label}. They can be delayed and are for information only.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
      )
    }
  }
}

@Composable
private fun Header(asset: Asset, state: DetailUiState) {
  Column(Modifier.padding(horizontal = 16.dp)) {
    Text(asset.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(asset.details(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    val quote = state.quote
    if (quote == null) {
      Text("—", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      Row(verticalAlignment = Alignment.Bottom) {
        Text(
          Formatters.price(quote.price, asset.type),
          style = MaterialTheme.typography.displaySmall,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.alignByBaseline(),
        )
        (quote.currency ?: asset.currency)?.let {
          Text(
            it,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp).alignByBaseline(),
          )
        }
      }
      ChangeLabel(
        change = quote.change,
        percent = quote.changePercent,
        price = quote.price,
        type = asset.type,
        style = MaterialTheme.typography.titleSmall,
        suffix = if (quote.changeBasis == ChangeBasis.ROLLING_24H) "in 24 hours" else "since previous close",
      )
      Text(status(quote), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.quoteError?.let {
      Text(
        if (quote != null) "${it.shortMessage()} · showing last known price" else it.shortMessage(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
      )
    }
  }
}

private fun status(quote: Quote): String {
  val asOf = "As of ${Formatters.moment(quote.asOfMillis ?: quote.fetchedAtMillis)}"
  return when (quote.marketOpen) {
    true -> if (quote.changeBasis == ChangeBasis.ROLLING_24H) asOf else "$asOf · Market open"
    false -> "$asOf · Market closed"
    null -> asOf
  }
}

@Composable
private fun ChartSection(
  asset: Asset,
  state: DetailUiState,
  onSelectRange: (ChartRange) -> Unit,
  onRetryChart: () -> Unit,
) {
  val chart = state.chart
  // Index of the point under the user's finger. Forgotten when different data arrives.
  var selectedIndex by remember(chart.candles) { mutableStateOf<Int?>(null) }

  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    ChartRange.entries.forEach { range ->
      FilterChip(selected = range == state.selectedRange, onClick = { onSelectRange(range) }, label = { Text(range.label) })
    }
  }

  val shownRange = chart.range
  val hasChart = chart.error == null && shownRange != null && chart.candles.size >= 2

  // One fixed-height line above the chart: the selected point, or else the move over the range.
  Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
    if (hasChart) {
      val selected = selectedIndex?.let { chart.candles.getOrNull(it) }
      if (selected != null) {
        Row(verticalAlignment = Alignment.Bottom) {
          Text(
            Formatters.price(selected.close, asset.type),
            style = MaterialTheme.typography.titleMedium.tabular,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.alignByBaseline(),
          )
          Text(
            Formatters.pointTime(selected.timeMillis, shownRange),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).alignByBaseline(),
          )
        }
      } else {
        val first = chart.candles.first().open
        val last = chart.candles.last().close
        ChangeLabel(
          change = last - first,
          percent = if (first != 0.0) (last - first) / first * 100 else 0.0,
          price = last,
          type = asset.type,
          style = MaterialTheme.typography.bodyMedium,
          suffix = spanDescription(shownRange, asset),
        )
      }
    }
  }

  Box(Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
    when {
      chart.error != null ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text("${chart.error.shortMessage()}. The chart could not be loaded.", textAlign = TextAlign.Center)
          TextButton(onClick = onRetryChart) { Text("Try again") }
        }
      hasChart ->
        PriceChart(
          candles = chart.candles,
          range = shownRange,
          type = asset.type,
          selectedIndex = selectedIndex,
          onSelect = { selectedIndex = it },
          surface = MaterialTheme.colorScheme.background,
          // While another range loads, the current chart stays in place, dimmed.
          modifier = Modifier.fillMaxSize().alpha(if (chart.loading) 0.4f else 1f),
        )
      !chart.loading ->
        Text(
          "No price history for this period.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
    }
    if (chart.loading) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        state.waitingUntilMillis?.let { until ->
          val seconds = secondsUntil(until)
          Text(
            "Waiting for the free plan's rate limit" + if (seconds > 0) " · $seconds s" else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

private fun spanDescription(range: ChartRange, asset: Asset): String =
  when {
    range != ChartRange.DAY -> range.description
    asset.type.hasSessions -> "since the open"
    else -> "in 24 hours"
  }

@Composable
private fun Statistics(asset: Asset, quote: Quote) {
  fun price(value: Double?) = value?.let { Formatters.price(it, asset.type) }
  val daily = quote.changeBasis == ChangeBasis.PREVIOUS_CLOSE
  val rows =
    listOfNotNull(
      price(quote.open)?.let { (if (daily) "Open" else "Price 24 hours ago") to it },
      price(quote.previousClose)?.let { "Previous close" to it },
      price(quote.high)?.let { (if (daily) "Day high" else "24-hour high") to it },
      price(quote.low)?.let { (if (daily) "Day low" else "24-hour low") to it },
      quote.volume?.let { volume ->
        val unit = if (daily) "" else " " + asset.symbol.substringBefore('-')
        (if (daily) "Volume" else "24-hour volume") to Formatters.compact(volume) + unit
      },
      price(quote.yearLow)?.let { "52-week low" to it },
      price(quote.yearHigh)?.let { "52-week high" to it },
    )
  Column(Modifier.padding(horizontal = 16.dp)) {
    rows.forEach { (label, value) ->
      HorizontalDivider()
      Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
          label,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular)
      }
    }
    if (rows.isNotEmpty()) HorizontalDivider()
  }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun DetailPreview() {
  PriceWatchTheme { DetailScreen(PreviewData.detail, {}, {}, {}) }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun DetailDarkPreview() {
  PriceWatchTheme(darkTheme = true) { DetailScreen(PreviewData.detailCrypto, {}, {}, {}) }
}
