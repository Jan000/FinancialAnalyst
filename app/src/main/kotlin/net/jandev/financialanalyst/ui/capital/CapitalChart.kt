package net.jandev.financialanalyst.ui.capital

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Ein Punkt des Kapitalverlaufs: Stand am Monatsende (beim heutigen Punkt: heute). */
data class CapitalPoint(val date: LocalDate, val cents: Long)

/** Rundet einen Abstand auf 1, 2 oder 5 mal eine Zehnerpotenz, für glatte Achsenwerte. */
private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val power = 10.0.pow(floor(log10(raw)))
    val fraction = raw / power
    return power * when {
        fraction <= 1 -> 1.0
        fraction <= 2 -> 2.0
        fraction <= 5 -> 5.0
        else -> 10.0
    }
}

private fun euroLabel(cents: Long): String = Money.format(cents).substringBefore(',') + " €"

/**
 * Kapitalverlauf: bisher (neutral), Prognose (Akzentfarbe), Trendgerade (gepunktet) und optional die Rechnung mit
 * Sparvorgabe (gestrichelt). Fläche zur Nulllinie grün darüber, rot darunter. Antippen oder Wischen wählt einen Punkt.
 *
 * @param history bisherige Monatsendstände, der letzte ist heute
 * @param projection Monatsendstände ab dem laufenden Monat
 */
