package com.example.soinubiderxl

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke

enum class BackgroundTexture { NONE, LEOPARD, ZEBRA, CLOUDS, GEOMETRIC }

// Geometría estable, calculada al cambiar tamaño o apariencia; no usa imágenes ni audio.
fun Modifier.soinuBackground(palette: SoinuPalette): Modifier = drawWithCache {
    val tile = 64.dp.toPx()
    val paths = mutableListOf<Path>()
    if (palette.texture != BackgroundTexture.NONE) {
        for (row in -1..(size.height / tile).toInt() + 1) {
            for (column in -1..(size.width / tile).toInt() + 1) {
                val x = column * tile + if (row % 2 == 0) 0f else tile * .5f
                val y = row * tile
                paths += Path().apply {
                    when (palette.texture) {
                        BackgroundTexture.LEOPARD -> {
                            moveTo(x + tile * .18f, y + tile * .32f)
                            cubicTo(x - tile * .02f, y + tile * .02f, x + tile * .64f, y - tile * .02f, x + tile * .61f, y + tile * .31f)
                            cubicTo(x + tile * .82f, y + tile * .64f, x + tile * .15f, y + tile * .68f, x + tile * .18f, y + tile * .32f)
                        }
                        BackgroundTexture.ZEBRA -> {
                            moveTo(x, y)
                            cubicTo(x + tile * .85f, y + tile * .3f, x - tile * .25f, y + tile * .65f, x + tile * .65f, y + tile)
                            lineTo(x + tile * .84f, y + tile)
                            cubicTo(x + tile * .08f, y + tile * .6f, x + tile, y + tile * .3f, x + tile * .12f, y)
                            close()
                        }
                        BackgroundTexture.CLOUDS -> {
                            moveTo(x + tile * .12f, y + tile * .66f)
                            cubicTo(x - tile * .05f, y + tile * .38f, x + tile * .2f, y + tile * .22f, x + tile * .33f, y + tile * .39f)
                            cubicTo(x + tile * .34f, y + tile * .03f, x + tile * .75f, y + tile * .05f, x + tile * .73f, y + tile * .4f)
                            cubicTo(x + tile, y + tile * .28f, x + tile * 1.03f, y + tile * .7f, x + tile * .8f, y + tile * .7f)
                            lineTo(x + tile * .2f, y + tile * .7f)
                            close()
                        }
                        BackgroundTexture.GEOMETRIC -> {
                            moveTo(x + tile * .15f, y + tile * .15f)
                            lineTo(x + tile * .75f, y + tile * .3f)
                            lineTo(x + tile * .4f, y + tile * .8f)
                            close()
                        }
                        BackgroundTexture.NONE -> Unit
                    }
                }
            }
        }
    }
    val ink = palette.lit.copy(alpha = if (palette.texture == BackgroundTexture.ZEBRA) .085f else .12f)
    val outline = Stroke(1.2.dp.toPx())
    onDrawBehind {
        drawRect(palette.background)
        paths.forEach { path ->
            if (palette.texture == BackgroundTexture.ZEBRA || palette.texture == BackgroundTexture.CLOUDS) {
                drawPath(path, ink)
            } else {
                drawPath(path, ink, style = outline)
            }
        }
    }
}
