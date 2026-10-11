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
import csv
from decimal import Decimal, InvalidOperation
import ipaddress
import io
import json
import math
import os
from datetime import datetime
from urllib.parse import urlsplit
from pathlib import Path

import websockets


# ============================================================
# Configuration
# ============================================================

# Local-only by default. Non-loopback mode requires a second explicit opt-in:
#   SENSORMAX_LISTEN_HOST=192.168.x.x SENSORMAX_ALLOW_INSECURE_LAN=1
SERVER_HOST = (
    os.environ.get("SENSORMAX_LISTEN_HOST", "127.0.0.1").strip()
    or "127.0.0.1"
)
SERVER_PORT = int(os.environ.get("SENSORMAX_LISTEN_PORT", "8765"))
ALLOW_INSECURE_LAN = (
    os.environ.get("SENSORMAX_ALLOW_INSECURE_LAN", "").strip().lower()
    in {"1", "true", "yes"}
)
MAX_CONNECTED_CLIENTS = 32
MAX_MESSAGE_BYTES = 1024 * 1024
MAX_PACKET_LOG_CHARS = 300

# Canonical network-ingress raw CSV contract (v2). Keep this column order
# synchronized with SensorEngine's local raw CSV header and the contract doc.
RAW_CSV_COLUMNS = (
    "Schema_Version",
    "Sensor_Timestamp_ns",
    "Timestamp_ms",
    "Machine_ID",
    "Point",
    "Source_Kind",
    "Source_ID",
    "Channel_ID",
    "Sensor_Type",
    "Sensor_Name",
    "Unit",
    "Val_0",
    "Val_1",
    "Val_2",
    "Val_3",
    "Val_4",
    "Val_5",
)
RAW_CSV_HEADER = ",".join(RAW_CSV_COLUMNS) + "\n"

# Network analysis packets are compact summary snapshots; they are deliberately
# distinct from the richer 19-column local Analysis CSV emitted by SensorEngine.
ANALYSIS_SNAPSHOT_CSV_COLUMNS = (
    "Timestamp_ms",
    "Machine_ID",
    "Point",
    "Sample_Rate_Hz",
    "RMS_Accel_ms2",
    "RMS_Velocity_mms",
    "RMS_Displacement_um",
    "Dominant_Axis",
    "Dominant_Freq_Hz",
    "Envelope_Peak_Freq_Hz",
    "ISO_Zone",
    "Bearing_Match",
)
ANALYSIS_SNAPSHOT_CSV_HEADER = ",".join(ANALYSIS_SNAPSHOT_CSV_COLUMNS) + "\n"


def is_loopback_host(host: str) -> bool:
    """Return True only for localhost or an IP address in the loopback range."""
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def is_explicit_private_lan_host(host: str) -> bool:
    """Return True only for a specific RFC1918 IPv4 or IPv6 ULA interface address.

    Wildcard, public, link-local, hostname, and multicast binds are rejected.
    This does not make plaintext WebSocket secure; it only limits accidental exposure.
    """
    try:
        address = ipaddress.ip_address(host.strip())
    except (ValueError, AttributeError):
        return False

    if address.is_loopback or address.is_unspecified or address.is_multicast or address.is_link_local:
        return False

    if isinstance(address, ipaddress.IPv4Address):
        private_networks = (
            ipaddress.ip_network("10.0.0.0/8"),
            ipaddress.ip_network("172.16.0.0/12"),
            ipaddress.ip_network("192.168.0.0/16"),
        )
        return any(address in network for network in private_networks)

    return address in ipaddress.ip_network("fc00::/7")


def can_bind_host(host: str, allow_insecure_lan: bool = False) -> bool:
    """Permit loopback by default; unsafe LAN mode requires explicit opt-in and a private interface IP.

    Do not permit 0.0.0.0/:: wildcard binds even when the opt-in is set. The gateway
    has no TLS or client authentication, so this is a containment check, not security.
    """
    return is_loopback_host(host) or (allow_insecure_lan and is_explicit_private_lan_host(host))


def is_allowed_origin(origin) -> bool:
    """Allow native clients without Origin, file:// dashboards, and loopback HTTP pages only."""
    if origin is None or origin == "null":
        return True
    if not isinstance(origin, str) or not origin.strip():
        return False
    try:
        parsed = urlsplit(origin)
        return (
            parsed.scheme.lower() == "http"
            and parsed.hostname is not None
            and is_loopback_host(parsed.hostname)
            and parsed.username is None
            and parsed.password is None
        )
    except ValueError:
        return False


