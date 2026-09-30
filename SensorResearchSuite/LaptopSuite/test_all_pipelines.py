#!/usr/bin/env python3
"""
SensorMax Desktop Integration Test

Purpose
-------
Validate the current SensorMax desktop/web architecture without
modifying AndroidApp.

Test chain
----------

    Synthetic SensorMax RAW CSV
              |
              +----> export_converter.py
              |          |
              |          +----> XLSX
              |
              +----> export_json.py
              |          |
              |          +----> JSON
              |
              +----> web_server.py
                         |
                         +----> WebSocket startup

    Synthetic SensorMax ANALYSIS CSV
              |
              +----> batch_dataset_processor.py
                         |
                         +----> XLSX

This replaces the old walking/activity-recognition integration test.

The test intentionally uses the CURRENT Android CSV schema:

RAW
---
Timestamp_ms
Machine_ID
Point
Sensor_Type
Sensor_Name
Val_0
Val_1
Val_2
Val_3
Val_4
Val_5

ANALYSIS
--------
Timestamp_ms
Machine_ID
Point
Sample_Rate_Hz
RMS_X_ms2
RMS_Y_ms2
RMS_Z_ms2
Overall_RMS_Accel_ms2
Overall_RMS_Velocity_mms
Overall_RMS_Displacement_um
Dominant_Axis
Dominant_Freq_Hz
Dominant_Accel_Amplitude_ms2
Envelope_Peak_Freq_Hz
ISO20816_Zone
Bearing_Match
RPM_Input
Impact_Event
Snapshot_Triggered
"""

from __future__ import annotations

import asyncio
import csv
import json
import math
import os
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path


# ============================================================
# Paths
# ============================================================

SCRIPT_DIR = Path(
    __file__
).resolve().parent

TEST_DIR = (
    SCRIPT_DIR /
    "test_output" /
    "sensormax_integration"
)


# ============================================================
# Test configuration
# ============================================================

MACHINE_ID = "TEST-MOTOR-01"

POINT = "DE-BRG-X"

RPM = 1800.0

RUNNING_FREQUENCY_HZ = RPM / 60.0

SAMPLE_RATE_HZ = 100.0

RAW_DURATION_SECONDS = 5.0

RAW_SAMPLE_COUNT = int(
    SAMPLE_RATE_HZ *
    RAW_DURATION_SECONDS
)

ANALYSIS_WINDOW_COUNT = 20

WEBSOCKET_HOST = "127.0.0.1"

WEBSOCKET_PORT = 18765


# ============================================================
# Console formatting
# ============================================================

def banner(title: str) -> None:

    print()
    print("=" * 68)
    print(f"  {title}")
    print("=" * 68)


def info(message: str) -> None:

    print(
        f"[INFO] {message}"
    )


def passed(message: str) -> None:

    print(
        f"[PASS] {message}"
    )


def failed(message: str) -> None:

    print(
        f"[FAIL] {message}"
    )


# ============================================================
# Command execution
# ============================================================

def run_command(
    title: str,
    command: list[str],
    timeout: int = 60,
) -> bool:

    print()
    print(
        f"[TEST] {title}"
    )

    print(
        "       " +
        " ".join(
            f'"{item}"'
            if " " in item
            else item
            for item in command
        )
    )

    try:

        result = subprocess.run(
            command,
            cwd=str(SCRIPT_DIR.parent),
            capture_output=True,
            text=True,
            timeout=timeout,
        )

    except subprocess.TimeoutExpired:

        failed(
            f"{title} timed out after {timeout}s"
        )

        return False

    except Exception as exc:

        failed(
            f"{title} could not execute: "
            f"{type(exc).__name__}: {exc}"
        )

        return False

    if result.stdout.strip():

        print(
            result.stdout.rstrip()
        )

    if result.stderr.strip():

        print(
            result.stderr.rstrip()
        )

    if result.returncode == 0:

        passed(title)

        return True

    failed(
        f"{title} returned exit code "
        f"{result.returncode}"
    )

    return False


# ============================================================
# Directory preparation
# ============================================================

def prepare_test_directory() -> None:

    if TEST_DIR.exists():

        shutil.rmtree(
            TEST_DIR
        )

    TEST_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    info(
        f"Test directory: {TEST_DIR}"
    )


# ============================================================
# Generate current Android-compatible raw CSV
# ============================================================

