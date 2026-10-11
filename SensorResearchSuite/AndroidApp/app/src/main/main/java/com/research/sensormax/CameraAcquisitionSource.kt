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
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Size
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * Minimal SensorMax photometric provider backed by the Camera2 YUV preview stream.
 * It emits scalar luminance statistics rather than exposing raw image frames to the
 * generic scalar telemetry pipeline.
 *
 * This intentionally does not claim parity with the recovered camera runtime's
 * exposure normalization, sRGB linearization, aperture metadata, or preview UI.
 */
data class CameraAcquisitionProfile(
    val width: Int = 320,
    val height: Int = 240,
    val maxImages: Int = 2,
    val sampleEveryPixels: Int = 4,
    val markUnderexposedAt: Int = 16,
    val markOverexposedAt: Int = 239
) {
    init {
        require(width >= 32) { "width must be >= 32" }
        require(height >= 32) { "height must be >= 32" }
        require(maxImages >= 2) { "maxImages must be >= 2" }
        require(sampleEveryPixels >= 1) { "sampleEveryPixels must be >= 1" }
        require(markUnderexposedAt in 0..255) { "markUnderexposedAt must be in 0..255" }
        require(markOverexposedAt in 0..255) { "markOverexposedAt must be in 0..255" }
        require(markUnderexposedAt < markOverexposedAt) {
            "markUnderexposedAt must be < markOverexposedAt"
        }
    }
}

data class CameraPhotometricStats(
    val meanLuma: Float,
    val underexposedFraction: Float,
    val overexposedFraction: Float,
    val sampleCount: Int
)

object CameraPhotometricMath {
    fun fromLumaValues(
        values: IntArray,
        underexposedAt: Int,
        overexposedAt: Int
    ): CameraPhotometricStats {
        if (values.isEmpty()) return CameraPhotometricStats(0f, 0f, 0f, 0)

        var sum = 0L
        var under = 0
        var over = 0
        values.forEach { raw ->
            val value = raw.coerceIn(0, 255)
            sum += value
            if (value <= underexposedAt) under++
            if (value >= overexposedAt) over++
        }

        return CameraPhotometricStats(
            meanLuma = (sum.toFloat() / values.size.toFloat()) / 255f,
            underexposedFraction = under.toFloat() / values.size.toFloat(),
            overexposedFraction = over.toFloat() / values.size.toFloat(),
            sampleCount = values.size
        )
    }

    fun fromYPlane(
        image: Image,
        sampleEveryPixels: Int,
        underexposedAt: Int,
        overexposedAt: Int
    ): CameraPhotometricStats {
        val plane = image.planes.firstOrNull()
            ?: return CameraPhotometricStats(0f, 0f, 0f, 0)

        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer.duplicate()

        var sum = 0L
        var under = 0
        var over = 0
        var count = 0

        val step = sampleEveryPixels.coerceAtLeast(1)
        for (y in 0 until height step step) {
            val rowBase = y * rowStride
            for (x in 0 until width step step) {
                val index = rowBase + x * pixelStride
                if (index >= buffer.limit()) continue
                val value = buffer.get(index).toInt() and 0xFF
                sum += value
                if (value <= underexposedAt) under++
                if (value >= overexposedAt) over++
                count++
            }
        }

        if (count == 0) return CameraPhotometricStats(0f, 0f, 0f, 0)

        return CameraPhotometricStats(
            meanLuma = (sum.toFloat() / count.toFloat()) / 255f,
            underexposedFraction = under.toFloat() / count.toFloat(),
            overexposedFraction = over.toFloat() / count.toFloat(),
            sampleCount = count
        )
    }
}

