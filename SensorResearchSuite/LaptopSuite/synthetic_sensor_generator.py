#!/usr/bin/env python3
"""
SensorMax Synthetic Sensor Dataset Generator

Generates deterministic, synthetic multi-sensor recordings for desktop
pipeline development, regression testing, and visualization.

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

Sensor types used by the current Android project:
    1 = Accelerometer
    2 = Magnetometer
    4 = Gyroscope

Profiles retained/adapted from the original research generator:
    stationary
    walking
    running
    rotation_3d
    tremor

Additional CBM-oriented profile:
    machine_vibration

Notes
-----
The generated signals are synthetic test fixtures. They are not measured
machine data and must not be presented as actual equipment observations.

The original Oppo-specific identity has been removed. Machine identity,
measurement point, and sensor name are explicit generator parameters.

AndroidApp is not modified.
"""

from __future__ import annotations

import argparse
import csv
import math
import os
import sys
from typing import Dict, Tuple

try:
    import numpy as np
except ImportError:
    print(
        "Error: numpy is required. "
        "Run first_initialize.bat."
    )
    sys.exit(1)


ACCEL = 1
MAG = 2
GYRO = 4

DEFAULT_START_TS = 1_700_000_000_000.0


def noise(
    rng: np.random.Generator,
    size: int,
    sigma: float,
) -> np.ndarray:
    """Gaussian noise helper."""
    if sigma <= 0:
        return np.zeros(size)

    return rng.normal(
        0.0,
        sigma,
        size,
    )