def generate_raw_csv() -> Path:

    path = (
        TEST_DIR /
        "synthetic_sensormax_raw.csv"
    )

    header = [
        "Timestamp_ms",
        "Machine_ID",
        "Point",
        "Sensor_Type",
        "Sensor_Name",
        "Val_0",
        "Val_1",
        "Val_2",
        "Val_3",
        "Val_4",
        "Val_5",
    ]

    start_ms = (
        int(
            time.time() *
            1000
        )
    )

    with path.open(
        "w",
        newline="",
        encoding="utf-8",
    ) as file:

        writer = csv.writer(
            file
        )

        writer.writerow(
            header
        )

        for i in range(
            RAW_SAMPLE_COUNT
        ):

            t = (
                i /
                SAMPLE_RATE_HZ
            )

            ts = (
                start_ms +
                int(
                    t *
                    1000
                )
            )

            # ------------------------------------------------
            # Synthetic rotating machine signal.
            #
            # 1800 RPM = 30 Hz.
            #
            # X:
            #   strong 1X
            #   smaller 2X
            #
            # Y:
            #   gravity + weak 1X
            #
            # Z:
            #   weak 3X
            # ------------------------------------------------

            x = (
                0.60 *
                math.sin(
                    2 *
                    math.pi *
                    RUNNING_FREQUENCY_HZ *
                    t
                )
                +
                0.16 *
                math.sin(
                    2 *
                    math.pi *
                    (
                        RUNNING_FREQUENCY_HZ *
                        2
                    ) *
                    t
                )
                +
                0.02 *
                math.sin(
                    2 *
                    math.pi *
                    7 *
                    t
                )
            )

            y = (
                9.81
                +
                0.25 *
                math.sin(
                    2 *
                    math.pi *
                    RUNNING_FREQUENCY_HZ *
                    t +
                    0.4
                )
            )

            z = (
                0.12 *
                math.sin(
                    2 *
                    math.pi *
                    (
                        RUNNING_FREQUENCY_HZ *
                        3
                    ) *
                    t
                )
            )

            writer.writerow(
                [
                    ts,
                    MACHINE_ID,
                    POINT,
                    1,
                    "Synthetic Accelerometer",
                    f"{x:.8f}",
                    f"{y:.8f}",
                    f"{z:.8f}",
                    "0",
                    "0",
                    "0",
                ]
            )

    passed(
        f"Generated RAW fixture: "
        f"{path.name} "
        f"({RAW_SAMPLE_COUNT} records)"
    )

    return path


# ============================================================
# Generate current Android-compatible analysis CSV
# ============================================================

def generate_analysis_csv() -> Path:

    path = (
        TEST_DIR /
        "synthetic_sensormax_analysis.csv"
    )

    header = [
        "Timestamp_ms",
        "Machine_ID",
        "Point",
        "Sample_Rate_Hz",
        "RMS_X_ms2",
        "RMS_Y_ms2",
        "RMS_Z_ms2",
        "Overall_RMS_Accel_ms2",
        "Overall_RMS_Velocity_mms",
        "Overall_RMS_Displacement_um",
        "Dominant_Axis",
        "Dominant_Freq_Hz",
        "Dominant_Accel_Amplitude_ms2",
        "Envelope_Peak_Freq_Hz",
        "ISO20816_Zone",
        "Bearing_Match",
        "RPM_Input",
        "Impact_Event",
        "Snapshot_Triggered",
    ]

    start_ms = (
        int(
            time.time() *
            1000
        )
    )

    with path.open(
        "w",
        newline="",
        encoding="utf-8",
    ) as file:

        writer = csv.writer(
            file
        )

        writer.writerow(
            header
        )

        for i in range(
            ANALYSIS_WINDOW_COUNT
        ):

            ts = (
                start_ms +
                i *
                500
            )

            # Smooth trend for repeatability testing.
            rms_accel = (
                0.55 +
                0.01 *
                math.sin(
                    i /
                    3
                )
            )

            rms_velocity = (
                1.80 +
                0.05 *
                math.sin(
                    i /
                    4
                )
            )

            displacement = (
                rms_velocity *
                6.0
            )

            impact = (
                "true"
                if i == 11
                else "false"
            )

            snapshot = (
                "true"
                if i in (
                    5,
                    15,
                )
                else "false"
            )

            writer.writerow(
                [
                    ts,
                    MACHINE_ID,
                    POINT,
                    f"{SAMPLE_RATE_HZ:.2f}",
                    "0.40",
                    "0.20",
                    "0.10",
                    f"{rms_accel:.4f}",
                    f"{rms_velocity:.4f}",
                    f"{displacement:.4f}",
                    "X",
                    f"{RUNNING_FREQUENCY_HZ:.4f}",
                    "0.60",
                    f"{RUNNING_FREQUENCY_HZ * 3.02:.4f}",
                    "ZONE A",
                    "NONE",
                    f"{RPM:.1f}",
                    impact,
                    snapshot,
                ]
            )

    passed(
        f"Generated ANALYSIS fixture: "
        f"{path.name} "
        f"({ANALYSIS_WINDOW_COUNT} windows)"
    )

    return path


