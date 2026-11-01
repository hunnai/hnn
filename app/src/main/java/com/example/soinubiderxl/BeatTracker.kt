package com.example.soinubiderxl

import kotlin.math.*

// Confinado a una cola de análisis. Mantiene el algoritmo original de iPhone.
class BeatTracker(private val rate: Double, private val range: AutoBpmRange = AutoBpmRange()) {
    private data class Fit(val bpm: Double, val anchor: Double, val coverage: Double, val uncertainty: Double)
    private val size: Int
    private val hop: Int
    private val labelOffset: Double
    private val fft: FFTAnalyzer
    private val weights: DoubleArray
    private val eventTimes: DoubleArray
    private val eventValues: DoubleArray
    private var pending: FloatArray
    private var pendingCount = 0
    private var previous: DoubleArray
    private var compressed: DoubleArray
    private var hasPrevious = false
    private val smoothing = DoubleArray(17) { exp(-.5 * ((it - 8) / 2.5).pow(2)) }
    private var nextInput: Long? = null
    private var cursor = 0.0
    private var lastAnalysis = Double.NEGATIVE_INFINITY
    private var head = 0
    private var count = 0
    private var segmentStart = Double.NEGATIVE_INFINITY
    private var change: Pair<Double, Double>? = null
    var estimate: TempoEstimate? = null; private set
    init {
        require(rate.isFinite() && rate in 8000.0..192000.0)
        var n = 128
        while (n < rate * .020) n *= 2
        size = n; hop = max(16, (rate * .005).roundToInt())
        labelOffset = (n * .5625).roundToInt().toDouble()
        fft = FFTAnalyzer(n)
        weights = DoubleArray(n / 2 + 1) {
            val hz = it * rate / n
            when { hz < 30 || hz > 8000 -> 0.0; hz < 180 -> 3.0; hz < 500 -> .5; else -> .03 }
        }
        eventTimes = DoubleArray(ceil(20 * rate / hop).toInt())
        eventValues = DoubleArray(eventTimes.size)
        pending = FloatArray(n * 2)
        previous = DoubleArray(n / 2 + 1)
        compressed = DoubleArray(n / 2 + 1)
    }
    private fun clearEvidence(start: Long) {
        pendingCount = 0; hasPrevious = false; head = 0; count = 0
        cursor = start.toDouble(); lastAnalysis = Double.NEGATIVE_INFINITY
        segmentStart = Double.NEGATIVE_INFINITY; change = null
        // Igual que en iPhone: reinicia evidencia, conservando el último tempo.
    }
    fun feed(start: Long, samples: FloatArray): TempoEstimate? {
        if (nextInput != start) clearEvidence(start)
        nextInput = start + samples.size
        val required = pendingCount + samples.size
        if (required > pending.size) pending = pending.copyOf(max(required, pending.size * 2))
        samples.copyInto(pending, pendingCount)
        pendingCount = required
        var offset = 0
        while (pendingCount - offset >= size) {
            val spectrum = fft.spectrum(pending, offset)
            for (i in compressed.indices) compressed[i] = ln1p(spectrum[i] * 500 / size)
            if (hasPrevious) {
                var flux = 0.0
                for (i in compressed.indices) flux += max(0.0, compressed[i] - previous[i]) * weights[i]
                eventTimes[head] = cursor + labelOffset
                eventValues[head] = flux
                head = (head + 1) % eventTimes.size
                count = min(count + 1, eventTimes.size)
            }
            val swap = previous
            previous = compressed
            compressed = swap
            hasPrevious = true
            offset += hop
            cursor += hop
        }
        if (offset > 0) {
            pending.copyInto(pending, 0, offset, pendingCount)
            pendingCount -= offset
        }
        if (start - lastAnalysis >= rate / 2) { lastAnalysis = start.toDouble(); analyze() }
        return estimate
    }
    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted(); val p = (sorted.size - 1) * fraction; val i = p.toInt()
        return sorted[i] + (sorted[min(i + 1, sorted.lastIndex)] - sorted[i]) * (p - i)
    }
    private fun analyze() {
        if (count < (4 * rate / hop).toInt()) return
        val first = (head - count + eventTimes.size) % eventTimes.size
        val times = DoubleArray(count) { eventTimes[(first + it) % eventTimes.size] }
        val flux = DoubleArray(count) { eventValues[(first + it) % eventValues.size] }
        val reference = times.last()
        val width = (0.5 * rate / hop).toInt() or 1; val half = width / 2
        val prefix = DoubleArray(count + 1)
        for (i in flux.indices) prefix[i + 1] = prefix[i] + flux[i]
        val envelope = DoubleArray(count) { i ->
            val a = max(0, i - half); val b = min(count, i + half + 1)
            val edge = max(0, half - i) * flux.first() + max(0, i + half + 1 - count) * flux.last()
            max(0.0, flux[i] - (prefix[b] - prefix[a] + edge) / width)
        }
        // Los golpes espaciados ocupan menos del 2 % de las ventanas.
        // Con el percentil original se confundían ritmos lentos con silencio.
        val scalePercentile = if (range.minimum < 115) .99 else .98
        val scale = percentile(times.indices.filter { times[it] >= reference - 1.5 * rate }.map { envelope[it] }, scalePercentile)
        if (scale < .02) { change = null; segmentStart = reference; return }
        val normalized = DoubleArray(count) { min(2.0, envelope[it] / scale) }
        val selected = mutableListOf<Int>()
        for (i in 1 until count - 1) {
            if (envelope[i] > envelope[i - 1] && envelope[i] >= envelope[i + 1] && envelope[i] > .18 * scale) {
                val last = selected.lastOrNull()
                if (last != null && times[i] - times[last] < .1 * rate) {
                    if (envelope[i] > envelope[last]) selected[selected.lastIndex] = i
                } else selected.add(i)
            }
        }
        val onsets = mutableListOf<Double>(); val strengths = mutableListOf<Double>()
        for (i in selected) {
            val d = envelope[i - 1] - 2 * envelope[i] + envelope[i + 1]
            val fraction = if (abs(d) > 1e-12) (.5 * (envelope[i - 1] - envelope[i + 1]) / d).coerceIn(-.5, .5) else 0.0
            onsets.add(times[i] + fraction * hop); strengths.add(envelope[i])
        }
        val fast = fit(times, normalized, onsets, strengths, reference, 6.0, 3.0)
        if (fast == null || fast.uncertainty > .12) { change = null; return }
        val slow = fit(times, normalized, onsets, strengths, reference, min(18.0, 32 * 60 / fast.bpm), 7.5)
        val old = estimate
        if (old != null && abs(fast.bpm - old.bpm) > .15) {
            val candidate = change
            if (candidate != null && abs(fast.bpm - candidate.first) <= .3) {
                if (reference - candidate.second >= 2 * rate) {
                    segmentStart = max(segmentStart, reference - 6 * rate)
                    estimate = TempoEstimate(fast.bpm, fast.anchor, fast.coverage, reference); change = null
                }
            } else change = fast.bpm to reference
            return
        }
        change = null
        if (slow == null || abs(slow.bpm - fast.bpm) > .12) return
        estimate = TempoEstimate(slow.bpm, slow.anchor, slow.coverage, reference)
    }
    private fun fit(
        times: DoubleArray, values: DoubleArray, onsets: List<Double>, strengths: List<Double>,
        reference: Double, seconds: Double, minimum: Double
    ): Fit? {
        val lower = max(segmentStart, reference - seconds * rate)
        var first = 0
        while (first < times.size && times[first] < lower) first++
        val last = times.lastIndex
        if (first > last || times[last] - times[first] < minimum * rate) return null
        val scores = DoubleArray((range.maximum - range.minimum) * 4 + 1)
        val histogram = DoubleArray(96)
        val profile = DoubleArray(96)
        var bestProfile = DoubleArray(96)
        var best = 0
        var score = Double.NEGATIVE_INFINITY
        for (step in scores.indices) {
            val candidatePeriod = 60 * rate / (range.minimum + step * .25)
            histogram.fill(0.0)
            for (i in first..last) {
                val phase = (reference - times[i]) % candidatePeriod
                histogram[(phase / candidatePeriod * 96).toInt().coerceIn(0, 95)] += values[i]
            }
            profile.fill(0.0)
            var candidateScore = 0.0
            for (bin in 0 until 96) {
                for (delta in -8..8) {
                    var index = bin + delta
                    if (index < 0) index += 96 else if (index >= 96) index -= 96
                    profile[bin] += histogram[index] * smoothing[delta + 8]
                }
                candidateScore = max(candidateScore, profile[bin])
            }
            scores[step] = candidateScore
            if (candidateScore > score) {
                score = candidateScore
                best = step
                profile.copyInto(bestProfile)
            }
        }
        if (score < 1.6 * max(1e-9, percentile(scores.toList(), .5))) return null
        var period = 60 * rate / (range.minimum + best * .25)
        val bin = bestProfile.indices.maxByOrNull { bestProfile[it] } ?: return null
        var anchor = reference - (bin + .5) / 96 * period
        val chosen = mutableMapOf<Int, Pair<Double, Double>>()
        for (i in onsets.indices) {
            val time = onsets[i]
            if (time < times[first] || time > times[last]) continue
            val number = ((time - anchor) / period).roundToInt()
            if (abs(time - (anchor + number * period)) >= .16 * period) continue
            val old = chosen[number]
            if (old != null && old.second >= strengths[i]) continue
            chosen[number] = time to strengths[i]
        }
        val numbers = chosen.keys.sorted()
        if (numbers.size < 7) return null
        val x = numbers.map { it.toDouble() }; val y = numbers.map { chosen.getValue(it).first }
        val strong = numbers.map { chosen.getValue(it).second }
        val median = max(1e-9, percentile(strong, .5)); val weights = strong.map { min(2.0, it / median) }
        var valid = BooleanArray(x.size) { true }; var xm = 0.0
        for (iteration in 0..4) {
            val kept = x.indices.filter { valid[it] }
            if (kept.size < 7) return null
            val total = kept.sumOf { weights[it] }
            xm = kept.sumOf { x[it] * weights[it] } / total
            val ym = kept.sumOf { y[it] * weights[it] } / total
            val denominator = kept.sumOf { weights[it] * (x[it] - xm).pow(2) }
            if (denominator <= 0) return null
            period = kept.sumOf { weights[it] * (x[it] - xm) * (y[it] - ym) } / denominator
            anchor = ym - period * xm
            if (iteration < 4) {
                val errors = x.indices.map { y[it] - (anchor + period * x[it]) }
                val med = percentile(kept.map { errors[it] }, .5)
                val mad = percentile(kept.map { abs(errors[it] - med) }, .5)
                valid = BooleanArray(x.size) { abs(errors[it]) < max(.002 * rate, 4 * 1.4826 * mad) }
            }
        }
        val kept = x.indices.filter { valid[it] }
        val a = kept.firstOrNull() ?: return null; val b = kept.lastOrNull() ?: return null
        if (y[b] - y[a] < minimum * rate || period <= 0 || reference - y[b] > 1.2 * rate) return null
        val bpm = 60 * rate / period
        val rms = sqrt(kept.sumOf { (y[it] - (anchor + period * x[it])).pow(2) } / kept.size)
        val coverage = kept.size.toDouble() / (x[b] - x[a] + 1)
        if (bpm < range.minimum - .05 || bpm > range.maximum + .05 || rms > .025 * period || coverage < .65) return null
        val spread = kept.sumOf { (x[it] - xm).pow(2) }
        val uncertainty = 60 * rate / (period * period) * rms / sqrt(max(1.0, spread))
        return Fit(bpm, anchor, coverage, uncertainty)
    }
}
