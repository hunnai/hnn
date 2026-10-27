package com.example.soinubiderxl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private lateinit var controller: SoinuController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ViewModelProvider(this)[SoinuController::class.java]
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val palette = controller.state.appearance.palette
            CompositionLocalProvider(LocalSoinuPalette provides palette) {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = palette.lit,
                onPrimary = palette.foreground,
                onPrimaryContainer = palette.foreground,
                onSurfaceVariant = palette.foreground,
                background = palette.background,
                surface = palette.background,
                onBackground = palette.foreground,
                onSurface = palette.foreground
            )) {
                CompositionLocalProvider(LocalContentColor provides palette.foreground) {
                val permission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { allowed ->
                    if (allowed) controller.start()
                    else controller.error("Permite el micrófono en los ajustes de Android para recibir audio.")
                }
                val startAudio: () -> Unit = {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) controller.start()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }
                LaunchedEffect(controller) {
                    if (controller.claimInitialStart()) startAudio()
                }
                SoinuScreen(controller)
                }
            }
            }
        }
    }
    override fun onStart() { super.onStart(); controller.onVisible() }
    override fun onStop() { controller.onHidden(); super.onStop() }
}

@Composable
private fun SoinuScreen(controller: SoinuController) {
    val palette = LocalSoinuPalette.current
    val state = controller.state
    var settings by rememberSaveable { mutableStateOf(false) }
    var touching by remember { mutableStateOf(setOf<Int>()) }
    BackHandler(enabled = settings) { settings = false }
    if (settings) {
        SettingsScreen(state, { settings = false }, controller::saveAdvance,
            controller::resetSavedAdvance, controller::setAutoMinimum, controller::setAutoMaximum,
            controller::setAppearance)
    } else {
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.background).safeDrawingPadding()) {
            // Conserva las proporciones de la referencia y permite desplazar
            // la pantalla en teléfonos pequeños y orientación horizontal.
            val contentHeight = maxOf(maxHeight, 520.dp)
            val scale = (maxWidth.value / 320f).coerceIn(.8f, 1.6f)
            val unit = (contentHeight - 84.dp) / 9.57f
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = touching.isEmpty())) {
                Column(Modifier.fillMaxWidth().height(contentHeight).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().height(unit * 1.25f), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(44.dp))
                        Text("SoinuBider XL", Modifier.weight(1f), fontSize = (23 * scale).sp,
                            maxLines = 1, textAlign = TextAlign.Center)
                        GearButton { controller.cancelTouches(); touching = emptySet(); settings = true }
                    }
                    InputMeter(state.inputPeak, Modifier.fillMaxWidth().height(unit * .52f))
                    TempoPanel(state, controller, unit * 1.8f, scale)
                    Row(Modifier.fillMaxWidth().height(unit), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("Borrar beats", "erase", controller, Modifier.weight(1f).fillMaxHeight(), textScale = scale)
                        ActionButton("Reset BPM", "reset", controller, Modifier.weight(1f).fillMaxHeight(), textScale = scale)
                    }
                    for (row in 0..1) {
                        Row(Modifier.fillMaxWidth().height(unit * 2), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (column in 0..2) {
                                val index = row * 3 + column
                                BankPad(index, controller, Modifier.weight(1f).fillMaxHeight(), scale) { down ->
                                    touching = if (down) touching + index else touching - index
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(unit), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("<<", "back", controller, Modifier.weight(1f).fillMaxHeight(), state.audioRunning, scale)
                        ActionButton(">>", "ahead", controller, Modifier.weight(1f).fillMaxHeight(), state.audioRunning, scale)
                    }
                }
            }
        }
    }
    state.error?.let { message ->
        AlertDialog(onDismissRequest = controller::dismissError,
            title = { Text("SoinuBider XL") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = controller::dismissError) { Text("Aceptar", color = palette.foreground) } })
    }
}

