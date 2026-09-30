#!/usr/bin/env python3
"""
SensorMax WebSocket Gateway

Purpose
-------
Acts as the laptop-side bridge between:

    Android SensorMax
            |
            | WebSocket :8765
            v
    this gateway
        |       |
        |       +----> browser dashboard
        |
        +-----------> CSV logs

The Android application is treated as the source of truth for packet format.

Current Android packet families supported:

1. Raw sensor packet
   {
       "ts": 1234567890,
       "id": 1,
       "v0": 1.2,
       "v1": 9.7,
       "v2": 0.3,
       ...
   }

2. Analysis packet
   {
       "kind": "analysis",
       "ts": 1234567890,
       "machineId": "...",
       "point": "...",
       "sampleRateHz": ...,
       "overallRmsAccelMs2": ...,
       "overallRmsVelocityMmS": ...,
       "overallRmsDisplacementUm": ...,
       "dominantAxis": "...",
       "dominantFreqHz": ...,
       "envelopePeakFreqHz": ...,
       "isoZone": "...",
       "bearingMatch": "..."
   }

Backward compatibility:
------------------------
Older packets containing x/y/z are also accepted.

Browser behavior:
-----------------
Any browser connected to ws://127.0.0.1:8765 receives the same JSON
packets that arrive from Android.

This file does NOT modify AndroidApp.
"""

import asyncio
import json
import os
from datetime import datetime
from pathlib import Path

import websockets


# ============================================================
# Configuration
# ============================================================

SERVER_HOST = "0.0.0.0"
SERVER_PORT = 8765

BASE_DIR = Path(__file__).resolve().parent
LOG_DIR = BASE_DIR / "Imported_Records"
LOG_DIR.mkdir(parents=True, exist_ok=True)

MAX_CONNECTED_CLIENTS = 32


# ============================================================
# Runtime state
# ============================================================

connected_clients = set()


# ============================================================
# Helpers
# ============================================================

def now_stamp() -> str:
    """Return a filesystem-safe local timestamp."""
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def safe_text(value, default=""):
    """Convert an arbitrary value to printable text."""
    if value is None:
        return default

    return str(value).replace("\n", " ").replace("\r", " ")


def json_line(data: dict) -> str:
    """Serialize JSON compactly for websocket transmission."""
    return json.dumps(
        data,
        separators=(",", ":"),
        ensure_ascii=False,
    )


def packet_type(data: dict) -> str:
    """
    Identify the SensorMax packet family.
    """

    if data.get("kind") == "analysis":
        return "analysis"

    # Current Android raw sensor packet.
    if any(key in data for key in ("v0", "v1", "v2")):
        return "raw"

    # Backward-compatible legacy packet.
    if any(key in data for key in ("x", "y", "z")):
        return "raw"

    return "unknown"


def extract_raw_values(data: dict):
    """
    Read raw XYZ values.

    Preferred/current format:
        v0 / v1 / v2

    Legacy format:
        x / y / z
    """

    def number(value, default=0.0):
        try:
            return float(value)
        except (TypeError, ValueError):
            return default

    if any(key in data for key in ("v0", "v1", "v2")):
        return (
            number(data.get("v0")),
            number(data.get("v1")),
            number(data.get("v2")),
        )

    return (
        number(data.get("x")),
        number(data.get("y")),
        number(data.get("z")),
    )


async def broadcast(message: str, sender=None):
    """
    Broadcast one JSON packet to every connected websocket client.

    The original sender is not required to receive it again, but
    normally we broadcast to everyone because the browser dashboard
    needs the same stream regardless of where it originated.
    """

    if not connected_clients:
        return

    recipients = list(connected_clients)

    results = await asyncio.gather(
        *[
            client.send(message)
            for client in recipients
            if client is not sender
        ],
        return_exceptions=True,
    )

    # Remove clients whose connection failed.
    for client, result in zip(
        [c for c in recipients if c is not sender],
        results,
    ):
        if isinstance(result, Exception):
            connected_clients.discard(client)


def append_csv(path: Path, header: str, row: str, create=False):
    """
    Append a row to a CSV file.

    create=True forces the header to be written when starting a session.
    """

    if create or not path.exists():
        with path.open("w", encoding="utf-8", newline="") as f:
            f.write(header)
            if not header.endswith("\n"):
                f.write("\n")

    with path.open("a", encoding="utf-8", newline="") as f:
        f.write(row)
        if not row.endswith("\n"):
            f.write("\n")


