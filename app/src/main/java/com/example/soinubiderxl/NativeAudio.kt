package com.example.soinubiderxl

// El controlador llama a estas funciones desde un único hilo principal.
// Solo fft() se ejecuta en el hilo de análisis y no utiliza la sesión.
object NativeAudio {
    init { System.loadLibrary("soinubider") }
    external fun create(): Long
    external fun start(handle: Long, inputDevice: Int, outputDevice: Int, advance: Double)
    external fun stop(handle: Long)
    external fun destroy(handle: Long)
    external fun snapshot(handle: Long): DoubleArray
    external fun arm(handle: Long, bank: Int, bpm: Double, anchor: Double, tapSeconds: Double): Boolean
    external fun command(handle: Long, operation: Int, bank: Int, value: Double): Boolean
    external fun readAnalysis(handle: Long, samples: FloatArray, start: LongArray): Int
    external fun fft(input: FloatArray, output: DoubleArray): Boolean
    const val TOGGLE = 1
    const val ERASE_ALL = 2
    const val ERASE_BANK = 3
    const val RESIZE = 4
    const val NUDGE_ALL = 5
    const val NUDGE_BANK = 6
    const val INITIAL_ADVANCE = 7
}