# ============================================================
# Generate separate calibration JSON
# ============================================================

def generate_calibration_json() -> Path:

    path = (
        TEST_DIR /
        "synthetic_calibration.json"
    )

    record = {
        "calId":
            "CAL-TEST-001",

        "manufacturer":
            "Synthetic",

        "model":
            "SensorMax Test Device",

        "sensorName":
            "Synthetic Accelerometer",

        "sensorVendor":
            "SensorMax Test",

        "sensorVersion":
            1,

        "mountingMethod":
            "Bench fixture",

        "dateEpochMs":
            int(
                time.time() *
                1000
            ),

        "operatorName":
            "Integration Test",

        "biasX":
            0.001,

        "biasY":
            0.002,

        "biasZ":
            0.003,

        "noiseX":
            0.010,

        "noiseY":
            0.020,

        "noiseZ":
            0.015,

        "declaredRangeMs2":
            39.24,

        "achievedSamplingHz":
            SAMPLE_RATE_HZ,

        "notes":
            "Synthetic integration-test calibration record",
    }

    with path.open(
        "w",
        encoding="utf-8",
    ) as file:

        json.dump(
            record,
            file,
            indent=4,
        )

    passed(
        f"Generated calibration fixture: "
        f"{path.name}"
    )

    return path


# ============================================================
# File validation helpers
# ============================================================

def require_file(
    path: Path,
    description: str,
) -> bool:

    if path.exists() and path.is_file():

        passed(
            f"{description}: {path.name}"
        )

        return True

    failed(
        f"{description} missing: {path}"
    )

    return False


def require_nonempty_file(
    path: Path,
    description: str,
) -> bool:

    if (
        path.exists()
        and
        path.is_file()
        and
        path.stat().st_size > 0
    ):

        passed(
            f"{description}: "
            f"{path.name} "
            f"({path.stat().st_size} bytes)"
        )

        return True

    failed(
        f"{description} is missing or empty: "
        f"{path}"
    )

    return False


def validate_json(
    path: Path,
) -> bool:

    try:

        with path.open(
            "r",
            encoding="utf-8",
        ) as file:

            data = json.load(
                file
            )

        required = {
            "schema",
            "metadata",
            "calibration_profile",
            "provenance",
            "sensor_telemetry",
        }

        missing = (
            required -
            set(data.keys())
        )

        if missing:

            failed(
                "JSON structure missing keys: "
                +
                ", ".join(
                    sorted(missing)
                )
            )

            return False

        passed(
            "JSON structure validated"
        )

        return True

    except Exception as exc:

        failed(
            f"JSON validation failed: "
            f"{type(exc).__name__}: {exc}"
        )

        return False


def validate_xlsx(
    path: Path,
    expected_sheets: set[str],
) -> bool:

    try:

        import openpyxl

        workbook = (
            openpyxl.load_workbook(
                path,
                read_only=True,
            )
        )

        actual = set(
            workbook.sheetnames
        )

        missing = (
            expected_sheets -
            actual
        )

        if missing:

            failed(
                "XLSX missing sheets: "
                +
                ", ".join(
                    sorted(missing)
                )
            )

            workbook.close()

            return False

        workbook.close()

        passed(
            "XLSX workbook structure validated"
        )

        return True

    except Exception as exc:

        failed(
            f"XLSX validation failed: "
            f"{type(exc).__name__}: {exc}"
        )

        return False


# ============================================================
# Python syntax verification
# ============================================================

def test_python_syntax() -> bool:

    banner(
        "1. PYTHON SYNTAX CHECK"
    )

    files = [
        SCRIPT_DIR /
        "web_server.py",

        SCRIPT_DIR /
        "export_converter.py",

        SCRIPT_DIR /
        "export_json.py",

        SCRIPT_DIR /
        "batch_dataset_processor.py",
    ]

    success = True

    for path in files:

        if not path.exists():

            failed(
                f"Missing source file: "
                f"{path.name}"
            )

            success = False

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

            passed(
                f"Syntax OK: {path.name}"
            )

        else:

            failed(
                f"Syntax FAILED: {path.name}"
            )

            if result.stderr:

                print(
                    result.stderr.rstrip()
                )

            success = False

    return success


