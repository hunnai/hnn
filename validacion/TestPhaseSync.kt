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

    // Ajuste manual: SYNC mantiene la nueva posición, también a 44,1 kHz.
    for (sampleRate in listOf(48000.0, 44100.0)) {
        val base = 20 * sampleRate
        val follower = PhaseSynchronizer(120.0, sampleRate, base, 17.0)
        follower.prime(TempoEstimate(120.0, base, 1.0, base), base)
        var advance = 17.0
        for (step in 1..8) {
            val clock = base + step * .5 * sampleRate
            check(follower.propose(TempoEstimate(120.0, base, 1.0, clock), clock, advance) == null)
        }
        check(follower.tracking)
        val manualClock = base + 4 * sampleRate
        for (delta in listOf(1.0, 1.0, -1.0, 1.0, 1.0, -1.0)) {
            follower.shiftReference(delta, manualClock)
            val oldSnapshot = advance
            check(follower.propose(TempoEstimate(120.0, base, 1.0, manualClock + .05 * sampleRate),
                manualClock + .05 * sampleRate, oldSnapshot) == null)
            check(follower.tracking)
            val frames = floor(abs(delta) * sampleRate / 1000 + .5) * sign(delta)
            advance += frames * 1000 / sampleRate
        }
        for (step in 9..100) {
            val clock = base + step * .5 * sampleRate
            check(follower.propose(TempoEstimate(120.0, base, 1.0, clock), clock, advance) == null) {
                "SYNC tried to undo manual offset at $sampleRate Hz"
            }
        }
        check(follower.tracking)
        println("PASS SYNC manual reference $sampleRate Hz: repeated +/− shifts preserved; stale snapshot ignored; no automatic undo")
    }

    run {
        val base = 20 * rate
        val follower = PhaseSynchronizer(119.8, rate, base, 0.0)
        follower.prime(estimate(120.0, base), base)
        var advance = 0.0
        var manualShift = 0.0
        var maximum = 0.0
        var correctionsAfterManual = 0
        for (step in 1..160) {
            val clock = base + step * .5 * rate
            if (step == 40) {
                follower.shiftReference(15.0, clock)
                advance += 15.0
                manualShift += 15.0
            }
            val tempo = estimate(120.0, clock)
            val delta = follower.propose(tempo, clock, advance)
            if (delta != null) {
                advance += round(delta * rate / 1000) * 1000 / rate
                follower.accepted(clock)
                if (step > 40) correctionsAfterManual++
            }
            if (step > 45) maximum = max(maximum, abs(wrap((clock - base) * (120 - 119.8) /
                (60 * rate) - (advance - manualShift) * 119.8 / 60000)) * 60000 / 119.8)
        }
        check(correctionsAfterManual > 0 && maximum < 6)
        println("PASS SYNC manual reference while drifting: automatic corrections continue around the new +15-ms target")
    }

    // Un reset/pausa y un cambio de frecuencia conservan la referencia manual.
    run {
        var sampleRate = 48000.0
        val follower = PhaseSynchronizer(120.0, sampleRate, 20.123*sampleRate, 17.0)
        follower.prime(TempoEstimate(120.0,0.0,1.0,20.123*sampleRate),20.123*sampleRate)
        var clock = 20.123*sampleRate
        var advance = 17.0
        for(step in 1..8) {
            clock += .5*sampleRate
            check(follower.propose(TempoEstimate(120.0,0.0,1.0,clock),clock,advance)==null)
        }
        check(follower.tracking)
        follower.shiftReference(1.0,clock);advance+=1.0
        follower.waitForReference();check(!follower.tracking)
        follower.reconfigureRate(96000.0);clock*=2;sampleRate=96000.0
        for(step in 1..12) {
            clock += .5*sampleRate
            check(follower.propose(TempoEstimate(120.0,0.0,1.0,clock),clock,advance)==null)
        }
        check(follower.tracking)
        println("PASS SYNC waiting and 48→96 kHz restart: same manual target, fresh evidence and no spurious phase correction")
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
