# SensorMax Research Suite Roadmap

This is a risk-ordered engineering backlog, not a release commitment. No target dates are assigned. Items remain open until the stated acceptance evidence is attached to a reviewed change.

## P0 — Public repository readiness

- [ ] **Resolve licensing and provenance.** Select a project license only after ownership review. Identify upstream origin and applicable terms for adapted experiment descriptors and bundled third-party material; retain required notices.
  - Acceptance: a reviewed license decision, required notices and attribution are present; no unreviewed third-party source/APK bundles are included.
- [ ] **Complete repository sanitation.** Review source/configuration for credentials, private paths, personal data, measurement logs, generated files and build artifacts. Run a secrets scan after creating the actual Git repository and inspect commit history before public release.
  - Acceptance: final tracked-file inventory is reviewed; no known live credentials or local-only artifacts remain; any exposed credential is rotated rather than merely deleted.
- [ ] **Make the release statement evidence-based.** Keep production and security claims blocked until P1–P3 gates pass.
  - Acceptance: README, security notes, release notes and actual test evidence agree.

## P1 — Reproducible installation and build evidence

- [ ] **Create a complete Python dependency lock.** Resolve a transitive lock with hashes for the supported Python/platform profile from a clean network-enabled environment.
  - Acceptance: install the lock in a new virtual environment, run `pip check`, direct-pin verification, focused tests and the full desktop integration pipeline; record Python/OS details.
- [ ] **Verify Android builds from a clean checkout.** Confirm the Gradle wrapper, Android SDK/JDK requirements, manifest merging, JVM/unit tests and packaging on a documented build host.
  - Acceptance: clean build and test logs; output APK provenance and signing approach documented; no local `local.properties` required in source control.
- [ ] **Validate end-to-end data contracts.** Replay a consented, sanitized Android-generated capture through the gateway, dashboard, analysis and export path.
  - Acceptance: raw and analysis schemas, channel identities, timestamps, units, missing values and export contents are checked against a versioned fixture.

## P2 — Network security

- [ ] **Keep current use local-only.** Do not enable insecure LAN mode for ordinary use; do not expose the gateway to the public Internet.
- [ ] **Design authenticated encrypted transport before remote access.** Define TLS/WSS, client authentication, authorization, certificate/key rotation, secure configuration, input limits, abuse/rate controls and secret-safe logging.
- [ ] **Secure or disable the raw TCP/NDJSON listener for remote profiles.** Android cleartext manifest settings do not protect raw sockets.
  - Acceptance: negative and positive tests cover unauthenticated clients, invalid certificates/credentials, unauthorized actions, malformed/oversized packets, disallowed interfaces and reconnect/error behavior. Tests run against the actual Android and desktop builds.

## P3 — Measurement integrity and engineering validation

- [ ] **Formalize the measurement contract.** Version the schema and document timestamps, sample-rate estimation, units, device/source/channel identifiers, unavailable channels and analysis snapshot semantics.
- [ ] **Harden quality reporting.** Test gaps, duplicate/backward timestamps, clipping, saturation, irregular sampling, channel mismatch, empty inputs and corrupt files.
- [ ] **Validate calculations against references.** Use known test signals and appropriately calibrated reference instruments to evaluate waveform scaling, FFT frequency/amplitude, filtering, RMS/velocity/displacement derivation and calibration procedures.
- [ ] **Keep indicators bounded.** Document thresholds and their context; do not label an FFT peak, envelope peak, ISO metadata field, or heuristic alarm as a validated fault diagnosis.
  - Acceptance: test datasets, expected outputs, measurement uncertainty and known limitations are recorded; synthetic values are explicitly labelled.

## P4 — Usability and reporting

- [ ] **Improve first-run diagnostics.** Make missing dependencies, unavailable ports, ADB authorization, Android connection, malformed data and output locations explicit.
- [ ] **Preserve traceability in exports.** Carry source file, session ID, schema/version, machine/point, units, sampling assumptions and processing parameters into reports where available.
- [ ] **Validate desktop platforms.** Define the officially supported OS/runtime combinations and test launchers, file handling, fonts/locales and dependency installation on each one.

## P5 — Versioned public release

- [ ] **Prepare a release candidate only after the gates above pass.** Review all tracked files, licenses, security posture and release notes from a clean clone.
- [ ] **Publish artifacts separately from source.** Attach versioned APKs to a GitHub Release rather than committing build outputs; provide provenance, checksums, signing details and sanitized screenshots.
- [ ] **Publish measured status, not assumptions.** List supported devices/runtimes, known issues, exact test evidence, security limitations and rollback/recovery steps.

## Current release gate

**NO-GO for production use, public Internet exposure, and untrusted-network operation.** Clean Android build, physical-device acceptance, end-to-end capture replay, complete dependency locking, and authenticated/encrypted transport remain unverified or incomplete in the supplied candidate.
