from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
output = Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
base = root / "app/src/main/java/com/example/soinubiderxl"
source = (base / "SoinuController.kt").read_text()
block = source[source.index("    private fun poll() {"):source.index("    fun auto() {")]
prefix = """package com.example.soinubiderxl
import kotlinx.coroutines.*
import kotlin.math.max
class PollHarness {
    private var polling: Job? = null
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var state = SoinuState(audioRunning = true, autoRange = AutoBpmRange(85, 175))
    private var handle = 1L
    private var generation = 0
    private val pending = mutableSetOf<Int>()
    private fun updateAdvance() {}
    private fun stop() { state = state.copy(audioRunning = false); polling?.cancel() }
    private fun error(message: String) { throw IllegalStateException(message) }
    fun begin() = poll()
    fun finish() { stop(); viewModelScope.cancel() }
"""
(output / "PollHarness.kt").write_text(prefix + block + "}\n")
reference = subprocess.check_output([
    "git", "show", "276b694:app/src/main/java/com/example/soinubiderxl/BeatTracker.kt"
], cwd=root, text=True).replace("class BeatTracker(", "class ReferenceBeatTracker(")
(output / "ReferenceBeatTracker.kt").write_text(reference)
