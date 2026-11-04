package com.example.soinubiderxl

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.max

// Propietario único de la sesión: las llamadas JNI de sesión usan el hilo principal.
class SoinuController(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("SoinuBider", Context.MODE_PRIVATE)
    private val manager = checkNotNull(application.getSystemService(AudioManager::class.java))
    private val tap = TapTempoEstimator()
    private var handle = 0L
    private var initialStartClaimed = false
    private var visible = false
    private var wanted = false
    private var polling: Job? = null
    private var routeWork: Job? = null
    private var generation = 0
    private var lastTap: Double? = null
    private var audioClock = 0.0
    private var audioRate = 48000.0
    private val synchronizers = mutableMapOf<Int, PhaseSynchronizer>()
    private val pending = mutableSetOf<Int>()
    private val activationOrder = mutableListOf<Int>()
    private val touching = mutableSetOf<Int>()
    private val suppressClick = mutableSetOf<Int>()
    private val actions = mutableSetOf<Action>()
    var state by mutableStateOf(SoinuState(savedAdvanceMS = readSaved(), autoRange = readAutoRange(),
        appearance = readAppearance(), buttonTransparency = readButtonTransparency()))
        private set

    private var inputPreferenceKey: String? = null
    var inputRecovery by mutableStateOf(false); private set
    var inputPair by mutableStateOf(0); private set
    var usbInputAvailable by mutableStateOf(false); private set

    fun changeInputRecovery(enabled: Boolean) {
        val key = inputPreferenceKey ?: return
        if (inputRecovery == enabled) return
        preferences.edit().putBoolean("$key/recovery8", enabled).apply()
        suspendAudio()
        start()
    }
    fun selectInputPair(pair: Int) {
        val key = inputPreferenceKey ?: return
        if (pair !in 0..3 || inputPair == pair) return
        preferences.edit().putInt("$key/pair", pair).apply()
        suspendAudio()
        start()
    }

    private fun readSaved(): Double = preferences.getString("advanceMS", "0")
        ?.toDoubleOrNull()?.takeIf { it.isFinite() && abs(it) <= 2000 } ?: 0.0

    private fun readAppearance(): Appearance = runCatching {
        Appearance.fromPreference(preferences.getString("appearance", null))
    }.getOrDefault(Appearance.VIOLET)

    fun setAppearance(appearance: Appearance) {
        if (appearance == state.appearance) return
        preferences.edit().putString("appearance", appearance.preference).apply()
        state = state.copy(appearance = appearance)
    }

    private fun readButtonTransparency(): Float = runCatching {
        preferences.getFloat("buttonTransparency", .20f)
            .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .20f
    }.getOrDefault(.20f)

    fun setButtonTransparency(value: Float) {
        if (value.isFinite()) state = state.copy(buttonTransparency = value.coerceIn(0f, 1f))
    }

    fun saveButtonTransparency() {
        preferences.edit().putFloat("buttonTransparency", state.buttonTransparency).apply()
    }

    private fun readAutoRange(): AutoBpmRange = runCatching {
        AutoBpmRange(preferences.getInt("autoMinimum", 115), preferences.getInt("autoMaximum", 135))
    }.getOrDefault(AutoBpmRange())

    fun setAutoMinimum(value: Int) = updateAutoRange(state.autoRange.withMinimum(value))
    fun setAutoMaximum(value: Int) = updateAutoRange(state.autoRange.withMaximum(value))

    private fun updateAutoRange(range: AutoBpmRange) {
        if (range == state.autoRange) return
        preferences.edit().putInt("autoMinimum", range.minimum).putInt("autoMaximum", range.maximum).apply()
        if (state.mode == TempoMode.AUTOMATIC) { clearSync(); generation++ }
        state = state.copy(autoRange = range,
            tempo = if (state.mode == TempoMode.AUTOMATIC) null else state.tempo)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (wanted && visible && !state.audioRunning) activate()
            AudioManager.AUDIOFOCUS_LOSS -> { wanted = false; suspendAudio() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> suspendAudio()
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
        .build()

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = routeChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = routeChanged()
    }
    init { manager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper())) }

    fun claimInitialStart(): Boolean {
        if (initialStartClaimed) return false
        initialStartClaimed = true
        return true
    }
    fun onVisible() {
        visible = true
        if (wanted && !state.audioRunning) start()
    }
    fun onHidden() {
        visible = false
        suspendAudio()
        manager.abandonAudioFocusRequest(focusRequest)
    }
    fun start() {
        if (state.audioRunning) return
        if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            error("Permite el acceso al micrófono para iniciar el audio."); return
        }
        wanted = true
        if (!visible) return
        if (manager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            wanted = false; error("No se pudo obtener el uso del audio. Cierra la otra aplicación de audio y vuelve a intentarlo."); return
        }
        activate()
    }
    fun stop() {
        wanted = false; routeWork?.cancel(); suspendAudio()
        manager.abandonAudioFocusRequest(focusRequest)
    }
    private fun routeChanged() {
        routeWork?.cancel()
        if (!wanted || !visible) return
        routeWork = viewModelScope.launch {
            delay(250)
            if (wanted && visible) activate()
        }
    }
    private fun isUsb(device: AudioDeviceInfo) = device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        device.type == AudioDeviceInfo.TYPE_USB_HEADSET || device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun usbRoute(): Pair<AudioDeviceInfo?, AudioDeviceInfo?> {
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter(::isUsb)
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter(::isUsb)
        android.util.Log.d("SoinuUSB", "USB: entradas=${inputs.size}, salidas=${outputs.size}")
        (inputs + outputs).forEach { device ->
            android.util.Log.d("SoinuUSB",
                "${if (device.isSource) "ENTRADA" else "SALIDA"} ${device.productName} " +
                "id=${device.id} canales=${device.channelCounts.contentToString()} " +
                "frecuencias=${device.sampleRates.contentToString()} formatos=${device.encodings.contentToString()}")
        }
        if (inputs.isEmpty() && outputs.isEmpty()) return null to null
        for (input in inputs) {
            val output = outputs.firstOrNull {
                if (Build.VERSION.SDK_INT >= 28 && input.address.isNotEmpty() && it.address.isNotEmpty())
                    input.address == it.address
                else input.productName.toString() == it.productName.toString()
            }
            if (output != null) return input to output
        }
        throw IllegalStateException("No hay una ruta USB completa de entrada y salida. Revisa la tarjeta y su conexión.")
    }
    private fun activate() {
        suspendAudio()
        try {
            val (input, output) = usbRoute()
            inputPreferenceKey = input?.let {
                "input/${it.productName}/${if (Build.VERSION.SDK_INT >= 28) it.address else ""}"
            }
            usbInputAvailable = input != null
            inputRecovery = inputPreferenceKey?.let { preferences.getBoolean("$it/recovery8", false) } ?: false
            inputPair = inputPreferenceKey?.let { preferences.getInt("$it/pair", 0).coerceIn(0, 3) } ?: 0
            if (handle == 0L) handle = NativeAudio.create()
            check(handle != 0L) { "No se pudo crear el motor C" }
            NativeAudio.setInputLayout(handle, if (inputRecovery) 8 else 0, inputPair)
            NativeAudio.start(handle, input?.id ?: 0, output?.id ?: 0, state.savedAdvanceMS)
            tap.reset(); lastTap = null; pending.clear(); generation++
            state = state.copy(audioRunning = true, tempo = null, tapCount = 0,
                route = input?.let { "USB: ${it.productName}" } ?: "Micrófono y salida del sistema")
            poll()
        } catch (e: Exception) {
            wanted = false; suspendAudio(); manager.abandonAudioFocusRequest(focusRequest)
            error(e.message ?: "No se pudo iniciar el audio")
        } catch (e: LinkageError) {
            wanted = false; suspendAudio(); manager.abandonAudioFocusRequest(focusRequest)
            error("No se pudo cargar el motor C: ${e.message}")
        }
    }
    private fun suspendAudio() {
        polling?.cancel(); polling = null; generation++
        cancelTouches()
        if (handle != 0L) NativeAudio.stop(handle)
        pending.clear()
        clearSync()
        state = state.copy(audioRunning = false, inputPeak = 0f, route = "Audio detenido")
    }
    // Se llama desde la pantalla, en Main. Toda consulta JNI termina antes de
    // abrir el selector de archivos, que puede detener la actividad y el audio.
    suspend fun captureInputDiagnostic(): ByteArray {
        check(state.audioRunning && handle != 0L) { "Conecta la tarjeta y abre el audio primero." }
        val active = handle
        val activeGeneration = generation
        val route = state.route
        check(NativeAudio.beginDiagnostic(active)) { "Ya hay una captura en curso." }
        val raw = withTimeout(7_000) {
            var result: FloatArray? = null
            while (result == null) {
                delay(50)
                check(handle == active && state.audioRunning && generation == activeGeneration) {
                    "La ruta de audio ha cambiado. Repite la captura."
                }
                result = NativeAudio.readDiagnostic(active)
            }
            result
        }
        val snapshot = NativeAudio.snapshot(active)
        return withContext(Dispatchers.Default) { diagnosticWav(raw, route, snapshot) }
    }

    private fun poll() {
        data class AnalysisBatch(
            val generation: Int, val rate: Double, val range: AutoBpmRange,
            val packets: List<Pair<Long, FloatArray>>
        )
        data class AnalysisResult(
            val generation: Int, val tempo: TempoEstimate?, val end: Long,
            val gaps: Int, val milliseconds: Double
        )
        polling?.cancel()
        polling = viewModelScope.launch {
            try {
                coroutineScope {
                    val queueDrops = java.util.concurrent.atomic.AtomicInteger()
                    val analysis = kotlinx.coroutines.channels.Channel<AnalysisBatch>(
                        capacity = 128,
                        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
                        onUndeliveredElement = { queueDrops.incrementAndGet() }
                    )
                    // El trabajador publica sin esperar al hilo de la pantalla.
                    val completed = kotlinx.coroutines.channels.Channel<AnalysisResult>(
                        kotlinx.coroutines.channels.Channel.CONFLATED
                    )
                    val analyzer = launch(Dispatchers.Default) {
                        var detector: BeatTracker? = null
                        var detectorGeneration = -1
                        var expected: Long? = null
                        var gaps = 0
                        for (batch in analysis) {
                            if (detectorGeneration != batch.generation) {
                                detector = BeatTracker(batch.rate, batch.range)
                                detectorGeneration = batch.generation
                                expected = null
                                gaps = 0
                            }
                            val worker = checkNotNull(detector)
                            val begin = System.nanoTime()
                            var result = worker.estimate
                            for ((position, audio) in batch.packets) {
                                ensureActive()
                                if (expected != null && expected != position) gaps++
                                result = worker.feed(position, audio)
                                expected = position + audio.size
                            }
                            completed.trySend(AnalysisResult(
                                batch.generation, result, checkNotNull(expected), gaps,
                                (System.nanoTime() - begin) / 1_000_000.0
                            ))
                        }
                    }
                    val samples = FloatArray(1024)
                    val start = LongArray(1)
                    var latest: AnalysisResult? = null
                    var lastPublish = 0L
                    var lastDiagnostic = 0L
                    var meterPeak = state.inputPeak
                    try {
                        while (state.audioRunning && handle != 0L) {
                            val snapshot = NativeAudio.snapshot(handle)
                            check(snapshot.size >= 37) { "Respuesta del motor incompleta" }
                            check(snapshot[3] == 0.0) { "El dispositivo de audio se ha desconectado." }
                            audioClock = snapshot[1]
                            audioRate = snapshot[0]
                            val result = completed.tryReceive().getOrNull()
                            if (result != null && result.generation == generation) latest = result
                            val now = System.nanoTime()
                            meterPeak = max(snapshot[2].toFloat(), meterPeak * .975f)
                            // Igual que iPhone: publica la interfaz cada 50 ms.
                            if (now - lastPublish >= 50_000_000L) {
                                lastPublish = now
                                val banks = List(6) { index ->
                                    val p = 7 + index * 5
                                    val status = snapshot[p].toInt()
                                    if (status != 0) pending.remove(index)
                                    LoopBankState(if (index in pending && status == 0) 1 else status,
                                        snapshot[p+1].toInt(), snapshot[p+2], snapshot[p+3], snapshot[p+4].toFloat())
                                }
                                val automatic = latest?.takeIf { it.generation == generation }
                                val tempo = if (state.mode == TempoMode.AUTOMATIC && automatic != null)
                                    automatic.tempo else state.tempo
                                state = state.copy(banks = banks, tempo = tempo, inputPeak = meterPeak)
                                updateSync()
                                updateAdvance()
                            }
                            val packets = mutableListOf<Pair<Long, FloatArray>>()
                            for (i in 0 until 64) {
                                val count = NativeAudio.readAnalysis(handle, samples, start)
                                if (count == 0) break
                                if (state.mode == TempoMode.AUTOMATIC) packets.add(start[0] to samples.copyOf(count))
                            }
                            if (packets.isNotEmpty()) analysis.trySend(
                                AnalysisBatch(generation, snapshot[0], state.autoRange, packets)
                            )
                            if (now - lastDiagnostic >= 2_000_000_000L) {
                                lastDiagnostic = now
                                if (snapshot.size >= 50) {
                                    android.util.Log.d("SoinuUSB",
                                        "IO Hz=${snapshot[0]} entradaSolicitada=${snapshot[37].toInt()} entradaAbierta=${snapshot[38].toInt()} " +
                                        "salidaSolicitada=${snapshot[39].toInt()} salidaAbierta=${snapshot[40].toInt()} " +
                                        "canalesEntrada=${snapshot[41].toInt()} canalesSalida=${snapshot[42].toInt()} " +
                                        "apiEntrada=${snapshot[43].toInt()} apiSalida=${snapshot[44].toInt()} " +
                                        "solicitadas=${snapshot[45].toLong()} recibidas=${snapshot[46].toLong()} " +
                                        "lecturasVacias=${snapshot[47].toLong()} perfil=${snapshot[48].toInt()} " +
                                        "bloquesConSenal=${snapshot[49].toLong()} pico=${snapshot[2]}" +
                                        if (snapshot.size >= 55) " descartadasCaptura=${snapshot[50].toLong()} colaCaptura=${snapshot[51].toLong()} " +
                                            "callbacksEntrada=${snapshot[52].toLong()} estadoEntrada=${snapshot[53].toInt()} estadoSalida=${snapshot[54].toInt()}" else "")
                                    if (snapshot.size >= 58) android.util.Log.d("SoinuUSB",
                                        "ENTRADA framesUtiles=${snapshot[55].toLong()} recuperacion=${snapshot[56].toInt()} " +
                                        "par=${snapshot[57].toInt() + 1}")
                                }
                                val automatic = latest?.takeIf { it.generation == generation }
                                if (state.mode == TempoMode.AUTOMATIC) android.util.Log.d("SoinuBPM",
                                    "Hz=${snapshot[0]} retraso=${automatic?.let { (snapshot[1] - it.end) / snapshot[0] }} " +
                                    "perdidasMotor=${snapshot[4].toLong()} perdidasCola=${queueDrops.get()} " +
                                    "saltos=${automatic?.gaps} calculoMS=${automatic?.milliseconds} " +
                                    "bpm=${automatic?.tempo?.bpm} edad=${automatic?.tempo?.let { (snapshot[1] - it.timestamp) / snapshot[0] }}")
                            }
                            delay(10)
                        }
                    } finally {
                        analysis.cancel()
                        completed.cancel()
                        analyzer.cancel()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { stop(); error(e.message ?: "Error de audio") }
        }
    }
    fun auto() {
        val held = state.heldBank?.takeIf { state.audioRunning && state.banks[it].state == 3 }
        if (held != null) {
            suppressClick.add(held)
            if (synchronizers.remove(held) != null) {
                publishSyncState()
                return
            }
            if (state.mode != TempoMode.AUTOMATIC) {
                generation++; tap.reset(); lastTap = null
                state = state.copy(mode = TempoMode.AUTOMATIC, tempo = null, tapCount = 0)
            }
            val bank = state.banks[held]
            if (bank.bpm.isFinite() && bank.bpm > 0) {
                val follower = PhaseSynchronizer(bank.bpm, audioRate, audioClock, bank.advanceMS)
                follower.prime(state.tempo, audioClock)
                synchronizers[held] = follower
                publishSyncState()
            }
            return
        }
        if (state.mode != TempoMode.AUTOMATIC) {
            generation++; tap.reset(); lastTap = null
            state = state.copy(mode = TempoMode.AUTOMATIC, tempo = null, tapCount = 0)
        }
    }
    fun tap(timestamp: Double) {
        if (state.mode != TempoMode.TAP) { clearSync(); generation++; tap.reset() }
        lastTap = timestamp
        val bpm = tap.press(timestamp)
        state = state.copy(mode = TempoMode.TAP, tapCount = tap.count,
            tempo = bpm?.let { TempoEstimate(it, 0.0, 1.0, timestamp) })
    }
    fun resetTempo() {
        clearSync()
        generation++; tap.reset(); lastTap = null
        state = state.copy(tempo = null, tapCount = 0)
    }
    private fun publishSyncState() {
        val enabled = synchronizers.keys.toSet()
        val tracking = synchronizers.filterValues { it.tracking }.keys.toSet()
        if (state.syncedBanks != enabled || state.syncTrackingBanks != tracking) {
            state = state.copy(syncedBanks = enabled, syncTrackingBanks = tracking)
        }
    }

    private fun clearSync(bank: Int? = null) {
        if (bank == null) synchronizers.clear() else synchronizers.remove(bank)
        publishSyncState()
    }

    private fun updateSync() {
        val iterator = synchronizers.iterator()
        while (iterator.hasNext()) {
            val (index, follower) = iterator.next()
            val bank = state.banks[index]
            if (!state.audioRunning || state.mode != TempoMode.AUTOMATIC || bank.state != 3) {
                iterator.remove()
                continue
            }
            val correction = follower.propose(state.tempo, audioClock, bank.advanceMS) ?: continue
            if (send(NativeAudio.NUDGE_BANK, index, correction, fromSync = true)) {
                follower.accepted(state.tempo!!.timestamp)
                android.util.Log.d("SoinuSYNC", "banco=${index + 1} correccionMS=$correction bpmLoop=${bank.bpm} bpmEntrada=${state.tempo!!.bpm}")
            }
        }
        publishSyncState()
    }

    private fun send(op: Int, bank: Int = -1, value: Double = 0.0, fromSync: Boolean = false): Boolean {
        if (handle == 0L) return false
        val accepted = NativeAudio.command(handle, op, bank, value)
        if (accepted && !fromSync) {
            when (op) {
                NativeAudio.NUDGE_ALL, NativeAudio.ERASE_ALL -> clearSync()
                NativeAudio.NUDGE_BANK -> synchronizers[bank]?.shiftReference(value, audioClock)
                NativeAudio.ERASE_BANK, NativeAudio.TOGGLE -> clearSync(bank)
            }
        }
        if (!accepted && !fromSync) error("La cola de audio está ocupada. Vuelve a intentarlo.")
        return accepted
    }
    fun bankPressed(index: Int) {
        if (!state.audioRunning || index in pending || index !in 0..5) return
        val bank = state.banks[index]
        when (bank.state) {
            0 -> {
                val tempo = state.tempo
                if (tempo == null) { error("Todavía no hay BPM. En TAP se necesitan ocho pulsaciones."); return }
                val tapTime = if (state.mode == TempoMode.TAP) lastTap ?: return else -1.0
                if (NativeAudio.arm(handle, index, tempo.bpm, tempo.anchor, tapTime)) {
                    pending.add(index); rememberActivation(index)
                } else error("No se pudo reservar el loop. Vuelve a intentarlo.")
            }
            3, 4 -> if (send(NativeAudio.TOGGLE, index) && bank.state == 4) rememberActivation(index)
        }
    }
    private fun rememberActivation(index: Int) { activationOrder.remove(index); activationOrder.add(index) }
    fun beginBank(index: Int) {
        touching.add(index); suppressClick.remove(index)
        if (state.heldBank == null && state.banks[index].state in 3..4) {
            actions.filter { it.kind != "reset" }.forEach { it.cancelled = true }
            state = state.copy(heldBank = index); updateAdvance()
        }
    }
    fun endBank(index: Int) {
        touching.remove(index)
        if (state.heldBank == index) {
            actions.filter { it.target == index }.forEach { it.cancelled = true }
            state = state.copy(heldBank = null); updateAdvance()
        }
    }
    fun clickAllowed(index: Int) = index !in suppressClick
    fun resize(index: Int, steps: Int) {
        suppressClick.add(index); endBank(index)
        if (state.audioRunning && state.banks[index].state == 3)
            send(NativeAudio.RESIZE, index, steps.toDouble())
    }
    fun cancelTouches() {
        touching.clear(); actions.forEach { it.cancelled = true }; actions.clear()
        state = state.copy(heldBank = null)
    }
    class Action(val kind: String, val target: Int?) { var cancelled = false }
    fun beginAction(kind: String): Action {
        val target = state.heldBank
        if (target != null && kind != "reset") suppressClick.add(target)
        return Action(kind, target).also { actions.add(it) }
    }
    fun actionValid(action: Action) = !action.cancelled &&
        (action.target == null || state.heldBank == action.target)
    fun actionRepeat(action: Action) {
        if (!state.audioRunning || !actionValid(action)) return
        val value = if (action.kind == "back") -1.0 else 1.0
        if (action.target == null) send(NativeAudio.NUDGE_ALL, value = value)
        else send(NativeAudio.NUDGE_BANK, action.target, value)
    }
    fun actionHold(action: Action) {
        if (!actionValid(action)) return
        when (action.kind) {
            "reset" -> resetTempo()
            "erase" -> if (action.target == null && state.heldBank == null && state.audioRunning)
                send(NativeAudio.ERASE_ALL)
        }
    }
    fun actionTap(action: Action) {
        if (action.kind == "erase" && action.target != null && actionValid(action) && state.audioRunning)
            send(NativeAudio.ERASE_BANK, action.target)
    }
    fun endAction(action: Action) { action.cancelled = true; actions.remove(action) }
    private fun updateAdvance() {
        val held = state.heldBank
        val index = if (held != null && state.banks[held].state in 3..4) held
            else activationOrder.asReversed().firstOrNull { state.banks[it].state == 3 }
                ?: state.banks.indices.firstOrNull { state.banks[it].state == 3 }
        state = state.copy(currentAdvanceMS = index?.let { state.banks[it].advanceMS })
    }
    fun saveAdvance() {
        val value = state.currentAdvanceMS ?: return
        if (!value.isFinite() || abs(value) > 2000) { error("El desfase guardado debe estar entre −2000 y +2000 ms."); return }
        if (state.audioRunning && !send(NativeAudio.INITIAL_ADVANCE, value = value)) return
        preferences.edit().putString("advanceMS", value.toString()).apply()
        state = state.copy(savedAdvanceMS = value)
    }
    fun resetSavedAdvance() {
        if (state.audioRunning && !send(NativeAudio.INITIAL_ADVANCE, value = 0.0)) return
        preferences.edit().putString("advanceMS", "0").apply()
        state = state.copy(savedAdvanceMS = 0.0)
    }
    fun error(message: String) { state = state.copy(error = message) }
    fun dismissError() { state = state.copy(error = null) }
    override fun onCleared() {
        routeWork?.cancel(); polling?.cancel(); manager.unregisterAudioDeviceCallback(devices)
        manager.abandonAudioFocusRequest(focusRequest)
        if (handle != 0L) { NativeAudio.destroy(handle); handle = 0L }
        super.onCleared()
    }
}

// WAV IEEE float sin normalización: conservar los valores originales permite
// detectar formato mal interpretado, clipping, canales intercalados y silencios.
internal fun diagnosticWav(raw: FloatArray, route: String, snapshot: DoubleArray): ByteArray {
    require(raw.size >= 4)
    val rate = raw[0].toInt()
    val channels = raw[1].toInt()
    val frames = raw[3].toInt()
    require(rate in 8000..192000 && channels in 1..2 && frames > 0)
    require(raw.size - 4 == frames * channels)
    val comment = ("SoinuBider input before FIFO; route=$route; " +
        "rate=$rate; channels=$channels; frames=$frames; callbackElapsedSeconds=${raw[2]}; " +
        "snapshot=${snapshot.joinToString(",")}; " +
        "Do not infer physical channel layout from advertised channels.\u0000")
        .toByteArray(Charsets.UTF_8)
    val commentSize = comment.size + (comment.size and 1)
    val dataSize = (raw.size - 4) * 4
    val out = java.nio.ByteBuffer.allocate(76 + commentSize + dataSize)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    fun id(text: String) { out.put(text.toByteArray(Charsets.US_ASCII)) }
    id("RIFF"); out.putInt(out.capacity() - 8); id("WAVE")
    id("fmt "); out.putInt(16); out.putShort(3.toShort()); out.putShort(channels.toShort())
    out.putInt(rate); out.putInt(rate * channels * 4); out.putShort((channels * 4).toShort()); out.putShort(32.toShort())
    id("fact"); out.putInt(4); out.putInt(frames)
    id("LIST"); out.putInt(12 + commentSize); id("INFO"); id("ICMT"); out.putInt(comment.size)
    out.put(comment); if ((comment.size and 1) != 0) out.put(0.toByte())
    id("data"); out.putInt(dataSize)
    for (index in 4 until raw.size) out.putFloat(raw[index])
    check(!out.hasRemaining())
    return out.array()
}
