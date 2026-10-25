package com.example.soinubiderxl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = SoinuConfiguration.lit,
                background = SoinuConfiguration.background,
                surface = SoinuConfiguration.background,
                onBackground = SoinuConfiguration.foreground,
                onSurface = SoinuConfiguration.foreground
            )) {
                val permission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { allowed ->
                    if (allowed) controller.start()
                    else controller.error("Permite el micrófono en los ajustes de Android para recibir audio.")
                }
                SoinuScreen(controller) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) controller.start()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
    override fun onStart() { super.onStart(); controller.onVisible() }
    override fun onStop() { controller.onHidden(); super.onStop() }
}

@Composable
private fun SoinuScreen(controller: SoinuController, start: () -> Unit) {
    val state = controller.state
    var settings by rememberSaveable { mutableStateOf(false) }
    var touching by remember { mutableStateOf(setOf<Int>()) }
    BackHandler(enabled = settings) { settings = false }
    if (settings) {
        SettingsScreen(state, { settings = false }, controller::saveAdvance, controller::resetSavedAdvance)
    } else {
        Column(
            Modifier.fillMaxSize().background(SoinuConfiguration.background).safeDrawingPadding()
                .verticalScroll(rememberScrollState(), enabled = touching.isEmpty()).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(44.dp))
                Text("SoinuBider XL", Modifier.weight(1f), fontSize = 23.sp, textAlign = TextAlign.Center)
                TextButton(onClick = { controller.cancelTouches(); settings = true }) { Text("⚙", fontSize = 24.sp) }
            }
            InputMeter(state.inputPeak)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = start, enabled = !state.audioRunning) { Text("Iniciar audio") }
                Button(onClick = controller::stop, enabled = state.audioRunning) { Text("Detener") }
            }
            Text(state.route, fontSize = 12.sp, textAlign = TextAlign.Center)
            Column(
                Modifier.fillMaxWidth().border(1.dp, SoinuConfiguration.paused, RoundedCornerShape(8.dp)).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("BPM", fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = controller::auto, colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.mode == TempoMode.AUTOMATIC) SoinuConfiguration.lit else SoinuConfiguration.control,
                        contentColor = if (state.mode == TempoMode.AUTOMATIC) SoinuConfiguration.background else SoinuConfiguration.foreground
                    )) { Text("AUTO") }
                    Text(state.tempo?.let { String.format(Locale.getDefault(),
                        if (state.mode == TempoMode.TAP) "%.1f" else "%.2f", it.bpm) } ?: "—",
                        Modifier.weight(1f), fontSize = 27.sp, textAlign = TextAlign.Center)
                    TapButton(state.mode == TempoMode.TAP, controller)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Borrar beats", "erase", controller, Modifier.weight(1f))
                ActionButton("Reset BPM", "reset", controller, Modifier.weight(1f))
            }
            for (row in 0..1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (column in 0..2) {
                        val index = row * 3 + column
                        BankPad(index, controller, Modifier.weight(1f)) { down ->
                            touching = if (down) touching + index else touching - index
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("<<", "back", controller, Modifier.weight(1f), state.audioRunning)
                ActionButton(">>", "ahead", controller, Modifier.weight(1f), state.audioRunning)
            }
            Text(
                if (state.mode == TempoMode.TAP) "TAP: ${state.tapCount} pulsaciones · mínimo 8"
                else "AUTO: detección entre 115 y 135 BPM",
                fontSize = 12.sp, textAlign = TextAlign.Center
            )
            Text("Mantén 2 s para borrar todo o resetear BPM. Mantén un banco para ajustarlo con las flechas; " +
                "para borrarlo debe estar pausado. Desliza un banco sonando hacia arriba o abajo para cambiar su duración.",
                fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
    state.error?.let { message ->
        AlertDialog(onDismissRequest = controller::dismissError,
            title = { Text("SoinuBider XL") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = controller::dismissError) { Text("Aceptar") } })
    }
}

@Composable
private fun TapButton(selected: Boolean, controller: SoinuController) {
    Surface(color = if (selected) SoinuConfiguration.lit else SoinuConfiguration.control,
        contentColor = if (selected) SoinuConfiguration.background else SoinuConfiguration.foreground,
        shape = RoundedCornerShape(8.dp), modifier = Modifier
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
        Box(Modifier.sizeIn(minWidth = 74.dp, minHeight = 48.dp), contentAlignment = Alignment.Center) { Text("TAP") }
    }
}

@Composable
private fun BankPad(index: Int, controller: SoinuController, modifier: Modifier, touching: (Boolean) -> Unit) {
    val state = controller.state
    val bank = state.banks[index]
    var pressed by remember { mutableStateOf(false) }
    val onTouch by rememberUpdatedState(touching)
    val duration = SoinuConfiguration.beats[index] / 2.0.pow(bank.divisions)
    val label = if (duration >= 1) "${duration.toInt()} beats" else "1/${(1 / duration).roundToInt()} beat"
    val color = when (bank.state) {
        1, 2 -> SoinuConfiguration.recording
        3 -> SoinuConfiguration.playing
        4 -> SoinuConfiguration.paused
        else -> SoinuConfiguration.empty
    }
    Surface(color = if (pressed) SoinuConfiguration.lit else color,
        contentColor = if (pressed) SoinuConfiguration.background else SoinuConfiguration.foreground,
        shape = RoundedCornerShape(8.dp), modifier = modifier.heightIn(min = 88.dp)
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
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(label, textAlign = TextAlign.Center)
            if (bank.state == 1 || bank.state == 2) Text(if (bank.state == 1) "Preparado" else "Grabando", fontSize = 11.sp)
        }
    }
}

@Composable
private fun ActionButton(label: String, kind: String, controller: SoinuController, modifier: Modifier, enabled: Boolean = true) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    Surface(color = if (pressed) SoinuConfiguration.lit else SoinuConfiguration.control,
        contentColor = if (pressed) SoinuConfiguration.background else SoinuConfiguration.foreground,
        shape = RoundedCornerShape(8.dp), modifier = modifier.heightIn(min = 48.dp)
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
        Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) { Text(label, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun InputMeter(peak: Float) {
    var clipping by remember { mutableStateOf(false) }
    LaunchedEffect(peak >= .999f) {
        if (peak >= .999f) clipping = true else { delay(1000); clipping = false }
    }
    Canvas(Modifier.fillMaxWidth().height(26.dp)) {
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
private fun SettingsScreen(state: SoinuState, close: () -> Unit, save: () -> Unit, reset: () -> Unit) {
    fun format(value: Double) = if (abs(value) < .5) "0 ms" else String.format(Locale.getDefault(), "%+.0f ms", value)
    Column(Modifier.fillMaxSize().background(SoinuConfiguration.background).safeDrawingPadding()
        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ajustes", Modifier.weight(1f), fontSize = 24.sp)
            TextButton(onClick = close) { Text("Cerrar ✕") }
        }
        Text("Desfase actual: ${state.currentAdvanceMS?.let(::format) ?: "—"}", fontSize = 20.sp)
        Button(onClick = save, enabled = state.currentAdvanceMS != null) { Text("Usar valor actual") }
        Text("Desfase guardado: ${format(state.savedAdvanceMS)}", fontSize = 20.sp)
        Button(onClick = reset) { Text("Resetear valor") }
        Text("El desfase guardado se aplica a las nuevas grabaciones. No es una medición automática de latencia.")
    }
}
