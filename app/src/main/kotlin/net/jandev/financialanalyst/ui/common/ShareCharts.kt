package net.jandev.financialanalyst.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.sqrt

/** Ein Anteil in Kreis-, Balken- oder Kacheldiagramm; die Farbe gehört zum Eintrag, nicht zu seinem Rang. */
data class ChartSlice(val key: String, val label: String, val cents: Long, val color: Color)

/**
 * Anteile als Ring. Antippen wählt einen Anteil, die Mitte zeigt dann ihn statt der Summe. Zwischen den Anteilen
 * bleibt eine schmale Fuge.
 */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    centerTitle: String,
    centerValue: String,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.cents }.coerceAtLeast(1)
    val measurer = rememberTextMeasurer()
    val titleStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val valueStyle = TextStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    val description = slices.joinToString("; ") { "${it.label}: ${Money.format(it.cents)}" }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics { contentDescription = description }
            .pointerInput(slices, selectedKey) {
                detectTapGestures { offset ->
                    val dx = offset.x - size.width / 2f
                    val dy = offset.y - size.height / 2f
                    val radius = minOf(size.width, size.height) / 2f
                    val distance = sqrt(dx * dx + dy * dy)
                    if (distance < radius * 0.5f || distance > radius) {
                        onSelect(null)
                        return@detectTapGestures
                    }
                    // Winkel ab 12 Uhr im Uhrzeigersinn.
                    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90
                    if (angle < 0) angle += 360
                    var start = 0.0
                    for (slice in slices) {
                        val sweep = 360.0 * slice.cents / total
                        if (angle < start + sweep) {
                            onSelect(if (slice.key == selectedKey) null else slice.key)
                            return@detectTapGestures
                        }
                        start += sweep
                    }
                }
            },
    ) {
        val diameter = minOf(size.width, size.height)
        val stroke = diameter * 0.16f
        val topLeft = Offset((size.width - diameter) / 2 + stroke / 2, (size.height - diameter) / 2 + stroke / 2)
        val arcSize = Size(diameter - stroke, diameter - stroke)
        val gapDegrees = if (slices.size > 1) Math.toDegrees((2.dp.toPx() / ((diameter - stroke) / 2)).toDouble()).toFloat() else 0f
        var start = -90f
        for (slice in slices) {
            val sweep = 360f * slice.cents / total
            val selected = slice.key == selectedKey
            val alpha = if (selectedKey == null || selected) 1f else 0.35f
            drawArc(
                color = slice.color.copy(alpha = alpha),
                startAngle = start + gapDegrees / 2,
                sweepAngle = (sweep - gapDegrees).coerceAtLeast(0.5f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = if (selected) stroke * 1.15f else stroke),
            )
            start += sweep
        }
        val maxText = (diameter - 2.4f * stroke).toInt().coerceAtLeast(1)
        val title = measurer.measure(centerTitle, titleStyle, constraints = Constraints(maxWidth = maxText), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val value = measurer.measure(centerValue, valueStyle, constraints = Constraints(maxWidth = maxText), maxLines = 1)
        val block = title.size.height + value.size.height
        drawText(title, topLeft = Offset(size.width / 2 - title.size.width / 2, size.height / 2 - block / 2))
        drawText(value, topLeft = Offset(size.width / 2 - value.size.width / 2, size.height / 2 - block / 2 + title.size.height))
    }
}

/**
 * Anteile als Kacheln (Treemap): die Fläche entspricht dem Betrag. Große Kacheln tragen Name und Betrag, kleine nur
 * die Farbe; die Liste darunter nennt alle.
 */
