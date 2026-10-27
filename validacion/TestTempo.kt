package com.example.soinubiderxl

import kotlin.math.*

fun main() {
    check(AutoBpmRange.minimumOptions == listOf(85, 95, 105, 115, 125, 135, 145, 155))
    check(AutoBpmRange.maximumOptions == listOf(105, 115, 125, 135, 145, 155, 165, 175))
    var transitions = 0
    for (minimum in AutoBpmRange.minimumOptions) for (maximum in AutoBpmRange.maximumOptions) {
        if (maximum - minimum < 20) continue
        val range = AutoBpmRange(minimum, maximum)
        for (value in AutoBpmRange.minimumOptions) {
            val updated = range.withMinimum(value)
            check(updated.minimum == value && updated.maximum == maxOf(maximum, value + 20))
            check(updated.maximum in AutoBpmRange.maximumOptions)
            transitions++
        }
        for (value in AutoBpmRange.maximumOptions) {
            val updated = range.withMaximum(value)
            check(updated.maximum == value && updated.minimum == minOf(minimum, value - 20))
            check(updated.minimum in AutoBpmRange.minimumOptions)
            transitions++
        }
    }
    check(AutoBpmRange().withMinimum(155) == AutoBpmRange(155, 175))
    check(AutoBpmRange().withMaximum(105) == AutoBpmRange(85, 105))
    check(runCatching { AutoBpmRange(115, 125) }.isFailure)
    check(AutoBpmRange().withMinimum(85) == AutoBpmRange(85, 135))
    check(AutoBpmRange().withMaximum(175) == AutoBpmRange(115, 175))
    check(AutoBpmRange(85, 175).withMinimum(95) == AutoBpmRange(95, 175))
    println("PASS AUTO range: $transitions transitions, limits and 20-BPM separation")

    val tap = TapTempoEstimator()
    for (i in 0 until 7) check(tap.press(10.0 + i * .5) == null)
    check(abs(tap.press(13.5)!! - 120) < 1e-8)
    val count = tap.count
    tap.press(13.5); check(tap.count == count)
    check(tap.press(17.0) == null && tap.count == 1)
    tap.reset(); check(tap.count == 0 && tap.bpm == null)
    println("PASS TAP: eight presses, 120 BPM, duplicate timestamp, pause and reset")

    for ((bpm, range) in listOf(
        118.0 to AutoBpmRange(), 120.0 to AutoBpmRange(), 132.0 to AutoBpmRange(),
        85.0 to AutoBpmRange(85, 105), 90.0 to AutoBpmRange(85, 105),
        170.0 to AutoBpmRange(155, 175), 175.0 to AutoBpmRange(155, 175),
        120.0 to AutoBpmRange(105, 125)
    )) {
        val rate = 48000.0; val tracker = BeatTracker(rate, range)
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
        println("PASS AUTO synthetic $bpm BPM in ${range.minimum}..${range.maximum} -> ${result.bpm}")
    }
    val silent = BeatTracker(48000.0)
    for (i in 0 until 1000) silent.feed(i.toLong() * 1024, FloatArray(1024))
    check(silent.estimate == null)
    println("PASS silence produces no BPM")
}