@Composable
fun CapitalChart(
    history: List<CapitalPoint>,
    projection: List<CapitalPoint>,
    scenario: List<CapitalPoint>?,
    trendPerMonthCents: Long,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    /** Ereignisse wie ein abbezahlter Kredit: Index in der gemeinsamen Achse (bisher + Prognose) und Beschriftung. */
    events: List<Pair<Int, String>> = emptyList(),
) {
    val finance = LocalFinanceColors.current
    val historyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val projectionColor = MaterialTheme.colorScheme.primary
    val scenarioColor = finance.chart.getOrElse(2) { finance.chartIncome }
    val trendColor = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val zeroColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)

    // Gemeinsame x-Achse: bisherige Monate, dann die Prognose; „heute“ ist der letzte bisherige Punkt.
    val todayIndex = history.lastIndex.coerceAtLeast(0)
    val all = history + projection
    val count = all.size
    val values = all.map { it.cents } + scenario.orEmpty().map { it.cents } + 0L
    val rawMin = values.min()
    val rawMax = values.max()
    val step = niceStep((rawMax - rawMin).coerceAtLeast(10000).toDouble() / 4)
    val axisMin = (floor(rawMin / step) * step).toLong()
    val axisMax = (ceil(rawMax / step) * step).toLong().let { if (it == axisMin) axisMin + step.toLong() else it }
    val ticks = generateSequence(axisMin) { it + step.toLong() }.takeWhile { it <= axisMax }.toList()

    Canvas(
        modifier
            .fillMaxWidth()
            .height(240.dp)
            .semantics { contentDescription = description }
            .pointerInput(count) {
                detectTapGestures { offset -> onSelect(indexAt(offset.x, size.width.toFloat(), count, gutter(ticks, measurer, labelStyle).toFloat())) }
            }
            .pointerInput(count) {
                detectHorizontalDragGestures { change, _ ->
                    onSelect(indexAt(change.position.x, size.width.toFloat(), count, gutter(ticks, measurer, labelStyle).toFloat()))
                }
            },
    ) {
        if (count < 2) return@Canvas
        val left = gutter(ticks, measurer, labelStyle).toFloat()
        val top = 8.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val right = size.width - 6.dp.toPx()
        fun x(index: Int) = left + (right - left) * index / (count - 1)
        fun y(cents: Long) = bottom - (bottom - top) * (cents - axisMin).toFloat() / (axisMax - axisMin).toFloat()
        val zeroY = y(0)

        // Raster mit Werten in vollen Euro.
        for (tick in ticks) {
            val ty = y(tick)
            drawLine(if (tick == 0L) zeroColor else finance.chartGrid, Offset(left, ty), Offset(right, ty), strokeWidth = (if (tick == 0L) 1.5f else 1f) * density)
            val text = measurer.measure(euroLabel(tick), labelStyle)
            drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), ty - text.size.height / 2))
        }

        // Fläche zwischen Kurve und Null: grün im Plus, rot im Minus.
        val area = Path().apply {
            moveTo(x(0), zeroY)
            all.forEachIndexed { index, point -> lineTo(x(index), y(point.cents)) }
            lineTo(x(count - 1), zeroY)
            close()
        }
        clipRect(left = left, top = top, right = right, bottom = zeroY) { drawPath(area, finance.chartIncome.copy(alpha = 0.14f)) }
        clipRect(left = left, top = zeroY, right = right, bottom = bottom) { drawPath(area, finance.chartExpense.copy(alpha = 0.16f)) }

        // „Heute“-Linie.
        val todayX = x(todayIndex)
        drawLine(labelColor.copy(alpha = 0.5f), Offset(todayX, top), Offset(todayX, bottom), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        val todayText = measurer.measure("heute", labelStyle)
        drawText(todayText, topLeft = Offset((todayX - todayText.size.width / 2).coerceIn(left, right - todayText.size.width), bottom + 4.dp.toPx()))

        // Jahreswechsel unter der Achse, sofern sie nicht mit „heute“ kollidieren.
        var lastLabelEnd = -1f
        all.forEachIndexed { index, point ->
            if (point.date.monthValue != 12 || index == count - 1) return@forEachIndexed
            val label = measurer.measure((point.date.year + 1).toString(), labelStyle)
            val lx = x(index) - label.size.width / 2
            val clashesToday = abs(x(index) - todayX) < (todayText.size.width + label.size.width) / 2 + 4.dp.toPx()
            if (lx > lastLabelEnd + 4.dp.toPx() && !clashesToday && lx + label.size.width <= right) {
                drawText(label, topLeft = Offset(lx, bottom + 4.dp.toPx()))
                lastLabelEnd = lx + label.size.width
            }
        }

        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(points: List<Pair<Int, Long>>, color: Color, effect: PathEffect? = null) {
            if (points.size < 2) return
            val path = Path().apply {
                points.forEachIndexed { i, (index, cents) -> if (i == 0) moveTo(x(index), y(cents)) else lineTo(x(index), y(cents)) }
            }
            drawPath(path, color, style = Stroke(width = stroke.width, cap = stroke.cap, join = stroke.join, pathEffect = effect))
        }
        // Trend: Ausgleichsgerade durch heute und die Monatsendstände der Prognose.
        val start = history.lastOrNull()?.cents ?: projection.first().cents
        val fitted = listOf(start) + projection.map { it.cents }
        val meanX = (fitted.size - 1) / 2.0
        val intercept = fitted.average() - trendPerMonthCents * meanX
        val trendStart = intercept.toLong()
        val trendEnd = (intercept + trendPerMonthCents * (fitted.size - 1)).toLong()
        drawLine(trendColor, Offset(todayX, y(trendStart)), Offset(x(count - 1), y(trendEnd)), strokeWidth = 1.5f.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 5.dp.toPx())))

        line(history.mapIndexed { index, point -> index to point.cents }, historyColor)
        scenario?.let { list -> line(listOf(todayIndex to start) + list.mapIndexed { i, p -> todayIndex + 1 + i to p.cents }, scenarioColor, PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))) }
        line(listOf(todayIndex to start) + projection.mapIndexed { i, p -> todayIndex + 1 + i to p.cents }, projectionColor)

        // Ereignisse: senkrechte Linie in Einnahmenfarbe, Punkt auf der Kurve und kurze Beschriftung oben.
        events.filter { it.first in all.indices }.forEach { (index, label) ->
            val ex = x(index)
            drawLine(finance.income.copy(alpha = 0.7f), Offset(ex, top), Offset(ex, bottom), strokeWidth = 1.5f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            drawCircle(surface, radius = 6.dp.toPx(), center = Offset(ex, y(all[index].cents)))
            drawCircle(finance.income, radius = 4.dp.toPx(), center = Offset(ex, y(all[index].cents)))
            val text = measurer.measure(label, labelStyle.copy(color = finance.income))
            val tx = if (ex + 4.dp.toPx() + text.size.width <= right) ex + 4.dp.toPx() else ex - 4.dp.toPx() - text.size.width
            drawRect(surface.copy(alpha = 0.85f), topLeft = Offset(tx - 2.dp.toPx(), top), size = androidx.compose.ui.geometry.Size(text.size.width + 4.dp.toPx(), text.size.height.toFloat()))
            drawText(text, topLeft = Offset(tx, top))
        }

        // Gewählter Punkt mit Ring in Flächenfarbe.
        if (selectedIndex in all.indices) {
            val sx = x(selectedIndex)
            val sy = y(all[selectedIndex].cents)
            drawLine(labelColor.copy(alpha = 0.35f), Offset(sx, top), Offset(sx, bottom), strokeWidth = 1.dp.toPx())
            val dot = if (selectedIndex <= todayIndex) historyColor else projectionColor
            drawCircle(surface, radius = 7.dp.toPx(), center = Offset(sx, sy))
            drawCircle(dot, radius = 5.dp.toPx(), center = Offset(sx, sy))
            scenario?.getOrNull(selectedIndex - todayIndex - 1)?.let { p ->
                drawCircle(surface, radius = 6.dp.toPx(), center = Offset(sx, y(p.cents)))
                drawCircle(scenarioColor, radius = 4.dp.toPx(), center = Offset(sx, y(p.cents)))
            }
        }
    }
}

private fun gutter(ticks: List<Long>, measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle): Int =
    ticks.maxOf { measurer.measure(euroLabel(it), style).size.width } + 24

private fun indexAt(x: Float, width: Float, count: Int, left: Float): Int {
    if (count < 2) return 0
    val right = width - 18f
    val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
    return Math.round(fraction * (count - 1))
}
