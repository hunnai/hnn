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

// Animales en dos columnas; nubes en el atlas original. Recorte sin estirar.
@Composable
fun Modifier.soinuBackground(palette: SoinuPalette): Modifier {
    if (palette.texture == BackgroundTexture.NONE) {
        return drawWithCache { onDrawBehind { drawRect(palette.background) } }
    }
    if (palette.texture == BackgroundTexture.GEOMETRIC) return geometricBackground()
    val animal = palette.texture == BackgroundTexture.LEOPARD || palette.texture == BackgroundTexture.ZEBRA
    val atlas = ImageBitmap.imageResource(
        if (animal) R.drawable.soinu_animal_textures else R.drawable.soinu_texture_atlas
    )
    val column = if (palette.texture == BackgroundTexture.ZEBRA) 1 else 0
    val row = if (palette.texture == BackgroundTexture.CLOUDS) 1 else 0
    return drawWithCache {
        // Recorta dentro de cada material: no estira el material ni muestra las juntas.
        val cellWidth = atlas.width / 2
        val cellHeight = if (animal) atlas.height else atlas.height / 2
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
            if (!animal) drawRect(Color.Black.copy(alpha = .18f))
        }
    }
}
