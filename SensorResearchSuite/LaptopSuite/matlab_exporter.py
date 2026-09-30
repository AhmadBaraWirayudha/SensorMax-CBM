#!/usr/bin/env python3
"""
SensorMax MATLAB Exporter

Converts a SensorMax raw CSV recording into a ready-to-run MATLAB script.

Current SensorMax raw schema:
    Timestamp_ms,
    Machine_ID,
    Point,
    Sensor_Type,
    Sensor_Name,
    Val_0,
    Val_1,
    Val_2,
    Val_3,
    Val_4,
    Val_5

The exporter prefers the current named schema and retains compatibility with
legacy positional rows where practical.

AndroidApp is not modified.

Outputs:
    - relative time vector
    - accelerometer X/Y/Z vectors
    - machine / point metadata
    - sampling-rate estimate
    - waveform figure
    - FFT figure
    - dominant frequency summary
"""

from __future__ import annotations

import argparse
import csv
import math
import os
import sys
from typing import Any, Dict, List, Optional, Tuple


ACCEL_SENSOR_TYPE = 1


def as_float(value: Any) -> Optional[float]:
    try:
        if value is None or value == "":
            return None
        value = float(value)
        return value if math.isfinite(value) else None
    except (TypeError, ValueError):
        return None


def as_text(value: Any, fallback: str = "UNSPECIFIED") -> str:
    if value is None:
        return fallback
    text = str(value).strip()
    return text if text else fallback


def sensor_type(value: Any) -> Optional[int]:
    if value is None:
        return None

    text = str(value).strip().lower()

    aliases = {
        "1": 1,
        "accelerometer": 1,
        "accel": 1,
        "2": 2,
        "magnetometer": 2,
        "mag": 2,
    }

    if text in aliases:
        return aliases[text]

    try:
        return int(float(text))
    except (TypeError, ValueError):
        return None


