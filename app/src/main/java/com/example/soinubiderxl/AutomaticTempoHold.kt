package com.example.soinubiderxl

// Tiempo en muestras del motor, igual que TempoEstimate.timestamp y anchor.
// No renueva la edad al mostrar un valor: una detección caducada no puede armar loops.
object AutomaticTempoHold {
    const val seconds = 5.0

    fun select(
        current: TempoEstimate?, candidate: TempoEstimate?, clock: Double, rate: Double
    ): TempoEstimate? {
        if (!clock.isFinite() || !rate.isFinite() || rate <= 0) return null
        fun fresh(value: TempoEstimate?): Boolean = value != null &&
            value.bpm.isFinite() && value.bpm > 0 && value.anchor.isFinite() &&
            value.timestamp.isFinite() &&
            clock - value.timestamp >= -.25 * rate && clock - value.timestamp <= seconds * rate
        val previous = current?.takeIf { fresh(it) }
        val next = candidate?.takeIf { fresh(it) }
        return if (next != null && (previous == null || next.timestamp >= previous.timestamp)) next else previous
    }
}
