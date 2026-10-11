package com.research.sensormax

/**
 * Immutable measurement-session identity and lifecycle state.
 * UI state, network state, and chart state must not redefine this identity.
 */
enum class RecordingSessionState {
    IDLE,
    RUNNING,
    STOPPING,
    COMPLETED
}

data class RecordingSession(
    val sessionId: String,
    val asset: String,
    val point: String,
    val customLabel: String,
    val startedAtMs: Long,
    val state: RecordingSessionState
)
