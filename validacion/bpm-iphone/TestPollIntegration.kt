package com.example.soinubiderxl
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlin.math.*
import java.util.concurrent.locks.LockSupport

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
fun main() = runBlocking {
    val ui = newSingleThreadContext("test-ui")
    Dispatchers.setMain(ui)
    val controller = PollHarness()
    val producer = Thread {
        var position = 0L
        val begin = System.nanoTime()
        while (position < 17 * 48000) {
            val target = begin + (position * 1_000_000_000L / 48000)
            while (System.nanoTime() < target) LockSupport.parkNanos(target - System.nanoTime())
            NativeAudio.enqueue(FloatArray(256) { i ->
                val phase = ((position + i) % 24000) / 48000.0
                (.7 * exp(-phase * 65) * sin(2 * PI * 90 * phase)).toFloat()
            })
            position += 256
        }
    }
    try {
        withContext(ui) { controller.begin() }
        producer.start()
        var detected = false; var blanks = 0
        repeat(160) { tick ->
            delay(100)
            withContext(ui) {
                // Carga de interfaz intermitente, sin detener la entrada de audio.
                if (tick % 10 == 0) Thread.sleep(60)
                val tempo = controller.state.tempo
                if (tempo != null) {
                    detected = true
                    check(abs(tempo.bpm - 120) < .15)
                } else if (detected) blanks++
            }
        }
        check(detected && blanks == 0) { "detected=$detected blanks=$blanks" }
        check(NativeAudio.drops == 0L) { "Audio FIFO drops=${NativeAudio.drops}" }
        val lines = android.util.Log.lines.toList()
        println("PASS integración JVM: poll de producción, entrada 48 kHz/256 frames, rango 85-175, pausas UI de 60 ms; 120 BPM sin desapariciones ni pérdidas en FIFO simulada")
        lines.takeLast(3).forEach(::println)
    } finally {
        withContext(ui) { controller.finish() }
        producer.join()
        Dispatchers.resetMain()
        ui.close()
    }
}
