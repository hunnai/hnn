package com.example.soinubiderxl

import kotlin.math.abs

fun main() {
    val rate = 48000.0
    val start = 20 * rate
    val controller = SyncControlHarness()
    controller.audioClock = start
    controller.state = SoinuState(audioRunning = true,
        tempo = TempoEstimate(120.0, 0.0, 1.0, start),
        banks = List(6) { i -> LoopBankState(state = if (i < 2) 3 else 0,
            bpm = if (i == 0) 119.8 else 120.2) })
    fun gesture(index: Int) {
        controller.beginBank(index)
        controller.auto()
        controller.endBank(index)
    }
    controller.auto()
    check(controller.state.syncedBanks.isEmpty())
    gesture(0)
    check(controller.state.syncedBanks == setOf(0))
    check(!controller.clickAllowed(0))
    check(controller.state.banks[0].state == 3)
    gesture(0)
    check(controller.state.syncedBanks.isEmpty())
    gesture(0); gesture(1)
    check(controller.state.syncedBanks == setOf(0, 1))
    for (step in 1..40) {
        val clock = start + step * .5 * rate
        controller.tick(clock, TempoEstimate(120.0, 0.0, 1.0, clock))
        val calls = NativeAudio.calls.toList()
        NativeAudio.calls.clear()
        for (call in calls) {
            check(call.op == NativeAudio.NUDGE_BANK && call.bank in 0..1 && abs(call.value) <= 3)
            check(if (call.bank == 0) call.value > 0 else call.value < 0)
            controller.state = controller.state.copy(banks = controller.state.banks.mapIndexed { i, bank ->
                if (i == call.bank) bank.copy(advanceMS = bank.advanceMS + call.value) else bank
            })
        }
    }
    check(controller.state.syncTrackingBanks == setOf(0, 1))
    check(controller.state.banks[0].bpm == 119.8 && controller.state.banks[1].bpm == 120.2)
    println("PASS SYNC controls: hold+AUTO toggles per bank, release suppressed, independent bounded phase corrections")

    controller.manualNudge(0)
    check(controller.state.syncedBanks == setOf(0, 1))
    check(controller.state.syncTrackingBanks == setOf(0, 1))
    controller.resetTempo()
    check(controller.state.syncedBanks.isEmpty() && controller.state.syncTrackingBanks.isEmpty())
    controller.state = controller.state.copy(tempo = TempoEstimate(120.0, 0.0, 1.0, controller.audioClock))
    gesture(0)
    controller.tap(100.0)
    check(controller.state.mode == TempoMode.TAP && controller.state.syncedBanks.isEmpty())
    controller.state = controller.state.copy(mode = TempoMode.AUTOMATIC,
        tempo = TempoEstimate(120.0, 0.0, 1.0, controller.audioClock))
    gesture(0)
    controller.state = controller.state.copy(banks = controller.state.banks.mapIndexed { i, bank ->
        if (i == 0) bank.copy(state = 4) else bank
    })
    controller.tick(controller.audioClock + .5 * rate, controller.state.tempo!!)
    check(controller.state.syncedBanks.isEmpty())
    gesture(0)
    check(controller.state.syncedBanks.isEmpty())
    controller.state = controller.state.copy(banks = controller.state.banks.mapIndexed { i, bank ->
        if (i == 0) bank.copy(state = 3) else bank
    })
    gesture(0)
    controller.sessionEnd()
    check(!controller.state.audioRunning && controller.state.syncedBanks.isEmpty())
    println("PASS SYNC controls: manual per-bank adjustment preserves SYNC; RESET, TAP, pause and stop clear references")
}
