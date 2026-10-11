package com.research.sensormax

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import java.nio.ShortBuffer
import kotlin.math.abs
import java.util.concurrent.Executor

/** Camera2 depth provider using capability-gated DEPTH16 output. */
data class DepthAcquisitionProfile(
    val width: Int = 320,
    val height: Int = 240,
    val maxImages: Int = 2,
    val sampleEveryPixels: Int = 4
) {
    init {
        require(width >= 2) { "width must be >= 2" }
        require(height >= 2) { "height must be >= 2" }
        require(maxImages >= 2) { "maxImages must be >= 2" }
        require(sampleEveryPixels >= 1) { "sampleEveryPixels must be >= 1" }
    }
}

data class DepthPhotometricStats(
    val meanDepthMm: Float,
    val minDepthMm: Int,
    val maxDepthMm: Int,
    val validFraction: Float,
    val meanConfidence: Float,
    val sampleCount: Int,
    val validCount: Int
)

object Depth16Math {
    private const val DEPTH_MASK = 0x1FFF
    private const val CONFIDENCE_MASK = 0x7
    private const val ZERO_CONFIDENCE_CODE = 1

    data class DecodedSample(
        val depthMm: Int,
        val confidence: Float,
        val confidenceCode: Int
    )

    fun decodeSample(sample: Int): DecodedSample {
        val unsigned = sample and 0xFFFF
        val depthMm = unsigned and DEPTH_MASK
        val confidenceCode = (unsigned ushr 13) and CONFIDENCE_MASK
        val confidence = if (confidenceCode == 0) {
            1f
        } else {
            (confidenceCode - 1).toFloat() / 7f
        }
        return DecodedSample(depthMm, confidence.coerceIn(0f, 1f), confidenceCode)
    }

    fun fromSamples(samples: IntArray): DepthPhotometricStats {
        if (samples.isEmpty()) {
            return DepthPhotometricStats(
                meanDepthMm = 0f,
                minDepthMm = 0,
                maxDepthMm = 0,
                validFraction = 0f,
                meanConfidence = 0f,
                sampleCount = 0,
                validCount = 0
            )
        }

        var sumDepth = 0L
        var sumConfidence = 0f
        var validCount = 0
        var minDepth = Int.MAX_VALUE
        var maxDepth = Int.MIN_VALUE

        samples.forEach { raw ->
            val decoded = decodeSample(raw)
            val usable = decoded.depthMm > 0 && decoded.confidenceCode != ZERO_CONFIDENCE_CODE
            if (usable) {
                validCount++
                sumDepth += decoded.depthMm.toLong()
                sumConfidence += decoded.confidence
                minDepth = minOf(minDepth, decoded.depthMm)
                maxDepth = maxOf(maxDepth, decoded.depthMm)
            }
        }

        if (validCount == 0) {
            return DepthPhotometricStats(
                meanDepthMm = 0f,
                minDepthMm = 0,
                maxDepthMm = 0,
                validFraction = 0f,
                meanConfidence = 0f,
                sampleCount = samples.size,
                validCount = 0
            )
        }

        return DepthPhotometricStats(
            meanDepthMm = sumDepth.toFloat() / validCount.toFloat(),
            minDepthMm = minDepth,
            maxDepthMm = maxDepth,
            validFraction = validCount.toFloat() / samples.size.toFloat(),
            meanConfidence = sumConfidence / validCount.toFloat(),
            sampleCount = samples.size,
            validCount = validCount
        )
    }

    fun fromImage(
        image: Image,
        sampleEveryPixels: Int
    ): DepthPhotometricStats {
        val plane = image.planes.firstOrNull()
            ?: return fromSamples(IntArray(0))

        val shortBuffer: ShortBuffer = plane.buffer.duplicate().asShortBuffer()
        val rowStrideShorts = plane.rowStride / 2
        val pixelStrideShorts = maxOf(1, plane.pixelStride / 2)
        val width = image.width
        val height = image.height
        val step = sampleEveryPixels.coerceAtLeast(1)
        val sampled = ArrayList<Int>((width / step + 1) * (height / step + 1))

        for (y in 0 until height step step) {
            val rowBase = y * rowStrideShorts
            for (x in 0 until width step step) {
                val index = rowBase + x * pixelStrideShorts
                if (index >= 0 && index < shortBuffer.limit()) {
                    sampled += shortBuffer.get(index).toInt() and 0xFFFF
                }
            }
        }

        return fromSamples(sampled.toIntArray())
    }
}

