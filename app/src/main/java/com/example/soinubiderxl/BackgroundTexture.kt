package com.example.soinubiderxl

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

enum class BackgroundTexture { NONE, LEOPARD, ZEBRA, CLOUDS, GEOMETRIC }

// Cuatro materiales en una imagen. El recorte se calcula al cambiar el tamaño.
@Composable
fun Modifier.soinuBackground(palette: SoinuPalette): Modifier {
    if (palette.texture == BackgroundTexture.NONE) {
        return drawWithCache { onDrawBehind { drawRect(palette.background) } }
    }
    val atlas = ImageBitmap.imageResource(R.drawable.soinu_texture_atlas)
    val column = when (palette.texture) {
        BackgroundTexture.ZEBRA, BackgroundTexture.GEOMETRIC -> 1
        else -> 0
    }
    val row = when (palette.texture) {
        BackgroundTexture.CLOUDS, BackgroundTexture.GEOMETRIC -> 1
        else -> 0
    }
    return drawWithCache {
        // Recorta dentro del cuadrante: no estira el material ni muestra las juntas.
        val cellWidth = atlas.width / 2
        val cellHeight = atlas.height / 2
        val scale = maxOf(size.width / cellWidth, size.height / cellHeight)
        val cropWidth = ceil(size.width / scale).toInt().coerceIn(1, cellWidth)
        val cropHeight = ceil(size.height / scale).toInt().coerceIn(1, cellHeight)
        val source = IntOffset(
            column * cellWidth + (cellWidth - cropWidth) / 2,
            row * cellHeight + (cellHeight - cropHeight) / 2
        )
        val destination = IntSize(ceil(size.width).toInt().coerceAtLeast(1), ceil(size.height).toInt().coerceAtLeast(1))
        onDrawBehind {
            drawRect(palette.background)
            drawImage(
                image = atlas,
                srcOffset = source,
                srcSize = IntSize(cropWidth, cropHeight),
                dstSize = destination,
                filterQuality = FilterQuality.Medium
            )
            drawRect(Color.Black.copy(alpha = .18f))
        }
    }
}
