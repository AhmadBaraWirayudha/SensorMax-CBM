package com.research.sensormax

fun main() {
    // Stop before start is a no-op and must not fabricate a completed session.
    run {
        val lifecycle = RecordingSessionLifecycle()
        lifecycle.requestStop()
        check(lifecycle.current.state == RecordingSessionState.IDLE)
        lifecycle.complete()
        check(lifecycle.current.state == RecordingSessionState.IDLE)
    }

    // Start -> stop -> repeated stop -> complete is deterministic.
    run {
        val lifecycle = RecordingSessionLifecycle()
        lifecycle.begin("S1", "Pump-01", "DE", "Axial", 1_000L)
        check(lifecycle.current.state == RecordingSessionState.RUNNING)
        lifecycle.requestStop()
        check(lifecycle.current.state == RecordingSessionState.STOPPING)
        lifecycle.requestStop()
        check(lifecycle.current.state == RecordingSessionState.STOPPING)
        lifecycle.complete()
        check(lifecycle.current.state == RecordingSessionState.COMPLETED)
        lifecycle.complete()
        check(lifecycle.current.state == RecordingSessionState.COMPLETED)
    }

    // A completed session can restart with a new identity without inheriting
    // the previous lifecycle state.
    run {
        val lifecycle = RecordingSessionLifecycle()
        lifecycle.begin("S1", "Pump-01", "DE", "Axial", 1_000L)
        lifecycle.requestStop()
        lifecycle.complete()
        lifecycle.begin("S2", "Pump-01", "DE", "Axial", 2_000L)
        check(lifecycle.current.sessionId == "S2")
        check(lifecycle.current.state == RecordingSessionState.RUNNING)
    }

    // Starting twice without completing the first session must be rejected.
    run {
        val lifecycle = RecordingSessionLifecycle()
        lifecycle.begin("S1", "Pump-01", "DE", "Axial", 1_000L)
        val error = runCatching {
            lifecycle.begin("S2", "Pump-01", "DE", "Axial", 2_000L)
        }.exceptionOrNull()
        check(error is IllegalStateException)
    }

    // UI reconnection semantics: retained buffer survives observer changes.
    run {
        val buffer = SignalBuffer<Int>(4)
        buffer.appendAll(listOf(10, 20, 30))
        val detachedSnapshot = buffer.snapshot()
        check(detachedSnapshot == listOf(10, 20, 30))
        buffer.append(40)
        check(buffer.snapshot() == listOf(10, 20, 30, 40))
    }

    // Analysis gate: start grants one pass; stop grants none.
    run {
        val gate = AnalysisGate(requireFillThreshold = 4)
        gate.onOpened()
        check(gate.shouldRun(0))
        check(!gate.shouldRun(0))
        gate.onStarted()
        check(gate.shouldRun(0))
        gate.onStopped()
        check(!gate.shouldRun(0))
        check(gate.shouldRun(4))
    }

    // Experiment clock remains monotonic and deterministic across restart.
    run {
        val clock = ExperimentClock()
        clock.markStart(10_000_000L, 5_000L)
        check(clock.elapsedMs(210_000_000L) == 200L)
        check(clock.wallClockMsFor(210_000_000L) == 5_200L)
    }

    println("LIFECYCLE_FAILURE_INJECTION_QA_PASS")
}
