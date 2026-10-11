package com.research.sensormax.qa

import com.research.sensormax.AcquisitionSourceKind
import com.research.sensormax.LocationAcquisitionProfile

/** Deterministic provider-configuration checks; location/GNSS behavior is device validation. */
object LocationAcquisitionQa {
    fun run() {
        val profile = LocationAcquisitionProfile()
        check(profile.minUpdateIntervalMs == 1_000L)
        check(profile.minDistanceMeters == 0f)
        check(profile.displayName == "Location / GNSS")
        check(AcquisitionSourceKind.LOCATION.name == "LOCATION")
        check(profile.minUpdateIntervalMs > 0L)
        check(profile.minDistanceMeters >= 0f)
    }
}