// Bisel fino: luz arriba/izquierda y sombra abajo/derecha.
private fun Modifier.buttonDepth(radius: Dp, pressed: Boolean = false): Modifier = drawWithContent {
    drawContent()
    val stroke = 1.dp.toPx()
    val inset = stroke / 2f
    val light = Color.White.copy(alpha = if (pressed) .12f else .24f)
    val dark = Color.Black.copy(alpha = if (pressed) .22f else .48f)
    drawRoundRect(
        brush = Brush.linearGradient(
            0f to light, .42f to Color.White.copy(alpha = .06f),
            .58f to Color.Black.copy(alpha = .08f), 1f to dark,
            start = Offset.Zero, end = Offset(size.width, size.height)
        ),
        topLeft = Offset(inset, inset),
        size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((radius.toPx() - inset).coerceAtLeast(0f)),
        style = Stroke(stroke)
    )
}

@Composable
private fun GearButton(open: () -> Unit) {
    val palette = LocalSoinuPalette.current
    Box(Modifier.size(44.dp).semantics { contentDescription = "Ajustes" }
        .clickable(role = Role.Button, onClick = open), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(27.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2
            val path = Path()
            for (point in 0 until 48) {
                val angle = point * 2 * PI / 48 - PI / 2
                val r = radius * if (point % 4 == 1 || point % 4 == 2) 1f else .79f
                val x = center.x + cos(angle).toFloat() * r
                val y = center.y + sin(angle).toFloat() * r
                if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, Color(0xFF9B9C96))
            drawCircle(palette.background, radius * .39f, center)
        }
    }
}

@Composable
private fun TempoPanel(state: SoinuState, controller: SoinuController, height: Dp, scale: Float) {
    val palette = LocalSoinuPalette.current
    Box(Modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxSize()) {
            val top = 10.dp.toPx()
            val stroke = 1.dp.toPx()
            val captionGap = 50.dp.toPx()
            val color = palette.paused
            drawLine(color, Offset(0f, top), Offset((size.width - captionGap) / 2, top), stroke)
            drawLine(color, Offset((size.width + captionGap) / 2, top), Offset(size.width, top), stroke)
            drawLine(color, Offset(stroke / 2, top), Offset(stroke / 2, size.height), stroke)
            drawLine(color, Offset(size.width - stroke / 2, top), Offset(size.width - stroke / 2, size.height), stroke)
            drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
        }
        Text("BPM", Modifier.align(Alignment.TopCenter), fontSize = (13 * scale).sp)
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 28.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val selected = state.mode == TempoMode.AUTOMATIC
            Surface(color = if (selected) palette.lit else palette.control,
                contentColor = palette.foreground,
                shape = RoundedCornerShape(8.dp), modifier = Modifier.width(74.dp * scale).fillMaxHeight().buttonDepth(8.dp)
                    .clickable(role = Role.Button, onClick = controller::auto)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("AUTO", fontSize = (15 * scale).sp) }
            }
            Text(state.tempo?.let { String.format(Locale.getDefault(),
                if (state.mode == TempoMode.TAP) "%.1f" else "%.2f", it.bpm) } ?: "—",
                Modifier.weight(1f), fontSize = (25 * scale).sp, maxLines = 1, textAlign = TextAlign.Center)
            TapButton(state.mode == TempoMode.TAP, controller, Modifier.width(74.dp * scale).fillMaxHeight(), scale)
        }
    }
}

@Composable
private fun TapButton(selected: Boolean, controller: SoinuController, modifier: Modifier, textScale: Float) {
    val palette = LocalSoinuPalette.current
    Surface(color = if (selected) palette.lit else palette.control,
        contentColor = palette.foreground,
        shape = RoundedCornerShape(8.dp), modifier = modifier.buttonDepth(8.dp)
            .semantics { role = Role.Button; onClick { controller.tap(SystemClock.uptimeMillis() / 1000.0); true } }
            .pointerInput(controller) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    controller.tap(down.uptimeMillis / 1000.0)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("TAP", fontSize = (15 * textScale).sp) }
    }
}

