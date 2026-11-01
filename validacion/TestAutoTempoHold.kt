package com.example.soinubiderxl

fun main() {
    val rate = 48000.0
    val first = TempoEstimate(120.0, 192.0, .9, 10 * rate)
    var displayed: TempoEstimate? = first
    // Un análisis sin estimación no borra el último valor ni renueva su timestamp.
    for (tick in 1..500) {
        displayed = AutomaticTempoHold.select(displayed, null, 10 * rate + tick * rate / 100, rate)
        check(displayed == first)
    }
    check(AutomaticTempoHold.select(displayed, null, 15 * rate + 1, rate) == null)
    check(AutomaticTempoHold.select(null, first, 16 * rate, rate) == null)
    println("PASS: 500 resultados vacíos sin parpadeo; caduca a los 5 s y no resucita")

    val updated = TempoEstimate(121.0, 840.0, .95, 14 * rate)
    check(AutomaticTempoHold.select(first, updated, 14 * rate, rate) == updated)
    check(AutomaticTempoHold.select(updated, first, 14 * rate, rate) == updated)
    check(AutomaticTempoHold.select(null, null, 14 * rate, rate) == null)
    check(AutomaticTempoHold.select(null, updated.copy(bpm = Double.NaN), 14 * rate, rate) == null)
    check(AutomaticTempoHold.select(null, updated.copy(timestamp = 20 * rate), 14 * rate, rate) == null)
    println("PASS: actualización inmediata, rechazo de resultados viejos/inválidos y reset sin valor")
}
