package com.example.soinubiderxl
import kotlin.math.*

fun main() {
    for (range in listOf(AutoBpmRange(), AutoBpmRange(85, 175))) {
        val old = ReferenceBeatTracker(48000.0, range)
        val optimized = BeatTracker(48000.0, range)
        var position = 0L
        var oldNanos = 0L; var newNanos = 0L; var estimates = 0
        while (position < 35 * 48000) {
            val samples = FloatArray(256) { i ->
                val phase = ((position + i) % 24000) / 48000.0
                (exp(-phase * 65) * .7 * sin(2 * PI * 90 * phase)).toFloat()
            }
            var begin = System.nanoTime()
            val a = old.feed(position, samples)
            oldNanos += System.nanoTime() - begin
            begin = System.nanoTime()
            val b = optimized.feed(position, samples)
            newNanos += System.nanoTime() - begin
            check(a == b) { "Different result at $position: $a != $b" }
            if (a != null) estimates++
            position += samples.size
        }
        check(estimates > 0)
        println("PASS equivalencia exacta bloque a bloque ${range.minimum}..${range.maximum}: $estimates estimaciones; anterior=${oldNanos / 1e6} ms optimizado=${newNanos / 1e6} ms (JVM, no Honor)")
    }
}
