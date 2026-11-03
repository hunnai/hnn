package com.example.soinubiderxl

import kotlin.math.*

private const val rate = 48000.0
private fun estimate(bpm: Double, timestamp: Double, anchor: Double = 0.0, confidence: Double = 1.0) =
    TempoEstimate(bpm, anchor, confidence, timestamp)
private fun wrap(value: Double) = value - floor(value + .5)

fun main() {
    for (loopBpm in listOf(119.8, 120.2, 119.5, 120.5)) {
        val clock0 = 20 * rate
        val initialAdvance = 37.0
        val lock = PhaseSynchronizer(loopBpm, rate, clock0, initialAdvance)
        lock.prime(estimate(120.0, clock0 - .5 * rate), clock0)
        var advance = initialAdvance
        var corrections = 0
        var maxError = 0.0
        var lastCorrection = Double.NEGATIVE_INFINITY
        for (step in 1..240) {
            val clock = clock0 + step * .5 * rate
            val tempo = estimate(120.0, clock - .5 * rate)
            val delta = lock.propose(tempo, clock, advance)
            if (delta != null) {
                check(abs(delta) <= 3.0)
                check(tempo.timestamp - lastCorrection >= .5 * rate)
                advance += round(delta * rate / 1000) * 1000 / rate
                lock.accepted(tempo.timestamp)
                lastCorrection = tempo.timestamp
                corrections++
                check(lock.propose(tempo, clock, advance) == null)
            }
            val inputCycles = (clock - clock0) * 120 / (60 * rate)
            val loopCycles = (clock - clock0) * loopBpm / (60 * rate) + (advance - initialAdvance) * loopBpm / 60000
            val error = abs(wrap(inputCycles - loopCycles) * 60000 / loopBpm)
            if (step > 20) maxError = max(maxError, error)
        }
        check(corrections > 0) { "No corrections for $loopBpm" }
        check(maxError < 20) { "Uncontrolled phase error $maxError ms for $loopBpm" }
        check((advance - initialAdvance) * (120 - loopBpm) > 0)
        println("PASS SYNC loop=$loopBpm input=120: $corrections corrections, max phase error after warmup %.3f ms".format(maxError))
    }

    // Tempo suavemente variable y entrega retardada de la estimación, a 44,1 kHz.
    run {
        val sampleRate = 44100.0
        val base = 20 * sampleRate
        val follower = PhaseSynchronizer(120.0, sampleRate, base, 0.0)
        follower.prime(TempoEstimate(120.0, base, 1.0, base), base)
        var advance = 0.0
        var maximum = 0.0
        val omega = 2 * PI / 30
        fun cycles(t: Double) = 2 * t + (.3 / 60 / omega) * (1 - cos(omega * t))
        for (step in 1..240) {
            val t = step * .5
            val bpm = 120 + .3 * sin(omega * t)
            val timestamp = base + t * sampleRate
            val anchor = timestamp - cycles(t) * 60 * sampleRate / bpm
            val now = timestamp + .2 * sampleRate
            val value = TempoEstimate(bpm, anchor, 1.0, timestamp)
            val delta = follower.propose(value, now, advance)
            if (delta != null) {
                advance += round(delta * sampleRate / 1000) * 1000 / sampleRate
                follower.accepted(timestamp)
            }
            if (step > 20) maximum = max(maximum, abs(wrap(cycles(t + .2) -
                (t + .2) * 2 - advance * 120 / 60000)) * 500)
        }
        check(maximum < 12) { "Variable-tempo phase error $maximum ms" }
        println("PASS SYNC variable tempo 44.1 kHz with 200-ms delayed estimates: max phase error %.3f ms".format(maximum))
    }

    val start = 20 * rate
    val steady = PhaseSynchronizer(120.0, rate, start, 83.0)
    steady.prime(estimate(120.0, start), start)
    for (step in 1..80) {
        val clock = start + step * .5 * rate
        val anchor = if (step % 7 == 0) .04 * rate else 0.0
        check(steady.propose(estimate(120.0, clock, anchor), clock, 83.0) == null)
    }
    println("PASS SYNC: manual alignment preserved; isolated off-beat phase outliers cause no shift")

    val cautious = PhaseSynchronizer(120.0, rate, start, 0.0)
    cautious.prime(estimate(120.0, start), start)
    for (step in 1..20) {
        val clock = start + step * .5 * rate
        check(cautious.propose(estimate(120.0, clock, confidence = .3), clock, 0.0) == null)
        check(cautious.propose(estimate(130.0, clock), clock, 0.0) == null)
        check(cautious.propose(estimate(120.0, clock - 3 * rate), clock, 0.0) == null)
        check(cautious.propose(estimate(120.0, clock, anchor = .15 * rate), clock, 0.0) == null)
    }
    check(cautious.propose(estimate(120.0, Double.NaN), start, 0.0) == null)
    check(cautious.propose(estimate(120.0, start), start, Double.NaN) == null)
    println("PASS SYNC: low confidence, large tempo difference, stale signal, large offset and NaN rejected")
}
