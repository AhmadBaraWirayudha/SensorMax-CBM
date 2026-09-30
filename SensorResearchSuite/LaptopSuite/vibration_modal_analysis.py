#!/usr/bin/env python3
"""
SensorMax Mechanical Vibration / Modal Screening Tool

Offline laptop-side analysis of SensorMax accelerometer recordings.

Supported current schema:
    Timestamp_ms,Machine_ID,Point,Sensor_Type,Sensor_Name,
    Val_0,Val_1,Val_2,Val_3,Val_4,Val_5

The tool also accepts the older positional layout when possible.

Outputs:
    - console report
    - JSON report with reproducible analysis metadata
    - optional spectrum CSV

Important engineering boundary
------------------------------
This is a vibration/modal SCREENING tool. It identifies spectral peaks and
can estimate half-power bandwidth from a measured spectrum. It must not be
presented as laboratory modal testing, certified condition assessment, or a
validated damping measurement without controlled excitation, sensor
calibration, mounting validation, and suitable instrumentation.

The raw source CSV is never modified.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import sys
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

try:
    import numpy as np
except ImportError:
    print("Error: numpy is required. Run first_initialize.bat.")
    sys.exit(1)


ACCELEROMETER_TYPE = 1
DEFAULT_SEGMENT_SECONDS = 2.0
DEFAULT_OVERLAP = 0.5
DEFAULT_TOP_PEAKS = 5
DEFAULT_MIN_FREQ_HZ = 0.5
DEFAULT_MAX_FREQ_HZ = None


def finite_float(value: Any) -> Optional[float]:
    try:
        v = float(value)
        return v if math.isfinite(v) else None
    except (TypeError, ValueError):
        return None


def iso_utc() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def text_or(value: Any, fallback: str = "UNSPECIFIED") -> str:
    if value is None:
        return fallback
    value = str(value).strip()
    return value if value else fallback


def load_accelerometer(csv_path: str) -> Dict[str, Any]:
    if not os.path.exists(csv_path):
        raise FileNotFoundError(f"File not found: {csv_path}")

    timestamps: List[float] = []
    axes: List[List[float]] = [[], [], []]
    machine_ids = set()
    points = set()
    sensor_names = set()
    schema = "unknown"

    with open(csv_path, "r", encoding="utf-8-sig", newline="") as fh:
        reader = csv.reader(fh)
        first = next(reader, None)
        if first is None:
            raise ValueError("CSV file is empty.")

        has_header = "Timestamp_ms" in first or "Sensor_Type" in first

        if has_header:
            header = [str(v).strip() for v in first]
            schema = (
                "SensorMax current named schema"
                if {"Timestamp_ms", "Sensor_Type", "Val_0", "Val_1", "Val_2"}.issubset(header)
                else "named CSV schema"
            )
            dict_reader = csv.DictReader(fh, fieldnames=header)
            for row in dict_reader:
                stype = row.get("Sensor_Type")
                try:
                    is_accel = int(float(stype)) == ACCELEROMETER_TYPE
                except (TypeError, ValueError):
                    is_accel = str(stype).strip().lower() in {"accelerometer", "accel"}
                if not is_accel:
                    continue

                tv = finite_float(row.get("Timestamp_ms"))
                xv = finite_float(row.get("Val_0", row.get("Value_0")))
                yv = finite_float(row.get("Val_1", row.get("Value_1")))
                zv = finite_float(row.get("Val_2", row.get("Value_2")))
                if tv is None or xv is None or yv is None or zv is None:
                    continue

                timestamps.append(tv)
                axes[0].append(xv)
                axes[1].append(yv)
                axes[2].append(zv)

                if row.get("Machine_ID"):
                    machine_ids.add(str(row["Machine_ID"]).strip())
                if row.get("Point"):
                    points.add(str(row["Point"]).strip())
                if row.get("Sensor_Name"):
                    sensor_names.add(str(row["Sensor_Name"]).strip())

        else:
            schema = "legacy positional schema"

            def consume(row: List[str]) -> None:
                if len(row) < 6:
                    return
                try:
                    if int(float(row[1])) != ACCELEROMETER_TYPE:
                        return
                    tv = float(row[0])
                    xv = float(row[3])
                    yv = float(row[4])
                    zv = float(row[5])
                except (ValueError, TypeError):
                    return
                if not all(math.isfinite(v) for v in (tv, xv, yv, zv)):
                    return
                timestamps.append(tv)
                axes[0].append(xv)
                axes[1].append(yv)
                axes[2].append(zv)

            consume(first)
            for row in reader:
                consume(row)

    if len(timestamps) < 8:
        raise ValueError(
            f"Only {len(timestamps)} valid accelerometer samples found; at least 8 are required."
        )

    order = np.argsort(np.asarray(timestamps, dtype=float), kind="stable")
    ts = np.asarray(timestamps, dtype=float)[order]
    data = np.column_stack(
        [np.asarray(axis, dtype=float)[order] for axis in axes]
    )

    return {
        "timestamps_ms": ts,
        "data": data,
        "schema": schema,
        "machine_ids": sorted(machine_ids),
        "points": sorted(points),
        "sensor_names": sorted(sensor_names),
    }


def estimate_fs(timestamps_ms: np.ndarray) -> Tuple[Optional[float], Dict[str, Any]]:
    if len(timestamps_ms) < 2:
        return None, {}
    dt = np.diff(timestamps_ms)
    valid = dt[np.isfinite(dt) & (dt > 0)]
    if valid.size == 0:
        return None, {"valid_intervals": 0}
    median_dt = float(np.median(valid))
    fs = 1000.0 / median_dt if median_dt > 0 else None
    return fs, {
        "valid_intervals": int(valid.size),
        "median_dt_ms": median_dt,
        "mean_dt_ms": float(np.mean(valid)),
        "std_dt_ms": float(np.std(valid)),
        "min_dt_ms": float(np.min(valid)),
        "max_dt_ms": float(np.max(valid)),
    }


def resample_uniform(
    timestamps_ms: np.ndarray,
    data: np.ndarray,
    fs: float,
) -> Tuple[np.ndarray, np.ndarray]:
    """Linearly resample finite, time-ordered data to a uniform grid."""
    keep = np.isfinite(timestamps_ms) & np.isfinite(data).all(axis=1)
    ts = timestamps_ms[keep]
    x = data[keep]
    if len(ts) < 2:
        return ts, x

    unique_ts, unique_idx = np.unique(ts, return_index=True)
    x = x[unique_idx]
    duration_s = (unique_ts[-1] - unique_ts[0]) / 1000.0
    count = max(2, int(round(duration_s * fs)) + 1)
    target = np.linspace(unique_ts[0], unique_ts[-1], count)

    out = np.column_stack(
        [np.interp(target, unique_ts, x[:, i]) for i in range(3)]
    )
    return target, out


def detrend_linear(values: np.ndarray) -> np.ndarray:
    if len(values) < 2:
        return values - np.mean(values)
    n = len(values)
    t = np.arange(n, dtype=float)
    coeff = np.polyfit(t, values, 1)
    return values - np.polyval(coeff, t)


def next_power_of_two(n: int) -> int:
    return 1 if n <= 1 else 2 ** int(math.ceil(math.log2(n)))


def welch_psd(signal: np.ndarray, fs: float, segment_samples: int, overlap: float) -> Tuple[np.ndarray, np.ndarray]:
    """Small dependency-light Welch PSD implementation."""
    n = len(signal)
    segment_samples = min(segment_samples, n)
    if segment_samples < 8:
        raise ValueError("Too few samples for PSD estimation.")

    overlap = min(max(overlap, 0.0), 0.95)
    step = max(1, int(round(segment_samples * (1.0 - overlap))))
    window = np.hanning(segment_samples)
    window_power = float(np.sum(window ** 2))
    nfft = next_power_of_two(segment_samples)

    spectra = []
    for start in range(0, n - segment_samples + 1, step):
        segment = signal[start:start + segment_samples]
        segment = detrend_linear(segment)
        tapered = segment * window
        spectrum = np.abs(np.fft.rfft(tapered, n=nfft)) ** 2
        spectrum /= max(fs * window_power, np.finfo(float).eps)
        spectra.append(spectrum)

    if not spectra:
        segment = detrend_linear(signal)
        tapered = segment * window
        spectrum = np.abs(np.fft.rfft(tapered, n=nfft)) ** 2
        spectrum /= max(fs * window_power, np.finfo(float).eps)
        spectra.append(spectrum)

    psd = np.mean(np.vstack(spectra), axis=0)
    freqs = np.fft.rfftfreq(nfft, d=1.0 / fs)

    if len(psd) > 0:
        psd[0] = 0.0

    return freqs, psd


def find_peaks_simple(values: np.ndarray) -> np.ndarray:
    if len(values) < 3:
        return np.empty(0, dtype=int)
    left = values[:-2]
    center = values[1:-1]
    right = values[2:]
    idx = np.where((center > left) & (center >= right))[0] + 1
    return idx.astype(int)


def top_spectral_peaks(
    freqs: np.ndarray,
    psd: np.ndarray,
    min_freq: float,
    max_freq: Optional[float],
    count: int,
) -> List[Dict[str, float]]:
    mask = freqs >= min_freq
    if max_freq is not None:
        mask &= freqs <= max_freq
    idx_pool = np.where(mask)[0]
    if idx_pool.size == 0:
        return []

    local_psd = psd[idx_pool]
    local_peaks = find_peaks_simple(local_psd)
    if local_peaks.size == 0:
        local_peaks = np.array([int(np.argmax(local_psd))])

    candidates = idx_pool[local_peaks]
    ranked = sorted(candidates, key=lambda i: psd[i], reverse=True)

    output: List[Dict[str, float]] = []
    seen = []
    for i in ranked:
        f = float(freqs[i])
        # Avoid reporting several adjacent bins as separate modes.
        if any(abs(f - previous) < max(float(freqs[1] if len(freqs) > 1 else 1.0), 0.25) for previous in seen):
            continue
        seen.append(f)
        output.append({
            "frequency_hz": f,
            "psd": float(psd[i]),
        })
        if len(output) >= count:
            break
    return output


def half_power_bandwidth(
    freqs: np.ndarray,
    psd: np.ndarray,
    peak_index: int,
) -> Dict[str, Optional[float]]:
    peak = float(psd[peak_index])
    if peak <= 0:
        return {"f1_hz": None, "f2_hz": None, "bandwidth_hz": None, "damping_ratio": None}

    level = peak / 2.0
    left = peak_index
    while left > 0 and psd[left] >= level:
        left -= 1

    right = peak_index
    while right < len(psd) - 1 and psd[right] >= level:
        right += 1

    # No valid lower/upper crossing means the half-power estimate is not supported.
    if left == 0 or right == len(psd) - 1:
        return {"f1_hz": None, "f2_hz": None, "bandwidth_hz": None, "damping_ratio": None}

    f1 = float(freqs[left])
    f2 = float(freqs[right])
    fn = float(freqs[peak_index])
    bandwidth = max(0.0, f2 - f1)
    damping = bandwidth / (2.0 * fn) if fn > 0 else None

    return {
        "f1_hz": f1,
        "f2_hz": f2,
        "bandwidth_hz": bandwidth,
        "damping_ratio": damping,
    }


def axis_spectrum(
    signal: np.ndarray,
    fs: float,
    segment_seconds: float,
    overlap: float,
    min_freq: float,
    max_freq: Optional[float],
    top_peaks: int,
) -> Dict[str, Any]:
    segment_samples = max(8, int(round(segment_seconds * fs)))
    freqs, psd = welch_psd(signal, fs, segment_samples, overlap)
    peaks = top_spectral_peaks(freqs, psd, min_freq, max_freq, top_peaks)

    valid_mask = freqs >= min_freq
    if max_freq is not None:
        valid_mask &= freqs <= max_freq
    valid_idx = np.where(valid_mask)[0]

    primary = None
    if valid_idx.size:
        peak_index = int(valid_idx[np.argmax(psd[valid_idx])])
        hp = half_power_bandwidth(freqs, psd, peak_index)
        primary = {
            "frequency_hz": float(freqs[peak_index]),
            "psd": float(psd[peak_index]),
            "half_power": hp,
        }

    return {
        "frequency_resolution_hz": float(freqs[1] - freqs[0]) if len(freqs) > 1 else None,
        "primary_peak": primary,
        "top_peaks": peaks,
        "frequencies_hz": freqs.tolist(),
        "psd": psd.tolist(),
    }


def analyze(data_bundle: Dict[str, Any], args: argparse.Namespace) -> Dict[str, Any]:
    ts = data_bundle["timestamps_ms"]
    data = data_bundle["data"]

    measured_fs, timing = estimate_fs(ts)
    if measured_fs is None:
        raise ValueError("Could not estimate sampling frequency from timestamps.")

    analysis_fs = args.sample_rate if args.sample_rate else measured_fs
    if analysis_fs <= 0:
        raise ValueError("Analysis sample rate must be > 0.")

    uniform_ts, uniform_data = resample_uniform(ts, data, analysis_fs)
    if len(uniform_data) < 8:
        raise ValueError("Too few samples after uniform resampling.")

    # Dynamic magnitude is useful for screening but does not replace per-axis analysis.
    magnitude = np.linalg.norm(uniform_data, axis=1)
    dynamic_magnitude = magnitude - np.mean(magnitude)

    axes = {}
    names = ["X", "Y", "Z"]
    for i, name in enumerate(names):
        axes[name] = axis_spectrum(
            uniform_data[:, i],
            analysis_fs,
            args.segment_seconds,
            args.overlap,
            args.min_freq,
            args.max_freq,
            args.top_peaks,
        )

    magnitude_spectrum = axis_spectrum(
        dynamic_magnitude,
        analysis_fs,
        args.segment_seconds,
        args.overlap,
        args.min_freq,
        args.max_freq,
        args.top_peaks,
    )

    return {
        "format": "SensorMaxModalScreeningReport",
        "schema_version": 1,
        "created_utc": iso_utc(),
        "source_csv": os.path.abspath(args.csv_file),
        "input_schema": data_bundle["schema"],
        "machine_ids": data_bundle["machine_ids"],
        "points": data_bundle["points"],
        "sensor_names": data_bundle["sensor_names"],
        "sample_count": int(len(data)),
        "duration_s": float((ts[-1] - ts[0]) / 1000.0),
        "measured_sampling": {
            "estimated_hz": float(measured_fs),
            **timing,
        },
        "analysis_sampling_hz": float(analysis_fs),
        "resampled_count": int(len(uniform_data)),
        "configuration": {
            "segment_seconds": float(args.segment_seconds),
            "overlap": float(args.overlap),
            "minimum_frequency_hz": float(args.min_freq),
            "maximum_frequency_hz": (
                float(args.max_freq) if args.max_freq is not None else None
            ),
            "top_peaks": int(args.top_peaks),
        },
        "axes": axes,
        "dynamic_magnitude": magnitude_spectrum,
        "engineering_boundary": (
            "Spectral peaks and half-power bandwidth are screening results. "
            "They do not by themselves establish a structural mode, certified "
            "damping ratio, machine severity, or root cause."
        ),
    }


def write_json(report: Dict[str, Any], path: str) -> None:
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2, ensure_ascii=False)


def write_spectrum_csv(report: Dict[str, Any], path: str) -> None:
    freq = report["axes"]["X"]["frequencies_hz"]
    rows = []
    axis_data = report["axes"]
    mag_data = report["dynamic_magnitude"]

    for i, f in enumerate(freq):
        rows.append([
            f,
            axis_data["X"]["psd"][i],
            axis_data["Y"]["psd"][i],
            axis_data["Z"]["psd"][i],
            mag_data["psd"][i] if i < len(mag_data["psd"]) else "",
        ])

    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow([
            "Frequency_Hz",
            "PSD_X_(m_s2)^2_per_Hz",
            "PSD_Y_(m_s2)^2_per_Hz",
            "PSD_Z_(m_s2)^2_per_Hz",
            "PSD_DynamicMagnitude_(m_s2)^2_per_Hz",
        ])
        writer.writerows(rows)


def print_report(report: Dict[str, Any]) -> None:
    print("=" * 72)
    print(" SENSORMAX VIBRATION / MODAL SCREENING REPORT")
    print("=" * 72)
    print(f"Source            : {report['source_csv']}")
    print(f"Input schema      : {report['input_schema']}")
    print(f"Machine IDs       : {', '.join(report['machine_ids']) or 'UNSPECIFIED'}")
    print(f"Points            : {', '.join(report['points']) or 'UNSPECIFIED'}")
    print(f"Samples           : {report['sample_count']}")
    print(f"Duration          : {report['duration_s']:.3f} s")
    print(f"Measured rate     : {report['measured_sampling']['estimated_hz']:.3f} Hz")
    print(f"Analysis rate     : {report['analysis_sampling_hz']:.3f} Hz")
    print("-" * 72)

    for axis in ("X", "Y", "Z", "dynamic_magnitude"):
        item = report["axes"][axis] if axis in report["axes"] else report[axis]
        label = axis if axis != "dynamic_magnitude" else "RSS/Dynamic magnitude"
        primary = item.get("primary_peak")
        if primary is None:
            print(f"{label:<24}: no supported spectral peak")
            continue
        print(
            f"{label:<24}: {primary['frequency_hz']:.3f} Hz | "
            f"PSD={primary['psd']:.6g}"
        )
        hp = primary["half_power"]
        if hp["damping_ratio"] is not None:
            print(
                f"{'  half-power':<24}: "
                f"{hp['f1_hz']:.3f}–{hp['f2_hz']:.3f} Hz | "
                f"BW={hp['bandwidth_hz']:.3f} Hz | "
                f"ζ={hp['damping_ratio']:.5f}"
            )
        else:
            print(f"{'  half-power':<24}: not bounded in spectrum")

    print("-" * 72)
    print("Engineering boundary : screening only; not certified modal testing")
    print("=" * 72)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="SensorMax vibration/modal spectral screening tool."
    )
    p.add_argument("csv_file", help="SensorMax raw accelerometer CSV")
    p.add_argument(
        "--sample-rate",
        type=float,
        default=None,
        help="Optional fixed analysis rate; default uses timestamp estimate.",
    )
    p.add_argument(
        "--segment-seconds",
        type=float,
        default=DEFAULT_SEGMENT_SECONDS,
        help=f"Welch segment duration (default {DEFAULT_SEGMENT_SECONDS}).",
    )
    p.add_argument(
        "--overlap",
        type=float,
        default=DEFAULT_OVERLAP,
        help=f"Welch overlap fraction (default {DEFAULT_OVERLAP}).",
    )
    p.add_argument(
        "--min-freq",
        type=float,
        default=DEFAULT_MIN_FREQ_HZ,
        help=f"Minimum frequency to consider (default {DEFAULT_MIN_FREQ_HZ} Hz).",
    )
    p.add_argument(
        "--max-freq",
        type=float,
        default=DEFAULT_MAX_FREQ_HZ,
        help="Maximum frequency to consider; default is Nyquist.",
    )
    p.add_argument(
        "--top-peaks",
        type=int,
        default=DEFAULT_TOP_PEAKS,
        help=f"Number of spectral peaks per channel (default {DEFAULT_TOP_PEAKS}).",
    )
    p.add_argument(
        "--output-json",
        default=None,
        help="Optional JSON report path; default <input>_modal_report.json.",
    )
    p.add_argument(
        "--output-spectrum",
        default=None,
        help="Optional spectrum CSV path; default <input>_spectrum.csv.",
    )
    return p


def main() -> int:
    args = build_parser().parse_args()

    if args.segment_seconds <= 0:
        print("[ERROR] --segment-seconds must be > 0.")
        return 2
    if not 0 <= args.overlap < 1:
        print("[ERROR] --overlap must be in [0, 1).")
        return 2
    if args.min_freq < 0:
        print("[ERROR] --min-freq must be >= 0.")
        return 2
    if args.max_freq is not None and args.max_freq <= args.min_freq:
        print("[ERROR] --max-freq must be greater than --min-freq.")
        return 2
    if args.top_peaks < 1:
        print("[ERROR] --top-peaks must be >= 1.")
        return 2

    try:
        bundle = load_accelerometer(args.csv_file)
        report = analyze(bundle, args)
    except Exception as exc:
        print(f"[ERROR] {exc}")
        return 1

    output_json = args.output_json or (
        os.path.splitext(args.csv_file)[0] + "_modal_report.json"
    )
    output_spectrum = args.output_spectrum or (
        os.path.splitext(args.csv_file)[0] + "_spectrum.csv"
    )

    write_json(report, output_json)
    write_spectrum_csv(report, output_spectrum)
    print_report(report)

    print(f"\n[SUCCESS] JSON report    : {os.path.abspath(output_json)}")
    print(f"[SUCCESS] Spectrum CSV   : {os.path.abspath(output_spectrum)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