# ============================================================
# Export converter test
# ============================================================

def test_export_converter(
    raw_csv: Path,
) -> tuple[bool, Path]:

    banner(
        "2. RAW CSV -> XLSX"
    )

    output_xlsx = (
        TEST_DIR /
        "raw_processed.xlsx"
    )

    command = [
        sys.executable,
        str(
            SCRIPT_DIR /
            "export_converter.py"
        ),
        str(raw_csv),
        str(output_xlsx),
    ]

    success = run_command(
        "export_converter.py",
        command,
    )

    if not success:

        return (
            False,
            output_xlsx,
        )

    if not require_nonempty_file(
        output_xlsx,
        "Converted workbook",
    ):

        return (
            False,
            output_xlsx,
        )

    workbook_ok = (
        validate_xlsx(
            output_xlsx,
            {
                "Session Metadata",
                "Accelerometer",
            },
        )
    )

    return (
        workbook_ok,
        output_xlsx,
    )


# ============================================================
# JSON exporter test
# ============================================================

def test_export_json(
    raw_csv: Path,
    calibration_json: Path,
) -> tuple[bool, Path]:

    banner(
        "3. RAW CSV + CALIBRATION -> JSON"
    )

    output_json = (
        raw_csv.with_suffix(
            ".json"
        )
    )

    command = [
        sys.executable,
        str(
            SCRIPT_DIR /
            "export_json.py"
        ),
        str(raw_csv),
        str(calibration_json),
    ]

    success = run_command(
        "export_json.py",
        command,
    )

    if not success:

        return (
            False,
            output_json,
        )

    if not require_nonempty_file(
        output_json,
        "JSON export",
    ):

        return (
            False,
            output_json,
        )

    validation_ok = (
        validate_json(
            output_json
        )
    )

    return (
        validation_ok,
        output_json,
    )


# ============================================================
# Batch processor test
# ============================================================

def test_batch_processor() -> tuple[bool, Path]:

    banner(
        "4. BATCH RAW + ANALYSIS -> XLSX"
    )

    output_xlsx = (
        TEST_DIR /
        "master_batch_comparison.xlsx"
    )

    command = [
        sys.executable,
        str(
            SCRIPT_DIR /
            "batch_dataset_processor.py"
        ),
        str(TEST_DIR),
        "--output",
        str(output_xlsx),
    ]

    success = run_command(
        "batch_dataset_processor.py",
        command,
    )

    if not success:

        return (
            False,
            output_xlsx,
        )

    if not require_nonempty_file(
        output_xlsx,
        "Batch workbook",
    ):

        return (
            False,
            output_xlsx,
        )

    validation_ok = validate_xlsx(
        output_xlsx,
        {
            "Batch Trial Comparison",
            "Analysis Windows",
            "Sensor Summary",
        },
    )

    return (
        validation_ok,
        output_xlsx,
    )


# ============================================================
# WebSocket server startup test
# ============================================================

def find_free_port(
    host: str,
) -> int:

    with socket.socket(
        socket.AF_INET,
        socket.SOCK_STREAM,
    ) as sock:

        sock.bind(
            (
                host,
                0,
            )
        )

        return sock.getsockname()[1]


async def websocket_client_test(
    port: int,
) -> bool:

    try:

        import websockets

    except ImportError:

        failed(
            "websockets package is not installed"
        )

        return False

    uri = (
        f"ws://127.0.0.1:{port}"
    )

    try:

        async with websockets.connect(
            uri,
            open_timeout=5,
            close_timeout=2,
        ) as client:

            # Send a current Android-shaped raw packet.
            packet = {
                "ts": int(
                    time.time() * 1000
                ),
                "id": 1,
                "sensorName":
                    "Integration Test Accelerometer",
                "v0": 0.10,
                "v1": 9.81,
                "v2": 0.05,
                "v3": 0.0,
                "v4": 0.0,
                "v5": 0.0,
            }

            await client.send(
                json.dumps(packet)
            )

            passed(
                "WebSocket gateway accepted "
                "current raw packet"
            )

            return True

    except Exception as exc:

        failed(
            "WebSocket client test failed: "
            f"{type(exc).__name__}: {exc}"
        )

        return False


