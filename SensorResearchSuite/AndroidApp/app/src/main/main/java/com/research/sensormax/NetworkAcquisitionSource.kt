package com.research.sensormax

import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * SensorMax-native inbound network profile.
 *
 * Protocol: UTF-8 NDJSON over TCP. One JSON object per line, maximum 16 KiB.
 * The transport is intentionally separate from StreamClient, which remains an
 * outbound WebSocket transport.
 */
data class NetworkAcquisitionProfile(
    val bindHost: String = "127.0.0.1",
    val port: Int = 8765,
    val maxConnections: Int = 4,
    val maxFrameBytes: Int = 16 * 1024,
    val clientIdleTimeoutMs: Int = 15_000
) {
    init {
        require(bindHost.isNotBlank()) { "bindHost must not be blank" }
        require(port in 1024..65_535) { "port must be in 1024..65535" }
        require(maxConnections in 1..8) { "maxConnections must be in 1..8" }
        require(maxFrameBytes in 1024..64 * 1024) { "maxFrameBytes out of range" }
        require(clientIdleTimeoutMs in 1_000..120_000) { "clientIdleTimeoutMs out of range" }
    }
}

data class NetworkSensorFrame(
    val version: Int,
    val sourceId: String,
    val channelId: String,
    val name: String,
    val vendor: String,
    val values: List<Float>,
    val unit: String,
    val sequence: Long?,
    val senderTimestampMs: Long?
)

/** Pure parser for deterministic unit/contract tests. */
object NetworkSensorFrameParser {
    const val SUPPORTED_VERSION = 1
    const val MAX_VALUES = 32

    fun parse(line: String, maxFrameBytes: Int = 16 * 1024): NetworkSensorFrame {
        require(line.toByteArray(Charsets.UTF_8).size <= maxFrameBytes) {
            "frame exceeds $maxFrameBytes bytes"
        }

        val parser = StrictJsonObjectParser(line)
        val fields = parser.parse()

        val versionLong = fields["version"]?.asLongExact("version")
            ?: throw IllegalArgumentException("version must be an integer")
        require(versionLong == SUPPORTED_VERSION.toLong()) {
            "unsupported protocol version: $versionLong"
        }
        val version = versionLong.toInt()

        val sourceId = fields["source_id"]?.asString("source_id")
            ?.trim()
            ?: throw IllegalArgumentException("source_id must be a string")
        require(sourceId.isNotBlank()) { "source_id must not be blank" }
        require(sourceId.length <= 128) { "source_id exceeds 128 characters" }

        val channelId = fields["channel_id"]?.asString("channel_id")
            ?.trim()
            ?: throw IllegalArgumentException("channel_id must be a string")
        require(channelId.isNotBlank()) { "channel_id must not be blank" }
        require(channelId.length <= 128) { "channel_id exceeds 128 characters" }

        val name = fields["name"]?.asString("name")?.trim()?.also {
            require(it.length <= 128) { "name exceeds 128 characters" }
        }?.ifBlank { channelId } ?: channelId

        val vendor = fields["vendor"]?.asString("vendor")?.trim()?.also {
            require(it.length <= 128) { "vendor exceeds 128 characters" }
        } ?: ""

        val unit = fields["unit"]?.asString("unit")?.trim()?.also {
            require(it.length <= 64) { "unit exceeds 64 characters" }
        } ?: ""

        val valuesNode = fields["values"] ?: throw IllegalArgumentException("values must be a JSON array")
        require(valuesNode is JsonValue.Array) { "values must be a JSON array" }
        require(valuesNode.values.size in 1..MAX_VALUES) {
            "values must contain 1..$MAX_VALUES elements"
        }
        val values = valuesNode.values.mapIndexed { index, value ->
            val number = value.asDoubleExact("values[$index]")
            require(number.isFinite()) { "values[$index] must be finite" }
            number.toFloat()
        }

        val sequence = fields["sequence"]?.asLongExact("sequence")
        val senderTimestampMs = fields["timestamp_ms"]?.asLongExact("timestamp_ms")

        return NetworkSensorFrame(
            version = version,
            sourceId = sourceId,
            channelId = channelId,
            name = name,
            vendor = vendor,
            values = values,
            unit = unit,
            sequence = sequence,
            senderTimestampMs = senderTimestampMs
        )
    }

    private sealed interface JsonValue {
        data class StringValue(val value: String) : JsonValue
        data class NumberValue(val raw: String) : JsonValue
        data class Array(val values: List<JsonValue>) : JsonValue
        data object NullValue : JsonValue
        data object BooleanValue : JsonValue

        fun asString(field: String): String =
            (this as? StringValue)?.value
                ?: throw IllegalArgumentException("$field must be a string")

        fun asDoubleExact(field: String): Double {
            val raw = (this as? NumberValue)?.raw
                ?: throw IllegalArgumentException("$field must be numeric")
            return raw.toDoubleOrNull()
                ?: throw IllegalArgumentException("$field must be numeric")
        }

