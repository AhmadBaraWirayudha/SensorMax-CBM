#!/usr/bin/env python3
"""
SensorMax Interactive Terminal Data Explorer

Interactive CLI for:
    load <file.csv>
    info
    stats
    plot <x|y|z|rss>
    fft [x|y|z|rss]
    analysis
    export <file.csv>
    help
    exit

Supports the current SensorMax raw CSV schema:

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

Also supports the current desktop analysis CSV schema when loaded.

AndroidApp is not modified.
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
from typing import Any, Dict, List, Optional, Tuple

try:
    import numpy as np
except ImportError:
    print("Error: numpy is required. Run first_initialize.bat.")
    sys.exit(1)


ACCEL_SENSOR_TYPE = 1


def as_float(value: Any) -> Optional[float]:
    try:
        if value is None or value == "":
            return None
        number = float(value)
        return number if np.isfinite(number) else None
    except (TypeError, ValueError):
        return None


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
        "4": 4,
        "gyroscope": 4,
        "gyro": 4,
    }

    if text in aliases:
        return aliases[text]

    try:
        return int(float(text))
    except (TypeError, ValueError):
        return None


def ascii_plot(
    values: np.ndarray,
    width: int = 70,
    height: int = 14,
) -> str:
    """Render a compact terminal plot."""
    if values.size == 0:
        return "No data."

    step = max(1, int(np.ceil(values.size / width)))
    sample = values[::step]

    if sample.size > width:
        sample = sample[:width]

    low = float(np.min(sample))
    high = float(np.max(sample))
    span = max(high - low, 1e-12)

    grid = [
        [" " for _ in range(sample.size)]
        for _ in range(height)
    ]

    for col, value in enumerate(sample):
        ratio = (float(value) - low) / span
        row = height - 1 - int(
            ratio * (height - 1)
        )
        row = max(0, min(height - 1, row))
        grid[row][col] = "*"

    lines = [
        f"Max: {high:+.5f}",
    ]

    for row in grid:
        lines.append("|" + "".join(row) + "|")

    lines.append(
        f"Min: {low:+.5f}"
    )

    return "\n".join(lines)


class SensorDataset:
    def __init__(self) -> None:
        self.path: Optional[str] = None
        self.schema = "none"
        self.rows: List[Dict[str, Any]] = []

        self.timestamps_ms = np.array(
            [],
            dtype=float,
        )

        self.x = np.array(
            [],
            dtype=float,
        )

        self.y = np.array(
            [],
            dtype=float,
        )

        self.z = np.array(
            [],
            dtype=float,
        )

        self.machine_ids: List[str] = []
        self.points: List[str] = []
        self.sensor_names: List[str] = []

        self.analysis_rows: List[Dict[str, Any]] = []

    @property
    def loaded(self) -> bool:
        return bool(
            self.path
            and self.rows
        )

    def clear(self) -> None:
        self.__init__()

    def load(self, path: str) -> None:
        if not os.path.exists(path):
            raise FileNotFoundError(
                f"File not found: {path}"
            )

        with open(
            path,
            "r",
            encoding="utf-8-sig",
            newline="",
        ) as handle:
            reader = csv.reader(handle)
            header = next(
                reader,
                None,
            )

            if header is None:
                raise ValueError(
                    "CSV file is empty."
                )

            header = [
                str(value).strip()
                for value in header
            ]

            # Current named raw schema.
            if (
                "Timestamp_ms" in header
                or "Sensor_Type" in header
                or "Machine_ID" in header
            ):
                self._load_named_csv(
                    handle,
                    header,
                )
            else:
                self._load_legacy_csv(
                    handle,
                    header,
                )

        self.path = os.path.abspath(path)

    def _load_named_csv(
        self,
        handle: Any,
        header: List[str],
    ) -> None:
        self.rows = []

        dict_reader = csv.DictReader(
            handle,
            fieldnames=header,
        )

        for row in dict_reader:
            if row:
                self.rows.append(
                    {
                        str(k).strip(): v
                        for k, v in row.items()
                        if k is not None
                    }
                )

        if {
            "Timestamp_ms",
            "Sensor_Type",
            "Val_0",
            "Val_1",
            "Val_2",
        }.issubset(set(header)):
            self.schema = "SensorMax current raw"
            self._extract_accelerometer()
        elif "Overall_RMS_Accel_ms2" in header:
            self.schema = "SensorMax analysis"
            self.analysis_rows = self.rows
            self._extract_analysis_context()
        else:
            self.schema = "named CSV"
            self._extract_accelerometer()

    def _load_legacy_csv(
        self,
        handle: Any,
        first_row: List[str],
    ) -> None:
        rows: List[Dict[str, Any]] = []

        def convert(
            values: List[str],
        ) -> Optional[Dict[str, Any]]:
            if len(values) < 6:
                return None

            return {
                "Timestamp_ms": values[0],
                "Sensor_Type": values[1],
                "Sensor_Name": (
                    values[2]
                    if len(values) > 2
                    else ""
                ),
                "Val_0": (
                    values[3]
                    if len(values) > 3
                    else ""
                ),
                "Val_1": (
                    values[4]
                    if len(values) > 4
                    else ""
                ),
                "Val_2": (
                    values[5]
                    if len(values) > 5
                    else ""
                ),
            }

        first = convert(first_row)

        if first:
            rows.append(first)

        for values in handle:
            row = convert(values)
            if row:
                rows.append(row)

        self.rows = rows
        self.schema = "legacy positional"
        self._extract_accelerometer()

    def _extract_accelerometer(self) -> None:
        ts: List[float] = []
        x: List[float] = []
        y: List[float] = []
        z: List[float] = []

        machine_ids = set()
        points = set()
        names = set()

        for row in self.rows:
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

            if None in (
                timestamp,
                xv,
                yv,
                zv,
            ):
                continue

            ts.append(float(timestamp))
            x.append(float(xv))
            y.append(float(yv))
            z.append(float(zv))

            if row.get("Machine_ID"):
                machine_ids.add(
                    str(
                        row["Machine_ID"]
                    ).strip()
                )

            if row.get("Point"):
                points.add(
                    str(
                        row["Point"]
                    ).strip()
                )

            if row.get("Sensor_Name"):
                names.add(
                    str(
                        row["Sensor_Name"]
                    ).strip()
                )

        self.timestamps_ms = np.asarray(
            ts,
            dtype=float,
        )

        self.x = np.asarray(
            x,
            dtype=float,
        )

        self.y = np.asarray(
            y,
            dtype=float,
        )

        self.z = np.asarray(
            z,
            dtype=float,
        )

        self.machine_ids = sorted(
            machine_ids
        )

        self.points = sorted(
            points
        )

        self.sensor_names = sorted(
            names
        )

    def _extract_analysis_context(self) -> None:
        machine_ids = set()
        points = set()

        for row in self.analysis_rows:
            if row.get("Machine_ID"):
                machine_ids.add(
                    str(
                        row["Machine_ID"]
                    ).strip()
                )

            if row.get("Point"):
                points.add(
                    str(
                        row["Point"]
                    ).strip()
                )

        self.machine_ids = sorted(
            machine_ids
        )

        self.points = sorted(
            points
        )

    def sample_rate_hz(self) -> Optional[float]:
        if self.timestamps_ms.size < 2:
            return None

        delta = np.diff(
            self.timestamps_ms
        )

        delta = delta[
            np.isfinite(delta)
            & (delta > 0)
        ]

        if delta.size == 0:
            return None

        median_dt = float(
            np.median(delta)
        )

        if median_dt <= 0:
            return None

        return 1000.0 / median_dt

    def duration_s(self) -> Optional[float]:
        if self.timestamps_ms.size < 2:
            return None

        return float(
            (
                self.timestamps_ms[-1]
                - self.timestamps_ms[0]
            )
            / 1000.0
        )

    def statistics(
        self,
        values: np.ndarray,
    ) -> Dict[str, float]:
        if values.size == 0:
            return {}

        return {
            "min": float(
                np.min(values)
            ),
            "max": float(
                np.max(values)
            ),
            "mean": float(
                np.mean(values)
            ),
            "std": float(
                np.std(values)
            ),
            "rms": float(
                np.sqrt(
                    np.mean(
                        values ** 2
                    )
                )
            ),
        }

    def fft(
        self,
        values: np.ndarray,
    ) -> Tuple[
        np.ndarray,
        np.ndarray,
        Optional[float],
    ]:
        fs = self.sample_rate_hz()

        if (
            values.size < 8
            or fs is None
            or fs <= 0
        ):
            return (
                np.array([]),
                np.array([]),
                None,
            )

        centered = (
            values
            - np.mean(values)
        )

        spectrum = np.fft.rfft(
            centered
        )

        amplitude = (
            2.0
            * np.abs(spectrum)
            / values.size
        )

        frequencies = (
            np.fft.rfftfreq(
                values.size,
                d=1.0 / fs,
            )
        )

        if frequencies.size <= 1:
            return (
                frequencies,
                amplitude,
                None,
            )

        peak_index = 1 + int(
            np.argmax(
                amplitude[1:]
            )
        )

        return (
            frequencies,
            amplitude,
            float(
                frequencies[
                    peak_index
                ]
            ),
        )

    def print_info(self) -> None:
        if not self.loaded:
            print("No dataset loaded.")
            return

        print("=" * 64)
        print(" SensorMax DATASET INFO")
        print("=" * 64)

        print(
            f"File          : {self.path}"
        )

        print(
            f"Schema        : {self.schema}"
        )

        print(
            f"Rows          : {len(self.rows)}"
        )

        if self.timestamps_ms.size:
            print(
                f"Accel samples : "
                f"{self.timestamps_ms.size}"
            )

            duration = self.duration_s()

            print(
                f"Duration      : "
                f"{duration:.3f} s"
                if duration is not None
                else "Duration      : unavailable"
            )

            rate = self.sample_rate_hz()

            print(
                f"Sample rate   : "
                f"{rate:.3f} Hz"
                if rate is not None
                else "Sample rate   : unavailable"
            )

            print(
                "Machine ID(s) : "
                + ", ".join(
                    self.machine_ids
                    or ["UNSPECIFIED"]
                )
            )

            print(
                "Point(s)      : "
                + ", ".join(
                    self.points
                    or ["UNSPECIFIED"]
                )
            )

            print(
                "Sensor name(s): "
                + ", ".join(
                    self.sensor_names
                    or ["UNSPECIFIED"]
                )
            )

        if self.analysis_rows:
            print(
                f"Analysis rows : "
                f"{len(self.analysis_rows)}"
            )

        print("=" * 64)

    def print_stats(self) -> None:
        if self.x.size == 0:
            if self.analysis_rows:
                print(
                    "Dataset is an analysis CSV; "
                    "no raw X/Y/Z vectors available."
                )
            else:
                print("No accelerometer data loaded.")
            return

        print("--- Accelerometer Statistics ---")

        for name, values in (
            ("X", self.x),
            ("Y", self.y),
            ("Z", self.z),
        ):
            stat = self.statistics(
                values
            )

            print(
                f"Axis {name}: "
                f"Min={stat['min']:+.5f} "
                f"Max={stat['max']:+.5f} "
                f"Mean={stat['mean']:+.5f} "
                f"Std={stat['std']:.5f} "
                f"RMS={stat['rms']:.5f}"
            )

        rss = np.sqrt(
            self.x ** 2
            + self.y ** 2
            + self.z ** 2
        )

        rss_stat = self.statistics(
            rss
        )

        print(
            f"RSS magnitude: "
            f"Mean={rss_stat['mean']:.5f} "
            f"Std={rss_stat['std']:.5f} "
            f"RMS={rss_stat['rms']:.5f}"
        )

    def print_analysis(self) -> None:
        if not self.analysis_rows:
            print(
                "No analysis rows loaded."
            )
            return

        print(
            "--- Latest SensorMax Analysis Snapshot ---"
        )

        latest = self.analysis_rows[-1]

        fields = [
            (
                "Machine ID",
                "Machine_ID",
            ),
            (
                "Point",
                "Point",
            ),
            (
                "Sample rate (Hz)",
                "Sample_Rate_Hz",
            ),
            (
                "RMS accel (m/s²)",
                "Overall_RMS_Accel_ms2",
            ),
            (
                "RMS velocity (mm/s)",
                "Overall_RMS_Velocity_mms",
            ),
            (
                "RMS displacement (µm)",
                "Overall_RMS_Displacement_um",
            ),
            (
                "Dominant axis",
                "Dominant_Axis",
            ),
            (
                "Dominant frequency (Hz)",
                "Dominant_Freq_Hz",
            ),
            (
                "Envelope peak (Hz)",
                "Envelope_Peak_Freq_Hz",
            ),
            (
                "ISO zone",
                "ISO20816_Zone",
            ),
            (
                "Bearing match",
                "Bearing_Match",
            ),
            (
                "RPM input",
                "RPM_Input",
            ),
            (
                "Impact",
                "Impact_Event",
            ),
            (
                "Snapshot",
                "Snapshot_Triggered",
            ),
        ]

        for label, key in fields:
            print(
                f"{label:28}: "
                f"{latest.get(key, 'UNAVAILABLE')}"
            )

    def export_accelerometer(
        self,
        output_path: str,
    ) -> None:
        if self.x.size == 0:
            raise ValueError(
                "No accelerometer data available."
            )

        os.makedirs(
            os.path.dirname(
                os.path.abspath(output_path)
            ),
            exist_ok=True,
        )

        with open(
            output_path,
            "w",
            newline="",
            encoding="utf-8",
        ) as handle:
            writer = csv.writer(handle)

            writer.writerow(
                [
                    "Timestamp_ms",
                    "Time_s",
                    "Machine_ID",
                    "Point",
                    "Val_0",
                    "Val_1",
                    "Val_2",
                ]
            )

            start = self.timestamps_ms[0]

            machine = (
                self.machine_ids[0]
                if self.machine_ids
                else "UNSPECIFIED"
            )

            point = (
                self.points[0]
                if self.points
                else "UNSPECIFIED"
            )

            for timestamp, xv, yv, zv in zip(
                self.timestamps_ms,
                self.x,
                self.y,
                self.z,
            ):
                writer.writerow(
                    [
                        f"{timestamp:.3f}",
                        f"{(timestamp - start) / 1000.0:.6f}",
                        machine,
                        point,
                        f"{xv:.8f}",
                        f"{yv:.8f}",
                        f"{zv:.8f}",
                    ]
                )

        print(
            f"Exported accelerometer data: "
            f"{os.path.abspath(output_path)}"
        )

    def show_plot(
        self,
        axis: str,
    ) -> None:
        if self.x.size == 0:
            print(
                "No raw accelerometer waveform available."
            )
            return

        arrays = {
            "x": self.x,
            "y": self.y,
            "z": self.z,
            "rss": np.sqrt(
                self.x ** 2
                + self.y ** 2
                + self.z ** 2
            ),
        }

        if axis not in arrays:
            print(
                "Usage: plot x|y|z|rss"
            )
            return

        print(
            f"--- {axis.upper()} waveform ---"
        )

        print(
            ascii_plot(
                arrays[axis]
            )
        )

    def show_fft(
        self,
        axis: str,
    ) -> None:
        if self.x.size == 0:
            print(
                "No raw accelerometer data available."
            )
            return

        arrays = {
            "x": self.x,
            "y": self.y,
            "z": self.z,
            "rss": np.sqrt(
                self.x ** 2
                + self.y ** 2
                + self.z ** 2
            ),
        }

        if axis not in arrays:
            print(
                "Usage: fft x|y|z|rss"
            )
            return

        frequencies, amplitude, peak = self.fft(
            arrays[axis]
        )

        if peak is None:
            print(
                "Not enough data for FFT."
            )
            return

        # Print top spectral peaks without requiring matplotlib.
        start = (
            1
            if amplitude.size > 1
            else 0
        )

        order = np.argsort(
            amplitude[start:]
        )[::-1]

        print(
            f"Dominant frequency "
            f"{axis.upper()}: {peak:.3f} Hz"
        )

        print(
            "Top spectral components:"
        )

        shown = 0
        seen_bins = set()

        for relative_index in order:
            index = start + int(
                relative_index
            )

            # Avoid printing adjacent FFT bins as a long list.
            neighborhood = range(
                max(start, index - 1),
                min(
                    amplitude.size,
                    index + 2,
                ),
            )

            if any(
                neighbor in seen_bins
                for neighbor in neighborhood
            ):
                continue

            seen_bins.add(index)

            print(
                f"  {frequencies[index]:9.3f} Hz"
                f"   amplitude={amplitude[index]:.6f}"
            )

            shown += 1

            if shown >= 5:
                break


def print_help() -> None:
    print(
        """