def generate_motion(
    profile: str,
    t: np.ndarray,
    rng: np.random.Generator,
) -> Dict[str, np.ndarray]:
    """
    Generate synthetic physical channels.

    The profiles intentionally remain simple analytical test signals so
    expected FFT components are known.
    """
    n = t.size
    g = 9.80665

    if profile == "stationary":
        acc_x = noise(rng, n, 0.05)
        acc_y = g + noise(rng, n, 0.05)
        acc_z = 0.10 + noise(rng, n, 0.05)

        gyro_x = noise(rng, n, 0.005)
        gyro_y = noise(rng, n, 0.005)
        gyro_z = noise(rng, n, 0.005)

        mag_x = 20.0 + noise(rng, n, 0.20)
        mag_y = -15.0 + noise(rng, n, 0.20)
        mag_z = 35.0 + noise(rng, n, 0.20)

    elif profile == "walking":
        step_freq = 1.8

        acc_x = (
            1.2 * np.sin(2 * np.pi * step_freq * t)
            + 0.3 * np.sin(
                2 * np.pi * 2.0 * step_freq * t
            )
            + noise(rng, n, 0.10)
        )

        acc_y = (
            g
            + 2.5 * np.cos(
                2 * np.pi * step_freq * t
            )
            + noise(rng, n, 0.10)
        )

        acc_z = (
            0.8 * np.sin(
                2 * np.pi * step_freq * t + 0.5
            )
            + noise(rng, n, 0.10)
        )

        gyro_x = (
            0.8 * np.cos(
                2 * np.pi * step_freq * t
            )
            + noise(rng, n, 0.02)
        )

        gyro_y = (
            0.4 * np.sin(
                2 * np.pi * step_freq * t
            )
            + noise(rng, n, 0.02)
        )

        gyro_z = (
            0.6 * np.sin(
                np.pi * step_freq * t
            )
            + noise(rng, n, 0.02)
        )

        mag_x = (
            20.0
            + 3.0 * np.sin(
                2 * np.pi * 0.2 * t
            )
            + noise(rng, n, 0.30)
        )

        mag_y = (
            -15.0
            + 3.0 * np.cos(
                2 * np.pi * 0.2 * t
            )
            + noise(rng, n, 0.30)
        )

        mag_z = (
            35.0
            + noise(rng, n, 0.30)
        )

    elif profile == "running":
        step_freq = 2.8

        acc_x = (
            3.5 * np.sin(
                2 * np.pi * step_freq * t
            )
            + noise(rng, n, 0.30)
        )

        acc_y = (
            g
            + 6.0 * np.cos(
                2 * np.pi * step_freq * t
            )
            + noise(rng, n, 0.30)
        )

        acc_z = (
            2.5 * np.sin(
                2 * np.pi * step_freq * t + 0.8
            )
            + noise(rng, n, 0.30)
        )

        gyro_x = (
            2.2 * np.cos(
                2 * np.pi * step_freq * t
            )
            + noise(rng, n, 0.05)
        )

        gyro_y = (
            1.5 * np.sin(
                2 * np.pi * step_freq * t
            )
            + noise(rng, n, 0.05)
        )

        gyro_z = (
            1.8 * np.sin(
                2 * np.pi * step_freq * t
            )
            + noise(rng, n, 0.05)
        )

        mag_x = (
            20.0
            + 5.0 * np.sin(
                2 * np.pi * 0.5 * t
            )
            + noise(rng, n, 0.50)
        )

        mag_y = (
            -15.0
            + 5.0 * np.cos(
                2 * np.pi * 0.5 * t
            )
            + noise(rng, n, 0.50)
        )

        mag_z = (
            35.0
            + noise(rng, n, 0.50)
        )

    elif profile == "rotation_3d":
        rot_speed = 0.5

        acc_x = (
            g
            * np.sin(
                2 * np.pi * rot_speed * t
            )
            + noise(rng, n, 0.05)
        )

        acc_y = (
            g
            * np.cos(
                2 * np.pi * rot_speed * t
            )
            * np.cos(
                2 * np.pi * rot_speed * 0.5 * t
            )
            + noise(rng, n, 0.05)
        )

        acc_z = (
            g
            * np.cos(
                2 * np.pi * rot_speed * t
            )
            * np.sin(
                2 * np.pi * rot_speed * 0.5 * t
            )
            + noise(rng, n, 0.05)
        )

        gyro_x = (
            2
            * np.pi
            * rot_speed
            + noise(rng, n, 0.01)
        )

        gyro_y = (
            2
            * np.pi
            * rot_speed
            * 0.5
            + noise(rng, n, 0.01)
        )

        gyro_z = (
            2
            * np.pi
            * rot_speed
            * 0.25
            + noise(rng, n, 0.01)
        )

        mag_x = (
            40.0
            * np.sin(
                2 * np.pi * rot_speed * t
            )
            + noise(rng, n, 0.20)
        )

        mag_y = (
            40.0
            * np.cos(
                2 * np.pi * rot_speed * t
            )
            + noise(rng, n, 0.20)
        )

        mag_z = (
            20.0
            * np.sin(
                2
                * np.pi
                * rot_speed
                * 0.5
                * t
            )
            + noise(rng, n, 0.20)
        )

    elif profile == "tremor":
        tremor_freq = 6.5

        acc_x = (
            0.8 * np.sin(
                2 * np.pi * tremor_freq * t
            )
            + noise(rng, n, 0.05)
        )

        acc_y = (
            g
            + 0.8 * np.cos(
                2 * np.pi * tremor_freq * t
            )
            + noise(rng, n, 0.05)
        )

        acc_z = (
            0.5 * np.sin(
                2 * np.pi * tremor_freq * t + 1.2
            )
            + noise(rng, n, 0.05)
        )

        gyro_x = (
            0.6 * np.cos(
                2 * np.pi * tremor_freq * t
            )
            + noise(rng, n, 0.01)
        )

        gyro_y = (
            0.6 * np.sin(
                2 * np.pi * tremor_freq * t
            )
            + noise(rng, n, 0.01)
        )

        gyro_z = (
            0.3 * np.sin(
                2 * np.pi * tremor_freq * t
            )
            + noise(rng, n, 0.01)
        )

        mag_x = (
            20.0
            + noise(rng, n, 0.20)
        )

        mag_y = (
            -15.0
            + noise(rng, n, 0.20)
        )

        mag_z = (
            35.0
            + noise(rng, n, 0.20)
        )

    elif profile == "machine_vibration":
        # CBM-oriented synthetic fixture:
        # dominant 12 Hz component + 24 Hz harmonic + small broadband noise.
        f1 = 12.0
        f2 = 24.0
        f3 = 36.0

        acc_x = (
            0.80 * np.sin(
                2 * np.pi * f1 * t
            )
            + 0.25 * np.sin(
                2 * np.pi * f2 * t
            )
            + 0.08 * np.sin(
                2 * np.pi * f3 * t
            )
            + noise(rng, n, 0.04)
        )

        acc_y = (
            g
            + 0.35 * np.sin(
                2 * np.pi * f1 * t
            )
            + 0.12 * np.sin(
                2 * np.pi * f2 * t
            )
            + noise(rng, n, 0.04)
        )

        acc_z = (
            0.55 * np.sin(
                2 * np.pi * f1 * t + 0.8
            )
            + 0.18 * np.sin(
                2 * np.pi * f2 * t
            )
            + noise(rng, n, 0.04)
        )

        gyro_x = (
            0.20 * np.sin(
                2 * np.pi * f1 * t
            )
            + noise(rng, n, 0.01)
        )

        gyro_y = (
            0.12 * np.sin(
                2 * np.pi * f2 * t
            )
            + noise(rng, n, 0.01)
        )

        gyro_z = noise(
            rng,
            n,
            0.01,
        )

        mag_x = (
            20.0
            + noise(rng, n, 0.25)
        )

        mag_y = (
            -15.0
            + noise(rng, n, 0.25)
        )

        mag_z = (
            35.0
            + noise(rng, n, 0.25)
        )

    else:
        raise ValueError(
            f"Unsupported profile: {profile}"
        )

    return {
        "acc_x": acc_x,
        "acc_y": acc_y,
        "acc_z": acc_z,
        "gyro_x": gyro_x,
        "gyro_y": gyro_y,
        "gyro_z": gyro_z,
        "mag_x": mag_x,
        "mag_y": mag_y,
        "mag_z": mag_z,
    }


