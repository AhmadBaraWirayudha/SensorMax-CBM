package com.research.sensormax

fun main() {
    val buffer = SignalBuffer<Int>(3)
    buffer.appendAll(listOf(1, 2, 3, 4))
    check(buffer.snapshot() == listOf(2, 3, 4))

    val gate = AnalysisGate(requireFillThreshold = 5)
    check(gate.shouldRun(0))
    check(!gate.shouldRun(0))
    check(!gate.shouldRun(4))
    check(gate.shouldRun(5))
    gate.onStarted()
    check(gate.shouldRun(0))
    gate.onStopped()
    check(!gate.shouldRun(0))

    val clock = ExperimentClock()
    clock.markStart(1_000_000_000L, 1_700_000_000_000L)
    check(clock.elapsedMs(1_250_000_000L) == 250L)
    check(clock.wallClockMsFor(1_250_000_000L) == 1_700_000_000_250L)

    println("PHYPhox_CONFORMANCE_QA_PASS")
}
