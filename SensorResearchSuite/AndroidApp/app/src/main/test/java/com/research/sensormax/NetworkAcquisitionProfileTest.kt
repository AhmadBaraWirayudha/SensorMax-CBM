package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAcquisitionProfileTest {
    @Test
    fun defaultListenerIsLoopbackOnly() {
        assertEquals("127.0.0.1", NetworkAcquisitionProfile().bindHost)
    }

    @Test
    fun lanBindingRequiresAnExplicitProfileOverride() {
        assertEquals("192.168.1.20", NetworkAcquisitionProfile(bindHost = "192.168.1.20").bindHost)
    }
}
