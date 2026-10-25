package com.example.soinubiderxl

class TapTempoEstimator {
    var count = 0; private set
    var bpm: Double? = null; private set
    private var first = 0.0
    private var last: Double? = null
    private var meanX = 0.0
    private var meanY = 0.0
    private var sxx = 0.0
    private var sxy = 0.0
    fun reset() {
        count = 0; bpm = null; first = 0.0; last = null
        meanX = 0.0; meanY = 0.0; sxx = 0.0; sxy = 0.0
    }
    fun press(timestamp: Double): Double? {
        if (!timestamp.isFinite()) return bpm
        val previous = last
        if (previous != null) {
            if (timestamp < previous || timestamp - previous > SoinuConfiguration.tapPauseSeconds) reset()
            else if (timestamp == previous) return bpm
        }
        if (count == 0) first = timestamp
        val x = count.toDouble(); val y = timestamp - first
        count++
        val dx = x - meanX; val dy = y - meanY
        meanX += dx / count; meanY += dy / count
        sxx += dx * (x - meanX); sxy += dx * (y - meanY)
        last = timestamp
        if (count >= SoinuConfiguration.tapMinimumPresses && sxx > 0.0) {
            val period = sxy / sxx
            if (period > 0.0) bpm = 60.0 / period
        }
        return bpm
    }
}
