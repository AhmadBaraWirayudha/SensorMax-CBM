# SensorResearchSuite Network Security Policy

**Current operating mode: local-only by default. Overall product release: NO-GO.** This policy describes the supplied development candidate; it does not certify implementation or device behavior.

## 1. Desktop WebSocket gateway

- Default bind: `127.0.0.1:8765`. Keep `SENSORMAX_LISTEN_HOST` unset for local development.
- The gateway uses plaintext `ws://` and does not authenticate native clients. Browser `Origin` checks are cross-origin controls, **not authentication**.
- The optional insecure-LAN mode requires both `SENSORMAX_LISTEN_HOST` set to a specific private interface IP and `SENSORMAX_ALLOW_INSECURE_LAN=1`. The implementation is intended to reject wildcard binds, public IPs, link-local IPs, and hostnames.
- This opt-in only constrains accidental exposure; it does not encrypt traffic, authenticate clients, or make the service safe on an untrusted network. Never expose the gateway directly to the Internet.
- Keep the gateway on loopback and use ADB reverse forwarding for the local USB development workflow where supported.

## 2. Android WebSocket client

- Source policy states that release builds must use `wss://`; `WebSocketEndpointBuilder` is intended to reject `ws://` in release mode.
- The main/release manifest sets `android:usesCleartextTraffic="false"`. The debug manifest alone allows cleartext for local development against `ws://127.0.0.1:8765`.
- These source-level controls require clean build and merged-manifest verification. Do not treat the manifest flag as a security control for arbitrary raw TCP sockets.

## 3. Android TCP/NDJSON listener

- `NetworkAcquisitionProfile` defaults to `127.0.0.1`; LAN access must remain unchecked by default.
- The explicit LAN option can bind broadly and displays a warning that this listener has no TLS or authentication. Treat it as a controlled laboratory exception only, not as a secure remote-access feature.
- Do not use this listener on public, shared, customer, or otherwise untrusted networks. Prefer disabling it when no local test requires it.
- A production or untrusted-network mode requires TLS plus a defined authentication and access-control mechanism, input limits, and integration/device testing; otherwise the remote profile must remain unavailable.

## 4. Required policy for current use

1. Keep the desktop gateway loopback-only.
2. Keep Android LAN/network listening disabled unless an isolated test explicitly requires it.
3. Do not expose ports via router forwarding, public tunnels, or a cloud VM.
4. Do not place secrets or sensitive measurement records in URLs, query strings, public logs, screenshots, issues, or test fixtures.
5. Stop the gateway and disconnect the test device when the session ends. Share only sanitized synthetic or approved records.

## 5. Requirements before remote networking is approved

- TLS/WSS with reviewed certificate validation and a defined key/certificate lifecycle.
- Client authentication and authorization, including rejection tests for anonymous and unauthorized clients.
- Secure defaults and explicit configuration validation; no wildcard/public bind in an insecure profile.
- Payload size limits, bounded queues, connection/rate limits, robust parsing, safe error behavior, and logging that excludes credentials and sensitive payloads.
- Tests for malformed JSON/NDJSON, oversized packets, repeated connections, invalid credentials, certificate failures, port conflicts, disallowed addresses, and interrupted/restarted connections.
- End-to-end verification on the actual Android and desktop builds and supported devices/OSes.
- A reviewed deployment threat model and user-facing security documentation.

## 6. Evidence and open work

- Static and Python regression tests cover selected loopback defaults, private-interface constraints, and source manifest policy. Such tests are not a substitute for runtime testing.
- Clean Android Gradle build, merged-manifest inspection, actual device networking, TLS/authentication, and Android-to-desktop end-to-end checks remain **UNVERIFIED**.
- The current candidate remains **NO-GO for public release, production use, or untrusted-network operation**.
