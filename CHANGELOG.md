# Changelog

This file records documented changes to the SensorMax Research Suite. The supplied ZIP did not include Git history, so changes predating this changelog cannot be reconstructed reliably. No release version or production acceptance is implied.

## Unreleased — 2026-10-11

### Documentation and repository hygiene

- Updated `README.md` to describe the actual project layout and distinguish documented behavior from release validation.
- Expanded the operating tutorial with the intended CPython 3.13 environment, verification commands, raw-data handling, network limits, and troubleshooting evidence.
- Added this changelog and a risk-ordered roadmap without promising target dates.
- Rewrote security documentation to make the plaintext unauthenticated WebSocket gateway and unprotected TCP/NDJSON path explicit.
- Aligned the compatibility-range dependency floors with the versions in the current direct-pin profile. `requirements.txt` remains a range declaration, not a complete transitive lock. Hardened `.gitignore` for IDE state, signing-key files, generated data, and excluded third-party bundles.
- Removed local build/IDE state and unreviewed bundled third-party source/reverse-engineering archives from this GitHub-candidate package; retained application source and experiment descriptors requiring provenance review.
- Renamed the dashboard file to the canonical `web_dashboard.html` path used by the launchers and health checks, changed its default WebSocket endpoint to match the loopback gateway (`ws://127.0.0.1:8765`), and renamed the standalone demo to `sensormax_demo.html`; the filenames no longer contain personal-name fragments. Updated the dashboard regression harness to stub the runtime state/functions used by the null-channel test.

- Updated CI path filters to trigger on the maintained public documentation and dependency declarations rather than removed private handoff files.

### Release caveats

- Documentation edits are not evidence of a clean Android build, device acceptance, complete desktop integration, diagnostic accuracy, transport security, or reproducible installation.
- No project `LICENSE` file is included. Review project ownership, third-party provenance, and license obligations before public distribution.
- Do not expose the current plaintext/unauthenticated gateway or TCP/NDJSON listener to untrusted networks or the public Internet.

## Initial candidate baseline (date of prior changes unknown)

The supplied source archive describes an Android acquisition app and Python desktop tools for WebSocket streaming, local CSV persistence, waveform/spectrum viewing, data-quality inspection, offline analysis, and report/export workflows. This section records the declared scope only; it is not a certification that every feature has passed acceptance testing.
