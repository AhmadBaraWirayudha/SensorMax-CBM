package com.research.sensormax

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Small local persistence layer for operator-defined assets and measurement
 * points. This deliberately stays behind an interface-like class so the UI
 * does not own persistence or JSON details.
 *
 * Scope: local operator convenience, not multi-user synchronization.
 */
class AssetPointRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "sensormax_asset_points"
        private const val KEY_POINTS = "points_json"
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private val gson = Gson()

    private val listType =
        object : TypeToken<List<AssetPoint>>() {}.type

    @Synchronized
    fun load(): List<AssetPoint> =
        runCatching {
            gson.fromJson<List<AssetPoint>>(
                prefs.getString(KEY_POINTS, "[]") ?: "[]",
                listType
            ) ?: emptyList()
        }.getOrElse {
            emptyList()
        }

    @Synchronized
    fun upsert(
        assetNameInput: String,
        pointNameInput: String,
        customLabelInput: String = ""
    ): AssetPoint {
        val assetName = assetNameInput.trim()
        val pointName = pointNameInput.trim()
        require(assetName.isNotBlank()) { "Asset name cannot be blank." }
        require(pointName.isNotBlank()) { "Measurement point cannot be blank." }

        val now = System.currentTimeMillis()
        val current = load().toMutableList()

        val existing = current.firstOrNull {
            it.assetName.equals(assetName, ignoreCase = true) &&
                it.pointName.equals(pointName, ignoreCase = true)
        }

        val existingAssetId = current.firstOrNull {
            it.assetName.equals(assetName, ignoreCase = true)
        }?.assetId

        val saved = if (existing == null) {
            AssetPoint(
                id = UUID.randomUUID().toString(),
                assetId = existingAssetId ?: UUID.randomUUID().toString(),
                assetName = assetName,
                pointName = pointName,
                customLabel = customLabelInput.trim(),
                createdAtMs = now,
                updatedAtMs = now
            )
        } else {
            existing.copy(
                customLabel = customLabelInput.trim(),
                updatedAtMs = now
            )
        }

        if (existing == null) {
            current += saved
        } else {
            val index = current.indexOf(existing)
            current[index] = saved
        }

        save(current)
        return saved
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val current = load().toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            save(current)
        }
        return removed
    }

    private fun save(points: List<AssetPoint>) {
        prefs.edit()
            .putString(KEY_POINTS, gson.toJson(points))
            .apply()
    }
}