        fun asLongExact(field: String): Long {
            val raw = (this as? NumberValue)?.raw
                ?: throw IllegalArgumentException("$field must be an integer")
            val parsed = raw.toLongOrNull()
            require(parsed != null && !raw.contains('.') && !raw.contains('e', true)) {
                "$field must be an integer"
            }
            return parsed
        }
    }

    private class StrictJsonObjectParser(private val input: String) {
        private var index = 0

        fun parse(): Map<String, JsonValue> {
            skipWhitespace()
            require(peek() == '{') { "frame must be a JSON object" }
            index++
            val fields = linkedMapOf<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                ensureEnd()
                return fields
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                require(peek() == ':') { "expected ':' at byte $index" }
                index++
                require(fields.put(key, readValue()) == null) {
                    "duplicate JSON field: $key"
                }
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        ensureEnd()
                        return fields
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at byte $index")
                }
            }
        }

        private fun readValue(): JsonValue {
            skipWhitespace()
            return when (peek()) {
                '"' -> JsonValue.StringValue(readString())
                '[' -> readArray()
                'n' -> {
                    expectLiteral("null")
                    JsonValue.NullValue
                }
                't' -> {
                    expectLiteral("true")
                    JsonValue.BooleanValue
                }
                'f' -> {
                    expectLiteral("false")
                    JsonValue.BooleanValue
                }
                '-', in '0'..'9' -> JsonValue.NumberValue(readNumber())
                else -> throw IllegalArgumentException("unsupported JSON value at byte $index")
            }
        }

        private fun readArray(): JsonValue.Array {
            index++
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (peek() == ']') {
                index++
                return JsonValue.Array(values)
            }
            while (true) {
                values += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.Array(values)
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at byte $index")
                }
            }
        }

        private fun readNumber(): String {
            val start = index
            if (peek() == '-') index++
            require(peek()?.isDigit() == true) { "invalid number at byte $index" }
            if (peek() == '0') {
                index++
            } else {
                while (peek()?.isDigit() == true) index++
            }
            if (peek() == '.') {
                index++
                require(peek()?.isDigit() == true) { "invalid fraction at byte $index" }
                while (peek()?.isDigit() == true) index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                require(peek()?.isDigit() == true) { "invalid exponent at byte $index" }
                while (peek()?.isDigit() == true) index++
            }
            return input.substring(start, index)
        }

        private fun readString(): String {
            require(peek() == '"') { "expected string at byte $index" }
            index++
            val out = StringBuilder()
            while (true) {
                val ch = peek() ?: throw IllegalArgumentException("unterminated JSON string")
                index++
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
                    else -> {
                        require(ch.code >= 0x20) { "control character in JSON string" }
                        out.append(ch)
                    }
                }
            }
        }

        private fun readEscape(): Char {
            val ch = peek() ?: throw IllegalArgumentException("unterminated JSON escape")
            index++
            return when (ch) {
                '"', '\\', '/' -> ch
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(index + 4 <= input.length) { "invalid unicode escape" }
                    val code = input.substring(index, index + 4).toIntOrNull(16)
                        ?: throw IllegalArgumentException("invalid unicode escape")
                    index += 4
                    code.toChar()
                }
                else -> throw IllegalArgumentException("unsupported JSON escape: \\$ch")
            }
        }

        private fun expectLiteral(literal: String) {
            require(input.regionMatches(index, literal, 0, literal.length)) {
                "invalid JSON literal at byte $index"
            }
            index += literal.length
        }

        private fun skipWhitespace() {
            while (peek()?.let { it == ' ' || it == '\t' || it == '\r' || it == '\n' } == true) {
                index++
            }
        }

        private fun peek(): Char? = input.getOrNull(index)

        private fun ensureEnd() {
            skipWhitespace()
            require(index == input.length) { "trailing data after JSON object" }
        }
    }
}

/**
 * SensorMax-native TCP/NDJSON inbound acquisition provider.
 *
 * Remote timestamps are treated as metadata only. SensorMax assigns a local
 * monotonic timestamp at frame receipt so the existing acquisition invariant
 * is not polluted by an unsynchronised remote clock.
 */
