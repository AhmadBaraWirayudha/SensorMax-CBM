#!/usr/bin/env python3
"""
SensorMax Real-Time Condition / Alarm Monitor

Consumes the existing SensorMax WebSocket gateway on port 8765.

Supported incoming payloads:

1) Analysis snapshots:
    {
        "kind": "analysis",
        "ts": ...,
        "machineId": ...,
        "point": ...,
        "sampleRateHz": ...,
        "overallRmsAccelMs2": ...,
        "overallRmsVelocityMmS": ...,
        "overallRmsDisplacementUm": ...,
        "dominantAxis": ...,
        "dominantFreqHz": ...,
        "envelopePeakFreqHz": ...,
        "isoZone": ...,
        "bearingMatch": ...
    }

2) Raw SensorMax packets:
    {
        "ts": ...,
        "id": 1,
        "v0": ...,
        "v1": ...,
        "v2": ...,
        ...
    }

AndroidApp is not modified by this tool.

Engineering boundary:
ISO 20816 zone labels are treated as advisory metadata already produced by
SensorMax. A phone MEMS accelerometer is not treated as a certified industrial
vibration probe.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import json
import math
import os
import sys
import time
from collections import deque
from datetime import datetime, timezone
from typing import Any, Deque, Dict, Optional, Tuple

try:
    import websockets
except ImportError:
    print(
        "Error: Python package 'websockets' is required. "
        "Run first_initialize.bat."
    )
    sys.exit(1)


# ---------------------------------------------------------------------------
# Current Android SensorEngine configuration
# ---------------------------------------------------------------------------

DEFAULT_WARN_MS2 = 2.0
DEFAULT_ALERT_MS2 = 4.0

IMPACT_COOLDOWN_MS = 300
IMPACT_MIN_DELTA_MS2 = 0.75
IMPACT_BASELINE_ALPHA = 0.02

RAW_WINDOW_SIZE = 50

# Prevent the same continuously-breached condition from flooding the console.
ALARM_REPEAT_COOLDOWN_S = 1.0


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def as_float(value: Any) -> Optional[float]:
    """Convert a value to finite float, otherwise return None."""
    try:
        if value is None or value == "":
            return None

        number = float(value)

        if math.isfinite(number):
            return number

        return None

    except (TypeError, ValueError):
        return None


def clean_text(
    value: Any,
    fallback: str = "UNSPECIFIED",
) -> str:
    """Normalize text metadata without introducing fake identity."""
    if value is None:
        return fallback

    text = str(value).strip()

    return text if text else fallback


def utc_now() -> str:
    """Return current UTC timestamp for the alarm log."""
    return datetime.now(timezone.utc).isoformat(
        timespec="milliseconds"
    )


# ---------------------------------------------------------------------------
# Alarm classification
# ---------------------------------------------------------------------------

def severity_for(
    rms_accel: Optional[float],
    iso_zone: str,
    warn_threshold: float,
    alert_threshold: float,
) -> Optional[
    Tuple[
        str,
        str,
        Optional[float],
        Optional[float],
    ]
]:
    """
    Determine condition severity.

    Priority:
        1. RMS acceleration ALERT
        2. ISO Zone D advisory
        3. RMS acceleration WARNING
        4. ISO Zone C advisory
    """

    zone = iso_zone.lower().strip()

    # Direct numerical alert threshold.
    if (
        rms_accel is not None
        and rms_accel >= alert_threshold
    ):
        return (
            "ALERT",
            "RMS acceleration threshold",
            rms_accel,
            alert_threshold,
        )

    # ISO Zone D is an advisory alarm.
    if zone in {"zone d", "d"}:
        return (
            "ALERT",
            "ISO 20816 Zone D advisory flag",
            rms_accel,
            None,
        )

    # Direct numerical warning threshold.
    if (
        rms_accel is not None
        and rms_accel >= warn_threshold
    ):
        return (
            "WARNING",
            "RMS acceleration threshold",
            rms_accel,
            warn_threshold,
        )

    # ISO Zone C is an advisory warning.
    if zone in {"zone c", "c"}:
        return (
            "WARNING",
            "ISO 20816 Zone C advisory flag",
            rms_accel,
            None,
        )

    return None


# ---------------------------------------------------------------------------
# Main monitor
# ---------------------------------------------------------------------------

class AlarmMonitor:
    def __init__(
        self,
        url: str,
        warn_threshold: float,
        alert_threshold: float,
        raw_limit: Optional[float],
        log_path: str,
        raw_window: int = RAW_WINDOW_SIZE,
    ) -> None:

        if warn_threshold < 0:
            raise ValueError(
                "Warning threshold must be non-negative."
            )

        if alert_threshold < 0:
            raise ValueError(
                "Alert threshold must be non-negative."
            )

        if alert_threshold < warn_threshold:
            raise ValueError(
                "alert threshold must be >= warning threshold."
            )

        if raw_limit is not None and raw_limit < 0:
            raise ValueError(
                "Raw limit must be non-negative."
            )

        self.url = url

        self.warn_threshold = warn_threshold
        self.alert_threshold = alert_threshold

        # Raw stream defaults to the same alert threshold.
        self.raw_limit = (
            raw_limit
            if raw_limit is not None
            else alert_threshold
        )

        self.log_path = log_path
        self.raw_window = raw_window

        # ------------------------------------------------------------------
        # Raw accelerometer rolling buffers
        # ------------------------------------------------------------------

        self.x: Deque[float] = deque(
            maxlen=raw_window
        )

        self.y: Deque[float] = deque(
            maxlen=raw_window
        )

        self.z: Deque[float] = deque(
            maxlen=raw_window
        )

        # ------------------------------------------------------------------
        # Android-equivalent adaptive impact detector
        # ------------------------------------------------------------------

        self.last_impact_ms = 0

        self.baseline_x = 0.0
        self.baseline_y = 0.0
        self.baseline_z = 0.0

        self.baseline_initialized = False

        # ------------------------------------------------------------------
        # Runtime counters
        # ------------------------------------------------------------------

        self.last_alarm_signature: Optional[str] = None
        self.last_alarm_monotonic = 0.0

        self.breaches = 0
        self.packet_count = 0
        self.analysis_count = 0
        self.raw_accel_count = 0

        # ------------------------------------------------------------------
        # Alarm log
        # ------------------------------------------------------------------

        os.makedirs(
            os.path.dirname(
                os.path.abspath(log_path)
            ) or ".",
            exist_ok=True,
        )

        self._ensure_log_header()

    # ----------------------------------------------------------------------
    # Log file
    # ----------------------------------------------------------------------

    def _ensure_log_header(self) -> None:
        """Create structured CSV log on first run."""
        if (
            os.path.exists(self.log_path)
            and os.path.getsize(self.log_path) > 0
        ):
            return

        with open(
            self.log_path,
            "w",
            newline="",
            encoding="utf-8",
        ) as handle:

            writer = csv.writer(handle)

            writer.writerow(
                [
                    "Timestamp_UTC",
                    "Source_TS_ms",
                    "Machine_ID",
                    "Point",
                    "Source",
                    "Severity",
                    "Reason",
                    "Observed_Value",
                    "Threshold",
                    "ISO20816_Zone",
                    "Dominant_Axis",
                    "Dominant_Freq_Hz",
                    "Envelope_Peak_Freq_Hz",
                    "Bearing_Match",
                ]
            )

    # ----------------------------------------------------------------------
    # Alarm logging
    # ----------------------------------------------------------------------

    def log_alarm(
        self,
        *,
        source_ts_ms: Optional[int],
        machine_id: str,
        point: str,
        source: str,
        severity: str,
        reason: str,
        observed: Optional[float],
        threshold: Optional[float],
        iso_zone: str = "UNAVAILABLE",
        dominant_axis: str = "UNAVAILABLE",
        dominant_freq: Optional[float] = None,
        envelope_freq: Optional[float] = None,
        bearing_match: str = "UNAVAILABLE",
        force: bool = False,
    ) -> None:

        signature = "|".join(
            [
                severity,
                reason,
                machine_id,
                point,
                source,
                (
                    f"{observed:.4f}"
                    if observed is not None
                    else "NA"
                ),
            ]
        )

        now_mono = time.monotonic()

        # Suppress repeated identical alarms within short interval.
        if not force:
            if signature == self.last_alarm_signature:
                if (
                    now_mono - self.last_alarm_monotonic
                    < ALARM_REPEAT_COOLDOWN_S
                ):
                    return

        self.last_alarm_signature = signature
        self.last_alarm_monotonic = now_mono

        self.breaches += 1

        observed_text = (
            f"{observed:.3f}"
            if observed is not None
            else "n/a"
        )

        threshold_text = (
            f"{threshold:.3f}"
            if threshold is not None
            else "n/a"
        )

        message = (
            f"[{datetime.now().strftime('%H:%M:%S.%f')[:-3]}] "
            f"[{severity} #{self.breaches}] "
            f"{reason} | "
            f"machine={machine_id} "
            f"point={point} "
            f"source={source} "
            f"value={observed_text} "
            f"limit={threshold_text} "
            f"iso={iso_zone}"
        )

        # Console output.
        if severity == "ALERT":
            print(
                f"\033[91m\033[1m{message}\033[0m",
                flush=True,
            )
        else:
            print(
                f"\033[93m{message}\033[0m",
                flush=True,
            )

        # Structured CSV record.
        with open(
            self.log_path,
            "a",
            newline="",
            encoding="utf-8",
        ) as handle:

            writer = csv.writer(handle)

            writer.writerow(
                [
                    utc_now(),
                    (
                        source_ts_ms
                        if source_ts_ms is not None
                        else ""
                    ),
                    machine_id,
                    point,
                    source,
                    severity,
                    reason,
                    observed_text,
                    threshold_text,
                    iso_zone,
                    dominant_axis,
                    (
                        f"{dominant_freq:.3f}"
                        if dominant_freq is not None
                        else ""
                    ),
                    (
                        f"{envelope_freq:.3f}"
                        if envelope_freq is not None
                        else ""
                    ),
                    bearing_match,
                ]
            )

    # ----------------------------------------------------------------------
    # Analysis packet processing
    # ----------------------------------------------------------------------

    def process_analysis(
        self,
        packet: Dict[str, Any],
    ) -> None:

        self.analysis_count += 1

        machine_id = clean_text(
            packet.get("machineId")
            or packet.get("Machine_ID")
        )

        point = clean_text(
            packet.get("point")
            or packet.get("Point")
        )

        source_ts = packet.get(
            "ts"
        )

        try:
            source_ts_ms = (
                int(source_ts)
                if source_ts is not None
                else None
            )

        except (TypeError, ValueError):
            source_ts_ms = None

        rms_accel = as_float(
            packet.get(
                "overallRmsAccelMs2",
                packet.get(
                    "Overall_RMS_Accel_ms2"
                ),
            )
        )

        iso_zone = clean_text(
            packet.get(
                "isoZone",
                packet.get(
                    "ISO20816_Zone"
                ),
            ),
            "UNAVAILABLE",
        )

        dominant_axis = clean_text(
            packet.get(
                "dominantAxis",
                packet.get(
                    "Dominant_Axis"
                ),
            ),
            "UNAVAILABLE",
        )

        dominant_freq = as_float(
            packet.get(
                "dominantFreqHz",
                packet.get(
                    "Dominant_Freq_Hz"
                ),
            )
        )

        envelope_freq = as_float(
            packet.get(
                "envelopePeakFreqHz",
                packet.get(
                    "Envelope_Peak_Freq_Hz"
                ),
            )
        )

        bearing_match = clean_text(
            packet.get(
                "bearingMatch",
                packet.get(
                    "Bearing_Match"
                ),
            ),
            "UNAVAILABLE",
        )

        decision = severity_for(
            rms_accel=rms_accel,
            iso_zone=iso_zone,
            warn_threshold=self.warn_threshold,
            alert_threshold=self.alert_threshold,
        )

        if not decision:
            return

        (
            severity,
            reason,
            observed,
            threshold,
        ) = decision

        self.log_alarm(
            source_ts_ms=source_ts_ms,
            machine_id=machine_id,
            point=point,
            source="analysis",
            severity=severity,
            reason=reason,
            observed=observed,
            threshold=threshold,
            iso_zone=iso_zone,
            dominant_axis=dominant_axis,
            dominant_freq=dominant_freq,
            envelope_freq=envelope_freq,
            bearing_match=bearing_match,
        )

    # ----------------------------------------------------------------------
    # Raw packet processing
    # ----------------------------------------------------------------------

    def process_raw(
        self,
        packet: Dict[str, Any],
    ) -> None:

        sensor_id = packet.get(
            "id",
            packet.get(
                "Sensor_Type"
            ),
        )

        try:
            if int(sensor_id) != 1:
                return

        except (TypeError, ValueError):
            return

        x = as_float(
            packet.get(
                "v0",
                packet.get(
                    "Val_0"
                ),
            )
        )

        y = as_float(
            packet.get(
                "v1",
                packet.get(
                    "Val_1"
                ),
            )
        )

        z = as_float(
            packet.get(
                "v2",
                packet.get(
                    "Val_2"
                ),
            )
        )

        if (
            x is None
            or y is None
            or z is None
        ):
            return

        self.raw_accel_count += 1

        self.x.append(x)
        self.y.append(y)
        self.z.append(z)

        source_ts = packet.get(
            "ts",
            packet.get(
                "Timestamp_ms"
            ),
        )

        try:
            source_ts_ms = (
                int(source_ts)
                if source_ts is not None
                else int(time.monotonic() * 1000)
            )

        except (TypeError, ValueError):
            source_ts_ms = int(
                time.monotonic() * 1000
            )

        machine_id = clean_text(
            packet.get("machineId")
            or packet.get("Machine_ID")
        )

        point = clean_text(
            packet.get("point")
            or packet.get("Point")
        )

        # --------------------------------------------------------------
        # Impact detection
        # --------------------------------------------------------------

        self._process_impact(
            x=x,
            y=y,
            z=z,
            source_ts_ms=source_ts_ms,
            machine_id=machine_id,
            point=point,
        )

        # --------------------------------------------------------------
        # Dynamic RMS
        # --------------------------------------------------------------

        if len(self.x) < self.raw_window:
            return

        xa = list(self.x)
        ya = list(self.y)
        za = list(self.z)

        magnitudes = [
            math.sqrt(
                a * a
                + b * b
                + c * c
            )
            for a, b, c
            in zip(
                xa,
                ya,
                za,
            )
        ]

        mean_magnitude = (
            sum(magnitudes)
            / len(magnitudes)
        )

        dyn_rms = math.sqrt(
            sum(
                (
                    m
                    - mean_magnitude
                ) ** 2
                for m in magnitudes
            )
            / len(magnitudes)
        )

        if dyn_rms < self.raw_limit:
            return

        self.log_alarm(
            source_ts_ms=source_ts_ms,
            machine_id=machine_id,
            point=point,
            source="raw",
            severity="ALERT",
            reason=(
                "Raw accelerometer "
                "dynamic RMS threshold"
            ),
            observed=dyn_rms,
            threshold=self.raw_limit,
            force=False,
        )

    # ----------------------------------------------------------------------
    # Android-equivalent impact detector
    # ----------------------------------------------------------------------

    def _process_impact(
        self,
        *,
        x: float,
        y: float,
        z: float,
        source_ts_ms: int,
        machine_id: str,
        point: str,
    ) -> None:

        # First sample initializes the baseline.
        if not self.baseline_initialized:

            self.baseline_x = x
            self.baseline_y = y
            self.baseline_z = z

            self.baseline_initialized = True

            return

        dx = x - self.baseline_x
        dy = y - self.baseline_y
        dz = z - self.baseline_z

        delta_magnitude = math.sqrt(
            dx * dx
            + dy * dy
            + dz * dz
        )

        # Slowly track operating baseline.
        self.baseline_x += (
            IMPACT_BASELINE_ALPHA
            * dx
        )

        self.baseline_y += (
            IMPACT_BASELINE_ALPHA
            * dy
        )

        self.baseline_z += (
            IMPACT_BASELINE_ALPHA
            * dz
        )

        baseline_magnitude = math.sqrt(
            self.baseline_x ** 2
            + self.baseline_y ** 2
            + self.baseline_z ** 2
        )

        # Same minimum + relative threshold concept as Android.
        threshold = max(
            IMPACT_MIN_DELTA_MS2,
            baseline_magnitude * 0.12,
        )

        if delta_magnitude < threshold:
            return

        if (
            source_ts_ms
            - self.last_impact_ms
            < IMPACT_COOLDOWN_MS
        ):
            return

        self.last_impact_ms = source_ts_ms

        axis = max(
            (
                (abs(dx), "X"),
                (abs(dy), "Y"),
                (abs(dz), "Z"),
            ),
            key=lambda item: item[0],
        )[1]

        self.log_alarm(
            source_ts_ms=source_ts_ms,
            machine_id=machine_id,
            point=point,
            source="raw",
            severity="WARNING",
            reason=(
                "Impact event detected "
                f"(dominant axis {axis})"
            ),
            observed=delta_magnitude,
            threshold=threshold,
            force=True,
        )

    # ----------------------------------------------------------------------
    # WebSocket connection loop
    # ----------------------------------------------------------------------

    async def run(self) -> None:

        print("=" * 66)
        print(
            " SensorMax REAL-TIME CONDITION / ALARM MONITOR"
        )
        print("=" * 66)

        print(
            f" WebSocket endpoint : {self.url}"
        )

        print(
            f" Warning RMS limit  : "
            f"{self.warn_threshold:.2f} m/s²"
        )

        print(
            f" Alert RMS limit    : "
            f"{self.alert_threshold:.2f} m/s²"
        )

        print(
            f" Raw RMS limit      : "
            f"{self.raw_limit:.2f} m/s²"
        )

        print(
            f" Raw window         : "
            f"{self.raw_window} samples"
        )

        print(
            f" Alarm log          : "
            f"{os.path.abspath(self.log_path)}"
        )

        print(
            " ISO 20816 status   : advisory metadata only"
        )

        print(
            " Press Ctrl+C to stop."
        )

        print("=" * 66)

        while True:

            try:

                print(
                    f"Connecting to {self.url} ...",
                    flush=True,
                )

                async with websockets.connect(
                    self.url,
                    ping_interval=15,
                    ping_timeout=20,
                    max_size=2**20,
                ) as websocket:

                    print(
                        "Gateway connection established.",
                        flush=True,
                    )

                    async for message in websocket:

                        self.packet_count += 1

                        if not isinstance(
                            message,
                            str,
                        ):
                            continue

                        try:
                            packet = json.loads(
                                message
                            )

                        except json.JSONDecodeError:
                            continue

                        if not isinstance(
                            packet,
                            dict,
                        ):
                            continue

                        # Current Android AnalysisSnapshot.
                        if packet.get(
                            "kind"
                        ) == "analysis":

                            self.process_analysis(
                                packet
                            )

                        # Current Android raw stream.
                        elif (
                            "id" in packet
                            and "v0" in packet
                        ):

                            self.process_raw(
                                packet
                            )

            except KeyboardInterrupt:
                raise

            except Exception as exc:

                print(
                    f"Gateway connection lost: {exc}",
                    flush=True,
                )

                await asyncio.sleep(
                    3.0
                )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:

    parser = argparse.ArgumentParser(
        description=(
            "SensorMax real-time alarm monitor "
            "for the existing WebSocket gateway."
        )
    )

    parser.add_argument(
        "--url",
        default="ws://127.0.0.1:8765",
        help=(
            "SensorMax WebSocket gateway URL "
            "(default: ws://127.0.0.1:8765)"
        ),
    )

    parser.add_argument(
        "--warn",
        type=float,
        default=DEFAULT_WARN_MS2,
        help=(
            "Analysis RMS warning threshold "
            f"in m/s² (default: {DEFAULT_WARN_MS2})"
        ),
    )

    parser.add_argument(
        "--alert",
        type=float,
        default=DEFAULT_ALERT_MS2,
        help=(
            "Analysis RMS alert threshold "
            f"in m/s² (default: {DEFAULT_ALERT_MS2})"
        ),
    )

    parser.add_argument(
        "--raw-limit",
        type=float,
        default=None,
        help=(
            "Raw dynamic RMS alert threshold "
            "in m/s² "
            "(default: same as --alert)"
        ),
    )

    parser.add_argument(
        "--window",
        type=int,
        default=RAW_WINDOW_SIZE,
        help=(
            "Raw accelerometer RMS window length "
            f"(default: {RAW_WINDOW_SIZE} samples)"
        ),
    )

    parser.add_argument(
        "--log",
        default=os.path.join(
            os.path.dirname(__file__),
            "alarm_events.csv",
        ),
        help="CSV alarm log path",
    )

    return parser


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> int:

    args = build_parser().parse_args()

    if args.window < 2:

        print(
            "Error: --window must be >= 2."
        )

        return 2

    monitor = AlarmMonitor(
        url=args.url,
        warn_threshold=args.warn,
        alert_threshold=args.alert,
        raw_limit=args.raw_limit,
        log_path=args.log,
        raw_window=args.window,
    )

    try:

        asyncio.run(
            monitor.run()
        )

    except KeyboardInterrupt:

        print(
            "\nMonitor stopped."
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(
        main()
    )