@Composable
private fun BankPad(index: Int, controller: SoinuController, modifier: Modifier, textScale: Float, touching: (Boolean) -> Unit) {
    val palette = LocalSoinuPalette.current
    val state = controller.state
    val bank = state.banks[index]
    var pressed by remember { mutableStateOf(false) }
    val onTouch by rememberUpdatedState(touching)
    val duration = SoinuConfiguration.beats[index] / 2.0.pow(bank.divisions)
    val label = if (duration >= 1) "${duration.toInt()} beats" else "1/${(1 / duration).roundToInt()} beat"
    val color = when (bank.state) {
        1, 2 -> palette.recording
        3 -> palette.playing
        4 -> palette.paused
        else -> palette.empty
    }
    Surface(color = if (pressed) palette.lit else color,
        contentColor = palette.foreground,
        shape = RoundedCornerShape(8.dp), modifier = modifier.buttonDepth(8.dp, pressed)
            .semantics {
                role = Role.Button
                onClick { if (controller.state.audioRunning) { controller.bankPressed(index); true } else false }
            }.pointerInput(index, state.audioRunning) {
                if (!state.audioRunning) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume(); pressed = true; onTouch(true); controller.beginBank(index)
                    val resizeAllowed = controller.state.banks[index].state == 3
                    var resized = false; var cancelled = false; var released = false
                    var endTime = down.uptimeMillis
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val p = change.position
                            val inColumn = p.x >= 0 && p.x <= size.width
                            if (!resized && !cancelled && resizeAllowed && inColumn) {
                                if (p.y < -8.dp.toPx()) { controller.resize(index, 1); resized = true }
                                else if (p.y > size.height + 8.dp.toPx()) { controller.resize(index, -1); resized = true }
                            }
                            if (!inColumn || (!resizeAllowed && (p.y < 0 || p.y > size.height))) cancelled = true
                            change.consume(); endTime = change.uptimeMillis
                            if (!change.pressed) {
                                released = p.x >= 0 && p.x <= size.width && p.y >= 0 && p.y <= size.height
                                break
                            }
                        } while (true)
                        if (released && !resized && !cancelled && endTime - down.uptimeMillis < 350 && controller.clickAllowed(index))
                            controller.bankPressed(index)
                    } finally {
                        controller.endBank(index); pressed = false; onTouch(false)
                    }
                }
            }) {
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(label, fontSize = (15 * textScale).sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ActionButton(label: String, kind: String, controller: SoinuController, modifier: Modifier, enabled: Boolean = true, textScale: Float = 1f) {
    val palette = LocalSoinuPalette.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    Surface(color = if (pressed) palette.lit else palette.control,
        contentColor = palette.foreground,
        shape = RoundedCornerShape(10.dp), modifier = modifier.buttonDepth(10.dp, pressed)
            .semantics {
                role = Role.Button
                onClick {
                    if (enabled) {
                        val action = controller.beginAction(kind)
                        when (kind) {
                            "back", "ahead" -> controller.actionRepeat(action)
                            "reset" -> controller.actionHold(action)
                            "erase" -> if (action.target == null) controller.actionHold(action) else controller.actionTap(action)
                        }
                        controller.endAction(action)
                    }
                    enabled
                }
            }.pointerInput(kind, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    pressed = true
                    val action = controller.beginAction(kind)
                    val repeat = kind == "back" || kind == "ahead"
                    if (repeat) controller.actionRepeat(action)
                    val job = scope.launch {
                        if (repeat) {
                            while (controller.actionValid(action)) { delay(250); controller.actionRepeat(action) }
                        } else { delay(2000); controller.actionHold(action) }
                    }
                    try {
                        if (tryAwaitRelease()) controller.actionTap(action)
                    } finally { job.cancel(); controller.endAction(action); pressed = false }
                })
            }) {
        Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = (15 * textScale).sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun InputMeter(peak: Float, modifier: Modifier) {
    var clipping by remember { mutableStateOf(false) }
    LaunchedEffect(peak >= .999f) {
        if (peak >= .999f) clipping = true else { delay(1000); clipping = false }
    }
    Canvas(modifier) {
        val db = 20 * log10(max(peak.toDouble(), 1e-9))
        val thresholds = (0 until 14).map { -60 + 45 * it / 13.0 } + listOf(-12.0, -9.0, -6.0, -3.0, -1.0, -.01)
        val cell = size.width / 20; val diameter = max(1f, min(20.dp.toPx(), cell - 5.dp.toPx()))
        for (index in 0 until 20) {
            val lit = db >= thresholds[index] || (index == 19 && clipping)
            val color = when {
                index < 14 -> if (lit) Color(0xFF48E880) else Color(0xFF183326)
                index < 17 -> if (lit) Color(0xFFF2B84B) else Color(0xFF44351C)
                else -> if (lit) Color(0xFFFF5364) else Color(0xFF49242D)
            }
            drawCircle(color, diameter / 2, Offset(cell * (index + .5f), size.height / 2))
        }
    }
}

@Composable
private fun SettingsScreen(
    state: SoinuState,
    close: () -> Unit,
    save: () -> Unit,
    reset: () -> Unit,
    minimumChanged: (Int) -> Unit,
    maximumChanged: (Int) -> Unit,
    appearanceChanged: (Appearance) -> Unit
) {
    val palette = LocalSoinuPalette.current
    fun format(value: Double) = if (abs(value) < .5) "0 ms" else String.format(Locale.getDefault(), "%+.0f ms", value)
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = palette.control,
        contentColor = palette.foreground,
        disabledContainerColor = palette.empty,
        disabledContentColor = palette.foreground
    )
    Column(Modifier.fillMaxSize().background(palette.background).safeDrawingPadding()
        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ajustes", Modifier.weight(1f), fontSize = 24.sp)
            TextButton(onClick = close) { Text("Cerrar ✕", color = palette.foreground) }
        }
        Text("Desfase actual: ${state.currentAdvanceMS?.let(::format) ?: "—"}", fontSize = 20.sp)
        Button(onClick = save, enabled = state.currentAdvanceMS != null, colors = buttonColors, modifier = Modifier.buttonDepth(50.dp)) { Text("Usar valor actual") }
        Text("Desfase guardado: ${format(state.savedAdvanceMS)}", fontSize = 20.sp)
        Button(onClick = reset, colors = buttonColors, modifier = Modifier.buttonDepth(50.dp)) { Text("Resetear valor") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rango BPM Auto", Modifier.weight(1f), fontSize = 16.sp)
            BpmDropdown(state.autoRange.minimum, AutoBpmRange.minimumOptions, "BPM mínimo", minimumChanged)
            BpmDropdown(state.autoRange.maximum, AutoBpmRange.maximumOptions, "BPM máximo", maximumChanged)
        }
        AppearanceSelector(state.appearance, appearanceChanged)
    }
}

