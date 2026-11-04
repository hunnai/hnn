package com.example.soinubiderxl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File

fun main() {
    for (channels in 1..2) {
        val values = floatArrayOf(-2f, -.5f, 0f, .5f, 2f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val raw = floatArrayOf(96000f, channels.toFloat(), .25f, (values.size / channels).toFloat()) + values
        val wav = diagnosticWav(raw, "USB: Traktor Audio 10", doubleArrayOf(96000.0, 1234.0))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        fun id(): String = ByteArray(4).also { b.get(it) }.toString(Charsets.US_ASCII)
        check(id() == "RIFF" && b.int == wav.size - 8 && id() == "WAVE")
        var format = false; var fact = false; var info = false; var data = false
        while(b.hasRemaining()) {
            val chunk = id(); val length = b.int; val end = b.position() + length
            when(chunk) {
                "fmt " -> {
                    check(b.short.toInt() == 3 && b.short.toInt() == channels)
                    check(b.int == 96000 && b.int == 96000 * channels * 4)
                    check(b.short.toInt() == channels * 4 && b.short.toInt() == 32)
                    format = true
                }
                "fact" -> { check(b.int == values.size / channels); fact = true }
                "LIST" -> {
                    check(id() == "INFO" && id() == "ICMT")
                    val text = ByteArray(b.int).also { b.get(it) }.toString(Charsets.UTF_8)
                    check(text.contains("callbackElapsedSeconds=0.25") && text.contains("Traktor Audio 10"))
                    info = true
                }
                "data" -> {
                    check(length == values.size * 4)
                    for (value in values) check(b.float.toRawBits() == value.toRawBits())
                    data = true
                }
                else -> error("Unexpected chunk $chunk")
            }
            b.position(end + (length and 1))
        }
        check(format && fact && info && data)
        File("/tmp/soinu-usb/diagnostic-$channels.wav").writeBytes(wav)
    }
    check(runCatching { diagnosticWav(floatArrayOf(96000f, 8f, 1f, 1f, .5f), "USB", doubleArrayOf()) }.isFailure)
    println("PASS WAV diagnostic: float samples preserved, mono/stereo, RIFF/fact/metadata lengths and invalid format rejection")
}
