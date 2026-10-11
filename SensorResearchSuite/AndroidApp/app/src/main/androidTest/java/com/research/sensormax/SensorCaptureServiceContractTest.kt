package com.research.sensormax

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensorCaptureServiceContractTest {

    @Test
    fun serviceIsNonExportedAndUsesHealthForegroundType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, SensorCaptureService::class.java)
        val info = context.packageManager.getServiceInfo(
            component,
            PackageManager.GET_META_DATA
        )

        assertFalse(info.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
            info.foregroundServiceType
        )
    }
}
