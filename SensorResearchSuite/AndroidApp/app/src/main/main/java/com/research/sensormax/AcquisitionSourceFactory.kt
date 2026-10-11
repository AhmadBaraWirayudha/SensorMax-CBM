package com.research.sensormax

import android.hardware.Sensor
import android.hardware.SensorManager
import android.content.Context

/** Creates SensorMax acquisition providers without coupling SensorEngine to a provider implementation. */
interface AcquisitionSourceFactory {
    fun createAndroidSensorSource(sensors: Set<Sensor>): AcquisitionSource
    fun createBleGattSource(context: Context, profile: BleGattProfile): AcquisitionSource
    fun createAudioSource(context: Context, profile: AudioAcquisitionProfile = AudioAcquisitionProfile()): AcquisitionSource
    fun createLocationSource(context: Context, profile: LocationAcquisitionProfile = LocationAcquisitionProfile()): AcquisitionSource
    fun createCameraSource(context: Context, profile: CameraAcquisitionProfile = CameraAcquisitionProfile()): AcquisitionSource
    fun createDepthSource(context: Context, profile: DepthAcquisitionProfile = DepthAcquisitionProfile()): AcquisitionSource
    fun createNetworkSource(profile: NetworkAcquisitionProfile = NetworkAcquisitionProfile()): AcquisitionSource
}

class DefaultAcquisitionSourceFactory(
    private val sensorManager: SensorManager
) : AcquisitionSourceFactory {
    override fun createAndroidSensorSource(sensors: Set<Sensor>): AcquisitionSource =
        AndroidSensorAcquisitionSource(
            sensorManager = sensorManager,
            sensors = sensors
        )

    override fun createBleGattSource(
        context: Context,
        profile: BleGattProfile
    ): AcquisitionSource = BleGattAcquisitionSource(
        context = context,
        profile = profile
    )

    override fun createAudioSource(
        context: Context,
        profile: AudioAcquisitionProfile
    ): AcquisitionSource = AudioAcquisitionSource(
        context = context,
        profile = profile
    )

    override fun createLocationSource(
        context: Context,
        profile: LocationAcquisitionProfile
    ): AcquisitionSource = LocationAcquisitionSource(
        context = context,
        profile = profile
    )

    override fun createCameraSource(
        context: Context,
        profile: CameraAcquisitionProfile
    ): AcquisitionSource = CameraAcquisitionSource(
        context = context,
        profile = profile
    )

    override fun createDepthSource(
        context: Context,
        profile: DepthAcquisitionProfile
    ): AcquisitionSource = DepthAcquisitionSource(
        context = context,
        profile = profile
    )

    override fun createNetworkSource(
        profile: NetworkAcquisitionProfile
    ): AcquisitionSource = NetworkAcquisitionSource(profile)
}
