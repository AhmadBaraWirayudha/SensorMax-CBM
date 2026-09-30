#!/usr/bin/env python3
"""
SensorMax Live Spectrum Studio

Desktop Tkinter viewer for the current SensorMax WebSocket architecture.

Transport:
    ws://127.0.0.1:8765

Supported packets:
    Raw:
        {
            "ts": 123456789,
            "id": 1,
            "v0": 0.1,
            "v1": 9.8,
            "v2": 0.2,
            ...
        }

    Analysis:
        {
            "kind": "analysis",
            "ts": 123456789,
            "machineId": "MACHINE-01",
            "point": "POINT-01",
            "sampleRateHz": 100.0,
            "overallRmsAccelMs2": 0.42,
            "dominantFreqHz": 12.5,
            ...
        }

AndroidApp is intentionally not modified by this file.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import queue
import threading
import time
import tkinter as tk
from collections import deque
from tkinter import messagebox, ttk
from typing import Any, Deque, Dict, Optional, Tuple

try:
    import numpy as np
except ImportError:
    raise SystemExit("numpy is required. Run first_initialize.bat.")

try:
    import websockets
except ImportError:
    raise SystemExit("websockets is required. Run first_initialize.bat.")


DEFAULT_URL = "ws://127.0.0.1:8765"
DEFAULT_BUFFER = 512
DEFAULT_MAX_FREQ = 50.0


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def finite_float(value: Any) -> Optional[float]:
    try:
        value = float(value)
        return value if math.isfinite(value) else None
    except (TypeError, ValueError):
        return None


def text_or(value: Any, fallback: str = "UNSPECIFIED") -> str:
    if value is None:
        return fallback
    text = str(value).strip()
    return text if text else fallback


def get_packet_axis(packet: Dict[str, Any], axis: int) -> Optional[float]:
    # Current compact websocket form.
    compact = packet.get(f"v{axis}")
    if compact is not None:
        return finite_float(compact)

    # Current CSV-style names, useful for replay/testing.
    named = packet.get(f"Val_{axis}")
    if named is not None:
        return finite_float(named)

    named_legacy = packet.get(f"Value_{axis}")
    if named_legacy is not None:
        return finite_float(named_legacy)

    return None


# ---------------------------------------------------------------------------
# Live spectrum application
# ---------------------------------------------------------------------------

class LiveSpectrumApp:
    def __init__(
        self,
        root: tk.Tk,
        url: str = DEFAULT_URL,
        buffer_size: int = DEFAULT_BUFFER,
        max_freq: float = DEFAULT_MAX_FREQ,
    ) -> None:
        self.root = root
        self.root.title("SensorMax Live Spectrum Studio")
        self.root.geometry("1180x820")
        self.root.minsize(900, 650)

        self.url = url
        self.buffer_size = max(64, int(buffer_size))
        self.max_freq = max(1.0, float(max_freq))

        self.running = False
        self.sim_active = False
        self.sim_t = 0.0
        self.ws_thread: Optional[threading.Thread] = None
        self.stop_event = threading.Event()

        self.packet_queue: queue.Queue[Dict[str, Any]] = queue.Queue(maxsize=5000)
        self.event_queue: queue.Queue[Tuple[str, Any]] = queue.Queue(maxsize=1000)

        self.buf_x: Deque[float] = deque(maxlen=self.buffer_size)
        self.buf_y: Deque[float] = deque(maxlen=self.buffer_size)
        self.buf_z: Deque[float] = deque(maxlen=self.buffer_size)
        self.buf_ts: Deque[float] = deque(maxlen=self.buffer_size)

        # Seed with a stationary gravity vector to make the first plot useful.
        self.buf_x.extend([0.0] * self.buffer_size)
        self.buf_y.extend([9.80665] * self.buffer_size)
        self.buf_z.extend([0.0] * self.buffer_size)
        self.buf_ts.extend(
            [i * 0.01 * 1000.0 for i in range(self.buffer_size)]
        )

        self.machine_id = "UNSPECIFIED"
        self.point = "UNSPECIFIED"
        self.sensor_name = "UNSPECIFIED"
        self.sample_rate_hz: Optional[float] = 100.0
        self.latest_analysis: Dict[str, Any] = {}

        self.raw_count = 0
        self.analysis_count = 0
        self.invalid_count = 0
        self.dropped_queue_count = 0
        self.last_packet_epoch = 0.0

        self.peak_hz = 0.0
        self.peak_amp = 0.0

        self.setup_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.root.after(40, self.process_and_draw)

    # ------------------------------------------------------------------
    # UI
    # ------------------------------------------------------------------

    def setup_ui(self) -> None:
        controls = ttk.LabelFrame(
            self.root,
            text=" SensorMax Connection & Simulation ",
            padding=8,
        )
        controls.pack(fill=tk.X, padx=10, pady=(8, 4))

        ttk.Label(controls, text="Gateway:").pack(side=tk.LEFT, padx=(2, 4))
        self.url_entry = ttk.Entry(controls, width=30)
        self.url_entry.insert(0, self.url)
        self.url_entry.pack(side=tk.LEFT, padx=4)

        self.btn_connect = ttk.Button(
            controls,
            text="Connect",
            command=self.toggle_connection,
        )
        self.btn_connect.pack(side=tk.LEFT, padx=5)

        self.btn_sim = ttk.Button(
            controls,
            text="Start 6.5 Hz Simulation",
            command=self.toggle_simulation,
        )
        self.btn_sim.pack(side=tk.LEFT, padx=5)

        self.btn_clear = ttk.Button(
            controls,
            text="Clear Buffer",
            command=self.clear_buffer,
        )
        self.btn_clear.pack(side=tk.LEFT, padx=5)

        self.status_label = ttk.Label(
            controls,
            text="Disconnected",
        )
        self.status_label.pack(side=tk.RIGHT, padx=8)

        metrics = ttk.LabelFrame(
            self.root,
            text=" Measurement Context ",
            padding=8,
        )
        metrics.pack(fill=tk.X, padx=10, pady=4)

        self.context_var = tk.StringVar(
            value="Machine=UNSPECIFIED | Point=UNSPECIFIED | Sensor=UNSPECIFIED"
        )
        ttk.Label(
            metrics,
            textvariable=self.context_var,
        ).pack(anchor="w")

        self.metrics_var = tk.StringVar(
            value=(
                "Rate=-- Hz | Dominant=-- Hz | RMS=-- m/s² | "
                "ISO=-- | Bearing=-- | Impact=--"
            )
        )
        ttk.Label(
            metrics,
            textvariable=self.metrics_var,
        ).pack(anchor="w", pady=(3, 0))

        self.paned = ttk.Panedwindow(
            self.root,
            orient=tk.VERTICAL,
        )
        self.paned.pack(
            fill=tk.BOTH,
            expand=True,
            padx=10,
            pady=5,
        )

        wave_frame = ttk.LabelFrame(
            self.paned,
            text=" Accelerometer Waveform — Current Buffer ",
            padding=5,
        )
        self.paned.add(wave_frame, weight=1)

        self.wave_canvas = tk.Canvas(
            wave_frame,
            bg="#0F172A",
            highlightthickness=0,
        )
        self.wave_canvas.pack(fill=tk.BOTH, expand=True)

        fft_frame = ttk.LabelFrame(
            self.paned,
            text=" FFT Spectrum — 0 to 50 Hz or Selected Upper Limit ",
            padding=5,
        )
        self.paned.add(fft_frame, weight=1)

        self.fft_canvas = tk.Canvas(
            fft_frame,
            bg="#0B1120",
            highlightthickness=0,
        )
        self.fft_canvas.pack(fill=tk.BOTH, expand=True)

        footer = ttk.Label(
            self.root,
            text=(
                "SensorMax desktop visualization. Spectrum peaks are indicators "
                "for engineering review, not diagnosis or certified machine protection."
            ),
        )
        footer.pack(fill=tk.X, padx=10, pady=(0, 8))

    # ------------------------------------------------------------------
    # Connection management
    # ------------------------------------------------------------------

    def toggle_connection(self) -> None:
        if self.running:
            self.stop_connection()
        else:
            self.start_connection()

    def start_connection(self) -> None:
        if self.running:
            return

        self.url = self.url_entry.get().strip() or DEFAULT_URL
        self.running = True
        self.stop_event.clear()
        self.btn_connect.config(text="Disconnect")
        self.status_label.config(text="Connecting...")

        self.ws_thread = threading.Thread(
            target=self._websocket_thread,
            daemon=True,
        )
        self.ws_thread.start()

    def stop_connection(self) -> None:
        self.running = False
        self.stop_event.set()
        self.btn_connect.config(text="Connect")
        self.status_label.config(text="Disconnected")

    def _websocket_thread(self) -> None:
        asyncio.run(self._websocket_loop())

    async def _websocket_loop(self) -> None:
        retry_delay = 1.0

        while not self.stop_event.is_set():
            try:
                self._queue_event("status", f"Connecting to {self.url} ...")

                async with websockets.connect(
                    self.url,
                    ping_interval=15,
                    ping_timeout=20,
                    max_size=2**20,
                ) as websocket:
                    self._queue_event("status", "Connected")
                    retry_delay = 1.0

                    while not self.stop_event.is_set():
                        try:
                            message = await asyncio.wait_for(
                                websocket.recv(),
                                timeout=0.5,
                            )
                        except asyncio.TimeoutError:
                            continue

                        if isinstance(message, bytes):
                            try:
                                message = message.decode("utf-8", errors="ignore")
                            except Exception:
                                continue

                        packet = self.parse_message(str(message))
                        if packet is None:
                            continue

                        try:
                            self.packet_queue.put_nowait(packet)
                        except queue.Full:
                            self.dropped_queue_count += 1

            except Exception as exc:
                if self.stop_event.is_set():
                    break

                self._queue_event("status", f"Disconnected: {exc}")
                await asyncio.sleep(retry_delay)
                retry_delay = min(retry_delay * 2.0, 5.0)

    # ------------------------------------------------------------------
    # Packet parsing
    # ------------------------------------------------------------------

    @staticmethod
    def parse_message(message: str) -> Optional[Dict[str, Any]]:
        message = message.strip()
        if not message:
            return None

        try:
            packet = json.loads(message)
            if isinstance(packet, dict):
                return packet
        except json.JSONDecodeError:
            pass

        # Compatibility with CSV lines sent by older testing tools.
        parts = [part.strip().strip('"') for part in message.split(",")]
        if len(parts) >= 6:
            try:
                sensor_type = int(float(parts[1]))
                if sensor_type == 1:
                    return {
                        "ts": int(float(parts[0])),
                        "id": 1,
                        "v0": float(parts[3]),
                        "v1": float(parts[4]),
                        "v2": float(parts[5]),
                    }
            except (TypeError, ValueError):
                pass

        return None

    # ------------------------------------------------------------------
    # Simulation
    # ------------------------------------------------------------------

    def toggle_simulation(self) -> None:
        self.sim_active = not self.sim_active
        self.btn_sim.config(
            text=(
                "Stop Simulation"
                if self.sim_active
                else "Start 6.5 Hz Simulation"
            )
        )

        if self.sim_active:
            self.status_label.config(text="Simulation active")

    def generate_simulation(self) -> None:
        # Approximately 100 Hz synthetic stream.
        for _ in range(4):
            self.sim_t += 0.01

            x = (
                1.8 * math.sin(2.0 * math.pi * 6.5 * self.sim_t)
                + 0.35 * math.sin(2.0 * math.pi * 13.0 * self.sim_t)
                + float(np.random.normal(0.0, 0.08))
            )

            y = (
                9.80665
                + 0.6 * math.sin(2.0 * math.pi * 6.5 * self.sim_t)
                + float(np.random.normal(0.0, 0.08))
            )

            z = (
                0.5 * math.sin(2.0 * math.pi * 3.2 * self.sim_t)
                + float(np.random.normal(0.0, 0.08))
            )

            timestamp_ms = self.sim_t * 1000.0
            self.append_sample(
                timestamp_ms,
                x,
                y,
                z,
                source="simulation",
            )

        self.machine_id = "SIMULATION"
        self.point = "SIM-POINT"
        self.sensor_name = "Synthetic Accelerometer"
        self.sample_rate_hz = 100.0

    # ------------------------------------------------------------------
    # Queue / packet handling
    # ------------------------------------------------------------------

    def process_and_draw(self) -> None:
        if self.sim_active:
            self.generate_simulation()

        while True:
            try:
                packet = self.packet_queue.get_nowait()
            except queue.Empty:
                break

            self.process_packet(packet)

        while True:
            try:
                kind, value = self.event_queue.get_nowait()
            except queue.Empty:
                break

            if kind == "status":
                self.status_label.config(text=str(value))

        self.draw_wave()
        self.draw_fft()
        self.update_metrics()

        if self.running or self.sim_active:
            self.root.after(40, self.process_and_draw)
        else:
            self.root.after(100, self.process_and_draw)

    def process_packet(self, packet: Dict[str, Any]) -> None:
        self.last_packet_epoch = time.time()

        kind = text_or(packet.get("kind"), "raw")

        if kind == "analysis":
            self.analysis_count += 1
            self.latest_analysis = dict(packet)

            self.machine_id = text_or(
                packet.get("machineId", packet.get("Machine_ID")),
                self.machine_id,
            )
            self.point = text_or(
                packet.get("point", packet.get("Point")),
                self.point,
            )

            self.sample_rate_hz = finite_float(
                packet.get(
                    "sampleRateHz",
                    packet.get("Sample_Rate_Hz"),
                )
            ) or self.sample_rate_hz

            return

        sensor_type = packet.get(
            "id",
            packet.get("Sensor_Type"),
        )

        try:
            if int(float(sensor_type)) != 1:
                return
        except (TypeError, ValueError):
            return

        x = get_packet_axis(packet, 0)
        y = get_packet_axis(packet, 1)
        z = get_packet_axis(packet, 2)

        if x is None or y is None or z is None:
            self.invalid_count += 1
            return

        timestamp = finite_float(
            packet.get(
                "ts",
                packet.get("Timestamp_ms"),
            )
        )

        if timestamp is None:
            timestamp = time.time() * 1000.0

        self.machine_id = text_or(
            packet.get("machineId", packet.get("Machine_ID")),
            self.machine_id,
        )
        self.point = text_or(
            packet.get("point", packet.get("Point")),
            self.point,
        )
        self.sensor_name = text_or(
            packet.get("sensorName", packet.get("Sensor_Name")),
            self.sensor_name,
        )

        self.raw_count += 1
        self.append_sample(
            timestamp,
            x,
            y,
            z,
            source="websocket",
        )

        self._estimate_sample_rate()

    def append_sample(
        self,
        timestamp_ms: float,
        x: float,
        y: float,
        z: float,
        source: str,
    ) -> None:
        self.buf_ts.append(float(timestamp_ms))
        self.buf_x.append(float(x))
        self.buf_y.append(float(y))
        self.buf_z.append(float(z))

        if source == "simulation":
            self.raw_count += 1

    def _estimate_sample_rate(self) -> None:
        if len(self.buf_ts) < 8:
            return

        ts = np.asarray(self.buf_ts, dtype=float)
        dt = np.diff(ts)
        valid = dt[(dt > 0) & np.isfinite(dt)]

        if valid.size == 0:
            return

        median_dt_ms = float(np.median(valid))
        if median_dt_ms <= 0:
            return

        estimated = 1000.0 / median_dt_ms
        if 1.0 <= estimated <= 5000.0:
            self.sample_rate_hz = estimated

    # ------------------------------------------------------------------
    # Plotting
    # ------------------------------------------------------------------

    def draw_wave(self) -> None:
        canvas = self.wave_canvas
        canvas.delete("all")

        width = canvas.winfo_width()
        height = canvas.winfo_height()
        if width <= 1 or height <= 1:
            return

        canvas.create_line(
            0,
            height / 2,
            width,
            height / 2,
            fill="#334155",
            width=1,
        )

        x = np.asarray(self.buf_x, dtype=float)
        y = np.asarray(self.buf_y, dtype=float)
        z = np.asarray(self.buf_z, dtype=float)

        if len(x) < 2:
            return

        all_values = np.concatenate([x, y, z])
        minimum = float(np.min(all_values))
        maximum = float(np.max(all_values))
        span = max(maximum - minimum, 2.0)

        scale = height * 0.72 / span
        mid = (maximum + minimum) / 2.0
        step = width / max(len(x) - 1, 1)

        def points(values: np.ndarray) -> list[float]:
            output: list[float] = []
            for i, value in enumerate(values):
                output.extend(
                    [
                        i * step,
                        height / 2.0 - (value - mid) * scale,
                    ]
                )
            return output

        # X / Y / Z lines.
        canvas.create_line(
            *points(x),
            fill="#F87171",
            width=2,
        )
        canvas.create_line(
            *points(y),
            fill="#4ADE80",
            width=2,
        )
        canvas.create_line(
            *points(z),
            fill="#38BDF8",
            width=2,
        )

        canvas.create_text(
            12,
            12,
            anchor="nw",
            text="X",
            fill="#F87171",
            font=("Arial", 9, "bold"),
        )
        canvas.create_text(
            32,
            12,
            anchor="nw",
            text="Y",
            fill="#4ADE80",
            font=("Arial", 9, "bold"),
        )
        canvas.create_text(
            52,
            12,
            anchor="nw",
            text="Z",
            fill="#38BDF8",
            font=("Arial", 9, "bold"),
        )

    def draw_fft(self) -> None:
        canvas = self.fft_canvas
        canvas.delete("all")

        width = canvas.winfo_width()
        height = canvas.winfo_height()
        if width <= 1 or height <= 1:
            return

        data = np.asarray(self.buf_x, dtype=float)
        if len(data) < 8:
            return

        sample_rate = self.sample_rate_hz
        if sample_rate is None or sample_rate <= 0:
            return

        centered = data - float(np.mean(data))

        # Hann window reduces leakage and produces a more stable visual peak.
        windowed = centered * np.hanning(len(centered))
        fft_values = np.abs(np.fft.rfft(windowed))
        freqs = np.fft.rfftfreq(
            len(windowed),
            d=1.0 / sample_rate,
        )

        if len(fft_values) < 2:
            return

        fft_values[0] = 0.0

        mask = (
            freqs <= self.max_freq
        )

        visible_freqs = freqs[mask]
        visible_fft = fft_values[mask]

        if len(visible_fft) == 0:
            return

        peak_idx = int(np.argmax(visible_fft))
        self.peak_hz = float(
            visible_freqs[peak_idx]
        )
        self.peak_amp = float(
            visible_fft[peak_idx]
        )

        max_amp = max(
            float(np.max(visible_fft)),
            1e-12,
        )

        x_axis_left = 34
        x_axis_right = width - 10
        y_axis_top = 10
        y_axis_bottom = height - 28

        canvas.create_line(
            x_axis_left,
            y_axis_bottom,
            x_axis_right,
            y_axis_bottom,
            fill="#475569",
        )

        canvas.create_line(
            x_axis_left,
            y_axis_top,
            x_axis_left,
            y_axis_bottom,
            fill="#475569",
        )

        # Frequency grid.
        tick_count = 10
        for i in range(tick_count + 1):
            frequency = self.max_freq * i / tick_count
            px = (
                x_axis_left
                + (
                    frequency / self.max_freq
                )
                * (x_axis_right - x_axis_left)
            )

            canvas.create_line(
                px,
                y_axis_top,
                px,
                y_axis_bottom,
                fill="#1E293B",
            )

            canvas.create_text(
                px,
                y_axis_bottom + 10,
                text=f"{frequency:.0f}",
                fill="#94A3B8",
                font=("Arial", 8),
            )

        # Draw frequency bars from actual frequency bins rather than treating
        # the bin index as Hz.
        if len(visible_freqs) > 1:
            df = float(
                visible_freqs[1] - visible_freqs[0]
            )
        else:
            df = self.max_freq

        for i, (frequency, amplitude) in enumerate(
            zip(visible_freqs, visible_fft)
        ):
            px1 = (
                x_axis_left
                + (
                    frequency / self.max_freq
                )
                * (x_axis_right - x_axis_left)
            )

            px2 = (
                x_axis_left
                + (
                    min(
                        frequency + df,
                        self.max_freq,
                    )
                    / self.max_freq
                )
                * (x_axis_right - x_axis_left)
                - 1
            )

            bar_height = (
                float(amplitude)
                / max_amp
                * (y_axis_bottom - y_axis_top - 5)
            )

            py1 = max(
                y_axis_top,
                y_axis_bottom - bar_height,
            )

            canvas.create_rectangle(
                px1,
                py1,
                max(px1 + 1, px2),
                y_axis_bottom,
                fill=(
                    "#F87171"
                    if i == peak_idx
                    else "#38BDF8"
                ),
                outline="",
            )

        canvas.create_text(
            width - 12,
            12,
            anchor="ne",
            text=(
                f"Dominant: {self.peak_hz:.2f} Hz"
            ),
            fill="#F8FAFC",
            font=("Arial", 10, "bold"),
        )

    # ------------------------------------------------------------------
    # Metrics
    # ------------------------------------------------------------------

    def update_metrics(self) -> None:
        analysis = self.latest_analysis

        rms = finite_float(
            analysis.get(
                "overallRmsAccelMs2",
                analysis.get("Overall_RMS_Accel_ms2"),
            )
        )

        iso = text_or(
            analysis.get(
                "isoZone",
                analysis.get("ISO20816_Zone"),
            ),
            "--",
        )

        bearing = text_or(
            analysis.get(
                "bearingMatch",
                analysis.get("Bearing_Match"),
            ),
            "--",
        )

        impact = text_or(
            analysis.get(
                "impactEvent",
                analysis.get("Impact_Event"),
            ),
            "--",
        )

        analysis_dominant = finite_float(
            analysis.get(
                "dominantFreqHz",
                analysis.get("Dominant_Freq_Hz"),
            )
        )

        dominant = (
            analysis_dominant
            if analysis_dominant is not None
            else self.peak_hz
        )

        rate_text = (
            f"{self.sample_rate_hz:.2f}"
            if self.sample_rate_hz is not None
            else "--"
        )

        rms_text = (
            f"{rms:.3f}"
            if rms is not None
            else "--"
        )

        self.context_var.set(
            "Machine="
            + self.machine_id
            + " | Point="
            + self.point
            + " | Sensor="
            + self.sensor_name
        )

        self.metrics_var.set(
            f"Rate={rate_text} Hz | "
            f"Dominant={dominant:.2f} Hz | "
            f"RMS={rms_text} m/s² | "
            f"ISO={iso} | "
            f"Bearing={bearing} | "
            f"Impact={impact} | "
            f"Raw={self.raw_count} | "
            f"Analysis={self.analysis_count}"
        )

    # ------------------------------------------------------------------
    # Buffer / lifecycle
    # ------------------------------------------------------------------

    def clear_buffer(self) -> None:
        self.buf_x.clear()
        self.buf_y.clear()
        self.buf_z.clear()
        self.buf_ts.clear()

        self.buf_x.extend([0.0] * self.buffer_size)
        self.buf_y.extend([9.80665] * self.buffer_size)
        self.buf_z.extend([0.0] * self.buffer_size)

        self.peak_hz = 0.0
        self.peak_amp = 0.0

    def _queue_event(self, kind: str, value: Any) -> None:
        try:
            self.event_queue.put_nowait(
                (kind, value)
            )
        except queue.Full:
            pass

    def on_close(self) -> None:
        self.stop_event.set()
        self.running = False
        self.sim_active = False

        try:
            self.root.destroy()
        except tk.TclError:
            pass


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "SensorMax live waveform and FFT viewer "
            "for the existing WebSocket gateway."
        )
    )

    parser.add_argument(
        "--url",
        default=DEFAULT_URL,
        help=f"WebSocket gateway URL (default: {DEFAULT_URL})",
    )

    parser.add_argument(
        "--buffer",
        type=int,
        default=DEFAULT_BUFFER,
        help=f"FFT/waveform buffer size (default: {DEFAULT_BUFFER})",
    )

    parser.add_argument(
        "--max-freq",
        type=float,
        default=DEFAULT_MAX_FREQ,
        help=f"Upper FFT display limit in Hz (default: {DEFAULT_MAX_FREQ})",
    )

    return parser


def main() -> int:
    args = build_parser().parse_args()

    if args.buffer < 64:
        print("Error: --buffer must be at least 64 samples.")
        return 2

    if args.max_freq <= 0:
        print("Error: --max-freq must be greater than zero.")
        return 2

    root = tk.Tk()
    LiveSpectrumApp(
        root,
        url=args.url,
        buffer_size=args.buffer,
        max_freq=args.max_freq,
    )
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
