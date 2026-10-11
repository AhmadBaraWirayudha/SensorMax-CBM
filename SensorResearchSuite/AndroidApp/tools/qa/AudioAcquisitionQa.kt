package com.research.sensormax.qa

import com.research.sensormax.AcquisitionSourceKind
import com.research.sensormax.AudioAcquisitionProfile
import com.research.sensormax.AudioPcm16Decoder

/** Deterministic configuration/PCM checks; microphone capture is device validation. */
object AudioAcquisitionQa {
    fun run() {
        val profile = AudioAcquisitionProfile()
        check(profile.sampleRateHz == 44_100)
        check(profile.chunkFrames == 2_048)
        check(profile.bytesPerFrame == 2)
        check(AcquisitionSourceKind.AUDIO.name == "AUDIO")

        val decoded = AudioPcm16Decoder.normalize(
            shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE),
            5
        )
        check(decoded[0] == -1.0f)
        check(decoded[1] == -1f / 32768f)
        check(decoded[2] == 0f)
        check(decoded[4] > 0.99f)

        val partial = AudioPcm16Decoder.normalize(shortArrayOf(1, 2, 3, 4), 2)
        check(partial.contentEquals(floatArrayOf(1f / 32768f, 2f / 32768f)))
    }
}