def get_websocket_origin(websocket):
    """Read Origin across supported websockets API generations."""
    headers = getattr(websocket, "request_headers", None)
    if headers is None:
        request = getattr(websocket, "request", None)
        headers = getattr(request, "headers", None)
    return headers.get("Origin") if headers is not None else None

BASE_DIR = Path(__file__).resolve().parent
# Overrideable for isolated tests and alternate local workspaces; the default
# remains the project's local Imported_Records directory.
LOG_DIR = Path(
    os.environ.get("SENSORMAX_LOG_DIR", str(BASE_DIR / "Imported_Records"))
).expanduser()
LOG_DIR.mkdir(parents=True, exist_ok=True)

# ============================================================
# Runtime state
# ============================================================

connected_clients = set()


# ============================================================
# Helpers
# ============================================================

def now_stamp() -> str:
    """Return a filesystem-safe local timestamp."""
    return datetime.now().strftime("%Y%m%d_%H%M%S_%f")


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

    # Current/legacy raw packets. Honor an explicit family even when every
    # measurement is null (which is different from an unknown packet).
    if data.get("kind") == "raw":
        return "raw"

    if any(key in data for key in ("v0", "v1", "v2")):
        return "raw"

    # Backward-compatible legacy packet.
    if any(key in data for key in ("x", "y", "z")):
        return "raw"

    return "unknown"


def extract_raw_values(data: dict):
    """Read raw XYZ values without inventing zeros for unavailable channels."""
    def number(value):
        if value is None or value == "":
            return None
        try:
            result = float(value)
        except (TypeError, ValueError, OverflowError):
            return None
        return result if math.isfinite(result) else None

    if any(key in data for key in ("v0", "v1", "v2")):
        return tuple(number(data.get(f"v{i}")) for i in range(3))
    return tuple(number(data.get(key)) for key in ("x", "y", "z"))


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


def numeric_csv_value(value, default="") -> str:
    """Keep numeric CSV columns numeric; preserve missing/invalid values as blank."""
    if value is None or value == "":
        return default
    try:
        number = float(value)
    except (TypeError, ValueError, OverflowError):
        return ""
    if not math.isfinite(number):
        return ""
    return safe_text(value)


def exact_integer_csv_value(value, default="") -> str:
    """Serialize integer-like timestamps without routing through float precision."""
    if value is None or value == "":
        return default
    try:
        decimal_value = Decimal(str(value).strip())
    except (InvalidOperation, ValueError, TypeError):
        return ""
    if not decimal_value.is_finite() or decimal_value != decimal_value.to_integral_value():
        return ""
    return str(int(decimal_value))


def csv_row(values, text_fields=()) -> str:
    """Serialize one CSV row with correct quoting and spreadsheet-safe text fields."""
    text_fields = set(text_fields)
    normalized = []
    for index, value in enumerate(values):
        if index in text_fields:
            cell = safe_text(value)
            # Prevent common spreadsheet formula prefixes in untrusted text metadata.
            if isinstance(value, str) and cell.lstrip().startswith(("=", "+", "-", "@")):
                cell = "'" + cell
            normalized.append(cell)
        else:
            normalized.append("" if value is None else safe_text(value))

    buffer = io.StringIO(newline="")
    csv.writer(buffer, lineterminator="").writerow(normalized)
    return buffer.getvalue()


def append_csv(path: Path, header: str, row: str, create=False):
    """
    Append a serialized CSV row.

    create=True forces the header to be written when starting a session.
    Callers should build rows with csv_row() rather than joining raw values.
    """

    if create or not path.exists():
        with path.open("w", encoding="utf-8", newline="") as f:
            f.write(header)
            if not header.endswith("\n"):
                f.write("\n")

    # create=True is also used to initialize a file before the first data row.
    # Do not emit an empty CSV record while writing the header.
    if row:
        with path.open("a", encoding="utf-8", newline="") as f:
            f.write(row)
            if not row.endswith("\n"):
                f.write("\n")


