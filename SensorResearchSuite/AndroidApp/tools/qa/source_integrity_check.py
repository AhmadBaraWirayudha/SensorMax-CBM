#!/usr/bin/env python3
"""Fast source-integrity gate for merge regressions in SensorMax.

Checks are intentionally syntax-light so they can run before Gradle/Android SDK:
- duplicate top-level Kotlin declarations
- duplicate private lateinit property names within a Kotlin file
- duplicate/legacy experiment buffer runtime model
- authoritative telemetry/grid declarations
"""
from __future__ import annotations

import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app" / "src" / "main" / "java"
PACKAGE = SRC / "com" / "research" / "sensormax"

TOP_LEVEL = re.compile(r"^\s*(class|data\s+class|enum\s+class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)")
LATEINIT = re.compile(r"^\s*private\s+lateinit\s+var\s+([A-Za-z_][A-Za-z0-9_]*)")


def main() -> int:
    errors: list[str] = []
    declarations: defaultdict[str, list[str]] = defaultdict(list)

    for path in sorted(SRC.rglob("*.kt")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        for line_no, line in enumerate(text.splitlines(), start=1):
            match = TOP_LEVEL.match(line)
            if match:
                declarations[match.group(2)].append(f"{path.relative_to(ROOT)}:{line_no}")

        counts = Counter(m.group(1) for m in LATEINIT.finditer(text, re.MULTILINE))
        for name, count in counts.items():
            if count > 1:
                errors.append(f"duplicate private lateinit {name} x{count} in {path.relative_to(ROOT)}")

    for name, locations in sorted(declarations.items()):
        if len(locations) > 1:
            errors.append(f"duplicate top-level declaration {name}: {', '.join(locations)}")

    legacy_buffer = PACKAGE / "PhyphoxBufferModel.kt"
    if legacy_buffer.exists():
        legacy_text = legacy_buffer.read_text(encoding="utf-8", errors="ignore")
        duplicate_symbols = (
            "class SignalBuffer",
            "enum class AnalysisInputPolicy",
            "data class AnalysisInput",
            "class AnalysisGate",
            "class ExperimentClock",
            "data class ClockEvent",
        )
        if any(symbol in legacy_text for symbol in duplicate_symbols):
            errors.append(f"duplicate legacy runtime model exists: {legacy_buffer.relative_to(ROOT)}")

    experiment_buffer = PACKAGE / "ExperimentBufferModel.kt"
    if not experiment_buffer.exists():
        errors.append("authoritative ExperimentBufferModel.kt is missing")

    old_parser = PACKAGE / "ExperimentFileCompatibility.kt"
    sensor_max_parser = PACKAGE / "SensorMaxExperimentFile.kt"
    if old_parser.exists():
        errors.append("stale project-facing ExperimentFileCompatibility.kt exists; use SensorMaxExperimentFile.kt")
    if not sensor_max_parser.exists():
        errors.append("authoritative SensorMaxExperimentFile.kt is missing")

    main_activity = PACKAGE / "MainActivity.kt"
    if main_activity.exists():
        text = main_activity.read_text(encoding="utf-8", errors="ignore")
        table_count = len(re.findall(r"private\s+lateinit\s+var\s+gridSecondaryTelemetry:\s*TableLayout", text))
        view_count = len(re.findall(r"private\s+lateinit\s+var\s+gridSecondaryTelemetry:\s*View", text))
        if table_count != 1 or view_count != 0:
            errors.append(
                "gridSecondaryTelemetry contract violation: "
                f"TableLayout={table_count}, View={view_count}; expected TableLayout=1, View=0"
            )
        for helper in ("toggleUIState", "haltDeployment", "resetDeploymentUiState"):
            if not re.search(r"\bprivate\s+fun\s+" + re.escape(helper) + r"\s*\(", text):
                errors.append(f"MainActivity is missing deployment UI helper {helper}")
        if "restoreActiveIntervalFromEngine(engine)" not in text:
            errors.append("MainActivity must restore the running engine interval after Activity recreation")
        if "deploymentController.refreshNotificationLocale(" not in text:
            errors.append("MainActivity must refresh foreground-notification locale after service rebind")

    service = PACKAGE / "SensorCaptureService.kt"
    if service.exists():
        service_text = service.read_text(encoding="utf-8", errors="ignore")
        if not re.search(r"var\s+isStreamingDeployment:\s*Boolean", service_text):
            errors.append("SensorCaptureService must retain the active stream/offline deployment mode")

    controller = PACKAGE / "MeasurementDeploymentController.kt"
    if controller.exists():
        controller_text = controller.read_text(encoding="utf-8", errors="ignore")
        if "val isStreamingDeployment" not in controller_text:
            errors.append("MeasurementDeploymentController must expose the active stream/offline mode")

    if errors:
        print("SOURCE_INTEGRITY_FAIL")
        for error in errors:
            print(f"- {error}")
        return 1

    print("SOURCE_INTEGRITY_PASS")
    print(f"Scanned {sum(1 for _ in SRC.rglob('*.kt'))} production Kotlin files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
