package com.research.sensormax

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun assertThat(condition: Boolean, message: String) {
    if (!condition) error(message)
}

fun main() {
    val parsed = NetworkSensorFrameParser.parse(
        """{"version":1,"source_id":"gateway-01","channel_id":"accel_x","name":"Acceleration X","vendor":"LabGateway","values":[1.5,-2.0,0.5],"unit":"m/s^2","sequence":7,"timestamp_ms":1234567890}"""
    )
    assertThat(parsed.version == 1, "version")
    assertThat(parsed.sourceId == "gateway-01", "source id")
    assertThat(parsed.values.size == 3, "values")
    assertThat(parsed.values[0] == 1.5f, "value decode")

    val sampleLatch = CountDownLatch(1)
    val sampleRef = AtomicReference<AcquisitionSample>()
    val source = NetworkAcquisitionSource(
        NetworkAcquisitionProfile(port = 18765, clientIdleTimeoutMs = 5_000)
    )
    val listener = object : AcquisitionSourceListener {
        override fun onSample(sample: AcquisitionSample) {
            sampleRef.set(sample)
            sampleLatch.countDown()
        }

        override fun onError(sourceId: String, message: String) {
            error("provider error: $message")
        }

        override fun onStateChanged(sourceId: String, state: AcquisitionSourceState, detail: String) = Unit
        override fun onLog(sourceId: String, message: String) = Unit
    }

    assertThat(source.start(AcquisitionSourceConfig(1), listener), "source did not start")
    Socket("127.0.0.1", 18765).use { socket ->
        socket.getOutputStream().bufferedWriter().use { out ->
            out.write("{\"version\":1,\"source_id\":\"gw\",\"channel_id\":\"temp\",\"values\":[24.5],\"unit\":\"degC\"}\n")
            out.flush()
        }
    }

    assertThat(sampleLatch.await(2, TimeUnit.SECONDS), "sample not received")
    val sample = sampleRef.get()
    assertThat(sample.sourceKind == AcquisitionSourceKind.NETWORK, "wrong source kind")
    assertThat(sample.sourceId == "network:gw", "network source identity")
    assertThat(sample.values[0] == 24.5f, "network value")
    assertThat(sample.timestampNs > 0L, "local monotonic timestamp missing")

    source.stop()
    assertThat(!source.isRunning, "source did not stop")

    runCatching {
        NetworkSensorFrameParser.parse(
            """{"version":2,"source_id":"gw","channel_id":"x","values":[1]}"""
        )
    }.onSuccess { error("unsupported version accepted") }

    runCatching {
        NetworkSensorFrameParser.parse(
            """{"version":1,"source_id":"gw","channel_id":"x","values":[NaN]}"""
        )
    }.onSuccess { error("non-finite value accepted") }

    runCatching {
        NetworkSensorFrameParser.parse(
            """{"version":1,"source_id":"gw","channel_id":"x","values":[]}"""
        )
    }.onSuccess { error("empty values accepted") }

    val profile = NetworkAcquisitionProfile()
    assertThat(profile.port == 8765, "default port")
    assertThat(profile.maxConnections == 4, "connection limit")
    assertThat(profile.maxFrameBytes == 16 * 1024, "frame limit")

    println("S25_NETWORK_NDJSON_INTEGRATION_QA_PASS")
}
