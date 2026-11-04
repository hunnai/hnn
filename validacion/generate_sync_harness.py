"""Extrae métodos de producción para probar gestos y comandos sin un dispositivo Android."""
from pathlib import Path
import sys
root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/com/example/soinubiderxl/SoinuController.kt').read_text()
methods = source[source.index('    fun auto() {'):source.index('    fun cancelTouches() {')]
start = source.index('    private fun suspendAudio() {')
brace = source.index('{', start)
depth = 1
end = brace + 1
while depth:
    if source[end] == '{': depth += 1
    elif source[end] == '}': depth -= 1
    end += 1
suspend = source[start:end] + '\n'
actions = source[source.index('    fun cancelTouches() {'):source.index('    private fun updateAdvance() {')]
output = Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
prefix = '''package com.example.soinubiderxl
import kotlinx.coroutines.*
class SyncControlHarness {
    var state = SoinuState()
    var audioClock = 0.0
    private var audioRate = 48000.0
    private val synchronizers = mutableMapOf<Int, PhaseSynchronizer>()
    private var handle = 1L
    private var generation = 0
    private var lastTap: Double? = null
    private val tap = TapTempoEstimator()
    private val pending = mutableSetOf<Int>()
    private val activationOrder = mutableListOf<Int>()
    private val touching = mutableSetOf<Int>()
    private val suppressClick = mutableSetOf<Int>()
    private var polling: Job? = null
    private val actions = mutableSetOf<Action>()
    private fun updateAdvance() {}
    private fun error(message: String) { throw IllegalStateException(message) }
    fun tick(clock: Double, tempo: TempoEstimate) {
        audioClock = clock; state = state.copy(tempo = tempo); updateSync()
    }
    fun manualNudge(index: Int) { send(NativeAudio.NUDGE_BANK, index, 1.0) }
    fun globalNudge(value: Double) = send(NativeAudio.NUDGE_ALL, value = value)
    fun sessionEnd() = suspendAudio()
    fun resume(rate: Double) {
        if (audioRate != rate) {
            synchronizers.values.forEach { it.reconfigureRate(rate) }
            audioClock *= rate / audioRate
            audioRate = rate
        }
        state = state.copy(audioRunning = true)
    }
'''
(output / 'SyncControlHarness.kt').write_text(prefix + methods + actions + suspend + '}\n')
(output / 'SyncControlNative.kt').write_text('''package com.example.soinubiderxl
object NativeAudio {
    data class Call(val op: Int, val bank: Int, val value: Double)
    val calls = mutableListOf<Call>()
    var accepted = true
    fun command(handle: Long, op: Int, bank: Int, value: Double): Boolean {
        check(handle == 1L); calls.add(Call(op, bank, value)); return accepted
    }
    fun arm(handle: Long, bank: Int, bpm: Double, anchor: Double, time: Double): Boolean = true
    fun stop(handle: Long) { check(handle == 1L) }
    const val TOGGLE = 1; const val ERASE_ALL = 2; const val ERASE_BANK = 3
    const val RESIZE = 4; const val NUDGE_ALL = 5; const val NUDGE_BANK = 6
    const val INITIAL_ADVANCE = 7
}
''')
