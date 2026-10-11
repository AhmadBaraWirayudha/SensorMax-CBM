package com.research.sensormax

enum class MeasurementSystem { METRIC, IMPERIAL }

/**
 * Display conversion helpers. Raw acquisition and stored data must remain SI;
 * convert only at presentation/export boundaries where the user explicitly asks.
 */
object MeasurementUnits {
    private const val METERS_PER_FOOT = 0.3048
    private const val MILLIMETERS_PER_INCH = 25.4

    /**
     * Parse a user-entered engineering number without depending on the device locale.
     * A single comma is accepted as a decimal separator. Inputs containing both comma
     * and point are rejected as ambiguous rather than guessed as grouping/decimal marks.
     */
    fun parseInputValue(text: String): Double? {
        val normalized = text.trim().replace(" ", "").replace('\u00A0'.toString(), "")
        if (normalized.isEmpty() || (normalized.contains(',') && normalized.contains('.'))) return null
        val parsed = normalized.replace(',', '.').toDoubleOrNull() ?: return null
        return parsed.takeIf { it.isFinite() }
    }

    /** Convert canonical SI acceleration to the selected display unit. */
    fun acceleration(valueMps2: Double, system: MeasurementSystem): Double =
        if (system == MeasurementSystem.METRIC) valueMps2 else valueMps2 / METERS_PER_FOOT

    /** Convert user-entered display acceleration back to canonical SI. */
    fun accelerationToSi(value: Double, system: MeasurementSystem): Double =
        if (system == MeasurementSystem.METRIC) value else value * METERS_PER_FOOT

    fun accelerationUnit(system: MeasurementSystem): String =
        if (system == MeasurementSystem.METRIC) "m/s²" else "ft/s²"

    /** Convert an SI velocity in m/s for general-purpose display. */
    fun velocity(valueMps: Double, system: MeasurementSystem): Double =
        if (system == MeasurementSystem.METRIC) valueMps else valueMps / METERS_PER_FOOT

    fun velocityUnit(system: MeasurementSystem): String =
        if (system == MeasurementSystem.METRIC) "m/s" else "ft/s"

    /** The vibration summary is currently calculated and stored in mm/s. */
    fun velocityMmSToDisplay(valueMmS: Double, system: MeasurementSystem): Double =
        if (system == MeasurementSystem.METRIC) valueMmS else valueMmS / MILLIMETERS_PER_INCH

    fun velocityMmSUnit(system: MeasurementSystem): String =
        if (system == MeasurementSystem.METRIC) "mm/s" else "in/s"

    /** The vibration summary stores displacement in micrometres. */
    fun displacementUmToDisplay(valueUm: Double, system: MeasurementSystem): Double =
        if (system == MeasurementSystem.METRIC) valueUm else valueUm / MILLIMETERS_PER_INCH

    fun displacementUmUnit(system: MeasurementSystem): String =
        if (system == MeasurementSystem.METRIC) "µm" else "mil"

    fun distance(valueMeters: Double, system: MeasurementSystem): Double =
        if (system == MeasurementSystem.METRIC) valueMeters else valueMeters / METERS_PER_FOOT

    fun distanceUnit(system: MeasurementSystem): String =
        if (system == MeasurementSystem.METRIC) "m" else "ft"

    /** Bearing geometry input is entered as mm in metric mode and inches in imperial mode. */
    fun lengthMmToDisplay(valueMm: Double, system: MeasurementSystem): Double =
        if (system == MeasurementSystem.METRIC) valueMm else valueMm / MILLIMETERS_PER_INCH

    fun lengthToMm(value: Double, system: MeasurementSystem): Double =
        if (system == MeasurementSystem.METRIC) value else value * MILLIMETERS_PER_INCH

    fun lengthUnit(system: MeasurementSystem): String =
        if (system == MeasurementSystem.METRIC) "mm" else "in"

    fun temperature(valueCelsius: Double, system: MeasurementSystem): Double =
        if (system == MeasurementSystem.METRIC) valueCelsius else valueCelsius * 9.0 / 5.0 + 32.0

    fun temperatureUnit(system: MeasurementSystem): String =
        if (system == MeasurementSystem.METRIC) "°C" else "°F"
}
