package com.research.sensormax

/**
 * Persisted measurement-point identity used by the operator tag manager.
 *
 * Asset and point IDs are stable within the local SensorMax store. The display
 * names remain editable labels and are not used as cryptographic identifiers.
 */
data class AssetPoint(
    val id: String,
    val assetId: String,
    val assetName: String,
    val pointName: String,
    val customLabel: String = "",
    val createdAtMs: Long,
    val updatedAtMs: Long
) {
    fun displayLabel(): String =
        if (customLabel.isBlank()) {
            "$assetName / $pointName"
        } else {
            "$assetName / $pointName · $customLabel"
        }
}
