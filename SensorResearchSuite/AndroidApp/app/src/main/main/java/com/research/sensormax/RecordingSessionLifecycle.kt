package com.research.sensormax

/**
 * Single authority for measurement-session lifecycle transitions.
 *
 * The Android Service/Activity may request transitions, but they must not
 * directly manufacture state combinations. Repeated stop requests are
 * idempotent; a new session can start only after the previous one completed.
 */
class RecordingSessionLifecycle {
    @Volatile
    var current: RecordingSession = RecordingSession(
        sessionId = "",
        asset = "",
        point = "",
        customLabel = "",
        startedAtMs = 0L,
        state = RecordingSessionState.IDLE
    )
        private set

    @Synchronized
    fun begin(
        sessionId: String,
        asset: String,
        point: String,
        customLabel: String,
        startedAtMs: Long
    ): RecordingSession {
        check(current.state != RecordingSessionState.RUNNING) {
            "Cannot begin a new recording while one is already running"
        }
        check(current.state != RecordingSessionState.STOPPING) {
            "Cannot begin a new recording while the previous recording is stopping"
        }

        current = RecordingSession(
            sessionId = sessionId,
            asset = asset,
            point = point,
            customLabel = customLabel,
            startedAtMs = startedAtMs,
            state = RecordingSessionState.RUNNING
        )
        return current
    }

    @Synchronized
    fun requestStop(): RecordingSession {
        current = when (current.state) {
            RecordingSessionState.RUNNING -> current.copy(state = RecordingSessionState.STOPPING)
            RecordingSessionState.STOPPING -> current
            RecordingSessionState.IDLE,
            RecordingSessionState.COMPLETED -> current
        }
        return current
    }

    @Synchronized
    fun complete(): RecordingSession {
        current = when (current.state) {
            RecordingSessionState.STOPPING,
            RecordingSessionState.RUNNING -> current.copy(state = RecordingSessionState.COMPLETED)
            RecordingSessionState.COMPLETED,
            RecordingSessionState.IDLE -> current
        }
        return current
    }
}