def load_rows(csv_path: str) -> Tuple[List[Dict[str, Any]], str]:
    """
    Load current named-schema CSV and fall back to a legacy positional form.

    Current schema is detected from Timestamp_ms / Sensor_Type fields.
    """
    rows: List[Dict[str, Any]] = []

    with open(csv_path, "r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.reader(handle)
        first = next(reader, None)

        if first is None:
            raise ValueError("CSV file is empty.")

        is_header = (
            "Timestamp_ms" in first
            or "Sensor_Type" in first
            or "Machine_ID" in first
        )

        if is_header:
            header = [str(v).strip() for v in first]
            dict_reader = csv.DictReader(handle, fieldnames=header)

            for row in dict_reader:
                if row:
                    rows.append(
                        {
                            str(k).strip(): v
                            for k, v in row.items()
                            if k is not None
                        }
                    )

            return rows, "current_named"

        # Legacy positional compatibility:
        # timestamp, sensor_type, sensor_name, x, y, z, ...
        def positional_to_dict(values: List[str]) -> Optional[Dict[str, Any]]:
            if len(values) < 6:
                return None

            return {
                "Timestamp_ms": values[0],
                "Sensor_Type": values[1],
                "Sensor_Name": values[2] if len(values) > 2 else "",
                "Val_0": values[3] if len(values) > 3 else "",
                "Val_1": values[4] if len(values) > 4 else "",
                "Val_2": values[5] if len(values) > 5 else "",
                "Val_3": values[6] if len(values) > 6 else "",
                "Val_4": values[7] if len(values) > 7 else "",
                "Val_5": values[8] if len(values) > 8 else "",
            }

        first_row = positional_to_dict(first)
        if first_row is not None:
            rows.append(first_row)

        for values in reader:
            row = positional_to_dict(values)
            if row is not None:
                rows.append(row)

        return rows, "legacy_positional"


def extract_accelerometer(
    rows: List[Dict[str, Any]],
) -> Dict[str, Any]:
    timestamps_ms: List[float] = []
    x: List[float] = []
    y: List[float] = []
    z: List[float] = []

    machine_ids = set()
    points = set()
    sensor_names = set()

    for row in rows:
        if sensor_type(
            row.get("Sensor_Type")
            or row.get("sensor_type")
        ) != ACCEL_SENSOR_TYPE:
            continue

        timestamp = as_float(
            row.get("Timestamp_ms")
            if "Timestamp_ms" in row
            else row.get("timestamp")
        )

        xv = as_float(
            row.get("Val_0")
            if "Val_0" in row
            else row.get("Value_0")
        )
        yv = as_float(
            row.get("Val_1")
            if "Val_1" in row
            else row.get("Value_1")
        )
        zv = as_float(
            row.get("Val_2")
            if "Val_2" in row
            else row.get("Value_2")
        )

        if None in (timestamp, xv, yv, zv):
            continue

        timestamps_ms.append(timestamp)
        x.append(xv)
        y.append(yv)
        z.append(zv)

        if row.get("Machine_ID"):
            machine_ids.add(str(row["Machine_ID"]).strip())

        if row.get("Point"):
            points.add(str(row["Point"]).strip())

        if row.get("Sensor_Name"):
            sensor_names.add(str(row["Sensor_Name"]).strip())

    return {
        "timestamps_ms": timestamps_ms,
        "x": x,
        "y": y,
        "z": z,
        "machine_ids": sorted(machine_ids),
        "points": sorted(points),
        "sensor_names": sorted(sensor_names),
    }


def estimate_sample_rate(timestamps_ms: List[float]) -> Optional[float]:
    if len(timestamps_ms) < 2:
        return None

    intervals = [
        b - a
        for a, b in zip(timestamps_ms, timestamps_ms[1:])
        if math.isfinite(a)
        and math.isfinite(b)
        and b > a
    ]

    if not intervals:
        return None

    median_dt_ms = sorted(intervals)[len(intervals) // 2]

    if median_dt_ms <= 0:
        return None

    return 1000.0 / median_dt_ms


def next_pow2(value: int) -> int:
    n = 1
    while n < value:
        n <<= 1
    return n


def compute_fft(
    values: List[float],
    sample_rate_hz: Optional[float],
) -> Tuple[List[float], List[float], Optional[float], Optional[float]]:
    """
    Return:
        frequency_hz,
        single_sided_amplitude,
        dominant_frequency_hz,
        dominant_amplitude
    """
    if len(values) < 4 or sample_rate_hz is None or sample_rate_hz <= 0:
        return [], [], None, None

    # Remove DC before spectral inspection.
    mean_value = sum(values) / len(values)
    centered = [value - mean_value for value in values]

    nfft = next_pow2(len(centered))
    padded = centered + [0.0] * (nfft - len(centered))

    # Pure-Python DFT fallback keeps generated MATLAB exporter dependency-free.
    # O(N^2) is acceptable for small research recordings; for large files,
    # MATLAB/Python-side FFT should be used directly.
    spectrum: List[complex] = []

    for k in range(nfft // 2 + 1):
        real = 0.0
        imag = 0.0

        for n, sample in enumerate(padded):
            angle = -2.0 * math.pi * k * n / nfft
            real += sample * math.cos(angle)
            imag += sample * math.sin(angle)

        spectrum.append(complex(real, imag))

    amplitudes = [
        2.0 * abs(value) / nfft
        for value in spectrum
    ]
    frequencies = [
        k * sample_rate_hz / nfft
        for k in range(len(amplitudes))
    ]

    if amplitudes:
        # Ignore DC bin when finding vibration peaks.
        start = 1 if len(amplitudes) > 1 else 0
        index = max(
            range(start, len(amplitudes)),
            key=lambda idx: amplitudes[idx],
        )

        return (
            frequencies,
            amplitudes,
            frequencies[index],
            amplitudes[index],
        )

    return [], [], None, None


def matlab_vector(values: List[float], digits: int) -> str:
    return " ".join(
        f"{float(value):.{digits}f}"
        for value in values
    )


def matlab_string(value: str) -> str:
    return value.replace("'", "''")


def matlab_cell(values: List[str]) -> str:
    if not values:
        return "{}"

    return (
        "{"
        + ", ".join(
            f"'{matlab_string(value)}'"
            for value in values
        )
        + "}"
    )


def generate_matlab(
    data: Dict[str, Any],
    source_csv: str,
) -> str:
    timestamps_ms = data["timestamps_ms"]
    x = data["x"]
    y = data["y"]
    z = data["z"]

    start_ts = timestamps_ms[0]
    time_s = [
        (value - start_ts) / 1000.0
        for value in timestamps_ms
    ]

    sample_rate_hz = estimate_sample_rate(timestamps_ms)

    dominant_axis_results = {}
    for axis_name, values in (
        ("X", x),
        ("Y", y),
        ("Z", z),
    ):
        _, _, frequency, amplitude = compute_fft(
            values,
            sample_rate_hz,
        )
        dominant_axis_results[axis_name] = {
            "frequency": frequency,
            "amplitude": amplitude,
        }

    lines = [
        "% SensorMax Research Dataset — MATLAB Export",
        "% Generated automatically by SensorResearchSuite.",
        "% AndroidApp source is not modified by this exporter.",
        "",
        "% Provenance",
        f"source_csv = '{matlab_string(os.path.abspath(source_csv))}';",
        f"machine_id = {matlab_cell(data['machine_ids'])};",
        f"measurement_point = {matlab_cell(data['points'])};",
        f"sensor_name = {matlab_cell(data['sensor_names'])};",
        f"sample_count = {len(time_s)};",
        (
            f"estimated_sample_rate_hz = {sample_rate_hz:.6f};"
            if sample_rate_hz is not None
            else "estimated_sample_rate_hz = NaN;"
        ),
        "",
        "% Accelerometer vectors",
        f"time_s = [{matlab_vector(time_s, 6)}];",
        f"acc_x_ms2 = [{matlab_vector(x, 6)}];",
        f"acc_y_ms2 = [{matlab_vector(y, 6)}];",
        f"acc_z_ms2 = [{matlab_vector(z, 6)}];",
        "",
        "% Basic statistics",
        "rms_x_ms2 = sqrt(mean(acc_x_ms2.^2));",
        "rms_y_ms2 = sqrt(mean(acc_y_ms2.^2));",
        "rms_z_ms2 = sqrt(mean(acc_z_ms2.^2));",
        "rss_ms2 = sqrt(acc_x_ms2.^2 + acc_y_ms2.^2 + acc_z_ms2.^2);",
        "rms_rss_ms2 = sqrt(mean(rss_ms2.^2));",
        "",
        "% Time-domain waveform",
        "figure('Name', 'SensorMax Accelerometer Waveform');",
        "subplot(3,1,1);",
        "plot(time_s, acc_x_ms2, 'LineWidth', 1.0);",
        "title('Accelerometer X');",
        "ylabel('m/s^2');",
        "grid on;",
        "",
        "subplot(3,1,2);",
        "plot(time_s, acc_y_ms2, 'LineWidth', 1.0);",
        "title('Accelerometer Y');",
        "ylabel('m/s^2');",
        "grid on;",
        "",
        "subplot(3,1,3);",
        "plot(time_s, acc_z_ms2, 'LineWidth', 1.0);",
        "title('Accelerometer Z');",
        "xlabel('Time (s)');",
        "ylabel('m/s^2');",
        "grid on;",
        "",
        "% MATLAB FFT",
        "if ~isnan(estimated_sample_rate_hz) && estimated_sample_rate_hz > 0",
        "    n = length(acc_x_ms2);",
        "    nfft = 2^nextpow2(n);",
        "    f = estimated_sample_rate_hz*(0:(nfft/2))/nfft;",
        "",
        "    X = fft(acc_x_ms2 - mean(acc_x_ms2), nfft);",
        "    Y = fft(acc_y_ms2 - mean(acc_y_ms2), nfft);",
        "    Z = fft(acc_z_ms2 - mean(acc_z_ms2), nfft);",
        "",
        "    AX = 2*abs(X(1:nfft/2+1))/nfft;",
        "    AY = 2*abs(Y(1:nfft/2+1))/nfft;",
        "    AZ = 2*abs(Z(1:nfft/2+1))/nfft;",
        "",
        "    figure('Name', 'SensorMax Accelerometer FFT');",
        "    subplot(3,1,1);",
        "    plot(f, AX, 'LineWidth', 1.0);",
        "    title('FFT X');",
        "    xlabel('Frequency (Hz)');",
        "    ylabel('Amplitude (m/s^2)');",
        "    grid on;",
        "",
        "    subplot(3,1,2);",
        "    plot(f, AY, 'LineWidth', 1.0);",
        "    title('FFT Y');",
        "    xlabel('Frequency (Hz)');",
        "    ylabel('Amplitude (m/s^2)');",
        "    grid on;",
        "",
        "    subplot(3,1,3);",
        "    plot(f, AZ, 'LineWidth', 1.0);",
        "    title('FFT Z');",
        "    xlabel('Frequency (Hz)');",
        "    ylabel('Amplitude (m/s^2)');",
        "    grid on;",
        "end",
        "",
        "% Desktop-side screening result.",
        "% Do not treat phone MEMS results as certified industrial vibration metrology.",
        "disp('SensorMax dataset loaded successfully.');",
        "disp(['Samples: ', num2str(sample_count)]);",
        "disp(['Estimated sample rate (Hz): ', num2str(estimated_sample_rate_hz)]);",
        "disp(['RSS acceleration RMS (m/s^2): ', num2str(rms_rss_ms2)]);",
    ]

    return "\n".join(lines) + "\n"


def convert_to_matlab(
    csv_path: str,
    m_path: str,
) -> int:
    if not os.path.exists(csv_path):
        print(f"[ERROR] File not found: {csv_path}")
        return 2

    try:
        rows, schema = load_rows(csv_path)
        data = extract_accelerometer(rows)
    except Exception as exc:
        print(f"[ERROR] Failed to read dataset: {exc}")
        return 1

    if not data["timestamps_ms"]:
        print("[ERROR] No accelerometer data found.")
        return 1

    output_dir = os.path.dirname(os.path.abspath(m_path))
    os.makedirs(output_dir, exist_ok=True)

    code = generate_matlab(
        data=data,
        source_csv=csv_path,
    )

    with open(
        m_path,
        "w",
        encoding="utf-8",
        newline="\n",
    ) as handle:
        handle.write(code)

    print("=" * 64)
    print(" SensorMax MATLAB EXPORT")
    print("=" * 64)
    print(f"Input schema      : {schema}")
    print(f"Accelerometer rows: {len(data['timestamps_ms'])}")
    print(
        "Machine ID(s)     : "
        + ", ".join(data["machine_ids"] or ["UNSPECIFIED"])
    )
    print(
        "Point(s)          : "
        + ", ".join(data["points"] or ["UNSPECIFIED"])
    )

    sample_rate = estimate_sample_rate(
        data["timestamps_ms"]
    )

    print(
        "Estimated rate    : "
        + (
            f"{sample_rate:.3f} Hz"
            if sample_rate is not None
            else "UNAVAILABLE"
        )
    )

    print(f"Output MATLAB     : {os.path.abspath(m_path)}")
    print("=" * 64)

    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Convert a SensorMax raw CSV into a MATLAB research script."
        )
    )

    parser.add_argument(
        "csv_file",
        help="Input SensorMax raw CSV dataset.",
    )

    parser.add_argument(
        "--output",
        default="sensormax_data.m",
        help=(
            "Output MATLAB script path "
            "(default: sensormax_data.m)."
        ),
    )

    return parser


def main() -> int:
    args = build_parser().parse_args()

    return convert_to_matlab(
        args.csv_file,
        args.output,
    )


if __name__ == "__main__":
    raise SystemExit(main())
