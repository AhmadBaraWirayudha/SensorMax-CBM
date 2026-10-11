package com.research.sensormax

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetPointRepositoryTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        context.getSharedPreferences(
            "sensormax_asset_points",
            0
        ).edit().clear().commit()
    }

    @Test
    fun upsertPersistsAssetAndReusesAssetIdentityAcrossPoints() {
        val repository = AssetPointRepository(context)

        val drive = repository.upsert(
            assetNameInput = "Pump-01",
            pointNameInput = "DE Bearing",
            customLabelInput = "Drive end"
        )

        val driveRepeat = repository.upsert(
            assetNameInput = "pump-01",
            pointNameInput = "NDE Bearing"
        )

        assertEquals(drive.assetId, driveRepeat.assetId)
        assertEquals(2, repository.load().size)

        val reloaded = AssetPointRepository(context).load()
        assertTrue(reloaded.any { it.pointName == "DE Bearing" })
        assertTrue(reloaded.any { it.pointName == "NDE Bearing" })
    }

    @Test
    fun upsertUpdatesExistingPointWithoutCreatingDuplicate() {
        val repository = AssetPointRepository(context)

        val first = repository.upsert(
            assetNameInput = "Fan-02",
            pointNameInput = "Motor"
        )

        val updated = repository.upsert(
            assetNameInput = "FAN-02",
            pointNameInput = "motor",
            customLabelInput = "Updated"
        )

        assertEquals(first.id, updated.id)
        assertEquals(1, repository.load().size)
        assertEquals("Updated", repository.load().single().customLabel)
    }
}
