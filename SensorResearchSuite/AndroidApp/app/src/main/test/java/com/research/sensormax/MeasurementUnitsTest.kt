package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementUnitsTest {
    @Test fun acceleration_roundTrips_without_changing_physical_threshold() {
        val metricThreshold = 9.80665
        val imperialDisplay = MeasurementUnits.acceleration(metricThreshold, MeasurementSystem.IMPERIAL)
        assertEquals(32.17405, imperialDisplay, 0.0001)
        assertEquals(metricThreshold, MeasurementUnits.accelerationToSi(imperialDisplay, MeasurementSystem.IMPERIAL), 1e-9)
        assertEquals(metricThreshold, MeasurementUnits.acceleration(metricThreshold, MeasurementSystem.METRIC), 0.0)
    }

    @Test fun bearing_dimensions_roundTrip_between_mm_and_inches() {
        val displayed = MeasurementUnits.lengthMmToDisplay(25.4, MeasurementSystem.IMPERIAL)
        assertEquals(1.0, displayed, 1e-12)
        assertEquals(25.4, MeasurementUnits.lengthToMm(displayed, MeasurementSystem.IMPERIAL), 1e-12)
    }

    @Test fun vibration_velocity_and_displacement_use_correct_inch_based_scales() {
        assertEquals(1.0, MeasurementUnits.velocityMmSToDisplay(25.4, MeasurementSystem.IMPERIAL), 1e-12)
        assertEquals("in/s", MeasurementUnits.velocityMmSUnit(MeasurementSystem.IMPERIAL))
        assertEquals(1.0, MeasurementUnits.displacementUmToDisplay(25.4, MeasurementSystem.IMPERIAL), 1e-12)
        assertEquals("mil", MeasurementUnits.displacementUmUnit(MeasurementSystem.IMPERIAL))
    }

    @Test fun metric_is_identity_and_never_mutates_input() {
        assertEquals(3.25, MeasurementUnits.acceleration(3.25, MeasurementSystem.METRIC), 0.0)
        assertEquals(3.25, MeasurementUnits.accelerationToSi(3.25, MeasurementSystem.METRIC), 0.0)
        assertEquals(12.0, MeasurementUnits.lengthMmToDisplay(12.0, MeasurementSystem.METRIC), 0.0)
    }
    @Test fun decimal_comma_is_accepted_but_ambiguous_grouping_is_rejected() {
        assertEquals(12.5, MeasurementUnits.parseInputValue("12,5")!!, 0.0)
        assertEquals(12.5, MeasurementUnits.parseInputValue("12.5")!!, 0.0)
        assertEquals(null, MeasurementUnits.parseInputValue("1,234.5"))
        assertEquals(null, MeasurementUnits.parseInputValue("NaN"))
    }

}
