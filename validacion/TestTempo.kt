package com.example.soinubiderxl

import kotlin.math.*

fun main() {
    val tap = TapTempoEstimator()
    for (i in 0 until 7) check(tap.press(10.0 + i * .5) == null)
    check(abs(tap.press(13.5)!! - 120) < 1e-8)
    val count = tap.count
    tap.press(13.5); check(tap.count == count)
    check(tap.press(17.0) == null && tap.count == 1)
    tap.reset(); check(tap.count == 0 && tap.bpm == null)
    println("PASS TAP: eight presses, 120 BPM, duplicate timestamp, pause and reset")

    for (bpm in listOf(118.0, 120.0, 132.0)) {
        val rate = 48000.0; val tracker = BeatTracker(rate)
        val period = rate * 60 / bpm
        var cursor = 0L; var seed = 173
        while (cursor < 30 * rate) {
            val block = FloatArray(1024) { i ->
                val phase = ((cursor + i) % period) / rate
                seed = seed * 1664525 + 1013904223
                val noise = (seed ushr 8) / 16777216.0 * 2 - 1
                (exp(-phase * 65) * (0.7 * sin(2 * PI * 90 * phase) + 0.1 * noise)).toFloat()
            }
            tracker.feed(cursor, block); cursor += block.size
        }
        val result = checkNotNull(tracker.estimate) { "No estimate for $bpm BPM synthetic rhythm" }
        check(abs(result.bpm - bpm) < .15) { "Expected $bpm, got ${result.bpm}" }
        println("PASS AUTO synthetic $bpm BPM -> ${result.bpm}")
    }
    val silent = BeatTracker(48000.0)
    for (i in 0 until 1000) silent.feed(i.toLong() * 1024, FloatArray(1024))
    check(silent.estimate == null)
    println("PASS silence produces no BPM")
}
