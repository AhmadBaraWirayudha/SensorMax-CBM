package com.research.sensormax

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/** SensorMax-owned location/GNSS provider using Android LocationManager. */
class LocationAcquisitionSource(
    private val context: Context,
    private val profile: LocationAcquisitionProfile = LocationAcquisitionProfile()
) : AcquisitionSource {

    override val descriptor = AcquisitionSourceDescriptor(
        id = "location:gnss",
        kind = AcquisitionSourceKind.LOCATION,
        displayName = profile.displayName,
        vendor = "Android LocationManager",
        channels = listOf(
            "latitude",
            "longitude",
            "altitude",
            "speed",
            "bearing",
            "horizontalAccuracy",
            "verticalAccuracy"
        )
    )

    override var isRunning: Boolean = false
        private set

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var locationListener: LocationListener? = null
    private var sourceListener: AcquisitionSourceListener? = null
    private val running = AtomicBoolean(false)
    private val clockMapper = AcquisitionClockMapper()

    override fun start(
        config: AcquisitionSourceConfig,
        listener: AcquisitionSourceListener
    ): Boolean {
        if (isRunning) return false
        if (!hasLocationPermission()) {
            listener.onError(descriptor.id, "Missing location permission")
            return false
        }
        val provider = selectProvider()
        if (provider == null) {
            listener.onError(descriptor.id, "No enabled location provider; enable location services first")
            listener.onStateChanged(
                descriptor.id,
                AcquisitionSourceState.ERROR,
                "Location services/provider unavailable"
            )
            return false
        }

        listener.onStateChanged(
            descriptor.id,
            AcquisitionSourceState.STARTING,
            "Using $provider"
        )

        val thread = HandlerThread("SensorMax-LocationSource")
        thread.start()
        val handler = thread.looper?.let(::Handler)
        if (handler == null) {
            thread.quitSafely()
            listener.onError(descriptor.id, "HandlerThread initialization failed")
            return false
        }

        val locationCallback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                emitLocation(location)
            }

            override fun onProviderEnabled(providerName: String) {
                listener.onStateChanged(
                    descriptor.id,
                    AcquisitionSourceState.READY,
                    "Provider enabled: $providerName"
                )
            }

            override fun onProviderDisabled(providerName: String) {
                listener.onStateChanged(
                    descriptor.id,
                    AcquisitionSourceState.DISCONNECTED,
                    "Provider disabled: $providerName"
                )
            }

            @Suppress("DEPRECATION")
            override fun onStatusChanged(providerName: String?, status: Int, extras: android.os.Bundle?) = Unit
        }

        clockMapper.reset()
        sourceListener = listener
        locationListener = locationCallback
        workerThread = thread
        workerHandler = handler

        val registered = runCatching {
            locationManager.requestLocationUpdates(
                provider,
                profile.minUpdateIntervalMs.coerceAtLeast(config.requestedIntervalMs),
                profile.minDistanceMeters,
                locationCallback,
                handler.looper
            )
        }.onFailure {
            listener.onError(
                descriptor.id,
                "Location registration failed: ${it.message ?: it::class.java.simpleName}"
            )
        }.isSuccess

        if (!registered) {
            stopInternal(notifyStopped = false)
            listener.onStateChanged(
                descriptor.id,
                AcquisitionSourceState.ERROR,
                "Location updates were not registered"
            )
            return false
        }

        running.set(true)
        isRunning = true
        listener.onStateChanged(
            descriptor.id,
            AcquisitionSourceState.READY,
            "Location updates active: $provider"
        )
        return true
    }

    override fun stop() {
        stopInternal(notifyStopped = true)
    }

    private fun stopInternal(notifyStopped: Boolean) {
        val currentListener = locationListener
        if (currentListener != null) {
            runCatching { locationManager.removeUpdates(currentListener) }
        }
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerHandler = null
        workerThread = null
        running.set(false)
        isRunning = false
        clockMapper.reset()
        if (notifyStopped) {
            sourceListener?.onStateChanged(
                descriptor.id,
                AcquisitionSourceState.STOPPED
            )
        }
        locationListener = null
        sourceListener = null
    }

    private fun emitLocation(location: Location) {
        val listener = sourceListener ?: return
        val timestampNs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.elapsedRealtimeNanos
        } else {
            android.os.SystemClock.elapsedRealtimeNanos()
        }
        if (timestampNs <= 0L) {
            listener.onError(descriptor.id, "Location timestamp was not monotonic")
            return
        }
        val wallClockMs = clockMapper.resolve(timestampNs, System.currentTimeMillis())

        emitScalar(listener, "latitude", "Latitude", location.latitude.toFloat(), "deg", timestampNs, wallClockMs)
        emitScalar(listener, "longitude", "Longitude", location.longitude.toFloat(), "deg", timestampNs, wallClockMs)
        emitScalar(listener, "altitude", "Altitude", location.altitude.toFloat(), "m", timestampNs, wallClockMs)
        emitScalar(listener, "speed", "Speed", location.speed, "m/s", timestampNs, wallClockMs)
        emitScalar(listener, "bearing", "Bearing", location.bearing, "deg", timestampNs, wallClockMs)
        emitScalar(listener, "horizontalAccuracy", "Horizontal accuracy", location.accuracy, "m", timestampNs, wallClockMs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            emitScalar(
                listener,
                "verticalAccuracy",
                "Vertical accuracy",
                location.verticalAccuracyMeters,
                "m",
                timestampNs,
                wallClockMs
            )
        }
    }

    private fun emitScalar(
        listener: AcquisitionSourceListener,
        channelId: String,
        name: String,
        value: Float,
        unit: String,
        timestampNs: Long,
        wallClockMs: Long
    ) {
        listener.onSample(
            AcquisitionSample(
                sourceId = descriptor.id,
                sourceKind = descriptor.kind,
                channelId = channelId,
                name = name,
                vendor = descriptor.vendor,
                timestampNs = timestampNs,
                wallClockTimestampMs = wallClockMs,
                values = floatArrayOf(value),
                androidSensorType = null,
                unit = unit
            )
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    private fun selectProvider(): String? {
        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        if (gpsEnabled) return LocationManager.GPS_PROVIDER

        val networkEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        if (networkEnabled) return LocationManager.NETWORK_PROVIDER

        return null
    }
}

data class LocationAcquisitionProfile(
    val displayName: String = "Location / GNSS",
    val minUpdateIntervalMs: Long = 1_000L,
    val minDistanceMeters: Float = 0f
) {
    init {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(minUpdateIntervalMs > 0L) { "minUpdateIntervalMs must be > 0" }
        require(minDistanceMeters >= 0f) { "minDistanceMeters must be >= 0" }
    }
}

object LocationAcquisitionPermissions {
    fun requiredPermissions(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun missingPermissions(context: Context): List<String> =
        if (hasRequiredPermission(context)) emptyList() else requiredPermissions().toList()

    fun hasRequiredPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { manager.isLocationEnabled }.getOrDefault(false)
        } else {
            runCatching {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }
    }
}