object DepthAcquisitionPermissions {
    fun requiredPermission(): String = Manifest.permission.CAMERA

    fun hasRequiredPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            requiredPermission()
        ) == PackageManager.PERMISSION_GRANTED
}

class DepthAcquisitionSource(
    context: Context,
    private val profile: DepthAcquisitionProfile = DepthAcquisitionProfile()
) : AcquisitionSource {

    private val appContext = context.applicationContext
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sourceId = "depth-tof"

    override val descriptor = AcquisitionSourceDescriptor(
        id = sourceId,
        kind = AcquisitionSourceKind.DEPTH,
        displayName = "Depth / ToF",
        vendor = "Android Camera2",
        channels = listOf(
            "mean_depth_mm",
            "min_depth_mm",
            "max_depth_mm",
            "valid_fraction",
            "mean_confidence",
            "frame_rate_hz"
        )
    )

    override var isRunning: Boolean = false
        private set

    private var sourceListener: AcquisitionSourceListener? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private val clockMapper = AcquisitionClockMapper()
    private var lastFrameTimestampNs = 0L

    override fun start(
        config: AcquisitionSourceConfig,
        listener: AcquisitionSourceListener
    ): Boolean {
        if (isRunning) return false

        if (!DepthAcquisitionPermissions.hasRequiredPermission(appContext)) {
            listener.onError(sourceId, "Camera permission is not granted for depth acquisition")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "CAMERA permission missing")
            return false
        }

        listener.onStateChanged(sourceId, AcquisitionSourceState.STARTING)
        sourceListener = listener

        val cameraId = chooseDepthCameraId()
        if (cameraId == null) {
            listener.onError(sourceId, "No Camera2 device advertises DEPTH_OUTPUT with DEPTH16 support")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Depth capability unavailable")
            sourceListener = null
            return false
        }

        val handlerThread = HandlerThread("SensorMax-DepthSource")
        handlerThread.start()
        val handler = handlerThread.looper?.let(::Handler)
        if (handler == null) {
            handlerThread.quitSafely()
            sourceListener = null
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Depth HandlerThread initialization failed")
            return false
        }

        workerThread = handlerThread
        workerHandler = handler
        lastFrameTimestampNs = 0L

        val resolution = chooseDepthSize(cameraId)
        if (resolution == null) {
            cleanupDepthResources()
            listener.onError(sourceId, "No supported DEPTH16 output size was found")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "No compatible depth output size")
            return false
        }

        val reader = runCatching {
            ImageReader.newInstance(
                resolution.width,
                resolution.height,
                ImageFormat.DEPTH16,
                profile.maxImages
            )
        }.getOrElse {
            cleanupDepthResources()
            listener.onError(sourceId, "Depth ImageReader creation failed: ${it.message ?: it::class.java.simpleName}")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Depth ImageReader creation failed")
            return false
        }

        reader.setOnImageAvailableListener({ available ->
            available.acquireLatestImage()?.let(::processImage)
        }, handler)
        imageReader = reader

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cleanupDepthResources()
            listener.onError(sourceId, "Camera permission was revoked before depth openCamera")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "CAMERA permission missing")
            return false
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    configureCaptureSession(camera, reader)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    sourceListener?.onStateChanged(sourceId, AcquisitionSourceState.DISCONNECTED)
                    cleanupDepthResources()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    sourceListener?.onError(sourceId, "Depth Camera2 error=$error")
                    sourceListener?.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Depth camera device error=$error")
                    cleanupDepthResources()
                }
            }, handler)
        } catch (securityException: SecurityException) {
            cleanupDepthResources()
            listener.onError(sourceId, "Depth openCamera permission denied: ${securityException.message ?: "CAMERA permission unavailable"}")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Depth camera permission denied")
            return false
        } catch (exception: Exception) {
            cleanupDepthResources()
            listener.onError(sourceId, "Depth openCamera failed: ${exception.message ?: exception::class.java.simpleName}")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Depth camera open failed")
            return false
        }

        return true
    }

    override fun stop() {
        val listener = sourceListener
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerHandler = null
        workerThread = null
        isRunning = false
        lastFrameTimestampNs = 0L
        listener?.onStateChanged(sourceId, AcquisitionSourceState.STOPPED)
        sourceListener = null
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun chooseDepthCameraId(): String? =
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            null
        } else runCatching {
            cameraManager.cameraIdList
                .map { id -> id to cameraManager.getCameraCharacteristics(id) }
                .filter { (_, characteristics) ->
                    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) == true
                }
                .sortedByDescending { (_, characteristics) ->
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
                .mapNotNull { (id, _) ->
                    chooseDepthSize(id)?.let { id }
                }
                .firstOrNull()
        }.getOrNull()

    private fun chooseDepthSize(cameraId: String): Size? =
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            null
        } else runCatching {
            val map = cameraManager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            map.getOutputSizes(ImageFormat.DEPTH16)
                .orEmpty()
                .filter { it.width % 2 == 0 && it.height % 2 == 0 }
                .minByOrNull {
                    abs(it.width * it.height - profile.width * profile.height)
                }
        }.getOrNull()

    private fun configureCaptureSession(
        camera: CameraDevice,
        reader: ImageReader
    ) {
        val listener = sourceListener ?: return
        val handler = workerHandler ?: return

        val request = runCatching {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
        }.getOrElse {
            listener.onError(sourceId, "Depth capture request creation failed: ${it.message ?: it::class.java.simpleName}")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Depth capture request failed")
            stop()
            return
        }

        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                runCatching {
                    session.setRepeatingRequest(request, null, handler)
                }.onFailure {
                    listener.onError(sourceId, "Depth repeating request failed: ${it.message ?: it::class.java.simpleName}")
                    listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Depth repeating request failed")
                    stop()
                    return@onFailure
                }
                isRunning = true
                listener.onStateChanged(sourceId, AcquisitionSourceState.READY)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                listener.onError(sourceId, "Depth capture session configuration failed")
                listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Depth capture configuration failed")
                stop()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputConfiguration = OutputConfiguration(reader.surface)
            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfiguration),
                Executor { command -> handler.post(command) },
                sessionCallback
            )
            camera.createCaptureSession(sessionConfiguration)
        } else {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(reader.surface),
                sessionCallback,
                handler
            )
        }
    }

    private fun processImage(image: Image) {
        try {
            val timestampNs = if (image.timestamp > 0L) image.timestamp else System.nanoTime()
            val stats = Depth16Math.fromImage(image, profile.sampleEveryPixels)
            if (stats.sampleCount == 0) return

            val wallClockMs = clockMapper.resolve(timestampNs, System.currentTimeMillis())
            val listener = sourceListener ?: return
            fun emit(channelId: String, name: String, value: Float, unit: String) {
                listener.onSample(
                    AcquisitionSample(
                        sourceId = sourceId,
                        sourceKind = AcquisitionSourceKind.DEPTH,
                        channelId = channelId,
                        name = name,
                        vendor = "Android Camera2",
                        timestampNs = timestampNs,
                        wallClockTimestampMs = wallClockMs,
                        values = floatArrayOf(value),
                        unit = unit
                    )
                )
            }

            emit("mean_depth_mm", "Mean depth", stats.meanDepthMm, "mm")
            emit("min_depth_mm", "Minimum depth", stats.minDepthMm.toFloat(), "mm")
            emit("max_depth_mm", "Maximum depth", stats.maxDepthMm.toFloat(), "mm")
            emit("valid_fraction", "Depth valid fraction", stats.validFraction, "fraction")
            emit("mean_confidence", "Mean depth confidence", stats.meanConfidence, "fraction")

            if (lastFrameTimestampNs > 0L && timestampNs > lastFrameTimestampNs) {
                val frameRateHz = 1_000_000_000f / (timestampNs - lastFrameTimestampNs).toFloat()
                if (frameRateHz.isFinite()) {
                    emit("frame_rate_hz", "Depth frame rate", frameRateHz, "Hz")
                }
            }
            lastFrameTimestampNs = timestampNs
        } finally {
            image.close()
        }
    }

    private fun cleanupDepthResources() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerHandler = null
        workerThread = null
        isRunning = false
        lastFrameTimestampNs = 0L
    }
}
