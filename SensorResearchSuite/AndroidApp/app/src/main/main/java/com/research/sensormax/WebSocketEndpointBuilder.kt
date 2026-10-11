package com.research.sensormax

import java.net.URI

/**
 * Validates the WebSocket target entered in the acquisition screen.
 *
 * A host-only value uses the separately entered port. If the host field is a
 * complete WebSocket URL with an explicit port, that explicit URL port wins.
 * Plain `ws://` is allowed only in debug builds; release targets must use WSS.
 */
object WebSocketEndpointBuilder {
    enum class Error {
        INSECURE_RELEASE,
        INVALID_PORT,
        INVALID_URL
    }

    data class BuildResult(
        val url: String,
        val error: Error? = null
    ) {
        val isSuccess: Boolean get() = error == null && url.isNotBlank()
    }

    fun build(hostInput: String, portInput: String, allowInsecure: Boolean): BuildResult {
        val input = hostInput.trim()
        if (input.isBlank()) return failure(Error.INVALID_URL)

        val hasScheme = SCHEME_PREFIX.containsMatchIn(input)
        val defaultScheme = if (allowInsecure) "ws" else "wss"
        val candidate = try {
            URI(if (hasScheme) input else "$defaultScheme://$input")
        } catch (_: Exception) {
            return failure(Error.INVALID_URL)
        }

        val scheme = (candidate.scheme ?: return failure(Error.INVALID_URL)).lowercase()
        if (scheme !in SUPPORTED_SCHEMES) return failure(Error.INVALID_URL)
        if (scheme == "ws" && !allowInsecure) return failure(Error.INSECURE_RELEASE)
        if (candidate.host.isNullOrBlank() || candidate.rawUserInfo != null || candidate.rawFragment != null) {
            return failure(Error.INVALID_URL)
        }
        if (candidate.rawPath?.contains('\\') == true) return failure(Error.INVALID_URL)

        val port = if (candidate.port != -1) {
            candidate.port
        } else {
            portInput.trim().toIntOrNull() ?: return failure(Error.INVALID_PORT)
        }
        if (port !in 1..65_535) return failure(Error.INVALID_PORT)

        // Construct from the parsed host and raw path/query to avoid appending a
        // port after a path or accidentally double-encoding an existing % escape.
        val host = candidate.host.let { parsedHost ->
            if (parsedHost.contains(':') && !parsedHost.startsWith('[')) "[$parsedHost]" else parsedHost
        }
        val path = candidate.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = candidate.rawQuery?.let { "?$it" }.orEmpty()
        val endpoint = "$scheme://$host:$port$path$query"
        return try {
            val validated = URI(endpoint)
            if (validated.host.isNullOrBlank() || validated.port !in 1..65_535) {
                failure(Error.INVALID_URL)
            } else {
                BuildResult(validated.toASCIIString())
            }
        } catch (_: Exception) {
            failure(Error.INVALID_URL)
        }
    }

    private fun failure(error: Error) = BuildResult(url = "", error = error)

    private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val SUPPORTED_SCHEMES = setOf("ws", "wss")
}
