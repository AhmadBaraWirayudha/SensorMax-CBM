package com.research.sensormax

import com.google.gson.Gson
import okhttp3.*
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Low-frequency companion packet to the raw per-sample stream: one of these
 * goes out per completed FFT window (every [VibrationAnalyzer]'s windowSize
 * samples), not per sample, so building it with Gson's reflection-based
 * serializer is fine -- the raw high-frequency path below stays on the
 * hand-built StringBuilder for that reason.
 */
data class AnalysisSnapshot(
    val kind: String = "analysis",
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

class StreamClient(private val logger: (String) -> Unit) {
    private var webSocket: WebSocket? = null
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val gson = Gson()

    private val payloadBuffer = ConcurrentLinkedQueue<String>()
    private var isConnected = false
    private var isStreamingAllowed = false
    private var currentUrl = ""
    private var reconnectTimer: Timer? = null

    fun connectToInternetEndpoint(serverUrl: String) {
        currentUrl = serverUrl
        isStreamingAllowed = true
        establishConnection()
    }

    private fun establishConnection() {
        if (!isStreamingAllowed) return

        val request = Request.Builder().url(currentUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                logger("[NETWORK] Telemetry Uplink Established: $currentUrl")
                flushBuffer(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                logger("[WARN] Uplink severed. Auto-reconnecting in 3s...")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                if (isStreamingAllowed) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectTimer?.cancel()
        reconnectTimer = Timer()
        reconnectTimer?.schedule(object : TimerTask() {
            override fun run() { establishConnection() }
        }, 3000)
    }

    /** Unchanged from the original: compact hand-built JSON for the per-sample raw stream. */
    fun streamSensorData(timestampMs: Long, sensorType: Int, sensorName: String, vals: FloatArray) {
        val payload = StringBuilder()
            .append("{\"ts\":").append(timestampMs)
            .append(",\"id\":").append(sensorType)
            .append(",\"v0\":").append(vals[0])
            .append(",\"v1\":").append(vals[1])
            .append(",\"v2\":").append(vals[2])
            .append(",\"v3\":").append(vals[3])
            .append(",\"v4\":").append(vals[4])
            .append(",\"v5\":").append(vals[5])
            .append("}").toString()

        if (isConnected) {
            webSocket?.send(payload)
        } else {
            // Buffer up to 1500 multi-sensor packets during cellular drops
            if (payloadBuffer.size < 1500) payloadBuffer.offer(payload)
        }
    }

    /**
     * NEW: pushes one computed [AnalysisSnapshot] (RMS/peak-frequency/ISO
     * zone) per completed FFT window. Shares the same socket, buffer and
     * reconnect logic as the raw stream; a receiver tells the two apart by
     * the "kind" / "id" field each payload carries.
     */
    fun streamAnalysisSnapshot(snapshot: AnalysisSnapshot) {
        val payload = gson.toJson(snapshot)
        if (isConnected) {
            webSocket?.send(payload)
        } else {
            if (payloadBuffer.size < 1500) payloadBuffer.offer(payload)
        }
    }

    private fun flushBuffer(socket: WebSocket) {
        while (payloadBuffer.isNotEmpty()) {
            payloadBuffer.poll()?.let { socket.send(it) }
        }
    }

    fun terminate() {
        isStreamingAllowed = false
        reconnectTimer?.cancel()
        webSocket?.close(1000, "Acquisition Halted by Engineer")
        webSocket = null
    }
}