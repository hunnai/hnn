package com.example.soinubiderxl

import kotlin.math.PI
import kotlin.math.cos

class FFTAnalyzer(val size: Int) {
    init { require(size >= 2 && (size and (size - 1)) == 0) }
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2.0 * PI * it / (size - 1))).toFloat() }
    private val work = FloatArray(size)
    private val magnitudes = DoubleArray(size / 2 + 1)
    fun spectrum(samples: List<Float>, offset: Int = 0): DoubleArray {
        require(offset >= 0 && offset <= samples.size - size)
        for (i in 0 until size) work[i] = samples[offset + i] * window[i]
        check(NativeAudio.fft(work, magnitudes)) { "No se pudo calcular la FFT" }
        return magnitudes
    }
}