Commands
--------
load <file.csv>   Load a SensorMax CSV.
info              Show dataset identity and timing.
stats             Show X/Y/Z/RSS statistics.
plot x|y|z|rss    Show an ASCII waveform.
fft x|y|z|rss     Show dominant spectral components.
analysis          Show latest analysis snapshot.
export <file.csv> Export accelerometer vectors.
help              Show this help.
clear             Clear the current dataset.
exit              Exit the explorer.

Examples
--------
load Imported_Records/session/raw.csv
info
stats
plot x
fft x
analysis
export extracted_accel.csv
exit
"""
    )


def run_explorer(
    initial_csv: Optional[str] = None,
) -> None:
    dataset = SensorDataset()

    if initial_csv:
        try:
            dataset.load(
                initial_csv
            )
            print(
                f"Loaded: {initial_csv}"
            )
        except Exception as exc:
            print(
                f"Load error: {exc}"
            )

    print("=" * 64)
    print(
        " SensorMax INTERACTIVE TERMINAL DATA EXPLORER"
    )
    print(
        " AndroidApp: LOCKED / UNCHANGED"
    )
    print(
        " Type 'help' for commands."
    )
    print("=" * 64)

    while True:
        prompt_name = (
            os.path.basename(
                dataset.path
            )
            if dataset.path
            else "none"
        )

        try:
            raw = input(
                f"\n({prompt_name})> "
            )
        except (
            KeyboardInterrupt,
            EOFError,
        ):
            print()
            break

        parts = raw.strip().split()

        if not parts:
            continue

        command = parts[0].lower()

        try:
            if command in {
                "exit",
                "quit",
            }:
                break

            if command == "help":
                print_help()

            elif command == "load":
                if len(parts) < 2:
                    print(
                        "Usage: load <file.csv>"
                    )
                    continue

                dataset.load(
                    parts[1]
                )

                print(
                    f"Loaded {dataset.path}"
                )

                if dataset.x.size:
                    print(
                        f"Accelerometer samples: "
                        f"{dataset.x.size}"
                    )

            elif command == "info":
                dataset.print_info()

            elif command == "stats":
                dataset.print_stats()

            elif command == "analysis":
                dataset.print_analysis()

            elif command == "plot":
                axis = (
                    parts[1].lower()
                    if len(parts) > 1
                    else "x"
                )
                dataset.show_plot(
                    axis
                )

            elif command == "fft":
                axis = (
                    parts[1].lower()
                    if len(parts) > 1
                    else "x"
                )
                dataset.show_fft(
                    axis
                )

            elif command == "export":
                if len(parts) < 2:
                    print(
                        "Usage: export <file.csv>"
                    )
                    continue

                dataset.export_accelerometer(
                    parts[1]
                )

            elif command == "clear":
                dataset.clear()
                print(
                    "Dataset cleared."
                )

            else:
                print(
                    "Unknown command. "
                    "Type 'help'."
                )

        except Exception as exc:
            print(
                f"Error: {exc}"
            )

    print(
        "Exiting SensorMax explorer."
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Interactive SensorMax CSV data explorer."
        )
    )

    parser.add_argument(
        "csv_file",
        nargs="?",
        default=None,
        help=(
            "Optional CSV to load at startup."
        ),
    )

    return parser


def main() -> int:
    args = build_parser().parse_args()
    run_explorer(args.csv_file)
    return 0


if __name__ == "__main__":
    raise SystemExit(
        main()
    )