@Composable
fun TreemapChart(
    slices: List<ChartSlice>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
    val valueStyle = TextStyle(fontSize = 11.sp, color = Color.White)
    val positive = slices.filter { it.cents > 0 }
    val description = positive.joinToString("; ") { "${it.label}: ${Money.format(it.cents)}" }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = description }
            .pointerInput(positive, selectedKey) {
                detectTapGestures { offset ->
                    val rects = squarify(positive.map { it.cents.toDouble() }, Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()))
                    val hit = positive.getOrNull(rects.indexOfFirst { it.contains(offset) })
                    onSelect(hit?.key?.takeIf { it != selectedKey })
                }
            },
    ) {
        val rects = squarify(positive.map { it.cents.toDouble() }, Rect(0f, 0f, size.width, size.height))
        val gap = 2.dp.toPx()
        val pad = 6.dp.toPx()
        positive.forEachIndexed { index, slice ->
            val r = rects[index]
            val inner = Rect(r.left + gap / 2, r.top + gap / 2, r.right - gap / 2, r.bottom - gap / 2)
            if (inner.width <= 0f || inner.height <= 0f) return@forEachIndexed
            val alpha = if (selectedKey == null || selectedKey == slice.key) 1f else 0.35f
            drawRoundRect(slice.color.copy(alpha = alpha), topLeft = inner.topLeft, size = inner.size, cornerRadius = CornerRadius(4.dp.toPx()))
            val maxWidth = (inner.width - 2 * pad).toInt()
            if (maxWidth < 28.dp.toPx()) return@forEachIndexed
            val label = measurer.measure(slice.label, labelStyle, constraints = Constraints(maxWidth = maxWidth), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val value = measurer.measure(Money.format(slice.cents), valueStyle, constraints = Constraints(maxWidth = maxWidth), maxLines = 1)
            if (label.size.height + value.size.height + 2 * pad <= inner.height) {
                drawText(label, topLeft = Offset(inner.left + pad, inner.top + pad))
                drawText(value, topLeft = Offset(inner.left + pad, inner.top + pad + label.size.height))
            } else if (label.size.height + 2 * pad <= inner.height) {
                drawText(label, topLeft = Offset(inner.left + pad, inner.top + pad))
            }
        }
    }
}

/** Teilt [bounds] nach dem Squarified-Verfahren in Rechtecke mit den Flächenanteilen von [values] (absteigend erwartet). */
internal fun squarify(values: List<Double>, bounds: Rect): List<Rect> {
    val total = values.sum()
    if (values.isEmpty() || total <= 0) return values.map { Rect.Zero }
    val scale = bounds.width * bounds.height / total
    val areas = values.map { it * scale }
    val result = ArrayList<Rect>(values.size)
    var rest = bounds
    var index = 0
    fun worst(row: List<Double>, side: Float): Double {
        val sum = row.sum()
        val s2 = side.toDouble() * side
        return maxOf(s2 * row.max() / (sum * sum), sum * sum / (s2 * row.min()))
    }
    while (index < areas.size) {
        val side = minOf(rest.width, rest.height)
        val row = arrayListOf(areas[index])
        var next = index + 1
        while (next < areas.size && worst(row + areas[next], side) <= worst(row, side)) {
            row.add(areas[next])
            next++
        }
        val rowSum = row.sum().toFloat()
        if (rest.width >= rest.height) {
            val width = if (rest.height > 0) rowSum / rest.height else 0f
            var y = rest.top
            for (area in row) {
                val h = if (width > 0) area.toFloat() / width else 0f
                result.add(Rect(rest.left, y, rest.left + width, y + h))
                y += h
            }
            rest = Rect(rest.left + width, rest.top, rest.right, rest.bottom)
        } else {
            val height = if (rest.width > 0) rowSum / rest.width else 0f
            var x = rest.left
            for (area in row) {
                val w = if (height > 0) area.toFloat() / height else 0f
                result.add(Rect(x, rest.top, x + w, rest.top + height))
                x += w
            }
            rest = Rect(rest.left, rest.top + height, rest.right, rest.bottom)
        }
        index = next
    }
    return result
}

/** Eine Säule eines gestapelten Diagramms: Teilbeträge von unten nach oben in der Reihenfolge der Liste. */
data class StackedBar(val label: String, val segments: List<Pair<Color, Long>>) {
    val total: Long get() = segments.sumOf { it.second }
}

