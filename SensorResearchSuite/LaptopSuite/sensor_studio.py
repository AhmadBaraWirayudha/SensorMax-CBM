#!/usr/bin/env python3
"""
SensorMax Studio - desktop real-time monitoring and recording HMI.

Locked dependency boundary:
    AndroidApp remains unchanged.

Transport:
    WebSocket gateway: ws://127.0.0.1:8765

Features:
    - Live raw accelerometer waveform
    - Browser-independent desktop FFT
    - Machine / point context
    - Analysis snapshot status
    - Raw CSV recording using the current SensorMax schema
    - Analysis CSV recording using the current analysis schema
    - Built-in synthetic vibration simulation for HMI testing
"""

from __future__ import annotations

import asyncio
import csv
import json
import math
import os
import queue
import threading
import time
import tkinter as tk
from collections import deque
from datetime import datetime
from tkinter import filedialog, messagebox, ttk
from typing import Any, Deque, Dict, Optional

try:
    import numpy as np
except ImportError:
    np = None

try:
    import websockets
except ImportError:
    websockets = None


GATEWAY_URL = "ws://127.0.0.1:8765"
BUFFER_SIZE = 512

RAW_FIELDS = [
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

ANALYSIS_FIELDS = [
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


class SensorStudio:
    """Tk desktop HMI for the existing SensorMax WebSocket gateway."""

    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("SensorMax Studio — Real-Time Condition Monitoring")
        self.root.geometry("1180x780")
        self.root.minsize(980, 680)

        self.running = False
        self.simulation = False
        self.ws_thread: Optional[threading.Thread] = None
        self.stop_event = threading.Event()
        self.packet_queue: queue.Queue[Dict[str, Any]] = queue.Queue(maxsize=20000)

        self.buf_x: Deque[float] = deque(maxlen=BUFFER_SIZE)
        self.buf_y: Deque[float] = deque(maxlen=BUFFER_SIZE)
        self.buf_z: Deque[float] = deque(maxlen=BUFFER_SIZE)
        self.buf_ts: Deque[int] = deque(maxlen=BUFFER_SIZE)

        self.machine_id = "UNSPECIFIED"
        self.point = "UNSPECIFIED"
        self.sample_rate_hz = 0.0
        self.latest_analysis: Dict[str, Any] = {}
        self.raw_count = 0
        self.analysis_count = 0
        self.last_rate_update = time.monotonic()
        self.rate_samples = 0
        self.sim_t = 0.0

        self.raw_writer: Optional[csv.DictWriter] = None
        self.raw_handle = None
        self.analysis_writer: Optional[csv.DictWriter] = None
        self.analysis_handle = None
        self.recording = False
        self.output_dir = os.path.join(
            os.path.expanduser("~"),
            "SensorMaxRecords",
        )
        os.makedirs(self.output_dir, exist_ok=True)

        self._build_ui()
        self._schedule_update()

        self.root.protocol("WM_DELETE_WINDOW", self.close)

    # ------------------------------------------------------------------
    # UI
    # ------------------------------------------------------------------

    def _build_ui(self) -> None:
        root = self.root

        header = ttk.LabelFrame(
            root,
            text=" SensorMax Connection & Context ",
            padding=8,
        )
        header.pack(fill=tk.X, padx=10, pady=6)

        ttk.Label(header, text="Gateway:").pack(side=tk.LEFT, padx=4)
        self.url_var = tk.StringVar(value=GATEWAY_URL)
        ttk.Entry(header, textvariable=self.url_var, width=31).pack(
            side=tk.LEFT, padx=4
        )

        self.connect_btn = ttk.Button(
            header,
            text="Connect",
            command=self.toggle_connection,
        )
        self.connect_btn.pack(side=tk.LEFT, padx=8)

        self.sim_btn = ttk.Button(
            header,
            text="Start Simulation",
            command=self.toggle_simulation,
        )
        self.sim_btn.pack(side=tk.LEFT, padx=4)

        self.status_var = tk.StringVar(value="Disconnected")
        ttk.Label(
            header,
            textvariable=self.status_var,
            font=("Segoe UI", 10, "bold"),
        ).pack(side=tk.LEFT, padx=14)

        self.context_var = tk.StringVar(
            value="Machine: UNSPECIFIED | Point: UNSPECIFIED"
        )
        ttk.Label(
            header,
            textvariable=self.context_var,
        ).pack(side=tk.RIGHT, padx=4)

        # Metrics row.
        metrics = ttk.Frame(root, padding=(10, 0, 10, 4))
        metrics.pack(fill=tk.X)

        self.metric_vars: Dict[str, tk.StringVar] = {}
        for key, label in [
            ("rate", "Rate"),
            ("rms", "Overall RMS"),
            ("freq", "Dominant Freq"),
            ("zone", "ISO Zone"),
            ("bearing", "Bearing Match"),
            ("impact", "Impact"),
        ]:
            frame = ttk.LabelFrame(metrics, text=label, padding=7)
            frame.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=3)
            var = tk.StringVar(value="—")
            self.metric_vars[key] = var
            ttk.Label(
                frame,
                textvariable=var,
                font=("Segoe UI", 10, "bold"),
            ).pack()

        # Main visual area.
        paned = ttk.Panedwindow(root, orient=tk.VERTICAL)
        paned.pack(fill=tk.BOTH, expand=True, padx=10, pady=5)

        wave_frame = ttk.LabelFrame(
            paned,
            text=" Accelerometer Waveform — Last 512 Samples ",
            padding=5,
        )
        paned.add(wave_frame, weight=3)

        self.wave_canvas = tk.Canvas(
            wave_frame,
            background="#111827",
            highlightthickness=0,
        )
        self.wave_canvas.pack(fill=tk.BOTH, expand=True)

        fft_frame = ttk.LabelFrame(
            paned,
            text=" FFT — Accelerometer X Dynamic Component ",
            padding=5,
        )
        paned.add(fft_frame, weight=2)

        self.fft_canvas = tk.Canvas(
            fft_frame,
            background="#0f172a",
            highlightthickness=0,
        )
        self.fft_canvas.pack(fill=tk.BOTH, expand=True)

        # Recording and log area.
        bottom = ttk.LabelFrame(
            root,
            text=" Recording / Diagnostic Log ",
            padding=7,
        )
        bottom.pack(fill=tk.X, padx=10, pady=5)

        self.record_btn = ttk.Button(
            bottom,
            text="Start Recording",
            command=self.toggle_recording,
        )
        self.record_btn.pack(side=tk.LEFT, padx=4)

        ttk.Button(
            bottom,
            text="Output Directory...",
            command=self.choose_output_dir,
        ).pack(side=tk.LEFT, padx=4)

        ttk.Button(
            bottom,
            text="Clear Log",
            command=self.clear_log,
        ).pack(side=tk.LEFT, padx=4)

        self.record_var = tk.StringVar(value="Not recording")
        ttk.Label(
            bottom,
            textvariable=self.record_var,
        ).pack(side=tk.LEFT, padx=12)

        self.log_text = tk.Text(
            root,
            height=7,
            wrap=tk.NONE,
            font=("Consolas", 9),
        )
        self.log_text.pack(fill=tk.X, padx=10, pady=(0, 10))

        self.log("SensorMax Studio initialized.")
        self.log(f"Gateway: {GATEWAY_URL}")

    # ------------------------------------------------------------------
    # Logging
    # ------------------------------------------------------------------

    def log(self, message: str) -> None:
        timestamp = datetime.now().strftime("%H:%M:%S")
        try:
            self.log_text.insert(
                tk.END,
                f"[{timestamp}] {message}\n",
            )
            self.log_text.see(tk.END)
        except tk.TclError:
            pass

    def clear_log(self) -> None:
        self.log_text.delete("1.0", tk.END)

    # ------------------------------------------------------------------
    # WebSocket
    # ------------------------------------------------------------------

    def toggle_connection(self) -> None:
        if self.running:
            self.disconnect()
        else:
            self.connect()

    def connect(self) -> None:
        if websockets is None:
            messagebox.showerror(
                "Missing dependency",
                "The 'websockets' package is required. Run first_initialize.bat.",
            )
            return

        self.running = True
        self.stop_event.clear()
        self.status_var.set("Connecting...")
        self.connect_btn.config(text="Disconnect")

        url = self.url_var.get().strip() or GATEWAY_URL
        self.ws_thread = threading.Thread(
            target=self._websocket_worker,
            args=(url,),
            daemon=True,
        )
        self.ws_thread.start()

    def disconnect(self) -> None:
        self.running = False
        self.stop_event.set()
        self.connect_btn.config(text="Connect")
        self.status_var.set("Disconnected")
        self.log("Gateway disconnected by operator.")

    def _websocket_worker(self, url: str) -> None:
        try:
            asyncio.run(self._websocket_session(url))
        except Exception as exc:
            self._enqueue_event(
                {
                    "__studio_event__": "error",
                    "message": str(exc),
                }
            )

    async def _websocket_session(self, url: str) -> None:
        try:
            async with websockets.connect(
                url,
                ping_interval=15,
                ping_timeout=20,
                max_size=2**20,
            ) as websocket:

                self._enqueue_event(
                    {
                        "__studio_event__": "connected"
                    }
                )

                while not self.stop_event.is_set():
                    try:
                        raw_message = await asyncio.wait_for(
                            websocket.recv(),
                            timeout=0.5,
                        )
                    except asyncio.TimeoutError:
                        continue

                    if isinstance(raw_message, bytes):
                        raw_message = raw_message.decode(
                            "utf-8",
                            errors="ignore",
                        )

                    try:
                        packet = json.loads(raw_message)
                    except (TypeError, json.JSONDecodeError):
                        continue

                    if isinstance(packet, dict):
                        self._enqueue_event(packet)

        except asyncio.CancelledError:
            raise
        except Exception as exc:
            self._enqueue_event(
                {
                    "__studio_event__": "connection_error",
                    "message": str(exc),
                }
            )

    def _enqueue_event(self, packet: Dict[str, Any]) -> None:
        try:
            self.packet_queue.put_nowait(packet)
        except queue.Full:
            try:
                self.packet_queue.get_nowait()
            except queue.Empty:
                pass
            try:
                self.packet_queue.put_nowait(packet)
            except queue.Full:
                pass

    # ------------------------------------------------------------------
    # Simulation
    # ------------------------------------------------------------------

    def toggle_simulation(self) -> None:
        self.simulation = not self.simulation
        self.sim_btn.config(
            text="Stop Simulation"
            if self.simulation
            else "Start Simulation"
        )
        self.log(
            "Synthetic vibration simulation "
            + ("started." if self.simulation else "stopped.")
        )

    def _generate_simulation(self) -> None:
        if not self.simulation:
            return

        # 100 Hz synthetic test stream.
        for _ in range(2):
            self.sim_t += 0.01

            x = (
                0.25 * math.sin(2 * math.pi * 6.5 * self.sim_t)
                + 0.08 * math.sin(2 * math.pi * 13.0 * self.sim_t)
            )
            y = 9.80665 + 0.12 * math.sin(
                2 * math.pi * 6.5 * self.sim_t
            )
            z = 0.18 * math.sin(
                2 * math.pi * 3.2 * self.sim_t
            )

            self._handle_raw(
                {
                    "ts": int(self.sim_t * 1000),
                    "id": 1,
                    "v0": x,
                    "v1": y,
                    "v2": z,
                    "machineId": "SIMULATION",
                    "point": "TEST_POINT",
                },
                source="simulation",
            )

    # ------------------------------------------------------------------
    # Queue and packet processing
    # ------------------------------------------------------------------

    def _schedule_update(self) -> None:
        self._process_queue()
        self.root.after(25, self._schedule_update)

    def _process_queue(self) -> None:
        for _ in range(5000):
            try:
                packet = self.packet_queue.get_nowait()
            except queue.Empty:
                break

            event = packet.get("__studio_event__")

            if event == "connected":
                self.status_var.set("Connected")
                self.log("Gateway connection established.")
                continue

            if event == "error":
                self.log(
                    f"WebSocket worker error: {packet.get('message', '')}"
                )
                continue

            if event == "connection_error":
                if self.running:
                    self.status_var.set("Connection error")
                    self.log(
                        "Gateway connection error: "
                        f"{packet.get('message', '')}"
                    )
                continue

            if packet.get("kind") == "analysis":
                self._handle_analysis(packet)
            elif "id" in packet and "v0" in packet:
                self._handle_raw(packet)

        self._generate_simulation()
        self._draw_waveform()
        self._draw_fft()

    # ------------------------------------------------------------------
    # Raw handling
    # ------------------------------------------------------------------

    def _handle_raw(
        self,
        packet: Dict[str, Any],
        source: str = "gateway",
    ) -> None:
        try:
            sensor_id = int(packet.get("id", 1))
        except (TypeError, ValueError):
            return

        if sensor_id != 1:
            return

        try:
            x = float(packet["v0"])
            y = float(packet["v1"])
            z = float(packet["v2"])
        except (KeyError, TypeError, ValueError):
            return

        try:
            ts = int(packet.get("ts", int(time.time() * 1000)))
        except (TypeError, ValueError):
            ts = int(time.time() * 1000)

        self.buf_x.append(x)
        self.buf_y.append(y)
        self.buf_z.append(z)
        self.buf_ts.append(ts)

        self.raw_count += 1
        self.rate_samples += 1

        self.machine_id = str(
            packet.get("machineId", packet.get("Machine_ID", self.machine_id))
            or self.machine_id
        )
        self.point = str(
            packet.get("point", packet.get("Point", self.point))
            or self.point
        )

        self.context_var.set(
            f"Machine: {self.machine_id} | Point: {self.point}"
        )

        now = time.monotonic()
        elapsed = now - self.last_rate_update
        if elapsed >= 0.5:
            self.sample_rate_hz = self.rate_samples / elapsed
            self.rate_samples = 0
            self.last_rate_update = now
            self.metric_vars["rate"].set(
                f"{self.sample_rate_hz:.1f} Hz"
            )

        if self.recording and source == "gateway":
            self._record_raw(packet, ts)

    # ------------------------------------------------------------------
    # Analysis handling
    # ------------------------------------------------------------------

    def _analysis_value(
        self,
        packet: Dict[str, Any],
        camel: str,
        csv_name: str,
    ) -> Any:
        if camel in packet:
            return packet[camel]
        return packet.get(csv_name)

    def _handle_analysis(
        self,
        packet: Dict[str, Any],
    ) -> None:
        self.analysis_count += 1
        self.latest_analysis = packet

        machine = self._analysis_value(
            packet, "machineId", "Machine_ID"
        )
        point = self._analysis_value(
            packet, "point", "Point"
        )

        if machine:
            self.machine_id = str(machine)
        if point:
            self.point = str(point)

        self.context_var.set(
            f"Machine: {self.machine_id} | Point: {self.point}"
        )

        rms = self._analysis_value(
            packet,
            "overallRmsAccelMs2",
            "Overall_RMS_Accel_ms2",
        )
        freq = self._analysis_value(
            packet,
            "dominantFreqHz",
            "Dominant_Freq_Hz",
        )
        zone = self._analysis_value(
            packet,
            "isoZone",
            "ISO20816_Zone",
        )
        bearing = self._analysis_value(
            packet,
            "bearingMatch",
            "Bearing_Match",
        )
        impact = self._analysis_value(
            packet,
            "impactEvent",
            "Impact_Event",
        )

        self.metric_vars["rms"].set(
            f"{float(rms):.3f} m/s²"
            if _number(rms) is not None
            else "—"
        )
        self.metric_vars["freq"].set(
            f"{float(freq):.2f} Hz"
            if _number(freq) is not None
            else "—"
        )
        self.metric_vars["zone"].set(
            str(zone) if zone not in (None, "") else "—"
        )
        self.metric_vars["bearing"].set(
            str(bearing) if bearing not in (None, "") else "—"
        )
        self.metric_vars["impact"].set(
            str(impact) if impact not in (None, "") else "—"
        )

        if self.recording:
            self._record_analysis(packet)

    # ------------------------------------------------------------------
    # Recording
    # ------------------------------------------------------------------

    def choose_output_dir(self) -> None:
        folder = filedialog.askdirectory(
            title="Select SensorMax Output Directory",
            initialdir=self.output_dir,
        )
        if folder:
            self.output_dir = folder
            os.makedirs(self.output_dir, exist_ok=True)
            self.log(
                f"Output directory: {self.output_dir}"
            )

    def toggle_recording(self) -> None:
        if self.recording:
            self.stop_recording()
        else:
            self.start_recording()

    def start_recording(self) -> None:
        os.makedirs(self.output_dir, exist_ok=True)

        stamp = datetime.now().strftime(
            "%Y%m%d_%H%M%S"
        )

        raw_path = os.path.join(
            self.output_dir,
            f"SensorMax_{stamp}_raw.csv",
        )

        analysis_path = os.path.join(
            self.output_dir,
            f"SensorMax_{stamp}_analysis.csv",
        )

        try:
            self.raw_handle = open(
                raw_path,
                "w",
                newline="",
                encoding="utf-8",
            )
            self.analysis_handle = open(
                analysis_path,
                "w",
                newline="",
                encoding="utf-8",
            )

            self.raw_writer = csv.DictWriter(
                self.raw_handle,
                fieldnames=RAW_FIELDS,
                extrasaction="ignore",
            )
            self.raw_writer.writeheader()

            self.analysis_writer = csv.DictWriter(
                self.analysis_handle,
                fieldnames=ANALYSIS_FIELDS,
                extrasaction="ignore",
            )
            self.analysis_writer.writeheader()

        except OSError as exc:
            self._close_record_handles()
            messagebox.showerror(
                "Recording error",
                str(exc),
            )
            return

        self.recording = True
        self.record_btn.config(text="Stop Recording")
        self.record_var.set(
            f"Recording: {raw_path}"
        )

        self.log(
            "Recording started. Raw + analysis CSV enabled."
        )
        self.log(
            f"Raw: {raw_path}"
        )
        self.log(
            f"Analysis: {analysis_path}"
        )

    def stop_recording(self) -> None:
        self.recording = False
        self._close_record_handles()
        self.record_btn.config(text="Start Recording")
        self.record_var.set("Not recording")
        self.log("Recording stopped.")

    def _close_record_handles(self) -> None:
        if self.raw_handle:
            try:
                self.raw_handle.close()
            except OSError:
                pass
        if self.analysis_handle:
            try:
                self.analysis_handle.close()
            except OSError:
                pass

        self.raw_handle = None
        self.analysis_handle = None
        self.raw_writer = None
        self.analysis_writer = None

    def _record_raw(
        self,
        packet: Dict[str, Any],
        timestamp_ms: int,
    ) -> None:
        if self.raw_writer is None or self.raw_handle is None:
            return

        row = {
            "Timestamp_ms": timestamp_ms,
            "Machine_ID": packet.get(
                "machineId",
                packet.get("Machine_ID", self.machine_id),
            ),
            "Point": packet.get(
                "point",
                packet.get("Point", self.point),
            ),
            "Sensor_Type": packet.get(
                "id",
                packet.get("Sensor_Type", "1"),
            ),
            "Sensor_Name": packet.get(
                "sensorName",
                packet.get("Sensor_Name", "Accelerometer"),
            ),
            "Val_0": packet.get("v0", packet.get("Val_0")),
            "Val_1": packet.get("v1", packet.get("Val_1")),
            "Val_2": packet.get("v2", packet.get("Val_2")),
            "Val_3": packet.get("v3", packet.get("Val_3")),
            "Val_4": packet.get("v4", packet.get("Val_4")),
            "Val_5": packet.get("v5", packet.get("Val_5")),
        }

        self.raw_writer.writerow(row)

        if self.raw_count % 50 == 0:
            self.raw_handle.flush()

    def _record_analysis(
        self,
        packet: Dict[str, Any],
    ) -> None:
        if self.analysis_writer is None or self.analysis_handle is None:
            return

        row: Dict[str, Any] = {}

        mapping = {
            "Timestamp_ms": ("ts", "Timestamp_ms"),
            "Machine_ID": ("machineId", "Machine_ID"),
            "Point": ("point", "Point"),
            "Sample_Rate_Hz": (
                "sampleRateHz", "Sample_Rate_Hz"
            ),
            "RMS_X_ms2": ("rmsXMs2", "RMS_X_ms2"),
            "RMS_Y_ms2": ("rmsYMs2", "RMS_Y_ms2"),
            "RMS_Z_ms2": ("rmsZMs2", "RMS_Z_ms2"),
            "Overall_RMS_Accel_ms2": (
                "overallRmsAccelMs2",
                "Overall_RMS_Accel_ms2",
            ),
            "Overall_RMS_Velocity_mms": (
                "overallRmsVelocityMms",
                "Overall_RMS_Velocity_mms",
            ),
            "Overall_RMS_Displacement_um": (
                "overallRmsDisplacementUm",
                "Overall_RMS_Displacement_um",
            ),
            "Dominant_Axis": (
                "dominantAxis",
                "Dominant_Axis",
            ),
            "Dominant_Freq_Hz": (
                "dominantFreqHz",
                "Dominant_Freq_Hz",
            ),
            "Dominant_Accel_Amplitude_ms2": (
                "dominantAccelAmplitudeMs2",
                "Dominant_Accel_Amplitude_ms2",
            ),
            "Envelope_Peak_Freq_Hz": (
                "envelopePeakFreqHz",
                "Envelope_Peak_Freq_Hz",
            ),
            "ISO20816_Zone": (
                "isoZone",
                "ISO20816_Zone",
            ),
            "Bearing_Match": (
                "bearingMatch",
                "Bearing_Match",
            ),
            "RPM_Input": (
                "rpmInput",
                "RPM_Input",
            ),
            "Impact_Event": (
                "impactEvent",
                "Impact_Event",
            ),
            "Snapshot_Triggered": (
                "snapshotTriggered",
                "Snapshot_Triggered",
            ),
        }

        for column, keys in mapping.items():
            row[column] = self._analysis_value(
                packet,
                keys[0],
                keys[1],
            )

        self.analysis_writer.writerow(row)
        self.analysis_handle.flush()

    # ------------------------------------------------------------------
    # Drawing
    # ------------------------------------------------------------------

    def _draw_waveform(self) -> None:
        canvas = self.wave_canvas
        canvas.delete("all")

        width = max(canvas.winfo_width(), 2)
        height = max(canvas.winfo_height(), 2)

        if len(self.buf_x) < 2:
            return

        arrays = [
            np.asarray(self.buf_x, dtype=float)
            if np is not None
            else None,
            np.asarray(self.buf_y, dtype=float)
            if np is not None
            else None,
            np.asarray(self.buf_z, dtype=float)
            if np is not None
            else None,
        ]

        if np is None:
            return

        all_values = np.concatenate(arrays)
        low = float(np.min(all_values))
        high = float(np.max(all_values))
        span = max(high - low, 1.0)

        # Reference grid.
        zero_y = height - ((0.0 - low) / span) * height
        zero_y = max(0.0, min(float(height), zero_y))
        canvas.create_line(
            0,
            zero_y,
            width,
            zero_y,
            fill="#475569",
        )

        mid_y = height * 0.5
        scale = height * 0.82 / span
        step_x = width / max(len(self.buf_x) - 1, 1)

        def points(values: np.ndarray) -> list[float]:
            pts: list[float] = []
            for i, value in enumerate(values):
                px = i * step_x
                py = mid_y - (
                    float(value) - (high + low) / 2.0
                ) * scale
                pts.extend((px, py))
            return pts

        canvas.create_line(
            *points(arrays[0]),
            fill="#ef4444",
            width=2,
        )
        canvas.create_line(
            *points(arrays[1]),
            fill="#22c55e",
            width=2,
        )
        canvas.create_line(
            *points(arrays[2]),
            fill="#38bdf8",
            width=2,
        )

        canvas.create_text(
            8,
            8,
            anchor=tk.NW,
            text="X",
            fill="#ef4444",
        )
        canvas.create_text(
            28,
            8,
            anchor=tk.NW,
            text="Y",
            fill="#22c55e",
        )
        canvas.create_text(
            48,
            8,
            anchor=tk.NW,
            text="Z",
            fill="#38bdf8",
        )

    def _draw_fft(self) -> None:
        canvas = self.fft_canvas
        canvas.delete("all")

        if np is None or len(self.buf_x) < 8:
            return

        width = max(canvas.winfo_width(), 2)
        height = max(canvas.winfo_height(), 2)

        values = np.asarray(
            self.buf_x,
            dtype=float,
        )

        ts = np.asarray(
            self.buf_ts,
            dtype=float,
        )

        if len(ts) >= 2:
            dt = np.diff(ts)
            valid = dt[np.isfinite(dt) & (dt > 0)]
            hz = (
                1000.0 / float(np.median(valid))
                if valid.size
                else self.sample_rate_hz
            )
        else:
            hz = self.sample_rate_hz

        if not hz or hz <= 0:
            hz = 100.0

        centered = values - np.mean(values)
        fft_values = np.abs(np.fft.rfft(centered))
        freqs = np.fft.rfftfreq(
            len(centered),
            d=1.0 / hz,
        )

        fft_values[0] = 0.0

        max_freq = min(50.0, hz / 2.0)
        mask = freqs <= max_freq

        if not np.any(mask):
            return

        display_freqs = freqs[mask]
        display_values = fft_values[mask]

        peak_index = int(np.argmax(display_values))
        peak_freq = float(display_freqs[peak_index])
        peak_value = float(display_values[peak_index])

        self.metric_vars["freq"].set(
            f"{peak_freq:.2f} Hz"
        )

        max_value = max(
            float(np.max(display_values)),
            1e-12,
        )

        bar_count = len(display_values)
        bar_width = width / max(bar_count, 1)

        for i, magnitude in enumerate(display_values):
            bar_height = (
                float(magnitude) / max_value
            ) * (height * 0.82)

            x1 = i * bar_width
            x2 = x1 + max(bar_width - 1, 1)
            y1 = height - bar_height

            fill = "#f97316" if i == peak_index else "#60a5fa"

            canvas.create_rectangle(
                x1,
                y1,
                x2,
                height,
                fill=fill,
                outline="",
            )

        canvas.create_text(
            8,
            8,
            anchor=tk.NW,
            text=f"Peak: {peak_freq:.2f} Hz | Fs: {hz:.1f} Hz",
            fill="#e5e7eb",
        )

    # ------------------------------------------------------------------
    # Shutdown
    # ------------------------------------------------------------------

    def close(self) -> None:
        self.running = False
        self.stop_event.set()
        self._close_record_handles()
        self.root.destroy()


def _number(value: Any) -> Optional[float]:
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (TypeError, ValueError):
        return None


def main() -> int:
    if np is None:
        print("Error: numpy is required. Run first_initialize.bat.")
        return 1

    if websockets is None:
        print("Error: websockets is required. Run first_initialize.bat.")
        return 1

    root = tk.Tk()
    SensorStudio(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
