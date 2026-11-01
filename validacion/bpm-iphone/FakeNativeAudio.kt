package com.example.soinubiderxl

import java.util.ArrayDeque
object NativeAudio {
    init { System.loadLibrary("soinubider") }
    external fun fft(input: FloatArray, output: DoubleArray): Boolean
    private val packets = ArrayDeque<Pair<Long, FloatArray>>()
    private var clock = 0L
    var drops = 0L; private set
    @Synchronized fun enqueue(samples: FloatArray) {
        if (packets.size == 63) drops++ else packets.addLast(clock to samples)
        clock += samples.size
    }
    @Synchronized fun snapshot(handle: Long): DoubleArray {
        check(handle == 1L && Thread.currentThread().name == "test-ui")
        return DoubleArray(37).also { it[0] = 48000.0; it[1] = clock.toDouble(); it[2] = .7; it[4] = drops.toDouble() }
    }
    @Synchronized fun readAnalysis(handle: Long, samples: FloatArray, start: LongArray): Int {
        check(handle == 1L && Thread.currentThread().name == "test-ui")
        val packet = packets.pollFirst() ?: return 0
        start[0] = packet.first
        packet.second.copyInto(samples)
        return packet.second.size
    }
}
