package com.research.sensormax

import com.google.gson.GsonBuilder
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.ArrayDeque
import java.util.Locale

/**
 * Explicit persistence/state boundaries for measurement data.
 * Repositories intentionally own storage mechanics; SensorEngine owns
 * acquisition and engineering calculations.
 */
interface RawRecordingRepository {
    val file: File?
    val lastErrorMessage: String?
    fun start(file: File, header: String)
    fun append(line: String)
    fun flush()
    fun close()
}

class CsvRawRecordingRepository : RawRecordingRepository {
    private val lock = Any()
    private var writer: BufferedWriter? = null
    override var file: File? = null
        private set

    @Volatile
    override var lastErrorMessage: String? = null
        private set

    override fun start(file: File, header: String) = synchronized(lock) {
        val parent = file.parentFile
        require(parent == null || parent.isDirectory || parent.mkdirs()) {
            "Unable to create parent directory for ${file.absolutePath}"
        }
        closeLocked()
        lastErrorMessage = null
        this.file = file
        writer = try {
            BufferedWriter(FileWriter(file, false)).apply {
                write(header)
            }
        } catch (error: Exception) {
            lastErrorMessage = error.message ?: error::class.java.simpleName
            throw error
        }
    }

    override fun append(line: String) {
        synchronized(lock) {
            runCatching { writer?.write(line) }
                .onFailure { lastErrorMessage = it.message ?: it::class.java.simpleName }
                .getOrThrow()
        }
    }

    override fun flush() {
        synchronized(lock) {
            writer?.flush()
        }
    }

    override fun close() = synchronized(lock) {
        closeLocked()
    }

    private fun closeLocked() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }
}

interface AnalysisRepository {
    val file: File?
    val lastErrorMessage: String?
    fun start(file: File, header: String)
    fun append(line: String)
    fun flush()
    fun close()
}

class CsvAnalysisRepository : AnalysisRepository {
    private val lock = Any()
    private var writer: BufferedWriter? = null
    override var file: File? = null
        private set

    @Volatile
    override var lastErrorMessage: String? = null
        private set

    override fun start(file: File, header: String) = synchronized(lock) {
        val parent = file.parentFile
        require(parent == null || parent.isDirectory || parent.mkdirs()) {
            "Unable to create parent directory for ${file.absolutePath}"
        }
        closeLocked()
        lastErrorMessage = null
        this.file = file
        writer = try {
            BufferedWriter(FileWriter(file, false)).apply {
                write(header)
            }
        } catch (error: Exception) {
            lastErrorMessage = error.message ?: error::class.java.simpleName
            throw error
        }
    }

    override fun append(line: String) {
        synchronized(lock) {
            runCatching { writer?.write(line) }
                .onFailure { lastErrorMessage = it.message ?: it::class.java.simpleName }
                .getOrThrow()
        }
    }

    override fun flush() {
        synchronized(lock) {
            writer?.flush()
        }
    }

    override fun close() = synchronized(lock) {
        closeLocked()
    }

    private fun closeLocked() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }
}

class SensorTelemetryRepository(private val capacityPerSensor: Int = 300) {
    init { require(capacityPerSensor > 0) { "capacityPerSensor must be > 0" } }

    private val buffers = linkedMapOf<String, ArrayDeque<SensorTelemetrySample>>()

    fun append(sample: SensorTelemetrySample) = synchronized(buffers) {
        val key = keyOf(sample)
        val buffer = buffers.getOrPut(key) { ArrayDeque(capacityPerSensor) }
        buffer.addLast(sample.copySnapshot())
        while (buffer.size > capacityPerSensor) buffer.removeFirst()
    }

    fun latestSnapshot(): List<SensorTelemetrySample> = synchronized(buffers) {
        buffers.values.mapNotNull { it.lastOrNull()?.copySnapshot() }
            .sortedWith(compareBy({ SensorTelemetryFormatter.label(it.type) }, { it.name }))
    }

    fun latestSnapshotSince(startMs: Long): List<SensorTelemetrySample> = synchronized(buffers) {
        buffers.values.mapNotNull { buffer ->
            buffer.lastOrNull { it.timestampMs >= startMs }?.copySnapshot()
        }.sortedWith(compareBy({ SensorTelemetryFormatter.label(it.type) }, { it.name }))
    }

    fun historySnapshot(): Map<String, List<SensorTelemetrySample>> = synchronized(buffers) {
        buffers.mapValues { (_, values) -> values.map { it.copySnapshot() } }
    }

    fun clear() = synchronized(buffers) { buffers.clear() }

    private fun keyOf(sample: SensorTelemetrySample): String =
        if (sample.channelId.isNotBlank()) {
            "${sample.sourceKind}|${sample.sourceId}|${sample.channelId}"
        } else {
            "${sample.type}|${sample.vendor}|${sample.name}"
        }
}

class SpectrumHistoryRepository(
    private val capacity: Int = 600
) {
    init { require(capacity > 0) { "capacity must be > 0" } }

    private val frames = ArrayDeque<AnalysisUpdate>(capacity)

    fun clear() = synchronized(frames) { frames.clear() }

    fun append(update: AnalysisUpdate) = synchronized(frames) {
        frames.addLast(update)
        while (frames.size > capacity) frames.removeFirst()
    }

    fun snapshot(): List<AnalysisUpdate> = synchronized(frames) { frames.toList() }
}

class TrendHistoryRepository(
    private val capacity: Int = 600
) {
    init { require(capacity > 0) { "capacity must be > 0" } }

    private val values = ArrayDeque<Float>(capacity)

    fun clear() = synchronized(values) { values.clear() }

    fun append(value: Float) = synchronized(values) {
        values.addLast(value)
        while (values.size > capacity) values.removeFirst()
    }

    fun snapshot(): FloatArray = synchronized(values) { values.toList().toFloatArray() }
}

class DiagnosticEventRepository(
    private val memoryCapacity: Int = 2000
) {
    init { require(memoryCapacity > 0) { "memoryCapacity must be > 0" } }

    private val lock = Any()
    private val memory = ArrayDeque<String>(memoryCapacity)
    private var writer: BufferedWriter? = null
    var file: File? = null
        private set

    fun start(file: File) = synchronized(lock) {
        closeLocked()
        this.file = file
        writer = BufferedWriter(FileWriter(file, false))
    }

    fun append(message: String) = synchronized(lock) {
        val line = message.trimEnd()
        memory.addLast(line)
        while (memory.size > memoryCapacity) memory.removeFirst()
        runCatching {
            writer?.let { activeWriter ->
                val ts = System.currentTimeMillis()
                activeWriter.write(String.format(Locale.US, "%d,%s\n", ts, line.replace("\n", "\\n")))
                if (memory.size % 20 == 0) activeWriter.flush()
            }
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { memory.toList() }

    fun flush() = synchronized(lock) { runCatching { writer?.flush() } }

    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }
}

class ConditionSummaryRepository {
    fun write(baseDir: File, fileName: String, summary: SessionConditionSummary): File {
        if (!baseDir.exists()) baseDir.mkdirs()
        val file = File(baseDir, fileName)
        val json = GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create()
            .toJson(summary)
        FileWriter(file).use { it.write(json) }
        return file
    }
}