def test_web_server() -> bool:

    banner(
        "5. WEBSOCKET GATEWAY"
    )

    port = find_free_port(
        WEBSOCKET_HOST
    )

    command = [
        sys.executable,
        str(
            SCRIPT_DIR /
            "web_server.py"
        ),
    ]

    # The server currently listens on its configured port.
    # The integration test therefore uses the project's expected
    # 8765 port whenever available.
    port = WEBSOCKET_PORT

    if not is_port_available(
        WEBSOCKET_HOST,
        port,
    ):

        failed(
            f"WebSocket test port "
            f"{port} is already occupied"
        )

        return False

    env = os.environ.copy()

    process = subprocess.Popen(
        command,
        cwd=str(
            SCRIPT_DIR.parent
        ),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
    )

    try:

        ready = wait_for_port(
            WEBSOCKET_HOST,
            port,
            process,
            timeout=8,
        )

        if not ready:

            stderr = ""

            try:

                stderr = (
                    process.stderr.read()
                    if process.stderr
                    else ""
                )

            except Exception:
                pass

            failed(
                "WebSocket gateway did not "
                "become ready"
            )

            if stderr.strip():

                print(
                    stderr.rstrip()
                )

            return False

        passed(
            f"WebSocket gateway listening "
            f"on {WEBSOCKET_HOST}:{port}"
        )

        result = asyncio.run(
            websocket_client_test(
                port
            )
        )

        return result

    finally:

        if process.poll() is None:

            process.terminate()

            try:

                process.wait(
                    timeout=3
                )

            except subprocess.TimeoutExpired:

                process.kill()

                process.wait()

        passed(
            "WebSocket gateway process stopped"
        )


def is_port_available(
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


def wait_for_port(
    host: str,
    port: int,
    process: subprocess.Popen,
    timeout: float,
) -> bool:

    deadline = (
        time.time() +
        timeout
    )

    while (
        time.time() <
        deadline
    ):

        if process.poll() is not None:

            return False

        if not is_port_available(
            host,
            port,
        ):

            return True

        time.sleep(
            0.15
        )

    return False


# ============================================================
# Output inventory
# ============================================================

def show_test_artifacts() -> None:

    banner(
        "TEST ARTIFACTS"
    )

    if not TEST_DIR.exists():

        return

    for path in sorted(
        TEST_DIR.rglob("*")
    ):

        if path.is_file():

            relative = (
                path.relative_to(
                    TEST_DIR
                )
            )

            print(
                f"  {relative}"
            )


# ============================================================
# Main
# ============================================================

def main() -> int:

    banner(
        "SENSORMAX DESKTOP INTEGRATION TEST"
    )

    print(
        "AndroidApp: UNTOUCHED"
    )

    print(
        f"Python: {sys.version.split()[0]}"
    )

    print(
        f"Script directory: {SCRIPT_DIR}"
    )

    prepare_test_directory()

    # --------------------------------------------------------
    # Generate fixtures
    # --------------------------------------------------------

    raw_csv = generate_raw_csv()

    analysis_csv = (
        generate_analysis_csv()
    )

    calibration_json = (
        generate_calibration_json()
    )

    # --------------------------------------------------------
    # Tests
    # --------------------------------------------------------

    results: list[
        tuple[str, bool]
    ] = []

    results.append(
        (
            "Python syntax",
            test_python_syntax(),
        )
    )

    converter_ok, _ = (
        test_export_converter(
            raw_csv
        )
    )

    results.append(
        (
            "RAW -> XLSX",
            converter_ok,
        )
    )

    json_ok, _ = (
        test_export_json(
            raw_csv,
            calibration_json,
        )
    )

    results.append(
        (
            "RAW + calibration -> JSON",
            json_ok,
        )
    )

    batch_ok, _ = (
        test_batch_processor()
    )

    results.append(
        (
            "Batch RAW + ANALYSIS -> XLSX",
            batch_ok,
        )
    )

    web_ok = test_web_server()

    results.append(
        (
            "WebSocket gateway",
            web_ok,
        )
    )

    # --------------------------------------------------------
    # Results
    # --------------------------------------------------------

    banner(
        "INTEGRATION TEST RESULTS"
    )

    passed_count = sum(
        1
        for _,
        result in results
        if result
    )

    total_count = len(
        results
    )

    for name, result in results:

        status = (
            "PASS"
            if result
            else "FAIL"
        )

        print(
            f"  [{status}] {name}"
        )

    print()

    print(
        f"  RESULT: "
        f"{passed_count}/{total_count} "
        f"tests passed"
    )

    show_test_artifacts()

    print()

    if (
        passed_count ==
        total_count
    ):

        print(
            "  SENSORMax desktop pipeline is "
            "internally consistent."
        )

        return 0

    print(
        "  One or more desktop pipeline tests failed."
    )

    return 1


if __name__ == "__main__":

    raise SystemExit(
        main()
    )