@Composable
private fun BpmDropdown(value: Int, options: List<Int>, description: String, changed: (Int) -> Unit) {
    val palette = LocalSoinuPalette.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(color = palette.control, contentColor = palette.foreground,
            shape = RoundedCornerShape(8.dp), modifier = Modifier.width(64.dp).height(48.dp).buttonDepth(8.dp)
                .semantics { contentDescription = description }
                .clickable(role = Role.Button) { expanded = true }) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(value.toString(), color = palette.foreground, fontSize = 16.sp)
                Canvas(Modifier.size(8.dp, 5.dp)) {
                    val triangle = Path().apply {
                        moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close()
                    }
                    drawPath(triangle, palette.foreground)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { number ->
                DropdownMenuItem(
                    text = { Text(number.toString(), color = palette.foreground) },
                    onClick = { expanded = false; changed(number) }
                )
            }
        }
    }
}

@Composable
private fun AppearanceSelector(selected: Appearance, changed: (Appearance) -> Unit) {
    val palette = LocalSoinuPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Apariencia", color = palette.foreground, fontSize = 18.sp)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Appearance.entries.forEach { appearance ->
                val active = appearance == selected
                Surface(
                    color = palette.control,
                    contentColor = palette.foreground,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(if (active) 2.dp else 1.dp,
                        if (active) palette.foreground else palette.paused),
                    modifier = Modifier.weight(1f).buttonDepth(8.dp)
                        .selectable(selected = active, role = Role.RadioButton,
                            onClick = { changed(appearance) })
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth().height(52.dp)) {
                            appearance.palette.previewStripes.forEach { color ->
                                Box(Modifier.weight(1f).fillMaxHeight().background(color))
                            }
                        }
                        Text(appearance.title, Modifier.padding(vertical = 8.dp),
                            color = palette.foreground, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
