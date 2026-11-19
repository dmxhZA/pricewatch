package app.pricewatch.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.pricewatch.data.model.AssetType
import app.pricewatch.data.model.Candle
import app.pricewatch.data.model.ChartRange
import app.pricewatch.ui.common.tabular
import app.pricewatch.ui.format.Formatters
import app.pricewatch.ui.theme.marketColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Closing prices over time as a single line.
 *
 * Points are spaced evenly rather than by clock time, so nights and weekends when a market is
 * closed do not show up as gaps. Touching the chart selects the nearest point; the caller shows
 * its value, since a readout drawn under the finger would be covered by it.
 *
 * @param selectedIndex index into [candles] of the point being inspected, if any
 * @param surface color behind the chart, used to ring the markers so they stay legible on the line
 */
@Composable
fun PriceChart(
  candles: List<Candle>,
  range: ChartRange,
  type: AssetType?,
  selectedIndex: Int?,
  onSelect: (Int?) -> Unit,
  surface: Color,
  modifier: Modifier = Modifier,
) {
  if (candles.size < 2) return
  val colors = MaterialTheme.marketColors
  val labelStyle = MaterialTheme.typography.labelSmall.tabular.copy(color = colors.chartLabel)
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val gapPx = with(density) { 8.dp.toPx() }

  val scale = remember(candles) { PriceScale.of(candles.map { it.close }) }
  val tickLabels: List<TextLayoutResult> =
    remember(scale, labelStyle) { scale.ticks.map { measurer.measure(Formatters.fixed(it, scale.decimals), labelStyle) } }
  val timeLabels: List<TextLayoutResult> =
    remember(candles, range, labelStyle) {
      listOf(candles.first(), candles[candles.size / 2], candles.last()).map {
        measurer.measure(Formatters.axisTime(it.timeMillis, range), labelStyle)
      }
    }
  // The plot ends where the widest price label begins.
  val plotInsetEnd = tickLabels.maxOf { it.size.width } + gapPx

  val currentOnSelect by rememberUpdatedState(onSelect)
  val dragging = remember { mutableStateOf(false) }
  val pointCount = candles.size

  Canvas(
    modifier =
      modifier
        .semantics { contentDescription = describe(candles, range, type) }
        .pointerInput(pointCount, plotInsetEnd) {
          fun indexAt(x: Float): Int {
            val plotWidth = (size.width - plotInsetEnd).coerceAtLeast(1f)
            return ((x / plotWidth).coerceIn(0f, 1f) * (pointCount - 1)).roundToInt()
          }
          // A horizontal drag scrubs along the line; a vertical one is left to scroll the page.
          detectHorizontalDragGestures(
            onDragStart = {
              dragging.value = true
              currentOnSelect(indexAt(it.x))
            },
            onHorizontalDrag = { change, _ ->
              currentOnSelect(indexAt(change.position.x))
              change.consume()
            },
            onDragEnd = {
              dragging.value = false
              currentOnSelect(null)
            },
            onDragCancel = {
              dragging.value = false
              currentOnSelect(null)
            },
          )
        }
        .pointerInput(pointCount, plotInsetEnd) {
          // Pressing without moving also selects, for as long as the finger stays down.
          detectTapGestures(
            onPress = { offset ->
              val plotWidth = (size.width - plotInsetEnd).coerceAtLeast(1f)
              currentOnSelect(((offset.x / plotWidth).coerceIn(0f, 1f) * (pointCount - 1)).roundToInt())
              tryAwaitRelease()
              if (!dragging.value) currentOnSelect(null)
            }
          )
        }
  ) {
    val timeAxisHeight = timeLabels.maxOf { it.size.height } + 6.dp.toPx()
    val left = 0f
    val right = size.width - plotInsetEnd
    val top = 8.dp.toPx()
    val bottom = size.height - timeAxisHeight
    if (right - left < 1f || bottom - top < 1f) return@Canvas

    fun x(index: Int) = left + (right - left) * index / (pointCount - 1)
    fun y(value: Double) = (bottom - (bottom - top) * ((value - scale.low) / (scale.high - scale.low))).toFloat()

    // Gridlines and their labels sit behind the data and stay quiet.
    val hairline = 1.dp.toPx().coerceAtLeast(1f)
    scale.ticks.forEachIndexed { i, tick ->
      val tickY = y(tick)
      drawLine(colors.chartGrid, Offset(left, tickY), Offset(right, tickY), hairline)
      val label = tickLabels[i]
      drawText(label, topLeft = Offset(size.width - label.size.width, tickY - label.size.height / 2f))
    }
    drawLine(colors.chartAxis, Offset(left, bottom), Offset(right, bottom), hairline)

    val line = Path()
    candles.forEachIndexed { i, candle ->
      if (i == 0) line.moveTo(x(i), y(candle.close)) else line.lineTo(x(i), y(candle.close))
    }
    val area =
      Path().apply {
        addPath(line)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
      }
    drawPath(area, colors.chartLine.copy(alpha = 0.10f))
    drawPath(line, colors.chartLine, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

    fun marker(index: Int) {
      val center = Offset(x(index), y(candles[index].close))
      drawCircle(surface, 6.dp.toPx(), center)
      drawCircle(colors.chartLine, 4.dp.toPx(), center)
    }
    marker(pointCount - 1)
    selectedIndex
      ?.takeIf { it in candles.indices }
      ?.let { index ->
        drawLine(colors.chartAxis, Offset(x(index), top), Offset(x(index), bottom), hairline)
        marker(index)
      }

    // Time labels: both ends always, the middle one only if there is room for it.
    val labelY = bottom + 6.dp.toPx()
    val (first, middle, last) = timeLabels
    drawText(first, topLeft = Offset(left, labelY))
    drawText(last, topLeft = Offset(right - last.size.width, labelY))
    val middleStart = (left + right) / 2f - middle.size.width / 2f
    val clearOfFirst = middleStart > left + first.size.width + gapPx
    val clearOfLast = middleStart + middle.size.width < right - last.size.width - gapPx
    if (clearOfFirst && clearOfLast) drawText(middle, topLeft = Offset(middleStart, labelY))
  }
}

private fun describe(candles: List<Candle>, range: ChartRange, type: AssetType?): String {
  val first = candles.first().close
  val last = candles.last().close
  val low = candles.minOf { it.close }
  val high = candles.maxOf { it.close }
  return "Price chart, ${range.label}. From ${Formatters.price(first, type)} to ${Formatters.price(last, type)}, " +
    "low ${Formatters.price(low, type)}, high ${Formatters.price(high, type)}."
}

/** Vertical axis: a little headroom around the data, with gridlines on round numbers. */
internal class PriceScale(val low: Double, val high: Double, val ticks: List<Double>, val decimals: Int) {
  companion object {
    fun of(values: List<Double>, targetTicks: Int = 4): PriceScale {
      val min = values.min()
      val max = values.max()
      // A flat line still needs a visible band around it.
      val span = (max - min).takeIf { it > 0 } ?: (abs(max) * 0.02).takeIf { it > 0 } ?: 1.0
      val low = min - span * 0.08
      val high = max + span * 0.08

      // Candidate steps are 1, 2 or 5 times a power of ten; take the one that yields a number of
      // gridlines closest to the target, preferring more over fewer when two are equally close.
      val magnitude = 10.0.pow(floor(log10((high - low) / targetTicks)))
      fun count(step: Double) = (floor(high / step) - ceil(low / step)).toInt() + 1
      val step =
        listOf(0.5, 1.0, 2.0, 5.0, 10.0)
          .map { it * magnitude }
          .minWith(compareBy<Double> { abs(count(it) - targetTicks) }.thenByDescending { count(it) })

      val firstIndex = ceil(low / step).toLong()
      val ticks = List(count(step).coerceAtLeast(0)) { (firstIndex + it) * step }
      // Enough decimals to tell neighbouring gridlines apart.
      val decimals = (-floor(log10(step))).toInt().coerceIn(0, 10)
      return PriceScale(low, high, ticks, decimals)
    }
  }
}
