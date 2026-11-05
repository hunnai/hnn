package com.example.soinubiderxl

enum class TempoMode { AUTOMATIC, TAP }
data class TempoEstimate(val bpm: Double, val anchor: Double, val confidence: Double, val timestamp: Double)
data class LoopBankState(
    val state: Int = 0, val divisions: Int = 0, val bpm: Double = 0.0,
    val advanceMS: Double = 0.0, val progress: Float = 0f
)
data class SoinuState(
    val mode: TempoMode = TempoMode.AUTOMATIC,
    val autoRange: AutoBpmRange = AutoBpmRange(),
    val appearance: Appearance = Appearance.VIOLET,
    val buttonTransparency: Float = .20f,
    val tempo: TempoEstimate? = null,
    val banks: List<LoopBankState> = List(6) { LoopBankState() },
    val inputPeak: Float = 0f,
    val inputPeakL: Float = 0f,
    val inputPeakR: Float = 0f,
    val audioRunning: Boolean = false,
    val tapCount: Int = 0,
    val heldBank: Int? = null,
    val syncedBanks: Set<Int> = emptySet(),
    val syncTrackingBanks: Set<Int> = emptySet(),
    val savedAdvanceMS: Double = 0.0,
    val currentAdvanceMS: Double? = null,
    val route: String = "Audio detenido",
    val error: String? = null
)

// Seguidor de fase relativo al instante en que el usuario ha cuadrado el loop.
// No modifica BPM ni la velocidad de lectura. Todos los tiempos son muestras.
internal class PhaseSynchronizer(
    private val bpm: Double,
    private var rate: Double,
    private var referenceClock: Double,
    private var referenceAdvanceMS: Double
) {
    private data class Observation(val time: Double, var errorMS: Double)
    private val observations = java.util.ArrayDeque<Observation>()
    private var referencePhase: Double? = null
    private var lastTimestamp = Double.NEGATIVE_INFINITY
    private var lastCorrection = Double.NEGATIVE_INFINITY
    private var observedAdvanceMS = referenceAdvanceMS
    private var manualHoldUntil = Double.NEGATIVE_INFINITY
    var tracking = false; private set

    init {
        require(bpm.isFinite() && bpm > 0 && rate.isFinite() && rate > 0)
        require(referenceClock.isFinite() && referenceAdvanceMS.isFinite())
    }

    private fun wrap(value: Double): Double = value - kotlin.math.floor(value + .5)
    private fun phase(at: Double, tempo: TempoEstimate): Double =
        (at - tempo.anchor) / (60 * rate / tempo.bpm)

    private fun usable(tempo: TempoEstimate, clock: Double): Boolean =
        clock.isFinite() && tempo.bpm.isFinite() && tempo.bpm > 0 &&
            tempo.anchor.isFinite() && tempo.timestamp.isFinite() &&
            tempo.confidence.isFinite() && tempo.confidence >= .75 &&
            clock - tempo.timestamp in (-.25 * rate)..(2 * rate) &&
            kotlin.math.abs(tempo.bpm - bpm) <= .005 * bpm

    fun prime(tempo: TempoEstimate?, clock: Double) {
        if (tempo != null && usable(tempo, clock)) referencePhase = wrap(phase(referenceClock, tempo))
    }

    // Esperar una referencia válida no cambia la elección SYNC ni su objetivo.
    fun waitForReference() {
        observations.clear()
        lastTimestamp = Double.NEGATIVE_INFINITY
        lastCorrection = Double.NEGATIVE_INFINITY
        tracking = false
    }

    fun reconfigureRate(newRate: Double) {
        require(newRate.isFinite() && newRate > 0)
        if (newRate == rate) return
        val ratio = newRate / rate
        referenceClock *= ratio
        manualHoldUntil *= ratio
        rate = newRate
        waitForReference()
    }

    fun shiftReference(milliseconds: Double, clock: Double) {
        if (!milliseconds.isFinite() || !clock.isFinite()) return
        // Misma cuantización que SBEngineNudgeBank (round de C, incluidos empates).
        val frames = kotlin.math.floor(kotlin.math.abs(milliseconds) * rate / 1000 + .5) *
            kotlin.math.sign(milliseconds)
        val appliedMS = frames * 1000 / rate
        referenceAdvanceMS += appliedMS
        observedAdvanceMS += appliedMS
        // Espera un snapshot posterior al comando antes de medir de nuevo la fase.
        manualHoldUntil = clock + .1 * rate
    }

    fun propose(tempo: TempoEstimate?, clock: Double, currentAdvanceMS: Double): Double? {
        if (clock < manualHoldUntil) return null
        if (tempo == null || !currentAdvanceMS.isFinite() || !usable(tempo, clock)) {
            observations.clear()
            tracking = false
            return null
        }
        if (tempo.timestamp <= lastTimestamp) return null
        tracking = false
        if (tempo.timestamp - lastTimestamp > 1.5 * rate) observations.clear()
        lastTimestamp = tempo.timestamp
        if (referencePhase == null) {
            // Tras activar AUTO desde TAP, espera una primera referencia válida.
            referencePhase = wrap(phase(referenceClock, tempo))
            return null
        }
        if (tempo.timestamp < referenceClock) return null
        val period = 60 * rate / bpm
        val loopPhase = (clock - referenceClock) / period +
            (currentAdvanceMS - referenceAdvanceMS) * rate / (1000 * period)
        val errorMS = wrap(phase(clock, tempo) - referencePhase!! - loopPhase) * period * 1000 / rate
        val maximumError = kotlin.math.min(50.0, .10 * 60000 / bpm)
        if (kotlin.math.abs(errorMS) > maximumError) {
            observations.clear()
            return null
        }
        // Expresa las observaciones antiguas en la fase de reproducción actual.
        val applied = currentAdvanceMS - observedAdvanceMS
        observations.forEach { it.errorMS -= applied }
        observedAdvanceMS = currentAdvanceMS
        observations.addLast(Observation(tempo.timestamp, errorMS))
        while (observations.size > 8) observations.removeFirst()
        if (observations.size < 4) return null
        val span = observations.last.time - observations.first.time
        if (span < kotlin.math.max(1.5 * rate, 3 * 60 * rate / tempo.bpm)) return null
        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        }
        // Tendencia robusta: una deriva continua no debe confundirse con un golpe extraño.
        val points = observations.toList()
        val slopes = mutableListOf<Double>()
        for (i in points.indices) for (j in i + 1 until points.size) {
            val seconds = (points[j].time - points[i].time) / rate
            if (seconds > .1) slopes.add((points[j].errorMS - points[i].errorMS) / seconds)
        }
        if (slopes.isEmpty()) return null
        val slope = median(slopes)
        if (kotlin.math.abs(slope) > 6.0) return null
        val projected = points.map { it.errorMS + slope * (tempo.timestamp - it.time) / rate }
        val median = median(projected)
        val mad = median(projected.map { kotlin.math.abs(it - median) })
        if (mad > 4.0 || kotlin.math.abs(errorMS - median) > 6.0) return null
        tracking = true
        if (kotlin.math.abs(median) < 2.0 || tempo.timestamp - lastCorrection < .5 * rate) return null
        // Como máximo 3 ms por corrección; el motor aplica su fundido de 25 ms.
        return median.coerceIn(-3.0, 3.0)
    }

    fun accepted(timestamp: Double) { lastCorrection = timestamp }
}
