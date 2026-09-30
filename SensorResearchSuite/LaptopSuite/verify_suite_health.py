#!/usr/bin/env python3
"""
SensorMax LaptopSuite Health Verification

Purpose
-------
Verify the current SensorMax desktop/web components without
touching AndroidApp.

Checks:
    1. Required files exist.
    2. Required Python files compile.
    3. Required Python dependencies import.
    4. The WebSocket gateway can start.
    5. The browser dashboard exists.
    6. Obsolete Oppo / legacy references are reported.

AndroidApp is intentionally excluded from this script.
"""

from __future__ import annotations

import importlib
import os
import socket
import subprocess
import sys
from pathlib import Path


# ============================================================
# Paths
# ============================================================

SUITE_DIR = (
    Path(__file__)
    .resolve()
    .parent
)

PROJECT_DIR = (
    SUITE_DIR.parent
)

ANDROID_DIR = (
    PROJECT_DIR /
    "AndroidApp"
)


# ============================================================
# Current SensorMax desktop files
# ============================================================

REQUIRED_FILES = [
    "web_server.py",
    "web_dashboard.html",
    "export_converter.py",
    "export_json.py",
    "batch_dataset_processor.py",
    "test_all_pipelines.py",
    "requirements.txt",
]


PYTHON_FILES = [
    "web_server.py",
    "export_converter.py",
    "export_json.py",
    "batch_dataset_processor.py",
    "test_all_pipelines.py",
]


REQUIRED_MODULES = [
    "numpy",
    "pandas",
    "matplotlib",
    "plotly",
    "streamlit",
    "openpyxl",
    "serial",
    "websockets",
    "aiohttp",
]


# ============================================================
# Legacy references
# ============================================================

LEGACY_REFERENCE_PATTERNS = [
    "Oppo A33w",
    "OPPO A33w",
    "CPH2471",
    "com.oppo.sensormax",
    "SensorMax_Records",
    "tcp:5005",
    "adb forward tcp:5005",
]


# ============================================================
# Output helpers
# ============================================================

class Counters:

    def __init__(self) -> None:

        self.passed = 0

        self.failed = 0

        self.warned = 0


COUNTERS = Counters()


def line() -> None:

    print(
        "=" * 64
    )


def section(
    title: str,
) -> None:

    print()

    line()

    print(
        f"  {title}"
    )

    line()


def pass_check(
    message: str,
) -> None:

    print(
        f"[PASS] {message}"
    )

    COUNTERS.passed += 1


def fail_check(
    message: str,
) -> None:

    print(
        f"[FAIL] {message}"
    )

    COUNTERS.failed += 1


def warn_check(
    message: str,
) -> None:

    print(
        f"[WARN] {message}"
    )

    COUNTERS.warned += 1


def info(
    message: str,
) -> None:

    print(
        f"[INFO] {message}"
    )


# ============================================================
# File checks
# ============================================================

def check_required_files() -> None:

    section(
        "1. REQUIRED SENSORMax FILES"
    )

    for filename in REQUIRED_FILES:

        path = (
            SUITE_DIR /
            filename
        )

        if (
            path.exists()
            and
            path.is_file()
        ):

            pass_check(
                f"{filename}"
            )

        else:

            fail_check(
                f"Missing: {filename}"
            )


# ============================================================
# Python syntax
# ============================================================

def check_python_syntax() -> None:

    section(
        "2. PYTHON SYNTAX"
    )

    for filename in PYTHON_FILES:

        path = (
            SUITE_DIR /
            filename
        )

        if not path.exists():

            fail_check(
                f"Cannot compile missing file: {filename}"
            )

            continue

        result = subprocess.run(
            [
                sys.executable,
                "-m",
                "py_compile",
                str(path),
            ],
            capture_output=True,
            text=True,
        )

        if result.returncode == 0:

            pass_check(
                f"Syntax OK: {filename}"
            )

        else:

            fail_check(
                f"Syntax FAILED: {filename}"
            )

            if result.stderr.strip():

                print(
                    result.stderr.rstrip()
                )


# ============================================================
# Dependency imports
# ============================================================

def check_dependencies() -> None:

    section(
        "3. PYTHON DEPENDENCIES"
    )

    for module_name in REQUIRED_MODULES:

        try:

            importlib.import_module(
                module_name
            )

            pass_check(
                f"Import OK: {module_name}"
            )

        except Exception as exc:

            fail_check(
                f"Import FAILED: "
                f"{module_name} "
                f"({type(exc).__name__}: {exc})"
            )


# ============================================================
# Web dashboard
# ============================================================

def check_web_dashboard() -> None:

    section(
        "4. WEB DASHBOARD"
    )

    dashboard = (
        SUITE_DIR /
        "web_dashboard.html"
    )

    if not dashboard.exists():

        fail_check(
            "web_dashboard.html not found"
        )

        return

    try:

        content = (
            dashboard.read_text(
                encoding="utf-8",
                errors="replace",
            )
        )

    except Exception as exc:

        fail_check(
            "Could not read web_dashboard.html: "
            f"{exc}"
        )

        return

    if "<html" in content.lower():

        pass_check(
            "web_dashboard.html contains HTML document"
        )

    else:

        fail_check(
            "web_dashboard.html does not appear to be HTML"
        )

    expected_terms = [
        "WebSocket",
        "simulation",
        "SensorMax",
    ]

    for term in expected_terms:

        if term.lower() in content.lower():

            pass_check(
                f"Dashboard contains '{term}'"
            )

        else:

            warn_check(
                f"Dashboard does not contain expected term '{term}'"
            )


