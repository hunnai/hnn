package com.example.soinubiderxl

enum class TempoMode { AUTOMATIC, TAP }
data class TempoEstimate(val bpm: Double, val anchor: Double, val confidence: Double, val timestamp: Double)
data class LoopBankState(
    val state: Int = 0, val divisions: Int = 0, val bpm: Double = 0.0,
    val advanceMS: Double = 0.0, val progress: Float = 0f
)
data class SoinuState(
    val mode: TempoMode = TempoMode.AUTOMATIC,
    val autoRange: AutoBpmRange = AutoBpmRange(),
    val appearance: Appearance = Appearance.VIOLET,
    val tempo: TempoEstimate? = null,
    val banks: List<LoopBankState> = List(6) { LoopBankState() },
    val inputPeak: Float = 0f,
    val audioRunning: Boolean = false,
    val tapCount: Int = 0,
    val heldBank: Int? = null,
    val savedAdvanceMS: Double = 0.0,
    val currentAdvanceMS: Double? = null,
    val route: String = "Audio detenido",
    val error: String? = null
)
