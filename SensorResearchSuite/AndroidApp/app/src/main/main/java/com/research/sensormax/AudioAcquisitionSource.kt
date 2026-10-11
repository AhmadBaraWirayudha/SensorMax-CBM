package com.research.sensormax

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/** SensorMax-owned microphone acquisition provider using Android AudioRecord. */
class AudioAcquisitionSource(
    private val context: Context,
    private val profile: AudioAcquisitionProfile = AudioAcquisitionProfile()
) : AcquisitionSource {

    override val descriptor = AcquisitionSourceDescriptor(
        id = "audio:microphone",
        kind = AcquisitionSourceKind.AUDIO,
        displayName = profile.displayName,
        vendor = "Android AudioRecord",
        channels = listOf("microphone")
    )

    override var isRunning: Boolean = false
        private set

    private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null
    private var sourceListener: AcquisitionSourceListener? = null
    private val running = AtomicBoolean(false)
    private val clockMapper = AcquisitionClockMapper()

    override fun start(
        config: AcquisitionSourceConfig,
        listener: AcquisitionSourceListener
    ): Boolean {
        if (isRunning) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError(descriptor.id, "Missing microphone permission")
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            profile.sampleRateHz,
            profile.channelConfig,
            profile.audioFormat
        )
        if (minBuffer <= 0) {
            listener.onError(descriptor.id, "AudioRecord rejected the requested format")
            return false
        }

        val requestedBuffer = profile.chunkFrames * profile.bytesPerFrame
        val bufferBytes = maxOf(minBuffer, requestedBuffer) * profile.bufferMultiplier
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                profile.sampleRateHz,
                profile.channelConfig,
                profile.audioFormat,
                bufferBytes
            )
        }.getOrElse {
            listener.onError(
                descriptor.id,
                "AudioRecord initialization failed: ${it.message ?: it::class.java.simpleName}"
            )
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            listener.onError(descriptor.id, "AudioRecord is not initialized")
            return false
        }

        this.sourceListener = listener
        listener.onStateChanged(descriptor.id, AcquisitionSourceState.STARTING)
        clockMapper.reset()

        val started = runCatching { record.startRecording() }.isSuccess
        if (!started || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            this.sourceListener = null
            listener.onStateChanged(
                descriptor.id,
                AcquisitionSourceState.ERROR,
                "AudioRecord failed to start"
            )
            return false
        }

        audioRecord = record
        running.set(true)
        isRunning = true
        listener.onStateChanged(descriptor.id, AcquisitionSourceState.READY)

        val thread = Thread({ captureLoop(record) }, "SensorMax-AudioSource")
        workerThread = thread
        thread.start()
        return true
    }

    override fun stop() {
        val wasActive = isRunning || audioRecord != null || workerThread != null
        running.set(false)
        isRunning = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        workerThread?.interrupt()
        workerThread = null
        clockMapper.reset()
        if (wasActive) {
            sourceListener?.onStateChanged(descriptor.id, AcquisitionSourceState.STOPPED)
        }
        sourceListener = null
    }

    private fun captureLoop(record: AudioRecord) {
        val pcm = ShortArray(profile.chunkFrames)
        while (running.get()) {
            val read = runCatching { record.read(pcm, 0, pcm.size) }.getOrElse {
                onError("AudioRecord read failed: ${it.message ?: it::class.java.simpleName}")
                -1
            }
            if (read <= 0) {
                if (running.get()) {
                    onError("AudioRecord read returned $read")
                    stopFromCaptureThread()
                }
                break
            }

            val timestampNs = SystemClock.elapsedRealtimeNanos()
            val wallClockMs = clockMapper.resolve(timestampNs, System.currentTimeMillis())
            val values = AudioPcm16Decoder.normalize(pcm, read)
            sourceListener?.onSample(
                AcquisitionSample(
                    sourceId = descriptor.id,
                    sourceKind = AcquisitionSourceKind.AUDIO,
                    channelId = "microphone",
                    name = "Microphone",
                    vendor = descriptor.vendor,
                    timestampNs = timestampNs,
                    wallClockTimestampMs = wallClockMs,
                    values = values,
                    androidSensorType = null,
                    unit = "normalized amplitude"
                )
            )
        }
    }

    private fun onError(message: String) {
        sourceListener?.onStateChanged(descriptor.id, AcquisitionSourceState.ERROR, message)
        sourceListener?.onError(descriptor.id, message)
    }

    private fun stopFromCaptureThread() {
        running.set(false)
        isRunning = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        clockMapper.reset()
        sourceListener?.onStateChanged(descriptor.id, AcquisitionSourceState.STOPPED)
        sourceListener = null
        workerThread = null
    }
}


data class AudioAcquisitionProfile(
    val displayName: String = "Microphone / Audio",
    val sampleRateHz: Int = 44_100,
    val chunkFrames: Int = 2_048,
    val bufferMultiplier: Int = 2,
    val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    init {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        require(chunkFrames > 0) { "chunkFrames must be > 0" }
        require(bufferMultiplier > 0) { "bufferMultiplier must be > 0" }
        require(channelConfig == AudioFormat.CHANNEL_IN_MONO) {
            "SensorMax currently supports mono microphone input only"
        }
        require(audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
            "SensorMax currently supports PCM 16-bit microphone input only"
        }
    }

    val bytesPerFrame: Int = 2
}

object AudioPcm16Decoder {
    fun normalize(pcm: ShortArray, length: Int): FloatArray {
        require(length in 0..pcm.size) { "length must be within PCM buffer bounds" }
        return FloatArray(length) { index ->
            pcm[index].toFloat() / 32768f
        }
    }
}

object AudioAcquisitionPermissions {
    fun requiredPermissions(): Array<String> =
        arrayOf(Manifest.permission.RECORD_AUDIO)

    fun missingPermissions(context: Context): List<String> =
        requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasRequiredPermissions(context: Context): Boolean =
        missingPermissions(context).isEmpty()
}