class CameraAcquisitionSource(
    context: Context,
    private val profile: CameraAcquisitionProfile = CameraAcquisitionProfile()
) : AcquisitionSource {

    private val appContext = context.applicationContext
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sourceId = "camera-photometric"

    override val descriptor = AcquisitionSourceDescriptor(
        id = sourceId,
        kind = AcquisitionSourceKind.CAMERA,
        displayName = "Camera / Photometric",
        channels = listOf(
            "mean_luma",
            "underexposed_fraction",
            "overexposed_fraction",
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
    private var lastFrameTimestampNs: Long = 0L
    private val clockMapper = AcquisitionClockMapper()

    override fun start(
        config: AcquisitionSourceConfig,
        listener: AcquisitionSourceListener
    ): Boolean {
        if (isRunning) return false

        if (!hasCameraPermission()) {
            listener.onError(sourceId, "Camera permission is not granted")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "CAMERA permission missing")
            return false
        }

        listener.onStateChanged(sourceId, AcquisitionSourceState.STARTING)
        sourceListener = listener

        val cameraId = chooseCameraId()
        if (cameraId == null) {
            listener.onError(sourceId, "No usable back-facing camera was found")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "No camera available")
            sourceListener = null
            return false
        }

        val thread = HandlerThread("SensorMax-CameraSource")
        thread.start()
        val handler = thread.looper?.let(::Handler)
        if (handler == null) {
            thread.quitSafely()
            sourceListener = null
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Camera HandlerThread initialization failed")
            return false
        }

        workerThread = thread
        workerHandler = handler
        lastFrameTimestampNs = 0L

        val resolution = chooseYuvSize(cameraId)
        if (resolution == null) {
            cleanupCameraResources()
            listener.onError(sourceId, "No supported YUV_420_888 camera output size was found")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "No compatible camera output size")
            return false
        }

        val reader = runCatching {
            ImageReader.newInstance(
                resolution.width,
                resolution.height,
                ImageFormat.YUV_420_888,
                profile.maxImages
            )
        }.getOrElse {
            cleanupCameraResources()
            listener.onError(sourceId, "ImageReader creation failed: ${it.message ?: it::class.java.simpleName}")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "ImageReader creation failed")
            return false
        }

        reader.setOnImageAvailableListener({ available ->
            val image = available.acquireLatestImage() ?: return@setOnImageAvailableListener
            processImage(image)
        }, handler)
        imageReader = reader

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cleanupCameraResources()
            listener.onError(sourceId, "Camera permission was revoked before openCamera")
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
                    cleanupCameraResources()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    sourceListener?.onError(sourceId, "Camera2 error=$error")
                    sourceListener?.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Camera2 device error=$error")
                    cleanupCameraResources()
                }
            }, handler)
        } catch (securityException: SecurityException) {
            cleanupCameraResources()
            listener.onError(sourceId, "openCamera permission denied: ${securityException.message ?: "CAMERA permission unavailable"}")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Camera permission denied")
            return false
        } catch (exception: Exception) {
            cleanupCameraResources()
            listener.onError(sourceId, "openCamera failed: ${exception.message ?: exception::class.java.simpleName}")
            listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Camera open failed")
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

    private fun chooseCameraId(): String? =
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            null
        } else runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
        }.getOrNull()

    private fun chooseYuvSize(cameraId: String): Size? =
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            null
        } else runCatching {
            val map = cameraManager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            sizes.minByOrNull {
                kotlin.math.abs(it.width * it.height - profile.width * profile.height)
            }
        }.getOrNull()

    private fun configureCaptureSession(
        camera: CameraDevice,
        reader: ImageReader
    ) {
        val listener = sourceListener ?: return
        val handler = workerHandler ?: return

        listener.onStateChanged(sourceId, AcquisitionSourceState.DISCOVERING_SERVICES)

        val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }.build()

        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                runCatching {
                    session.setRepeatingRequest(previewRequest, null, handler)
                }.onFailure {
                    listener.onError(sourceId, "Camera capture request failed: ${it.message ?: it::class.java.simpleName}")
                    listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Camera repeating request failed")
                    stop()
                    return@onFailure
                }

                isRunning = true
                listener.onStateChanged(sourceId, AcquisitionSourceState.READY)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                listener.onError(sourceId, "Camera capture session configuration failed")
                listener.onStateChanged(sourceId, AcquisitionSourceState.ERROR, "Capture session configuration failed")
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
            if (image.planes.isEmpty()) return

            val timestampNs = if (image.timestamp > 0L) image.timestamp else System.nanoTime()
            val stats = CameraPhotometricMath.fromYPlane(
                image = image,
                sampleEveryPixels = profile.sampleEveryPixels,
                underexposedAt = profile.markUnderexposedAt,
                overexposedAt = profile.markOverexposedAt
            )

            if (stats.sampleCount == 0) return

            val wallClockMs = clockMapper.resolve(timestampNs, System.currentTimeMillis())
            val listener = sourceListener ?: return

            listener.onSample(
                AcquisitionSample(
                    sourceId = sourceId,
                    sourceKind = AcquisitionSourceKind.CAMERA,
                    channelId = "mean_luma",
                    name = "Camera mean luminance",
                    vendor = "Android Camera2",
                    timestampNs = timestampNs,
                    wallClockTimestampMs = wallClockMs,
                    values = floatArrayOf(stats.meanLuma),
                    unit = "relative luma"
                )
            )
            listener.onSample(
                AcquisitionSample(
                    sourceId = sourceId,
                    sourceKind = AcquisitionSourceKind.CAMERA,
                    channelId = "underexposed_fraction",
                    name = "Camera underexposed fraction",
                    vendor = "Android Camera2",
                    timestampNs = timestampNs,
                    wallClockTimestampMs = wallClockMs,
                    values = floatArrayOf(stats.underexposedFraction),
                    unit = "fraction"
                )
            )
            listener.onSample(
                AcquisitionSample(
                    sourceId = sourceId,
                    sourceKind = AcquisitionSourceKind.CAMERA,
                    channelId = "overexposed_fraction",
                    name = "Camera overexposed fraction",
                    vendor = "Android Camera2",
                    timestampNs = timestampNs,
                    wallClockTimestampMs = wallClockMs,
                    values = floatArrayOf(stats.overexposedFraction),
                    unit = "fraction"
                )
            )

            if (lastFrameTimestampNs > 0L && timestampNs > lastFrameTimestampNs) {
                val frameRateHz = 1_000_000_000f / (timestampNs - lastFrameTimestampNs).toFloat()
                if (frameRateHz.isFinite()) {
                    listener.onSample(
                        AcquisitionSample(
                            sourceId = sourceId,
                            sourceKind = AcquisitionSourceKind.CAMERA,
                            channelId = "frame_rate_hz",
                            name = "Camera frame rate",
                            vendor = "Android Camera2",
                            timestampNs = timestampNs,
                            wallClockTimestampMs = wallClockMs,
                            values = floatArrayOf(frameRateHz),
                            unit = "Hz"
                        )
                    )
                }
            }
            lastFrameTimestampNs = timestampNs
        } finally {
            image.close()
        }
    }

    private fun cleanupCameraResources() {
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