/**
 * Gestapelte Säulen je Zeitraum (etwa Monate). Antippen wählt eine Säule; Beschriftungen werden ausgedünnt, damit sie
 * sich nicht überlappen. Eine gestrichelte Linie markiert auf Wunsch einen Vergleichswert (Durchschnitt).
 */
@Composable
fun StackedBarChart(
    bars: List<StackedBar>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    referenceCents: Long? = null,
) {
    val finance = LocalFinanceColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val selectedStyle = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val referenceColor = MaterialTheme.colorScheme.onSurfaceVariant
    val max = maxOf(bars.maxOfOrNull { it.total } ?: 1, referenceCents ?: 0).coerceAtLeast(1)
    val description = bars.joinToString("; ") { "${it.label}: ${Money.format(it.total)}" }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = description }
            .pointerInput(bars.size) {
                detectTapGestures { offset ->
                    if (bars.isNotEmpty()) onSelect((offset.x / (size.width.toFloat() / bars.size)).toInt().coerceIn(0, bars.lastIndex))
                }
            },
    ) {
        if (bars.isEmpty()) return@Canvas
        val labelHeight = 18.dp.toPx()
        val topPadding = 6.dp.toPx()
        val plotBottom = size.height - labelHeight
        val plotHeight = plotBottom - topPadding
        val slot = size.width / bars.size
        val barWidth = (slot * 0.68f).coerceIn(3.dp.toPx(), 28.dp.toPx())
        val gap = 2.dp.toPx()
        val widest = bars.maxOf { measurer.measure(it.label, labelStyle).size.width }
        val every = ceil((widest + 6.dp.toPx()) / slot).toInt().coerceAtLeast(1)
        bars.forEachIndexed { index, bar ->
            val selected = index == selectedIndex
            if (selected) {
                drawRoundRect(highlight, topLeft = Offset(slot * index, 0f), size = Size(slot, size.height), cornerRadius = CornerRadius(6.dp.toPx()))
            }
            val left = slot * index + (slot - barWidth) / 2
            val alpha = 1f
            var bottom = plotBottom
            val visible = bar.segments.filter { it.second > 0 }
            visible.forEachIndexed { segmentIndex, (color, cents) ->
                val height = plotHeight * cents / max
                val top = bottom - height
                if (segmentIndex == visible.lastIndex) {
                    topRoundedBar(color.copy(alpha = alpha), left, top, barWidth, bottom, 3.dp.toPx())
                } else {
                    drawRect(color.copy(alpha = alpha), topLeft = Offset(left, top), size = Size(barWidth, height))
                    // Schmale Fuge in Flächenfarbe zum nächsten Teil.
                    if (height > gap) drawRect(if (selected) highlight else surface, topLeft = Offset(left, top - gap / 2), size = Size(barWidth, gap))
                }
                bottom = top
            }
            if (selected || (index % every == 0 && abs(index - selectedIndex) >= every)) {
                val layout = measurer.measure(bar.label, if (selected) selectedStyle else labelStyle)
                val x = (slot * index + slot / 2 - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(x, plotBottom + 3.dp.toPx()))
            }
        }
        drawLine(finance.chartGrid, Offset(0f, plotBottom), Offset(size.width, plotBottom), strokeWidth = 1.dp.toPx())
        // Rasterlinien mit Wert in vollen Euro, über den Säulen, damit nichts verdeckt wird.
        for (fraction in listOf(0.5f, 1f)) {
            val y = plotBottom - plotHeight * fraction
            drawLine(finance.chartGrid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            val value = measurer.measure(Money.format((max * fraction).toLong() / 100 * 100).substringBefore(',') + " €", labelStyle)
            drawRect(surface.copy(alpha = 0.85f), topLeft = Offset(0f, y - value.size.height - 1.dp.toPx()), size = Size(value.size.width + 4.dp.toPx(), value.size.height.toFloat()))
            drawText(value, topLeft = Offset(2.dp.toPx(), y - value.size.height - 1.dp.toPx()))
        }
        if (referenceCents != null && referenceCents > 0) {
            val y = plotBottom - plotHeight * referenceCents / max
            drawLine(
                referenceColor.copy(alpha = 0.8f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
    }
}