def raw_packet_to_csv_row(data: dict) -> str:
    """Normalize current and legacy raw JSON packets to the canonical raw CSV v2."""
    values = [data.get(f"v{i}") for i in range(6)]

    # Legacy x/y/z fallback is used only when v0-v2 are absent, not when a v2
    # packet explicitly carries null values to represent unavailable readings.
    if not any(key in data for key in ("v0", "v1", "v2")) and any(
        key in data for key in ("x", "y", "z")
    ):
        values[:3] = [data.get("x"), data.get("y"), data.get("z")]

    row_values = [
        numeric_csv_value(2),
        exact_integer_csv_value(data.get("sensorTimestampNs", data.get("Sensor_Timestamp_ns"))),
        numeric_csv_value(data.get("ts", data.get("Timestamp_ms"))),
        data.get("machineId", data.get("Machine_ID", "")),
        data.get("point", data.get("Point", "")),
        data.get("sourceKind", data.get("Source_Kind", "")),
        data.get("sourceId", data.get("Source_ID", "")),
        data.get("channelId", data.get("Channel_ID", "")),
        numeric_csv_value(
            data.get("sensorType")
            if data.get("sensorType") is not None
            else data.get("id", -1)
        ),
        data.get("sensor", data.get("name", data.get("sensorName", ""))),
        data.get("unit", data.get("Unit", "")),
        *(numeric_csv_value(value) for value in values),
    ]
    text_fields = {3, 4, 5, 6, 7, 9, 10}
    return csv_row(row_values, text_fields=text_fields)


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

    origin = get_websocket_origin(websocket)
    if not is_allowed_origin(origin):
        print("[!] Rejecting WebSocket client with a non-local Origin header.")
        try:
            await websocket.close(
                code=1008,
                reason="Origin not allowed by SensorMax local gateway",
            )
        except Exception:
            pass
        return

    if len(connected_clients) >= MAX_CONNECTED_CLIENTS:
        print(
            f"[!] Rejecting WebSocket client: capacity reached "
            f"({MAX_CONNECTED_CLIENTS})"
        )
        try:
            await websocket.close(
                code=1013,
                reason="SensorMax gateway is at client capacity",
            )
        except Exception:
            pass
        return

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

    raw_header = RAW_CSV_HEADER
    analysis_header = ANALYSIS_SNAPSHOT_CSV_HEADER

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

                if not raw_initialized:
                    append_csv(
                        raw_csv,
                        raw_header,
                        "",
                        create=True,
                    )
                    raw_initialized = True

                row = raw_packet_to_csv_row(data)

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

                    format_debug_value = lambda value: "NA" if value is None else f"{value:.3f}"
                    print(
                        "[*] Raw stream active: "
                        f"{raw_packet_count} packets | "
                        f"X={format_debug_value(x)}, "
                        f"Y={format_debug_value(y)}, "
                        f"Z={format_debug_value(z)}"
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

                row = csv_row(
                    [
                        numeric_csv_value(ts, ""),
                        machine_id,
                        point,
                        numeric_csv_value(data.get("sampleRateHz")),
                        numeric_csv_value(data.get("overallRmsAccelMs2")),
                        numeric_csv_value(data.get("overallRmsVelocityMmS")),
                        numeric_csv_value(data.get("overallRmsDisplacementUm")),
                        data.get("dominantAxis", ""),
                        numeric_csv_value(data.get("dominantFreqHz")),
                        numeric_csv_value(data.get("envelopePeakFreqHz")),
                        data.get("isoZone", ""),
                        data.get("bearingMatch", ""),
                    ],
                    text_fields={1, 2, 7, 10, 11},
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

                preview = safe_text(json_line(data))[:MAX_PACKET_LOG_CHARS]
                print(
                    f"[!] Unknown packet type from {client_ip}; "
                    f"preview={preview!r}"
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

    if not can_bind_host(SERVER_HOST, ALLOW_INSECURE_LAN):
        print("[SECURITY ERROR] Refusing this WebSocket bind target.")
        print("This gateway uses plaintext WebSocket without client authentication.")
        print("For local-only use, unset SENSORMAX_LISTEN_HOST to use 127.0.0.1.")
        print(
            "For a trusted, isolated LAN only, set SENSORMAX_LISTEN_HOST to a specific "
            "private interface IP (for example 192.168.1.20) and set "
            "SENSORMAX_ALLOW_INSECURE_LAN=1. Wildcard (0.0.0.0/::), public IPs, and "
            "hostnames are rejected. This opt-in is not suitable for untrusted networks."
        )
        return 2

    print("=" * 68)
    print("  SENSORMAX WEBSOCKET GATEWAY")
    print("=" * 68)
    print(
        f"  Listening on ws://{SERVER_HOST}:{SERVER_PORT}"
    )
    if not is_loopback_host(SERVER_HOST):
        print()
        print("  SECURITY WARNING: insecure non-loopback mode explicitly enabled.")
        print("  This gateway has no TLS/authentication; never expose it to the public internet.")
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
        max_size=MAX_MESSAGE_BYTES,
        ping_interval=20,
        ping_timeout=20,
    ):
        await asyncio.Future()


# ============================================================
# Entry point
# ============================================================

if __name__ == "__main__":

    try:
        exit_code = asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[!] SensorMax gateway manually stopped.")
        exit_code = 130

    raise SystemExit(exit_code or 0)