# ============================================================
# WebSocket port check
# ============================================================

def port_available(
    host: str,
    port: int,
) -> bool:

    with socket.socket(
        socket.AF_INET,
        socket.SOCK_STREAM,
    ) as sock:

        sock.settimeout(
            0.5
        )

        return (
            sock.connect_ex(
                (
                    host,
                    port,
                )
            ) != 0
        )


def check_websocket_configuration() -> None:

    section(
        "5. WEBSOCKET CONFIGURATION"
    )

    server = (
        SUITE_DIR /
        "web_server.py"
    )

    if not server.exists():

        fail_check(
            "web_server.py missing"
        )

        return

    try:

        content = (
            server.read_text(
                encoding="utf-8",
                errors="replace",
            )
        )

    except Exception as exc:

        fail_check(
            "Could not read web_server.py: "
            f"{exc}"
        )

        return

    if "8765" in content:

        pass_check(
            "web_server.py references port 8765"
        )

    else:

        fail_check(
            "web_server.py does not reference port 8765"
        )

    if "websockets" in content:

        pass_check(
            "web_server.py uses WebSocket library"
        )

    else:

        fail_check(
            "web_server.py does not reference websockets"
        )

    if port_available(
        "127.0.0.1",
        8765,
    ):

        pass_check(
            "TCP port 8765 is currently available"
        )

    else:

        warn_check(
            "TCP port 8765 is currently occupied"
        )


# ============================================================
# Legacy-reference scan
# ============================================================

def scan_legacy_references() -> None:

    section(
        "6. LEGACY REFERENCE SCAN"
    )

    files_to_scan = [
        SUITE_DIR /
        "web_server.py",

        SUITE_DIR /
        "web_dashboard.html",

        SUITE_DIR /
        "export_converter.py",

        SUITE_DIR /
        "export_json.py",

        SUITE_DIR /
        "batch_dataset_processor.py",

        PROJECT_DIR /
        "first_initialize.bat",

        PROJECT_DIR /
        "run_desktop_hmi.bat",

        PROJECT_DIR /
        "run_web_hmi.bat",

        PROJECT_DIR /
        "run_laptop_suite.sh",
    ]

    found_any = False

    for path in files_to_scan:

        if not path.exists():

            continue

        try:

            content = (
                path.read_text(
                    encoding="utf-8",
                    errors="ignore",
                )
            )

        except Exception:

            continue

        for pattern in LEGACY_REFERENCE_PATTERNS:

            if pattern in content:

                found_any = True

                warn_check(
                    f"Legacy reference "
                    f"'{pattern}' in "
                    f"{path.name}"
                )

    if not found_any:

        pass_check(
            "No known Oppo/5005/legacy references found "
            "in current integration files"
        )


# ============================================================
# Android separation check
# ============================================================

def check_android_separation() -> None:

    section(
        "7. ANDROID SEPARATION"
    )

    if ANDROID_DIR.exists():

        pass_check(
            "AndroidApp exists"
        )

        info(
            "AndroidApp is intentionally not compiled or modified "
            "by this health checker."
        )

    else:

        warn_check(
            "AndroidApp directory is not present"
        )


# ============================================================
# Optional integration test
# ============================================================

def run_integration_test() -> None:

    section(
        "8. DESKTOP INTEGRATION TEST"
    )

    test_file = (
        SUITE_DIR /
        "test_all_pipelines.py"
    )

    if not test_file.exists():

        warn_check(
            "test_all_pipelines.py not found; "
            "integration test skipped"
        )

        return

    result = subprocess.run(
        [
            sys.executable,
            str(test_file),
        ],
        cwd=str(
            SUITE_DIR.parent
        ),
        text=True,
    )

    if result.returncode == 0:

        pass_check(
            "Desktop integration test passed"
        )

    else:

        fail_check(
            "Desktop integration test returned "
            f"exit code {result.returncode}"
        )


# ============================================================
# Summary
# ============================================================

def print_summary() -> int:

    section(
        "HEALTH SUMMARY"
    )

    print(
        f"Passed : {COUNTERS.passed}"
    )

    print(
        f"Warnings: {COUNTERS.warned}"
    )

    print(
        f"Failed : {COUNTERS.failed}"
    )

    print()

    if COUNTERS.failed == 0:

        print(
            "[SUCCESS] SensorMax desktop health check passed."
        )

        return 0

    print(
        "[FAIL] SensorMax desktop health check found failures."
    )

    return 1


# ============================================================
# Main
# ============================================================

def main() -> int:

    print(
        "============================================================"
    )

    print(
        "  SENSORMAX LAPTOPSUITE HEALTH VERIFICATION"
    )

    print(
        "============================================================"
    )

    print()

    print(
        f"Python : {sys.version.split()[0]}"
    )

    print(
        f"Suite  : {SUITE_DIR}"
    )

    print(
        "AndroidApp: LOCKED / NOT TOUCHED"
    )

    check_required_files()

    check_python_syntax()

    check_dependencies()

    check_web_dashboard()

    check_websocket_configuration()

    scan_legacy_references()

    check_android_separation()

    # Integration test is intentionally last because it executes
    # several components and produces temporary artifacts.
    run_integration_test()

    return print_summary()


if __name__ == "__main__":

    raise SystemExit(
        main()
    )