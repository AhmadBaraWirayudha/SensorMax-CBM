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

RAW v2
------
Schema_Version
Sensor_Timestamp_ns
Timestamp_ms
Machine_ID
Point
Source_Kind
Source_ID
Channel_ID
Sensor_Type
Sensor_Name
Unit
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

try:  # Support both `python LaptopSuite/test_all_pipelines.py` and package import.
    from . import web_server as gateway_module
except ImportError:
    import web_server as gateway_module


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
INTEGRATION_SENSOR_TIMESTAMP_NS = "9223372036854775000"  # signed 64-bit boundary fixture

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

    header = list(gateway_module.RAW_CSV_COLUMNS)

    start_ms = int(time.time() * 1000)
    start_sensor_ns = time.monotonic_ns()

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

            row = {
                "Schema_Version": 2,
                "Sensor_Timestamp_ns": start_sensor_ns + int(t * 1_000_000_000),
                "Timestamp_ms": ts,
                "Machine_ID": MACHINE_ID,
                "Point": POINT,
                "Source_Kind": "ANDROID_SENSOR",
                "Source_ID": "integration-accelerometer-01",
                "Channel_ID": "accelerometer",
                "Sensor_Type": 1,
                "Sensor_Name": "Synthetic Accelerometer",
                "Unit": "m/s²",
                "Val_0": f"{x:.8f}",
                "Val_1": f"{y:.8f}",
                "Val_2": f"{z:.8f}",
                "Val_3": "0",
                "Val_4": "0",
                "Val_5": "0",
            }
            writer.writerow([row.get(column, "") for column in header])

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

        if data.get("provenance", {}).get("source_schema") != "android_raw_v2":
            failed(
                "JSON source schema was not recognized as android_raw_v2"
            )
            return False

        telemetry = data.get("sensor_telemetry")
        if not isinstance(telemetry, list) or len(telemetry) != RAW_SAMPLE_COUNT:
            failed(
                f"JSON telemetry row count mismatch: expected {RAW_SAMPLE_COUNT}, "
                f"got {len(telemetry) if isinstance(telemetry, list) else 'non-list'}"
            )
            return False

        if telemetry and str(telemetry[0].get("Schema_Version")) != "2":
            failed("JSON did not preserve the v2 schema version")
            return False

        passed(
            "JSON structure, v2 schema and row count validated"
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


async def websocket_client_test(port: int) -> bool:
    """Check Android-sender -> gateway -> dashboard forwarding on a v2 packet."""
    try:
        import websockets
    except ImportError:
        failed("websockets package is not installed")
        return False

    uri = f"ws://127.0.0.1:{port}"
    timestamp_ms = int(time.time() * 1000)
    sensor_timestamp_ns = INTEGRATION_SENSOR_TIMESTAMP_NS
    raw_packet = {
        "kind": "raw",
        "schemaVersion": 2,
        "sensorTimestampNs": sensor_timestamp_ns,
        "ts": timestamp_ms,
        "machineId": MACHINE_ID,
        "point": POINT,
        "sourceKind": "ANDROID_SENSOR",
        "sourceId": "integration, accelerometer-01",
        "channelId": "accelerometer|main",
        "sensorType": 1,
        "id": 1,
        "sensor": 'Synthetic Accelerometer, "Rev B"',
        "unit": "m/s²",
        "v0": 1.25,
        "v1": None,
        "v2": 0.0,
        "v3": None,
        "v4": None,
        "v5": None,
    }
    analysis_packet = {
        "kind": "analysis",
        "ts": timestamp_ms,
        "machineId": MACHINE_ID,
        "point": POINT,
        "sampleRateHz": 100.0,
        "overallRmsAccelMs2": 1.25,
        "overallRmsVelocityMmS": None,
        "overallRmsDisplacementUm": 0.0,
        "dominantAxis": "X",
        "dominantFreqHz": 30.0,
        "envelopePeakFreqHz": None,
        "isoZone": "A",
        "bearingMatch": "",
    }

    try:
        async with websockets.connect(uri, open_timeout=5, close_timeout=2) as dashboard:
            async with websockets.connect(uri, open_timeout=5, close_timeout=2) as sender:
                await sender.send(json.dumps(raw_packet, allow_nan=False))
                forwarded_raw = json.loads(await asyncio.wait_for(dashboard.recv(), timeout=3))
                if forwarded_raw != raw_packet:
                    failed("Dashboard did not receive the exact raw v2 packet")
                    return False

                await sender.send(json.dumps(analysis_packet, allow_nan=False))
                forwarded_analysis = json.loads(await asyncio.wait_for(dashboard.recv(), timeout=3))
                if forwarded_analysis != analysis_packet:
                    failed("Dashboard did not receive the exact analysis packet")
                    return False

        passed("Gateway forwarded exact raw v2 and analysis packets to a separate dashboard client")
        return True
    except Exception as exc:
        failed(f"WebSocket end-to-end test failed: {type(exc).__name__}: {exc}")
        return False


def test_web_server() -> bool:
    banner("5. WEBSOCKET GATEWAY -> DASHBOARD -> CSV/JSON/XLSX")

    port = find_free_port(WEBSOCKET_HOST)
    log_dir = TEST_DIR / "gateway_records"
    if log_dir.exists():
        shutil.rmtree(log_dir)
    log_dir.mkdir(parents=True, exist_ok=True)

    command = [
        sys.executable,
        "-u",
        str(SCRIPT_DIR / "web_server.py"),
    ]
    env = os.environ.copy()
    env["SENSORMAX_LISTEN_HOST"] = WEBSOCKET_HOST
    env["SENSORMAX_LISTEN_PORT"] = str(port)
    env["SENSORMAX_LOG_DIR"] = str(log_dir)

    if not is_port_available(WEBSOCKET_HOST, port):
        failed(f"Selected integration port {port} is already occupied")
        return False

    process = subprocess.Popen(
        command,
        cwd=str(SCRIPT_DIR.parent),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
    )

    gateway_ok = False
    process_stdout = ""
    process_stderr = ""
    try:
        ready = wait_for_port(WEBSOCKET_HOST, port, process, timeout=8)
        if not ready:
            failed(f"Gateway did not become ready on 127.0.0.1:{port}")
        else:
            passed(f"Gateway listening on 127.0.0.1:{port}")
            gateway_ok = asyncio.run(websocket_client_test(port))
    finally:
        if process.poll() is None:
            process.terminate()
        try:
            process_stdout, process_stderr = process.communicate(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process_stdout, process_stderr = process.communicate(timeout=3)
        passed("WebSocket gateway process stopped")

    if not gateway_ok:
        if process_stdout.strip():
            print(process_stdout[-3000:])
        if process_stderr.strip():
            print(process_stderr[-3000:])
        return False

    # Verify files the gateway actually persisted, not merely the client send call.
    raw_files = list(log_dir.glob("*_raw.csv"))
    analysis_files = list(log_dir.glob("*_analysis.csv"))
    if len(raw_files) != 1 or len(analysis_files) != 1:
        failed(
            f"Expected one raw and one analysis CSV; got raw={len(raw_files)}, "
            f"analysis={len(analysis_files)}"
        )
        return False

    try:
        with raw_files[0].open("r", encoding="utf-8", newline="") as file:
            raw_reader = csv.DictReader(file)
            raw_rows = list(raw_reader)
            raw_columns = raw_reader.fieldnames
        with analysis_files[0].open("r", encoding="utf-8", newline="") as file:
            analysis_reader = csv.DictReader(file)
            analysis_rows = list(analysis_reader)
            analysis_columns = analysis_reader.fieldnames

        if tuple(raw_columns or ()) != tuple(gateway_module.RAW_CSV_COLUMNS):
            failed("Gateway raw CSV header differs from the canonical v2 header")
            return False
        if len(raw_rows) != 1 or len(analysis_rows) != 1:
            failed("Gateway CSV row counts do not match the two emitted packets")
            return False
        raw = raw_rows[0]
        if (
            raw["Sensor_Timestamp_ns"] != INTEGRATION_SENSOR_TIMESTAMP_NS
            or raw["Timestamp_ms"] == ""
            or raw["Machine_ID"] != MACHINE_ID
            or raw["Source_ID"] != "integration, accelerometer-01"
            or raw["Channel_ID"] != "accelerometer|main"
            or raw["Val_1"] != ""
            or float(raw["Val_2"]) != 0.0
        ):
            failed("Gateway raw CSV failed provenance, timestamp or null-vs-zero assertions")
            return False
        expected_analysis = tuple(gateway_module.ANALYSIS_SNAPSHOT_CSV_COLUMNS)
        if tuple(analysis_columns or ()) != expected_analysis:
            failed("Gateway analysis CSV header differs from the documented snapshot contract")
            return False
        if analysis_rows[0]["RMS_Velocity_mms"] != "" or analysis_rows[0]["RMS_Displacement_um"] != "0.0":
            failed("Analysis CSV failed missing-value vs real-zero assertions")
            return False
        passed("Gateway persisted canonical raw/analysis CSV with exact identity, timestamps, nulls and zero")

        xlsx_path = log_dir / "gateway_raw.xlsx"
        converter_ok = run_command(
            "export_converter.py on the actual gateway CSV",
            [
                sys.executable,
                str(SCRIPT_DIR / "export_converter.py"),
                str(raw_files[0]),
                str(xlsx_path),
            ],
            timeout=30,
        )
        if not converter_ok or not xlsx_path.exists():
            failed("XLSX export of the actual gateway CSV did not produce an artifact")
            return False
        workbook_ok = validate_xlsx(xlsx_path, {"Session Metadata", "Accelerometer"})
        if not workbook_ok:
            return False
        import openpyxl
        workbook = openpyxl.load_workbook(xlsx_path, data_only=True, read_only=True)
        try:
            sheet = workbook["Accelerometer"]
            headers = {sheet.cell(4, col).value: col for col in range(1, sheet.max_column + 1)}
            if sheet.cell(5, headers["Sensor Timestamp (ns)"]).value != raw["Sensor_Timestamp_ns"]:
                failed("XLSX did not preserve the exact nanosecond timestamp text")
                return False
            if sheet.cell(5, headers["Value 1"]).value is not None or sheet.cell(5, headers["Value 2"]).value != 0:
                failed("XLSX changed unavailable channel or true zero semantics")
                return False
        finally:
            workbook.close()

        json_path = log_dir / raw_files[0].name.replace("_raw.csv", "_raw.json")
        return_code = run_command(
            "export_json.py on the actual gateway CSV",
            [sys.executable, str(SCRIPT_DIR / "export_json.py"), str(raw_files[0])],
            timeout=30,
        )
        if not return_code or not json_path.exists():
            failed("JSON export of gateway CSV did not produce an artifact")
            return False
        json_data = json.loads(json_path.read_text(encoding="utf-8"))
        if json_data.get("provenance", {}).get("source_schema") != "android_raw_v2":
            failed("JSON export did not retain the v2 source schema")
            return False
        telemetry = json_data.get("sensor_telemetry", [])
        if len(telemetry) != 1 or telemetry[0].get("Sensor_Timestamp_ns") != raw["Sensor_Timestamp_ns"]:
            failed("JSON export changed row count or exact sensor timestamp")
            return False
        if telemetry[0].get("Val_1") is not None or float(telemetry[0].get("Val_2")) != 0.0:
            failed("JSON export changed unavailable channel or true zero semantics")
            return False
        passed("JSON and XLSX exports agree with the gateway CSV's timestamp and nullable readings")
        return True
    except Exception as exc:
        failed(f"Gateway artifact validation failed: {type(exc).__name__}: {exc}")
        return False


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
        "AndroidApp runtime/device: NOT RUN (gateway test uses a synthetic Android-shaped packet)"
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