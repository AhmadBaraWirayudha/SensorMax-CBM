# Security Notes — SensorResearchSuite

## Status and intended use

This document describes the supplied development candidate. It is **not a security certification**, penetration-test report, or proof that all attack paths have been audited. The supported operating posture is a local, isolated development setup using a trusted workstation and an explicitly connected Android test device.

**Current release decision: NO-GO for production use, public Internet exposure, or untrusted-network operation.**

## Known transport limitations

- The desktop WebSocket gateway currently serves plaintext `ws://` and has **no client authentication**. Its default bind target is `127.0.0.1`; loopback reduces accidental exposure but does not authenticate the protocol.
- Browser `Origin` validation is a browser-origin control, not a substitute for client authentication. Native clients can omit an Origin header.
- The Android TCP/NDJSON acquisition path is not protected by TLS. Android's `usesCleartextTraffic` setting does not automatically secure raw `Socket` or `ServerSocket` traffic.
- An explicit insecure-LAN option is a constrained development exception, not a production deployment mode.
- Do not treat a private address, firewall rule, ADB reverse forwarding, or UI confirmation as a replacement for authenticated, encrypted transport. Never expose it to the Internet.

Before remote/LAN support can be considered complete, define and implement a deployment model with TLS/WSS, client authentication, authorization where applicable, safe key/certificate handling and rotation, input-size limits, rate/abuse controls, secret-safe logging, and end-to-end tests against actual Android and desktop builds. Until those controls exist, keep the gateway local-only and do not enable insecure LAN mode on untrusted networks.

## Source, secrets, and local data

- Do not commit API tokens, passwords, private keys, signing materials, `.env` values, local SDK configuration, machine-specific workspace files, IDE caches, generated builds, or device-specific state.
- Treat `LaptopSuite/Imported_Records/`, `LaptopSuite/Exported_Analysis/`, alarm logs, test outputs, screenshots, and exported reports as potentially sensitive. Measurement metadata may identify a machine, process, location, operator, or customer.
- Review archives, staged files, screenshots, HTML metadata, notebooks, test fixtures, and generated files before sharing. Ignore rules help prevent future accidental tracking but do not remove already tracked files or sanitize an existing ZIP.
- The supplied ZIP did not include Git history. Historical-secret review, commit-history review, and base-to-head diff review have not been established. If a live credential is found, revoke/rotate it using the authorized provider and investigate possible use; deleting the string alone is insufficient.
- Before publishing a repository, review both the working tree and the index (staged files). After repository initialization, scan all reachable Git history as a separate task.

## Dependencies and third-party material

- `LaptopSuite/requirements.txt` specifies minimum compatible versions; it is **not a lockfile**. `requirements-direct-pins-py313.txt` pins direct dependencies only. A complete hash-locked transitive dependency graph and clean-lock installation have not been verified.
- The project includes adapted experiment descriptors whose upstream provenance and redistribution obligations must be reviewed before public release. Do not re-add unreviewed reference archives, extracted APK/DEX files, or reverse-engineered source bundles.
- No project `LICENSE` file is included in this candidate. Do not infer a public license or permission to redistribute third-party material from its absence. Resolve ownership, project licensing, attribution, and third-party terms before publication.

## Reporting a finding

Do not post secrets, exploit payloads containing real private data, or sensitive measurement records in a public issue. Share a concise reproduction, affected component/version, impact, and redacted logs through the repository owner's private reporting channel. No dedicated security-reporting email or formal bounty process is established by this candidate; do not assume one exists.

## Release gates

A release should remain blocked until the following are evidenced:

1. Clean dependency installation and reviewed dependency provenance.
2. Clean Android build, manifest inspection, and tests from a clean checkout.
3. End-to-end validation against a sanitized real capture and versioned fixtures.
4. A decision and required notices for project and third-party licenses.
5. Repository and history secret scans, including review of staged and generated files.
6. Authenticated/encrypted transport for any supported remote profile; otherwise document and enforce local-only operation.
7. Physical-device acceptance, privacy review, and tested failure/recovery behavior.

The existence of these notes does not resolve these findings. Keep the status **NO-GO** until evidence supports a different decision.
