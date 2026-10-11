package com.research.sensormax

import org.junit.Test

class AcquisitionDeploymentRequestTest {
    private class FakeSource : AcquisitionSource {
        override val descriptor = AcquisitionSourceDescriptor(
            id = "fake-audio",
            kind = AcquisitionSourceKind.AUDIO,
            displayName = "Fake Audio",
            channels = listOf("mic")
        )

        override var isRunning: Boolean = false

        override fun start(
            config: AcquisitionSourceConfig,
            listener: AcquisitionSourceListener
        ): Boolean = true

        override fun stop() {
            isRunning = false
        }
    }

    @Test
    fun provider_neutral_request_does_not_require_android_sensor_selection() {
        val request = AcquisitionDeploymentRequest(
            source = FakeSource(),
            intervalMs = 20L,
            enableNetwork = false,
            enableDisk = true
        )

        check(request.source.descriptor.kind == AcquisitionSourceKind.AUDIO)
        check(request.source.descriptor.channels == listOf("mic"))
        check(request.intervalMs == 20L)
    }

    @Test
    fun provider_neutral_request_rejects_non_positive_interval() {
        try {
            AcquisitionDeploymentRequest(
                source = FakeSource(),
                intervalMs = 0L,
                enableNetwork = false,
                enableDisk = false
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