# ============================================================
# Main websocket handler
# ============================================================

async def handle_sensor_stream(websocket, path=None):
    """
    Handle one websocket connection.

    Compatible with both older and newer websockets releases:
    path is optional because some releases call the handler with only
    the websocket connection object.
    """

    remote = getattr(websocket, "remote_address", None)

    if isinstance(remote, tuple):
        client_ip = remote[0]
    else:
        client_ip = safe_text(remote, "unknown")

    connected_clients.add(websocket)

    print(
        f"[+] WebSocket connected: {client_ip} "
        f"| clients={len(connected_clients)}"
    )

    # One pair of logs per Android connection.
    session_timestamp = now_stamp()

    raw_csv = LOG_DIR / (
        f"live_stream_{session_timestamp}_raw.csv"
    )

    analysis_csv = LOG_DIR / (
        f"live_stream_{session_timestamp}_analysis.csv"
    )

    raw_header = (
        "Timestamp_ms,"
        "Sensor_ID,"
        "Sensor_Type,"
        "Sensor_Name,"
        "Value_0,"
        "Value_1,"
        "Value_2,"
        "Value_3,"
        "Value_4,"
        "Value_5\n"
    )

    analysis_header = (
        "Timestamp_ms,"
        "Machine_ID,"
        "Point,"
        "Sample_Rate_Hz,"
        "RMS_Accel_ms2,"
        "RMS_Velocity_mms,"
        "RMS_Displacement_um,"
        "Dominant_Axis,"
        "Dominant_Freq_Hz,"
        "Envelope_Peak_Freq_Hz,"
        "ISO_Zone,"
        "Bearing_Match\n"
    )

    raw_initialized = False
    analysis_initialized = False

    raw_packet_count = 0
    analysis_packet_count = 0
    unknown_packet_count = 0

    try:

        async for message in websocket:

            # ----------------------------------------------------
            # Parse JSON
            # ----------------------------------------------------

            try:
                data = json.loads(message)
            except json.JSONDecodeError as exc:
                print(
                    f"[!] Invalid JSON from {client_ip}: {exc}"
                )
                continue

            if not isinstance(data, dict):
                print(
                    f"[!] Ignoring non-object packet from {client_ip}"
                )
                continue

            kind = packet_type(data)

            # ----------------------------------------------------
            # RAW SENSOR PACKET
            # ----------------------------------------------------

            if kind == "raw":

                ts = data.get("ts", "")
                sensor_id = data.get("id", "")
                sensor_type = data.get("id", "")
                sensor_name = data.get(
                    "name",
                    data.get("sensorName", "")
                )

                values = [
                    data.get(f"v{i}")
                    for i in range(6)
                ]

                # Legacy x/y/z fallback.
                if all(v is None for v in values[:3]):
                    values[0], values[1], values[2] = (
                        extract_raw_values(data)
                    )

                if not raw_initialized:
                    append_csv(
                        raw_csv,
                        raw_header,
                        "",
                        create=True,
                    )

                    raw_initialized = True

                row = ",".join(
                    [
                        safe_text(ts),
                        safe_text(sensor_id),
                        safe_text(sensor_type),
                        safe_text(sensor_name),
                        safe_text(values[0], "0"),
                        safe_text(values[1], "0"),
                        safe_text(values[2], "0"),
                        safe_text(values[3], "0"),
                        safe_text(values[4], "0"),
                        safe_text(values[5], "0"),
                    ]
                )

                append_csv(
                    raw_csv,
                    raw_header,
                    row,
                )

                raw_packet_count += 1

                # Send exact Android JSON unchanged to browser clients.
                await broadcast(
                    json_line(data),
                    sender=websocket,
                )

                if raw_packet_count % 200 == 0:

                    x, y, z = extract_raw_values(data)

                    print(
                        "[*] Raw stream active: "
                        f"{raw_packet_count} packets | "
                        f"X={x:.3f}, "
                        f"Y={y:.3f}, "
                        f"Z={z:.3f}"
                    )

            # ----------------------------------------------------
            # ANALYSIS PACKET
            # ----------------------------------------------------

            elif kind == "analysis":

                ts = data.get("ts", "")
                machine_id = data.get(
                    "machineId",
                    "UNSPECIFIED"
                )
                point = data.get(
                    "point",
                    "UNSPECIFIED"
                )

                if not analysis_initialized:

                    append_csv(
                        analysis_csv,
                        analysis_header,
                        "",
                        create=True,
                    )

                    analysis_initialized = True

                row = ",".join(
                    [
                        safe_text(ts),
                        safe_text(machine_id),
                        safe_text(point),
                        safe_text(
                            data.get("sampleRateHz"),
                            "0"
                        ),
                        safe_text(
                            data.get("overallRmsAccelMs2"),
                            "0"
                        ),
                        safe_text(
                            data.get("overallRmsVelocityMmS"),
                            "0"
                        ),
                        safe_text(
                            data.get(
                                "overallRmsDisplacementUm"
                            ),
                            "0"
                        ),
                        safe_text(
                            data.get("dominantAxis"),
                            ""
                        ),
                        safe_text(
                            data.get("dominantFreqHz"),
                            "0"
                        ),
                        safe_text(
                            data.get("envelopePeakFreqHz"),
                            "0"
                        ),
                        safe_text(
                            data.get("isoZone"),
                            ""
                        ),
                        safe_text(
                            data.get("bearingMatch"),
                            ""
                        ),
                    ]
                )

                append_csv(
                    analysis_csv,
                    analysis_header,
                    row,
                )

                analysis_packet_count += 1

                # Forward exact Android analysis packet to browser.
                await broadcast(
                    json_line(data),
                    sender=websocket,
                )

                if analysis_packet_count % 20 == 0:

                    print(
                        "[*] Analysis stream active: "
                        f"{analysis_packet_count} windows | "
                        f"Asset={safe_text(machine_id)} | "
                        f"Point={safe_text(point)} | "
                        f"RMS={safe_text(data.get('overallRmsVelocityMmS'), '0')} mm/s | "
                        f"Peak={safe_text(data.get('dominantFreqHz'), '0')} Hz"
                    )

            # ----------------------------------------------------
            # UNKNOWN PACKET
            # ----------------------------------------------------

            else:

                unknown_packet_count += 1

                print(
                    f"[!] Unknown packet type from {client_ip}: "
                    f"{data}"
                )

                # Still forward it so the browser can inspect it.
                await broadcast(
                    json_line(data),
                    sender=websocket,
                )

    except websockets.exceptions.ConnectionClosed:
        pass

    except Exception as exc:
        print(
            f"[!] Stream handler error from {client_ip}: "
            f"{type(exc).__name__}: {exc}"
        )

    finally:

        connected_clients.discard(websocket)

        print(
            f"[-] WebSocket disconnected: {client_ip} | "
            f"raw={raw_packet_count}, "
            f"analysis={analysis_packet_count}, "
            f"unknown={unknown_packet_count} | "
            f"clients={len(connected_clients)}"
        )

        # Only report files that were actually created.
        if raw_initialized:
            print(
                f"[*] Raw CSV: {raw_csv}"
            )

        if analysis_initialized:
            print(
                f"[*] Analysis CSV: {analysis_csv}"
            )


# ============================================================
# Server
# ============================================================

async def main():

    print("=" * 68)
    print("  SENSORMAX WEBSOCKET GATEWAY")
    print("=" * 68)
    print(
        f"  Listening on ws://{SERVER_HOST}:{SERVER_PORT}"
    )
    print(
        f"  Log directory: {LOG_DIR}"
    )
    print()
    print(
        "  Supported:"
    )
    print(
        "    Android raw sensor packets"
    )
    print(
        "    Android analysis packets"
    )
    print(
        "    Browser dashboard clients"
    )
    print("=" * 68)

    async with websockets.serve(
        handle_sensor_stream,
        SERVER_HOST,
        SERVER_PORT,
        max_size=4 * 1024 * 1024,
        ping_interval=20,
        ping_timeout=20,
    ):
        await asyncio.Future()


# ============================================================
# Entry point
# ============================================================

if __name__ == "__main__":

    try:
        asyncio.run(main())

    except KeyboardInterrupt:

        print(
            "\n[!] SensorMax gateway manually stopped."
        )