class NetworkAcquisitionSource(
    private val profile: NetworkAcquisitionProfile
) : AcquisitionSource {

    override val descriptor = AcquisitionSourceDescriptor(
        id = "network-tcp-ndjson",
        kind = AcquisitionSourceKind.NETWORK,
        displayName = "SensorMax Network Sensor (TCP/NDJSON)",
        vendor = "SensorMax",
        channels = emptyList()
    )

    @Volatile
    override var isRunning: Boolean = false
        private set

    private val stopRequested = AtomicBoolean(false)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val lastAcceptedTimestampNs = ConcurrentHashMap<String, Long>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var clientExecutor: ExecutorService? = null
    private var listener: AcquisitionSourceListener? = null
    private var requestedIntervalNs: Long = 20_000_000L

    override fun start(
        config: AcquisitionSourceConfig,
        listener: AcquisitionSourceListener
    ): Boolean {
        if (isRunning) return false

        this.listener = listener
        requestedIntervalNs = max(1L, config.requestedIntervalMs) * 1_000_000L
        stopRequested.set(false)
        lastAcceptedTimestampNs.clear()
        listener.onStateChanged(descriptor.id, AcquisitionSourceState.STARTING)

        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(profile.bindHost, profile.port))
            }
        }.getOrElse { error ->
            listener.onError(
                descriptor.id,
                "Cannot bind ${profile.bindHost}:${profile.port}: " +
                        (error.message ?: error::class.java.simpleName)
            )
            listener.onStateChanged(
                descriptor.id,
                AcquisitionSourceState.ERROR,
                "TCP listener bind failed"
            )
            this.listener = null
            return false
        }

        serverSocket = server
        clientExecutor = Executors.newFixedThreadPool(profile.maxConnections) { runnable ->
            Thread(runnable, "SensorMax-NetworkClient").apply { isDaemon = true }
        }
        isRunning = true
        listener.onStateChanged(
            descriptor.id,
            AcquisitionSourceState.READY,
            "Listening on ${profile.bindHost}:${profile.port}; NDJSON v$SUPPORTED_PROTOCOL_VERSION"
        )

        acceptThread = Thread({ acceptLoop(server) }, "SensorMax-NetworkAccept").apply {
            isDaemon = true
            start()
        }
        return true
    }

    override fun stop() {
        if (!isRunning && serverSocket == null) return

        stopRequested.set(true)
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeSockets.forEach { socket ->
            runCatching { socket.close() }
        }
        activeSockets.clear()
        clientExecutor?.shutdownNow()
        clientExecutor = null
        acceptThread = null
        lastAcceptedTimestampNs.clear()
        listener?.onStateChanged(descriptor.id, AcquisitionSourceState.STOPPED)
        listener = null
        isRunning = false
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!stopRequested.get()) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                if (!stopRequested.get()) {
                    listener?.onStateChanged(
                        descriptor.id,
                        AcquisitionSourceState.ERROR,
                        "TCP accept failed"
                    )
                }
                break
            }

            if (stopRequested.get()) {
                closeQuietly(socket)
                break
            }

            if (activeSockets.size >= profile.maxConnections) {
                closeQuietly(socket)
                listener?.onLog(
                    descriptor.id,
                    "Rejected inbound connection: max ${profile.maxConnections} clients already active."
                )
                continue
            }

            activeSockets += socket
            clientExecutor?.execute {
                handleClient(socket)
            } ?: closeQuietly(socket)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = profile.clientIdleTimeoutMs
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            while (!stopRequested.get()) {
                val line = readUtf8Line(input, profile.maxFrameBytes) ?: break
                if (line.isBlank()) continue

                val frame = try {
                    NetworkSensorFrameParser.parse(line, profile.maxFrameBytes)
                } catch (error: Exception) {
                    listener?.onError(
                        descriptor.id,
                        "Rejected network frame: ${error.message ?: error::class.java.simpleName}"
                    )
                    continue
                }

                emitFrame(frame)
            }
        } catch (error: Exception) {
            if (!stopRequested.get()) {
                listener?.onLog(
                    descriptor.id,
                    "Inbound client closed: ${error.message ?: error::class.java.simpleName}"
                )
            }
        } finally {
            activeSockets.remove(socket)
            closeQuietly(socket)
        }
    }

    private fun emitFrame(frame: NetworkSensorFrame) {
        val nowNs = System.nanoTime()
        val key = "${frame.sourceId}|${frame.channelId}"
        val previous = lastAcceptedTimestampNs[key]
        if (previous != null && nowNs - previous < requestedIntervalNs) {
            return
        }
        lastAcceptedTimestampNs[key] = nowNs

        listener?.onSample(
            AcquisitionSample(
                sourceId = "network:${frame.sourceId}",
                sourceKind = AcquisitionSourceKind.NETWORK,
                channelId = frame.channelId,
                name = frame.name,
                vendor = frame.vendor.ifBlank { "network:${frame.sourceId}" },
                timestampNs = nowNs,
                wallClockTimestampMs = System.currentTimeMillis(),
                values = frame.values.toFloatArray(),
                unit = frame.unit
            )
        )
    }

    private fun readUtf8Line(input: BufferedInputStream, maxBytes: Int): String? {
        val bytes = ByteArray(maxBytes)
        var count = 0
        while (true) {
            val value = input.read()
            if (value == -1) {
                if (count == 0) return null
                break
            }
            if (value == '\n'.code) break
            if (value == '\r'.code) continue
            if (count == maxBytes) {
                throw IllegalArgumentException("network frame exceeds $maxBytes bytes")
            }
            bytes[count++] = value.toByte()
        }
        return bytes.copyOf(count).toString(Charsets.UTF_8)
    }

    private fun closeQuietly(closeable: Closeable) {
        runCatching { closeable.close() }
    }

    companion object {
        const val SUPPORTED_PROTOCOL_VERSION = NetworkSensorFrameParser.SUPPORTED_VERSION
    }
}
