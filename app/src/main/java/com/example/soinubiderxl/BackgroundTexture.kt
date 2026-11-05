package com.example.soinubiderxl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
    if (palette.texture == BackgroundTexture.GEOMETRIC) return geometricBackground()
    // Misma imagen: levantar los tonos del pelo sin apagar los blancos.
    val animal = palette.texture == BackgroundTexture.LEOPARD || palette.texture == BackgroundTexture.ZEBRA
    val materialFilter = remember(palette.texture) {
        val gain = when (palette.texture) {
            BackgroundTexture.LEOPARD -> 3.2f
            BackgroundTexture.ZEBRA -> 5f
            else -> 1f
        }
        val offset = when (palette.texture) {
            BackgroundTexture.LEOPARD -> -6f
            BackgroundTexture.ZEBRA -> -16f
            else -> 0f
        }
        if (!animal) null else ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            gain, 0f, 0f, 0f, offset,
            0f, gain, 0f, 0f, offset,
            0f, 0f, gain, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )))
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
                filterQuality = FilterQuality.Medium,
                colorFilter = materialFilter
            )
            if (!animal) drawRect(Color.Black.copy(alpha = .18f))
        }
    }
}
