package com.example.soinubiderxl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class SoinuPalette(
    val background: Color,
    val control: Color,
    val empty: Color,
    val lit: Color,
    val recording: Color,
    val playing: Color,
    val paused: Color,
    val texture: BackgroundTexture = BackgroundTexture.NONE
) {
    val foreground = Color.White
    val previewStripes: List<Color>
        get() = listOf(background, empty, paused, control, playing, recording, lit)
}

enum class Appearance(val preference: String, val title: String, val palette: SoinuPalette) {
    VIOLET("violeta", "Violeta", SoinuPalette(
        background = SoinuConfiguration.background,
        control = SoinuConfiguration.control,
        empty = SoinuConfiguration.empty,
        lit = SoinuConfiguration.lit,
        recording = SoinuConfiguration.recording,
        playing = SoinuConfiguration.playing,
        paused = SoinuConfiguration.paused
    )),
    BLUE("azul", "Azul", SoinuPalette(
        background = Color(0xFF191B20), control = Color(0xFF35465B),
        empty = Color(0xFF2C323B), lit = Color(0xFF75B9F1),
        recording = Color(0xFF397FC0), playing = Color(0xFF24578A), paused = Color(0xFF424D5C)
    )),
    AMBER("ambar", "Ámbar", SoinuPalette(
        background = Color(0xFF1C1B19), control = Color(0xFF514333),
        empty = Color(0xFF34312D), lit = Color(0xFFDFAD64),
        recording = Color(0xFFAD7A39), playing = Color(0xFF805929), paused = Color(0xFF56504A)
    )),
    GRAY("gris", "Gris", SoinuPalette(
        background = Color(0xFF1A1B1E), control = Color(0xFF45484F),
        empty = Color(0xFF303237), lit = Color(0xFFB1B6BE),
        recording = Color(0xFF828892), playing = Color(0xFF626771), paused = Color(0xFF50535A)
    )),
    LEOPARD("leopardo", "Leopardo", AMBER.palette.copy(texture = BackgroundTexture.LEOPARD)),
    ZEBRA("cebra", "Cebra", GRAY.palette.copy(texture = BackgroundTexture.ZEBRA)),
    CLOUDS("nubes", "Nubes", BLUE.palette.copy(texture = BackgroundTexture.CLOUDS)),
    GEOMETRIC("geometrica", "Geométrica", SoinuPalette(
        background = Color.Black, control = Color(0xFF24485D),
        empty = Color(0xFF24485D), lit = Color(0xFF487F96),
        recording = Color(0xFF9B344E), playing = Color(0xFF216A5A), paused = Color(0xFF353943),
        texture = BackgroundTexture.GEOMETRIC
    ));

    companion object {
        fun fromPreference(value: String?): Appearance = entries.firstOrNull { it.preference == value } ?: VIOLET
    }
}

val LocalSoinuPalette = staticCompositionLocalOf { Appearance.VIOLET.palette }

val LocalButtonOpacity = staticCompositionLocalOf { .80f }

// La transparencia afecta al relleno; conserva textos y LED opacos.
@Composable
fun Color.buttonFill(): Color {
    val opacity = LocalButtonOpacity.current
    return copy(alpha = opacity.coerceIn(0f, 1f))
}

// Colores profundos que mantienen el contraste del texto blanco.
private val geometricButtonColors = listOf(
    Color(0xFF205674), Color(0xFF604177), Color(0xFF813D51),
    Color(0xFF236158), Color(0xFF77561F), Color(0xFF3E4F85)
)

fun SoinuPalette.buttonColor(index: Int, fallback: Color = control): Color =
    if (texture == BackgroundTexture.GEOMETRIC) geometricButtonColors[Math.floorMod(index, geometricButtonColors.size)]
    else fallback