def generate_synthetic_dataset(
    *,
    profile: str = "machine_vibration",
    duration_sec: float = 10.0,
    rate_hz: float = 100.0,
    output_csv: str = "synthetic_sensormax_dataset.csv",
    machine_id: str = "SYNTH-MACHINE-01",
    point: str = "SYNTH-POINT-01",
    seed: int = 151101,
    start_timestamp_ms: float = DEFAULT_START_TS,
) -> int:
    """
    Generate a deterministic SensorMax raw recording.

    Accelerometer is emitted at the requested rate.
    Gyroscope is emitted with a small timestamp offset.
    Magnetometer is emitted at one-fifth the requested rate, when possible.
    """

    if duration_sec <= 0:
        raise ValueError(
            "duration must be > 0."
        )

    if rate_hz <= 0:
        raise ValueError(
            "rate must be > 0."
        )

    if seed is None:
        seed = 151101

    n_samples = int(
        round(
            duration_sec
            * rate_hz
        )
    )

    if n_samples < 2:
        raise ValueError(
            "duration * rate must produce at least 2 samples."
        )

    dt_ms = 1000.0 / rate_hz

    # Endpoint excluded so the requested rate is represented directly.
    t = np.arange(
        n_samples,
        dtype=float,
    ) / rate_hz

    rng = np.random.default_rng(
        seed
    )

    channels = generate_motion(
        profile,
        t,
        rng,
    )

    rows = []

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

    for i in range(n_samples):
        ts = (
            start_timestamp_ms
            + i * dt_ms
        )

        # Accelerometer.
        rows.append(
            [
                f"{ts:.3f}",
                machine_id,
                point,
                ACCEL,
                "Synthetic Accelerometer",
                f"{channels['acc_x'][i]:.6f}",
                f"{channels['acc_y'][i]:.6f}",
                f"{channels['acc_z'][i]:.6f}",
                "",
                "",
                "",
            ]
        )

        # Gyroscope.
        rows.append(
            [
                f"{ts + min(0.2, dt_ms * 0.25):.3f}",
                machine_id,
                point,
                GYRO,
                "Synthetic Gyroscope",
                f"{channels['gyro_x'][i]:.6f}",
                f"{channels['gyro_y'][i]:.6f}",
                f"{channels['gyro_z'][i]:.6f}",
                "",
                "",
                "",
            ]
        )

        # Magnetometer at approximately one-fifth the accel rate.
        if (
            i % 5 == 0
            or rate_hz < 25.0
        ):
            rows.append(
                [
                    f"{ts + min(0.5, dt_ms * 0.5):.3f}",
                    machine_id,
                    point,
                    MAG,
                    "Synthetic Magnetometer",
                    f"{channels['mag_x'][i]:.6f}",
                    f"{channels['mag_y'][i]:.6f}",
                    f"{channels['mag_z'][i]:.6f}",
                    "",
                    "",
                    "",
                ]
            )

    output_dir = os.path.dirname(
        os.path.abspath(output_csv)
    )

    os.makedirs(
        output_dir,
        exist_ok=True,
    )

    with open(
        output_csv,
        "w",
        newline="",
        encoding="utf-8",
    ) as handle:
        writer = csv.writer(handle)

        writer.writerow(header)
        writer.writerows(rows)

    print("=" * 68)
    print(
        " SensorMax SYNTHETIC DATASET GENERATOR"
    )
    print("=" * 68)
    print(
        f"Profile           : {profile}"
    )
    print(
        f"Duration          : {duration_sec:.3f} s"
    )
    print(
        f"Requested rate    : {rate_hz:.3f} Hz"
    )
    print(
        f"Accelerometer     : {n_samples} samples"
    )
    print(
        f"Total packets     : {len(rows)}"
    )
    print(
        f"Machine ID        : {machine_id}"
    )
    print(
        f"Measurement Point : {point}"
    )
    print(
        f"Seed              : {seed}"
    )
    print(
        f"Output            : {os.path.abspath(output_csv)}"
    )
    print(
        "Data type         : synthetic test fixture"
    )
    print("=" * 68)

    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Generate deterministic synthetic SensorMax datasets."
        )
    )

    parser.add_argument(
        "--profile",
        choices=[
            "stationary",
            "walking",
            "running",
            "rotation_3d",
            "tremor",
            "machine_vibration",
        ],
        default="machine_vibration",
        help=(
            "Synthetic signal profile "
            "(default: machine_vibration)."
        ),
    )

    parser.add_argument(
        "--duration",
        type=float,
        default=10.0,
        help="Duration in seconds (default: 10).",
    )

    parser.add_argument(
        "--rate",
        type=float,
        default=100.0,
        help="Accelerometer rate in Hz (default: 100).",
    )

    parser.add_argument(
        "--output",
        default="synthetic_sensormax_dataset.csv",
        help=(
            "Output CSV path "
            "(default: synthetic_sensormax_dataset.csv)."
        ),
    )

    parser.add_argument(
        "--machine-id",
        default="SYNTH-MACHINE-01",
        help="Machine identifier.",
    )

    parser.add_argument(
        "--point",
        default="SYNTH-POINT-01",
        help="Measurement point identifier.",
    )

    parser.add_argument(
        "--seed",
        type=int,
        default=151101,
        help="Random seed (default: 151101).",
    )

    parser.add_argument(
        "--start-timestamp-ms",
        type=float,
        default=DEFAULT_START_TS,
        help="Synthetic starting epoch timestamp in milliseconds.",
    )

    return parser


def main() -> int:
    args = build_parser().parse_args()

    try:
        return generate_synthetic_dataset(
            profile=args.profile,
            duration_sec=args.duration,
            rate_hz=args.rate,
            output_csv=args.output,
            machine_id=args.machine_id,
            point=args.point,
            seed=args.seed,
            start_timestamp_ms=args.start_timestamp_ms,
        )
    except Exception as exc:
        print(
            f"[ERROR] {exc}"
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(
        main()
    )
