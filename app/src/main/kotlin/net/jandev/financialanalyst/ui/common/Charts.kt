package net.jandev.financialanalyst.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
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

data class BarPair(val label: String, val incomeCents: Long, val expenseCents: Long)

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Balken mit abgerundetem oberen Ende, unten bündig auf der Grundlinie. */
private fun DrawScope.topRoundedBar(color: Color, left: Float, top: Float, width: Float, bottom: Float, radius: Float) {
    if (bottom - top <= 0f) return
    val r = minOf(radius, width / 2, bottom - top)
    val path = Path().apply {
        addRoundRect(
            RoundRect(Rect(left, top, left + width, bottom), topLeft = CornerRadius(r, r), topRight = CornerRadius(r, r)),
        )
    }
    drawPath(path, color)
}

/**
 * Einnahmen und Ausgaben je Zeitraum als Balkenpaare. Antippen wählt einen Zeitraum;
 * die Werte des gewählten Zeitraums stehen als Zahlen über dem Diagramm.
 */
@Composable
fun IncomeExpenseChart(
    bars: List<BarPair>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val finance = LocalFinanceColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val selectedStyle = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface)
    val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
    val max = bars.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents) }?.coerceAtLeast(1) ?: 1
    val description = bars.joinToString("; ") {
        "${it.label}: Einnahmen ${Money.format(it.incomeCents)}, Ausgaben ${Money.format(it.expenseCents)}"
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(finance.chartIncome, "Einnahmen")
            LegendDot(finance.chartExpense, "Ausgaben")
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = description }
                .pointerInput(bars.size) {
                    detectTapGestures { offset ->
                        if (bars.isNotEmpty()) {
                            onSelect((offset.x / (size.width.toFloat() / bars.size)).toInt().coerceIn(0, bars.lastIndex))
                        }
                    }
                },
        ) {
            if (bars.isEmpty()) return@Canvas
            val labelHeight = 20.dp.toPx()
            val topPadding = 6.dp.toPx()
            val plotBottom = size.height - labelHeight
            val plotHeight = plotBottom - topPadding
            val slot = size.width / bars.size
            val gap = 2.dp.toPx()
            val barWidth = ((slot * 0.62f - gap) / 2).coerceIn(2.dp.toPx(), 18.dp.toPx())
            val radius = 4.dp.toPx()
            // So viele Beschriftungen auslassen, dass sie sich nicht überlappen.
            val widest = bars.maxOf { measurer.measure(it.label, labelStyle).size.width }
            val every = kotlin.math.ceil((widest + 6.dp.toPx()) / slot).toInt().coerceAtLeast(1)

            for (fraction in listOf(0.5f, 1f)) {
                val y = plotBottom - plotHeight * fraction
                drawLine(finance.chartGrid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            bars.forEachIndexed { index, bar ->
                val center = slot * index + slot / 2
                val selected = index == selectedIndex
                if (selected) {
                    drawRoundRect(
                        color = highlight,
                        topLeft = Offset(slot * index + 1.dp.toPx(), 0f),
                        size = Size(slot - 2.dp.toPx(), size.height),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
                val alpha = if (selected || selectedIndex !in bars.indices) 1f else 0.55f
                topRoundedBar(
                    finance.chartIncome.copy(alpha = alpha),
                    center - gap / 2 - barWidth,
                    plotBottom - plotHeight * bar.incomeCents / max,
                    barWidth, plotBottom, radius,
                )
                topRoundedBar(
                    finance.chartExpense.copy(alpha = alpha),
                    center + gap / 2,
                    plotBottom - plotHeight * bar.expenseCents / max,
                    barWidth, plotBottom, radius,
                )
                if (selected || (index - (bars.lastIndex % every)) % every == 0 && kotlin.math.abs(index - selectedIndex) >= every) {
                    val layout = measurer.measure(bar.label, if (selected) selectedStyle else labelStyle)
                    val x = (center - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                    drawText(layout, topLeft = Offset(x, plotBottom + 4.dp.toPx()))
                }
            }
            drawLine(finance.chartGrid, Offset(0f, plotBottom), Offset(size.width, plotBottom), strokeWidth = 1.dp.toPx())
        }
    }
}

/** Waagerechter Anteil-Balken in einer Farbe, für Ranglisten. */
@Composable
fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}
