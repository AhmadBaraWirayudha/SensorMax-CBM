package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketEndpointBuilderTest {
    @Test
    fun hostAndPortUseDebugWebSocketScheme() {
        val result = WebSocketEndpointBuilder.build("127.0.0.1", "8765", allowInsecure = true)
        assertTrue(result.isSuccess)
        assertEquals("ws://127.0.0.1:8765/", result.url)
    }

    @Test
    fun hostAndPortUseSecureSchemeForRelease() {
        val result = WebSocketEndpointBuilder.build("example.com", "443", allowInsecure = false)
        assertTrue(result.isSuccess)
        assertEquals("wss://example.com:443/", result.url)
    }

    @Test
    fun releaseRejectsExplicitInsecureUrl() {
        val result = WebSocketEndpointBuilder.build("ws://example.com/events", "8765", allowInsecure = false)
        assertFalse(result.isSuccess)
        assertEquals(WebSocketEndpointBuilder.Error.INSECURE_RELEASE, result.error)
    }

    @Test
    fun secureUrlRetainsPathAndExplicitPort() {
        val result = WebSocketEndpointBuilder.build("wss://example.com:9443/sensormax/stream", "8765", allowInsecure = false)
        assertTrue(result.isSuccess)
        assertEquals("wss://example.com:9443/sensormax/stream", result.url)
    }

    @Test
    fun rejectsUnsupportedSchemeAndCredentials() {
        assertEquals(
            WebSocketEndpointBuilder.Error.INVALID_URL,
            WebSocketEndpointBuilder.build("https://example.com", "443", false).error
        )
        assertEquals(
            WebSocketEndpointBuilder.Error.INVALID_URL,
            WebSocketEndpointBuilder.build("wss://user:password@example.com", "443", false).error
        )
    }

    @Test
    fun validatesPortOnlyWhenUrlDoesNotProvideOne() {
        assertEquals(
            WebSocketEndpointBuilder.Error.INVALID_PORT,
            WebSocketEndpointBuilder.build("example.com", "70000", false).error
        )
        assertTrue(WebSocketEndpointBuilder.build("wss://example.com:9443", "invalid", false).isSuccess)
    }

    @Test
    fun acceptsBracketedIpv6Address() {
        val result = WebSocketEndpointBuilder.build("[::1]", "8765", allowInsecure = true)
        assertTrue(result.isSuccess)
        assertEquals("ws://[::1]:8765/", result.url)
    }
}
