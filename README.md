# SensorResearchSuite

SensorResearchSuite is a local engineering/research toolchain built around the SensorMax Android acquisition app, Python desktop tools, a WebSocket gateway, and a browser dashboard. It is not a hosted or multi-tenant SaaS.

> **Development-candidate status:** this is not yet a product-accepted release. Physical Android-device acceptance remain open. The current tests cover selected source, contract and fixture behaviors; they do not validate every workflow, hardware provider, or operating condition. Do not claim validated fault diagnosis based on this candidate alone.

Use [`TUTORIAL.md`](TUTORIAL.md) for the operating procedure, [`CHANGELOG.md`](CHANGELOG.md) for documented changes, and [`ROADMAP.md`](ROADMAP.md) for outstanding acceptance work.

## Project layout

```text
SensorResearchSuite/
├── AndroidApp/                    # Android Studio / Gradle / Kotlin app
├── LaptopSuite/                   # Python gateway, analysis and export tools
│   ├── web_server.py              # WebSocket gateway and CSV persistence
│   ├── export_converter.py        # CSV-to-XLSX exporter
│   ├── export_json.py             # JSON export
│   ├── batch_dataset_processor.py
│   ├── test_all_pipelines.py      # Desktop integration pipeline
│   ├── verify_suite_health.py    # Bounded desktop health checks
│   ├── requirements.txt           # Compatibility ranges, not a lockfile
│   └── requirements-direct-pins-py313.txt
├── web_dashboard.html             # Browser dashboard at project root
├── sensormax_demo.html            # Standalone demo page
├── first_initialize.bat           # Windows Python setup
├── run_web_hmi.bat                # Gateway/dashboard launcher
├── run_desktop_hmi.bat            # Desktop HMI wrapper
├── run_laptop_suite.sh            # Bash launcher
├── import_and_process.bat         # ADB import/offline processing workflow
├── SECURITY.md                    # Security limitations and reporting guidance
└── NETWORK_SECURITY_POLICY.md     # Required network operating policy
```

The actual paths are authoritative. In particular, `web_dashboard.html` is in this project root, not inside `LaptopSuite/`.

## Architecture

```text
SensorMax Android app
       │ WebSocket packets (local development workflow)
       ▼
LaptopSuite/web_server.py ──► live raw/analysis CSV records
       │
       ├──► web_dashboard.html
       └──► offline analysis and export tools
                  ├── XLSX
                  ├── JSON
                  └── tool-specific outputs
```

The desktop gateway defaults to `127.0.0.1:8765`. It currently uses plaintext `ws://` and has no client authentication. The Android app also contains a separate TCP/NDJSON acquisition path that is not protected by TLS. Network exposure is not a secure remote-access feature. Read `SECURITY.md` and `NETWORK_SECURITY_POLICY.md` before starting live acquisition; keep the current workflow local-only.

## Requirements and setup

### Desktop/web suite

- Configured runtime baseline: **CPython 3.13.x** for the current direct-pin profile.
- `LaptopSuite/requirements.txt` contains compatibility ranges; it is **NOT a lockfile**.
- `LaptopSuite/requirements-direct-pins-py313.txt` pins the nine direct dependencies, but does not lock the complete transitive dependency graph or its hashes.
- A full transitive lock is still outstanding. A clean lock-based installation and a full end-to-end run have not been verified in the candidate package. Previous checks in the review environment reported missing `streamlit` and `pyserial` imports; installation should be revalidated on a clean supported machine.

On Windows, run from this project root:

```bat
first_initialize.bat
```

The initializer creates `sensormax_env`, installs the direct-pinned profile, checks imports, and runs setup checks. For manual commands in a new Command Prompt, activate that environment first:

```bat
call sensormax_env\Scripts\activate.bat
python LaptopSuite\verify_environment.py --check-pins
python LaptopSuite\verify_suite_health.py --skip-integration
```

The `--skip-integration` option deliberately skips the full integration pipeline. Run it separately when dependencies and environment prerequisites are available:

```bat
python LaptopSuite\test_all_pipelines.py
```

On Linux/macOS, follow [`LaptopSuite/PYTHON_ENVIRONMENT.md`](LaptopSuite/PYTHON_ENVIRONMENT.md) and use the supported Python environment before running `bash run_laptop_suite.sh`.

Do not treat successful launcher startup or a bounded health check as evidence that every runtime dependency, hardware source, or measurement workflow has been validated.

### Android app

Open `AndroidApp/` in Android Studio. The project declares Gradle wrapper 8.6, Android Gradle Plugin 8.4.2, Kotlin Android plugin 2.1.20, compile/target SDK 34, min SDK 24, and Java/Kotlin JVM target 21. A clean Gradle build and JVM test run have not been demonstrated in the review environment; the declared versions are not proof of build success.

## Verification commands

Run from this project root using the intended virtual environment:

```bash
# Python syntax check
python -m compileall -q LaptopSuite

# Focused regression tests
python -m unittest \
  LaptopSuite.test_sprint3_measurement_contract \
  LaptopSuite.test_sprint31_regressions \
  LaptopSuite.test_sprint32_gateway_hardening \
  LaptopSuite.test_sprint35_transport_policy \
  LaptopSuite.test_python_environment \
  LaptopSuite.test_sprint33_release_docs \
  LaptopSuite.test_sprint3_integration_acceptance -v

# Android source/resource checks
python AndroidApp/tools/qa/resource_integrity_check.py
python AndroidApp/tools/qa/source_integrity_check.py

# Bounded health check; this does not replace the integration pipeline
python LaptopSuite/verify_suite_health.py --skip-integration

# Full desktop pipeline
python LaptopSuite/test_all_pipelines.py
```

Interpret results honestly: a blocked package download, missing dependency, unavailable Android SDK/device, skipped test, or `--skip-integration` run is not a full pass. Record verified environment details and test output rather than assuming successful validation.

## Measurement and demo-data cautions

- Keep unavailable sensor channels distinct from a measured zero.
- Preserve sensor identity, source/channel identifiers, timestamps, units, and schema/version context through gateway and export paths.
- Preserve original raw files. Filtering and normalization should write derived files rather than overwrite source data.
- Synthetic generator outputs and browser-demo values are for development/testing, not evidence of measurement accuracy.
- Tools under `LaptopSuite/WIP/` are experimental and have not been product-validated.
- Treat spectrum/fault labels, alarms, RMS thresholds, ISO metadata, and bearing-match outputs as analysis aids, not validated diagnoses or maintenance prescriptions.

## Security, privacy, and licensing

- Use local-only workflow unless a reviewed and explicitly approved network model exists.
- The current plaintext unauthenticated WebSocket gateway and the TCP/NDJSON path without TLS must not be exposed to an untrusted network or the public Internet.
- Store real measurement data and logs locally; review and sanitize records and screenshots before sharing.
- Keep `.env` files, credentials, private keys, local SDK paths, generated outputs, caches, build artifacts, and machine-specific workspace state out of source control and release packages.
- The supplied candidate ZIP had no Git history, so historical-secret review and full commit-history review are unverified.
- No project `LICENSE` file was present in the reviewed candidate. No license or permission to redistribute third-party reference material is asserted here. Resolve project licensing and third-party provenance before public distribution.

## Project status and release gate

This repository is a development candidate, not a production release. Clean buildability, replay of a real Android-generated capture through the data contract, locale validation, full desktop integration, transport security, dependency locking, package provenance, backups/recovery, and physical Android-device acceptance remain open. See [`ROADMAP.md`](ROADMAP.md) for the proposed release work sequence and [`CHANGELOG.md`](CHANGELOG.md) for documented changes.
