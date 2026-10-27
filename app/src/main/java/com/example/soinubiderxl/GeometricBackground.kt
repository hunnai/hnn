package com.example.soinubiderxl

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class GeometricFigure(
    val x: Float, val y: Float, val radius: Float,
    val sides: Int, val rotation: Float, val color: Int, val filled: Boolean
)

// Composición asimétrica: cuatro formas, varias escalas y espacio negro entre ellas.
private val figures = listOf(
    GeometricFigure(30f, 24f, 21f, 0, 0f, 0, false),
    GeometricFigure(152f, 14f, 12f, 3, 15f, 2, true),
    GeometricFigure(278f, 37f, 34f, 5, -20f, 1, false),
    GeometricFigure(355f, 100f, 18f, 4, 18f, 4, true),
    GeometricFigure(92f, 102f, 30f, 4, 14f, 3, false),
    GeometricFigure(215f, 111f, 17f, 0, 0f, 4, true),
    GeometricFigure(16f, 167f, 26f, 3, -15f, 1, true),
    GeometricFigure(161f, 184f, 41f, 5, 14f, 0, false),
    GeometricFigure(298f, 192f, 26f, 3, 25f, 2, false),
    GeometricFigure(75f, 239f, 12f, 0, 0f, 2, true),
    GeometricFigure(241f, 264f, 33f, 4, 32f, 1, true),
    GeometricFigure(359f, 300f, 29f, 0, 0f, 3, false),
    GeometricFigure(32f, 327f, 37f, 5, -8f, 4, false),
    GeometricFigure(143f, 304f, 16f, 3, -20f, 3, true),
    GeometricFigure(144f, 410f, 34f, 0, 0f, 1, false),
    GeometricFigure(302f, 391f, 39f, 5, 22f, 0, true),
    GeometricFigure(52f, 451f, 20f, 4, -10f, 0, true),
    GeometricFigure(220f, 473f, 24f, 3, 12f, 2, false),
    GeometricFigure(351f, 496f, 15f, 4, 25f, 4, false)
)
private val figureColors = listOf(
    Color(0xFF49C6EE), Color(0xFFB48BF0), Color(0xFFFF738A),
    Color(0xFF4ED5B0), Color(0xFFF3BE58)
)

internal fun Modifier.geometricBackground(): Modifier = drawWithCache {
    val unit = size.width.coerceAtMost(440.dp.toPx()).coerceAtLeast(1f) / 360f
    val period = 540f * unit
    val columns = (size.width / (360f * unit)).toInt() + 1
    val rows = (size.height / period).toInt() + 1
    data class Mark(val path: Path, val brush: Brush, val stroke: Color, val width: Float)
    val marks = buildList {
        for (row in 0..rows) for (column in 0..columns) {
            figures.forEachIndexed { index, figure ->
                val x = (figure.x + column * 360f) * unit
                val y = figure.y * unit + row * period
                val radius = figure.radius * unit
                val path = Path().apply {
                    if (figure.sides == 0) {
                        addOval(Rect(x - radius, y - radius, x + radius, y + radius))
                    } else {
                        repeat(figure.sides) { vertex ->
                            val angle = (figure.rotation - 90f) * PI / 180 + vertex * 2 * PI / figure.sides
                            val px = x + cos(angle).toFloat() * radius
                            val py = y + sin(angle).toFloat() * radius
                            if (vertex == 0) moveTo(px, py) else lineTo(px, py)
                        }
                        close()
                    }
                }
                val color = figureColors[figure.color]
                add(Mark(path, Brush.linearGradient(
                    listOf(color.copy(alpha = if (figure.filled) .24f else .055f),
                        color.copy(alpha = if (figure.filled) .07f else .012f)),
                    start = Offset(x - radius, y - radius), end = Offset(x + radius, y + radius)
                ), color.copy(alpha = if (figure.filled) .34f else .52f),
                    (if (index % 3 == 0) 1.6f else 1.05f) * unit))
            }
        }
    }
    onDrawBehind {
        drawRect(Color.Black)
        marks.forEach { mark ->
            drawPath(mark.path, mark.brush)
            drawPath(mark.path, mark.stroke, style = Stroke(mark.width))
        }
    }
}
