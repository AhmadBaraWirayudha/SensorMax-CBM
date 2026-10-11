package com.research.sensormax

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-frequency companion packet to the raw per-sample stream.
 * One packet is sent per completed FFT window rather than per sensor sample.
 *
 * Gson is intentionally used only for this low-frequency message. The raw
 * high-rate path continues to use a hand-built StringBuilder to minimize
 * allocations and reflection overhead in the acquisition loop.
 */
data class AnalysisSnapshot(
    val kind: String = "analysis",
    val schemaVersion: Int = 1,
    val ts: Long,
    val machineId: String,
    val point: String,
    val sampleRateHz: Double,
    val overallRmsAccelMs2: Float,
    val overallRmsVelocityMmS: Float,
    val overallRmsDisplacementUm: Float,
    val dominantAxis: String,
    val dominantFreqHz: Float,
    val envelopePeakFreqHz: Float,
    val isoZone: String,
    val bearingMatch: String
)

/**
 * Network transport for SensorMax telemetry.
 *
 * DESIGN GOALS
 * ------------
 * 1. Never block the sensor/acquisition thread waiting for the network.
 * 2. Keep reconnect handling deterministic and single-instance.
 * 3. Keep a bounded offline buffer.
 * 4. Do not let high-rate raw traffic permanently starve low-rate analysis.
 * 5. Reject malformed endpoints before creating an OkHttp WebSocket.
 * 6. Ignore callbacks from stale/previous sockets.
 * 7. Never emit invalid JSON because a sensor value is NaN/Infinity.
 * 8. Drop OLDEST raw samples when the bounded buffer is full, preserving the
 *    most recent history for condition-monitoring context.
 * 9. Clear stale unsent data when a deployment is explicitly terminated.
 *
 * PUBLIC API IS KEPT COMPATIBLE WITH THE EXISTING SENSOR ENGINE:
 *
 *     connectToInternetEndpoint(serverUrl)
 *     streamSensorData(...)
 *     streamAnalysisSnapshot(snapshot)
 *     terminate()
 */
class StreamClient(
    private val logger: (String) -> Unit
) {

    companion object {

        /**
         * Raw packets are high-frequency, so this remains deliberately
         * bounded. At ~50 Hz, 1500 packets represents roughly 30 seconds of
         * raw history before the oldest entries begin to roll off.
         */
        private const val MAX_RAW_BUFFER = 1500

        /**
         * Analysis packets are low-frequency and therefore get their own
         * smaller protected queue. This prevents raw traffic from starving
         * diagnostic snapshots during an outage.
         */
        private const val MAX_ANALYSIS_BUFFER = 64

        /** Initial reconnect delay. */
        private const val INITIAL_RECONNECT_DELAY_SEC = 3L

        /** Maximum reconnect delay. */
        private const val MAX_RECONNECT_DELAY_SEC = 30L

        /** WebSocket keep-alive. */
        private const val PING_INTERVAL_SEC = 15L

        /**
         * Network setup timeouts. The WebSocket remains long-lived after the
         * handshake, so the normal HTTP read timeout is effectively not used
         * for application payload timing.
         */
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val WRITE_TIMEOUT_SEC = 10L

        /** Reason used for an explicit application-side shutdown. */
        private const val NORMAL_CLOSE_CODE = 1000
    }

    // =====================================================================
    // OKHTTP
    // =====================================================================

    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .pingInterval(
                PING_INTERVAL_SEC,
                TimeUnit.SECONDS
            )
            .connectTimeout(
                CONNECT_TIMEOUT_SEC,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                WRITE_TIMEOUT_SEC,
                TimeUnit.SECONDS
            )
            .retryOnConnectionFailure(true)
            .build()

    private val gson =
        Gson()

    // =====================================================================
    // STATE
    // =====================================================================

    /**
     * Volatile because callbacks and sensor events are on different threads.
     */
    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isConnected = false

    @Volatile
    private var isStreamingAllowed = false

    @Volatile
    private var currentUrl = ""

    /**
     * Guards connection transitions and reconnect scheduling.
     */
    private val stateLock =
        Any()

    /**
     * Identifies the current connection generation.
     * A callback from an older WebSocket is ignored after a replacement has
     * been created.
     */
    private val connectionGeneration =
        AtomicLong(0L)

    private var reconnectAttempt =
        0

    private var reconnectFuture:
            ScheduledFuture<*>? = null

    /**
     * One single-thread scheduler prevents multiple reconnect Timer threads
     * from accumulating after repeated failures.
     */
    private val reconnectExecutor:
            ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor {
                runnable ->
            Thread(
                runnable,
                "SensorMax-WebSocket-Reconnect"
            ).apply {
                isDaemon = true
            }
        }

    // =====================================================================
    // BOUNDED OFFLINE BUFFERS
    // =====================================================================

    /**
     * Raw and analysis packets intentionally use separate queues.
     *
     * On reconnect, analysis is flushed first so the receiver receives the
     * higher-level condition summary even if raw traffic is large.
     */
    private val rawPayloadBuffer =
        ConcurrentLinkedQueue<String>()

    private val analysisPayloadBuffer =
        ConcurrentLinkedQueue<String>()

    /** Number of raw packets discarded due to outage-buffer overflow. */
    private val droppedRawPackets =
        AtomicLong(0L)

    /** Number of analysis packets discarded due to outage-buffer overflow. */
    private val droppedAnalysisPackets =
        AtomicLong(0L)

    /** Returns an immutable network-health snapshot for diagnostics/UI. */
    fun healthSnapshot(): NetworkHealthSnapshot =
        synchronized(stateLock) {
            NetworkHealthSnapshot(
                streamingAllowed = isStreamingAllowed,
                connected = isConnected,
                queuedRawPackets = rawPayloadBuffer.size,
                queuedAnalysisPackets = analysisPayloadBuffer.size,
                droppedRawPackets = droppedRawPackets.get(),
                droppedAnalysisPackets = droppedAnalysisPackets.get(),
                reconnectAttempt = reconnectAttempt
            )
        }

    // =====================================================================
    // PUBLIC CONNECTION API
    // =====================================================================

    /**
     * Enables streaming to [serverUrl].
     *
     * Explicitly starting a new endpoint is treated as a new network
     * session: stale payloads from a previous terminated deployment are
     * cleared rather than silently attached to the new measurement.
     */
    fun connectToInternetEndpoint(
        serverUrl: String
    ) {

        val normalizedUrl =
            serverUrl.trim()

        if (!isValidWebSocketUrl(normalizedUrl)) {

            logger(
                "[ERROR] Invalid WebSocket endpoint: $normalizedUrl " +
                        "(expected ws:// or wss:// URL)."
            )

            return
        }

        val oldSocket: WebSocket?

        synchronized(stateLock) {

            currentUrl =
                normalizedUrl

            isStreamingAllowed =
                true

            reconnectAttempt =
                0

            reconnectFuture?.cancel(
                false
            )

            reconnectFuture =
                null

            connectionGeneration.incrementAndGet()

            oldSocket =
                webSocket

            webSocket =
                null

            isConnected =
                false
        }

        clearBuffers()

        try {
            oldSocket?.close(
                NORMAL_CLOSE_CODE,
                "Endpoint replaced"
            )
        } catch (_: Exception) {
            // Best-effort shutdown of the previous socket.
        }

        establishConnection()
    }

    /**
     * Creates one WebSocket for the current connection generation.
     *
     * The method is intentionally asynchronous: OkHttp performs the actual
     * connection work off the caller's thread.
     */
    private fun establishConnection() {

        val url: String
        val generation: Long

        synchronized(stateLock) {

            if (
                !isStreamingAllowed
            ) {
                return
            }

            if (
                currentUrl.isBlank()
            ) {

                logger(
                    "[WARN] Cannot establish uplink: endpoint is empty."
                )

                return
            }

            /*
             * Do not create a second socket while one is already considered
             * active/connecting.
             */
            if (
                webSocket != null
            ) {
                return
            }

            url =
                currentUrl

            generation =
                connectionGeneration.get()
        }

        val request =
            try {
                Request.Builder()
                    .url(url)
                    .build()
            } catch (e: Exception) {

                logger(
                    "[ERROR] WebSocket request creation failed: " +
                            "${e.message}"
                )

                scheduleReconnect()
                return
            }

        val listener =
            object : WebSocketListener() {

                override fun onOpen(
                    socket: WebSocket,
                    response: Response
                ) {

                    if (
                        !isCurrentGeneration(
                            generation
                        )
                    ) {

                        socket.close(
                            NORMAL_CLOSE_CODE,
                            "Stale connection"
                        )

                        return
                    }

                    synchronized(stateLock) {

                        if (
                            !isStreamingAllowed ||
                            generation !=
                            connectionGeneration.get()
                        ) {

                            return@synchronized
                        }

                        // The callback may arrive before establishConnection()
                        // stores the returned WebSocket reference. Store it here
                        // as well so the connection is immediately usable.
                        webSocket =
                            socket

                        isConnected =
                            true

                        reconnectAttempt =
                            0

                        reconnectFuture?.cancel(
                            false
                        )

                        reconnectFuture =
                            null
                    }

                    logger(
                        "[NETWORK] Telemetry Uplink Established: $url"
                    )

                    flushBuffer(
                        socket
                    )
                }

                override fun onFailure(
                    socket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {

                    if (
                        !isCurrentSocket(
                            socket,
                            generation
                        )
                    ) {
                        return
                    }

                    synchronized(stateLock) {

                        if (
                            generation !=
                            connectionGeneration.get()
                        ) {
                            return@synchronized
                        }

                        isConnected =
                            false

                        webSocket =
                            null
                    }

                    logger(
                        "[WARN] Uplink failure: " +
                                "${t.message ?: t::class.java.simpleName}. " +
                                "Auto-reconnecting."
                    )

                    scheduleReconnect()
                }

                override fun onClosed(
                    socket: WebSocket,
                    code: Int,
                    reason: String
                ) {

                    if (
                        !isCurrentSocket(
                            socket,
                            generation
                        )
                    ) {
                        return
                    }

                    synchronized(stateLock) {

                        if (
                            generation !=
                            connectionGeneration.get()
                        ) {
                            return@synchronized
                        }

                        isConnected =
                            false

                        webSocket =
                            null
                    }

                    logger(
                        "[NETWORK] Uplink closed: " +
                                "code=$code reason=$reason"
                    )

                    if (
                        isStreamingAllowed
                    ) {
                        scheduleReconnect()
                    }
                }
            }

        val socket =
            try {
                client.newWebSocket(
                    request,
                    listener
                )
            } catch (e: Exception) {

                synchronized(stateLock) {

                    if (
                        generation ==
                        connectionGeneration.get()
                    ) {
                        webSocket = null
                        isConnected = false
                    }
                }

                logger(
                    "[ERROR] WebSocket creation failed: " +
                            "${e.message}"
                )

                scheduleReconnect()
                return
            }

        synchronized(stateLock) {

            if (
                isStreamingAllowed &&
                generation ==
                connectionGeneration.get()
            ) {

                webSocket =
                    socket

            } else {

                socket.close(
                    NORMAL_CLOSE_CODE,
                    "Streaming disabled before connect completed"
                )
            }
        }
    }

    // =====================================================================
    // RECONNECT
    // =====================================================================

    /**
     * Schedules exactly one reconnect attempt.
     *
     * Backoff:
     *
     *     3s → 6s → 12s → 24s → 30s → 30s...
     */
    private fun scheduleReconnect() {

        synchronized(stateLock) {

            if (
                !isStreamingAllowed
            ) {
                return
            }

            if (
                reconnectFuture?.isDone == false
            ) {
                return
            }

            val delaySec =
                calculateReconnectDelaySeconds(
                    reconnectAttempt
                )

            reconnectAttempt++

            reconnectFuture =
                reconnectExecutor.schedule(
                    {

                        synchronized(stateLock) {
                            reconnectFuture =
                                null
                        }

                        establishConnection()
                    },
                    delaySec,
                    TimeUnit.SECONDS
                )

            logger(
                "[NETWORK] Auto-reconnect scheduled in " +
                        "$delaySec s (attempt $reconnectAttempt)."
            )
        }
    }

    private fun calculateReconnectDelaySeconds(
        attempt: Int
    ): Long {

        val exponent =
            attempt.coerceIn(
                0,
                4
            )

        return minOf(
            MAX_RECONNECT_DELAY_SEC,
            INITIAL_RECONNECT_DELAY_SEC *
                    (1L shl exponent)
        )
    }

    // =====================================================================
    // RAW SENSOR STREAM
    // =====================================================================

    /**
     * Compact hand-built JSON for the per-sample raw stream.
     *
     * The method is intentionally allocation-light relative to Gson.
     *
     * The raw acquisition values are passed through without zero-padding.
     * Missing channels and non-finite values are emitted as JSON null so they
     * cannot be mistaken for real zero measurements in downstream records.
     */
    fun streamSensorData(
        timestampMs: Long,
        sensorTimestampNs: Long,
        machineId: String,
        point: String,
        sourceKind: String,
        sourceId: String,
        channelId: String,
        sensorType: Int?,
        sensorName: String,
        unit: String,
        vals: FloatArray
    ) {

        val payload =
            StringBuilder(320)
                .append("{\"kind\":\"raw\"")
                .append(",\"schemaVersion\":2")
                // Keep nanoseconds as a decimal string: JavaScript Number cannot
                // exactly represent every signed 64-bit timestamp.
                .append(",\"sensorTimestampNs\":\"")
                .append(sensorTimestampNs)
                .append("\"")
                .append(",\"ts\":")
                .append(timestampMs)
                .append(",\"machineId\":\"")
                .append(escapeJsonString(machineId))
                .append("\"")
                .append(",\"point\":\"")
                .append(escapeJsonString(point))
                .append("\"")
                .append(",\"sourceKind\":\"")
                .append(escapeJsonString(sourceKind))
                .append("\"")
                .append(",\"sourceId\":\"")
                .append(escapeJsonString(sourceId))
                .append("\"")
                .append(",\"channelId\":\"")
                .append(escapeJsonString(channelId))
                .append("\"")
                .append(",\"sensorType\":")
                .append(sensorType?.toString() ?: "null")
                // Keep the legacy `id` alias for existing dashboard clients.
                // It is never treated as a unique sensor instance identifier.
                .append(",\"id\":")
                .append(sensorType ?: -1)
                .append(",\"sensor\":\"")
                .append(escapeJsonString(sensorName))
                .append("\"")
                .append(",\"unit\":\"")
                .append(escapeJsonString(unit))
                .append("\"")

        appendFloatField(
            payload,
            "v0",
            vals.getOrNull(0)
        )

        appendFloatField(
            payload,
            "v1",
            vals.getOrNull(1)
        )

        appendFloatField(
            payload,
            "v2",
            vals.getOrNull(2)
        )

        appendFloatField(
            payload,
            "v3",
            vals.getOrNull(3)
        )

        appendFloatField(
            payload,
            "v4",
            vals.getOrNull(4)
        )

        appendFloatField(
            payload,
            "v5",
            vals.getOrNull(5)
        )

        payload.append('}')

        sendOrBufferRaw(
            payload.toString()
        )
    }

    private fun appendFloatField(
        builder: StringBuilder,
        key: String,
        value: Float?
    ) {

        builder
            .append(",\"")
            .append(key)
            .append("\":")

        if (
            value != null &&
            value.isFinite()
        ) {

            builder.append(
                value
            )

        } else {

            /*
             * JSON has no NaN or Infinity numeric literals.
             * null is valid JSON and preserves the fact that the value was
             * unavailable/non-finite rather than silently changing it to 0.
             */
            builder.append(
                "null"
            )
        }
    }

    private fun sendOrBufferRaw(
        payload: String
    ) {

        if (
            trySendNow(
                payload
            )
        ) {
            return
        }

        if (
            !isStreamingAllowed
        ) {
            return
        }

        enqueueBounded(
            queue = rawPayloadBuffer,
            payload = payload,
            maxSize = MAX_RAW_BUFFER,
            droppedCounter = droppedRawPackets,
            label = "raw"
        )
    }

    // =====================================================================
    // ANALYSIS STREAM
    // =====================================================================

    /**
     * Sends one computed AnalysisSnapshot per completed FFT window.
     *
     * Analysis traffic has its own bounded queue so it cannot be displaced
     * entirely by high-frequency raw traffic during a network outage.
     */
    fun streamAnalysisSnapshot(
        snapshot: AnalysisSnapshot
    ) {

        val payload =
            try {
                gson.toJson(
                    snapshot
                )
            } catch (e: Exception) {

                logger(
                    "[WARN] Analysis JSON serialization failed: " +
                            "${e.message}"
                )

                return
            }

        if (
            trySendNow(
                payload
            )
        ) {
            return
        }

        if (
            !isStreamingAllowed
        ) {
            return
        }

        enqueueBounded(
            queue = analysisPayloadBuffer,
            payload = payload,
            maxSize = MAX_ANALYSIS_BUFFER,
            droppedCounter = droppedAnalysisPackets,
            label = "analysis"
        )
    }

    // =====================================================================
    // SEND PATH
    // =====================================================================

    /**
     * Attempts an immediate non-blocking WebSocket send.
     *
     * OkHttp's WebSocket.send() is asynchronous. We only use its boolean
     * result here to decide whether the payload needs to enter our bounded
     * outage buffer.
     */
    private fun trySendNow(
        payload: String
    ): Boolean {

        if (
            !isStreamingAllowed
        ) {
            return false
        }

        if (
            !isConnected
        ) {
            return false
        }

        val socket =
            webSocket
                ?: return false

        return try {
            socket.send(
                payload
            )
        } catch (e: Exception) {

            logger(
                "[WARN] WebSocket send failed: " +
                        "${e.message}"
            )

            false
        }
    }

    /**
     * Flush buffered traffic after a successful connection.
     *
     * Analysis messages are intentionally sent first.
     *
     * If a send fails, the unsent message is returned to its queue and the
     * flush stops. onFailure/onClosed will handle reconnection.
     */
    private fun flushBuffer(
        socket: WebSocket
    ) {

        if (
            !isCurrentSocketReference(
                socket
            )
        ) {
            return
        }

        /*
         * --------------------------------------------------------------
         * ANALYSIS FIRST
         * --------------------------------------------------------------
         */
        while (
            true
        ) {

            val payload =
                analysisPayloadBuffer.poll()
                    ?: break

            val sent =
                try {
                    socket.send(
                        payload
                    )
                } catch (_: Exception) {
                    false
                }

            if (!sent) {

                analysisPayloadBuffer.offer(
                    payload
                )

                return
            }
        }

        /*
         * --------------------------------------------------------------
         * RAW SECOND
         * --------------------------------------------------------------
         */
        while (
            true
        ) {

            val payload =
                rawPayloadBuffer.poll()
                    ?: break

            val sent =
                try {
                    socket.send(
                        payload
                    )
                } catch (_: Exception) {
                    false
                }

            if (!sent) {

                rawPayloadBuffer.offer(
                    payload
                )

                return
            }
        }

        val rawDropped =
            droppedRawPackets.getAndSet(
                0L
            )

        val analysisDropped =
            droppedAnalysisPackets.getAndSet(
                0L
            )

        if (
            rawDropped > 0L ||
            analysisDropped > 0L
        ) {

            logger(
                "[NETWORK] Outage buffer recovered. " +
                        "Dropped raw=$rawDropped, " +
                        "analysis=$analysisDropped " +
                        "packet(s) while disconnected."
            )
        }

        logger(
            "[NETWORK] Buffered telemetry flushed."
        )
    }

    // =====================================================================
    // QUEUE MANAGEMENT
    // =====================================================================

    /**
     * Bounded newest-history policy:
     *
     * When full, remove the oldest payload before adding the newest.
     *
     * This is more useful for a live condition-monitoring stream than keeping
     * stale data while discarding the newest measurements.
     */
    private fun enqueueBounded(
        queue: ConcurrentLinkedQueue<String>,
        payload: String,
        maxSize: Int,
        droppedCounter: AtomicLong,
        label: String
    ) {

        while (
            queue.size >= maxSize
        ) {

            if (
                queue.poll() == null
            ) {
                break
            }

            // Oldest entry was discarded to preserve the newest measurement
            // history while disconnected.
            droppedCounter.incrementAndGet()
        }

        queue.offer(
            payload
        )

        val dropped =
            droppedCounter.get()

        if (
            dropped > 0L &&
            dropped % 100L == 0L
        ) {

            logger(
                "[WARN] Network unavailable: " +
                        "dropped $dropped $label packets " +
                        "because the outage buffer is full."
            )
        }
    }

    private fun clearBuffers() {

        rawPayloadBuffer.clear()
        analysisPayloadBuffer.clear()

        droppedRawPackets.set(
            0L
        )

        droppedAnalysisPackets.set(
            0L
        )
    }

    // =====================================================================
    // SOCKET IDENTITY / URL VALIDATION
    // =====================================================================

    private fun isCurrentGeneration(
        generation: Long
    ): Boolean {

        synchronized(stateLock) {

            return isStreamingAllowed &&
                    generation ==
                    connectionGeneration.get()
        }
    }

    private fun isCurrentSocket(
        socket: WebSocket,
        generation: Long
    ): Boolean {

        synchronized(stateLock) {

            return isStreamingAllowed &&
                    generation ==
                    connectionGeneration.get() &&
                    webSocket === socket
        }
    }

    private fun isCurrentSocketReference(
        socket: WebSocket
    ): Boolean {

        synchronized(stateLock) {

            return isStreamingAllowed &&
                    webSocket === socket
        }
    }

    private fun isValidWebSocketUrl(
        url: String
    ): Boolean {

        if (
            url.isBlank()
        ) {
            return false
        }

        return url.startsWith(
            "ws://",
            ignoreCase = true
        ) || url.startsWith(
            "wss://",
            ignoreCase = true
        )
    }

    /**
     * Minimal JSON string escaping for sensor names.
     * Gson is deliberately not used here because this function sits on the
     * high-frequency raw stream path.
     */
    private fun escapeJsonString(
        value: String
    ): String {

        val builder =
            StringBuilder(
                value.length + 16
            )

        for (
        character in value
        ) {

            when (character) {

                '\\' ->
                    builder.append("\\\\")

                '"' ->
                    builder.append("\\\"")

                '\b' ->
                    builder.append("\\b")

                '\u000C' ->
                    builder.append("\\f")

                '\n' ->
                    builder.append("\\n")

                '\r' ->
                    builder.append("\\r")

                '\t' ->
                    builder.append("\\t")

                else -> {

                    if (
                        character.code < 0x20
                    ) {

                        builder.append(
                            String.format(
                                java.util.Locale.US,
                                "\\u%04x",
                                character.code
                            )
                        )

                    } else {

                        builder.append(
                            character
                        )
                    }
                }
            }
        }

        return builder.toString()
    }

    // =====================================================================
    // TERMINATION
    // =====================================================================

    /**
     * Explicitly terminates streaming.
     *
     * Buffered payloads are cleared because they belong to the deployment
     * that has just ended. Keeping them would risk attaching old measurements
     * to the next deployment if the operator reconnects later.
     */
    fun terminate() {

        val socket: WebSocket?

        synchronized(stateLock) {

            isStreamingAllowed =
                false

            reconnectFuture?.cancel(
                false
            )

            reconnectFuture =
                null

            reconnectAttempt =
                0

            connectionGeneration.incrementAndGet()

            socket =
                webSocket

            webSocket =
                null

            isConnected =
                false
        }

        clearBuffers()

        try {
            socket?.close(
                NORMAL_CLOSE_CODE,
                "Acquisition Halted by Engineer"
            )
        } catch (_: Exception) {
            // Best-effort close.
        }

        logger(
            "[NETWORK] Telemetry uplink terminated."
        )
    }
}
