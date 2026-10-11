"""Sprint 3 integration acceptance tests for dashboard null semantics and export parity.

Run from the repository root:
    python -m unittest LaptopSuite.test_sprint3_integration_acceptance -v
"""
from __future__ import annotations

import csv
import json
import math
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from LaptopSuite import batch_dataset_processor, export_json, web_server

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DASHBOARD = PROJECT_ROOT / "web_dashboard.html"
ANDROID_STREAM = (
    PROJECT_ROOT
    / "AndroidApp/app/src/main/java/com/research/sensormax/StreamClient.kt"
)


def _extract_js_function(source: str, name: str) -> str:
    """Extract a top-level JS function using brace counting aware of strings/comments."""
    match = re.search(rf"\bfunction\s+{re.escape(name)}\s*\(", source)
    if not match:
        raise AssertionError(f"function {name} not found in dashboard")
    opening = source.find("{", match.end())
    if opening < 0:
        raise AssertionError(f"function {name} has no body")

    depth = 0
    quote = None
    escaped = False
    line_comment = False
    block_comment = False
    regex_literal = False
    regex_class = False
    i = opening
    while i < len(source):
        char = source[i]
        nxt = source[i + 1] if i + 1 < len(source) else ""
        if regex_literal:
            if char == "\\":
                i += 2
                continue
            if char == "[":
                regex_class = True
            elif char == "]":
                regex_class = False
            elif char == "/" and not regex_class:
                regex_literal = False
                i += 1
                while i < len(source) and source[i].isalpha():
                    i += 1
                continue
            i += 1
            continue
        if line_comment:
            if char == "\n":
                line_comment = False
            i += 1
            continue
        if block_comment:
            if char == "*" and nxt == "/":
                block_comment = False
                i += 2
                continue
            i += 1
            continue
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            i += 1
            continue
        if char == "/" and nxt == "/":
            line_comment = True
            i += 2
            continue
        if char == "/" and nxt == "*":
            block_comment = True
            i += 2
            continue
        if char == "/":
            previous = source[:i].rstrip()
            previous_char = previous[-1] if previous else ""
            previous_word_match = re.search(r"([A-Za-z_$][\w$]*)$", previous)
            previous_word = previous_word_match.group(1) if previous_word_match else ""
            if previous_char in "([=,:;!&|?{}" or previous_word in {"return", "case", "throw"}:
                regex_literal = True
                regex_class = False
                i += 1
                continue
        if char in ("'", '"', "`"):
            quote = char
            i += 1
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[match.start() : i + 1]
        i += 1
    raise AssertionError(f"function {name} body was not closed")


class Sprint3IntegrationAcceptanceTests(unittest.TestCase):
    def test_batch_reader_and_analysis_preserve_missing_channels(self):
        """Blank channels remain absent and do not become fabricated zeros in XYZ metrics."""
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "partial_raw.csv"
            fields = list(web_server.RAW_CSV_COLUMNS)
            rows = [
                {
                    "Schema_Version": "2",
                    "Sensor_Timestamp_ns": "9223372036854775000",
                    "Timestamp_ms": "1700000000000",
                    "Machine_ID": "M1",
                    "Point": "P1",
                    "Source_Kind": "ANDROID_SENSOR",
                    "Source_ID": "sensor-1",
                    "Channel_ID": "accel-xyz",
                    "Sensor_Type": "1",
                    "Sensor_Name": "Accelerometer",
                    "Unit": "m/s²",
                    "Val_0": "1.0",
                    "Val_1": "2.0",
                    "Val_2": "2.0",
                    "Val_3": "",
                    "Val_4": "",
                    "Val_5": "",
                },
                {
                    "Schema_Version": "2",
                    "Sensor_Timestamp_ns": "9223372036854775001",
                    "Timestamp_ms": "1700000000010",
                    "Machine_ID": "M1",
                    "Point": "P1",
                    "Source_Kind": "ANDROID_SENSOR",
                    "Source_ID": "sensor-1",
                    "Channel_ID": "accel-xyz",
                    "Sensor_Type": "1",
                    "Sensor_Name": "Accelerometer",
                    "Unit": "m/s²",
                    "Val_0": "3.0",
                    "Val_1": "",
                    "Val_2": "4.0",
                    "Val_3": "",
                    "Val_4": "",
                    "Val_5": "",
                },
            ]
            with path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, fieldnames=fields)
                writer.writeheader()
                writer.writerows(rows)

            records = batch_dataset_processor.read_current_raw_csv(path)
            self.assertEqual(len(records), 2)
            self.assertEqual(records[0]["v1"], 2.0)
            self.assertIsNone(records[1]["v1"])
            self.assertEqual(records[0]["v0"], 1.0)
            self.assertEqual(records[1]["v0"], 3.0)

            summary = batch_dataset_processor.analyze_raw_records(records)
            self.assertEqual(summary["accel_count"], 1, "Only the complete XYZ row contributes to vector metrics")
            self.assertTrue(math.isclose(summary["rms_x"], math.sqrt((1.0**2 + 3.0**2) / 2)))
            self.assertEqual(summary["rms_y"], 2.0, "Missing Y must not be treated as 0.0")
            self.assertTrue(math.isclose(summary["rms_z"], math.sqrt((2.0**2 + 4.0**2) / 2)))
            self.assertEqual(summary["max_rss"], 3.0, "Partial second row must not invent RSS = 5")
            self.assertEqual(summary["peak_to_peak"], 0.0)

    def test_json_export_preserves_signed_64_bit_ns_timestamp_and_null_zero(self):
        timestamp_ns = "9223372036854775000"
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "large_timestamp.csv"
            row = {
                "Schema_Version": "2",
                "Sensor_Timestamp_ns": timestamp_ns,
                "Timestamp_ms": "1700000000000",
                "Machine_ID": "M1",
                "Point": "P1",
                "Source_Kind": "ANDROID_SENSOR",
                "Source_ID": "sensor-1",
                "Channel_ID": "accel-xyz",
                "Sensor_Type": "1",
                "Sensor_Name": "Accelerometer",
                "Unit": "m/s²",
                "Val_0": "0.0",
                "Val_1": "",
                "Val_2": "2.5",
                "Val_3": "",
                "Val_4": "",
                "Val_5": "",
            }
            with path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, fieldnames=list(web_server.RAW_CSV_COLUMNS))
                writer.writeheader()
                writer.writerow(row)

            output = export_json.compile_to_json(path)
            data = json.loads(output.read_text(encoding="utf-8"))
            record = data["sensor_telemetry"][0]
            self.assertEqual(record["Sensor_Timestamp_ns"], timestamp_ns)
            self.assertEqual(record["Val_0"], 0.0)
            self.assertIsNone(record["Val_1"])
            self.assertEqual(record["Val_2"], 2.5)

    @unittest.skipUnless(shutil.which("node"), "Node.js is unavailable; browser JS harness skipped")
    def test_dashboard_handles_unavailable_values_without_zero_coercion(self):
        source = DASHBOARD.read_text(encoding="utf-8")
        names = [
            "nullableNumber",
            "formatMeasurementValue",
            "getRawValues",
            "getSensorType",
            "sensorNameFromType",
            "getSensorName",
            "handleRawPacket",
            "handleAnalysisPacket",
            "downloadRawCSV",
            "csvEscape",
        ]
        functions = "\n\n".join(_extract_js_function(source, name) for name in names)
        harness = r'''
const elements = {};
const document = { getElementById(id) { return elements[id] || (elements[id] = { textContent: "" }); } };
let currentRPM = 1800;
let currentMachineId = "";
let currentPoint = "";
let currentSensorType = null;
let currentSensorName = "";
let lastPacketTimestamp = 0;
let sampleCounter = 0;
let recording = true;
let rawRecording = [];
let analysisRecording = [];
let timestamps = [];
let latestAnalysis = null;
let analysisCounter = 0;
let trend = [];
let generatedCsv = "";
const MAX_WAVEFORM_POINTS = 16;
const MAX_TREND_POINTS = 8;
const waveform = { x: [], y: [], z: [], rss: [] };
const sensorStates = {};
function ensureSensorState(type) {
  return sensorStates[type] || (sensorStates[type] = { name: "", x: null, y: null, z: null, ts: null, packets: 0 });
}
function trimArray(values, max) { if (values.length > max) values.splice(0, values.length - max); }
function computeAndDisplayBrowserFFT() {}
function updateBrowserQuality() {}
function updateSensorTable() {}
function updateLiveRate() {}
function drawAll() {}
function updateRunningSpeedHints() {}
function updateFaultHint() {}
function downloadText(text, filename) { generatedCsv = text; }
function alert() {}
''' + functions + r'''
const packet = {
  kind: "raw", schemaVersion: 2, sensorTimestampNs: "9223372036854775000",
  ts: 1700000000123, machineId: "M1", point: "P1", sourceKind: "ANDROID_SENSOR",
  sourceId: "sensor, 1", channelId: "accel-xyz", sensorType: 1,
  sensor: "Hardware Accelerometer, Rev B", unit: "m/s²", v0: 0.0, v1: null, v2: 3.0,
  v3: null, v4: null, v5: null
};
handleRawPacket(packet);
handleAnalysisPacket({
  kind: "analysis", ts: 1700000000123, machineId: "M1", point: "P1",
  sampleRateHz: null, overallRmsAccelMs2: 0.0, overallRmsVelocityMmS: null,
  overallRmsDisplacementUm: null, dominantAxis: "X", dominantFreqHz: null,
  envelopePeakFreqHz: null, isoZone: "", bearingMatch: ""
});
downloadRawCSV();
console.log(JSON.stringify({
  missingNumber: nullableNumber(null) === null,
  nonFiniteNumber: nullableNumber(Infinity) === null,
  unavailableLabel: formatMeasurementValue(null) === "N/A",
  rawValues: getRawValues(packet),
  sensorType: getSensorType(packet),
  sensorName: getSensorName(packet),
  state: sensorStates[1],
  waveformY: waveform.y,
  waveformRss: waveform.rss,
  record: rawRecording[0],
  rawCsv: generatedCsv,
  analysisRecord: analysisRecording[0],
  trend: trend,
  velocityLabel: elements["overallRmsVelocity"].textContent,
  envelopeLabel: elements["envelopePeak"].textContent,
  sampleRateLabel: elements["sampleRate"].textContent
}));
'''
        result = subprocess.run([shutil.which("node"), "-e", harness], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, msg=result.stderr or result.stdout)
        actual = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertTrue(actual["missingNumber"])
        self.assertTrue(actual["nonFiniteNumber"])
        self.assertTrue(actual["unavailableLabel"])
        self.assertEqual(actual["rawValues"], [0, None, 3])
        self.assertEqual(actual["sensorType"], 1)
        self.assertEqual(actual["sensorName"], "Hardware Accelerometer, Rev B")
        self.assertIsNone(actual["state"]["y"])
        self.assertIsNone(actual["waveformY"][0])
        self.assertIsNone(actual["waveformRss"][0])
        self.assertEqual(actual["record"]["Sensor_Timestamp_ns"], "9223372036854775000")
        self.assertEqual(actual["record"]["Val_0"], 0)
        self.assertIsNone(actual["record"]["Val_1"])
        self.assertEqual(actual["record"]["Val_2"], 3)
        self.assertIsNone(actual["analysisRecord"]["RMS_Velocity_mms"])
        self.assertIsNone(actual["analysisRecord"]["RMS_Displacement_um"])
        self.assertIsNone(actual["trend"][0])
        self.assertEqual(actual["velocityLabel"], "N/A")
        self.assertEqual(actual["envelopeLabel"], "N/A")
        self.assertEqual(actual["sampleRateLabel"], "N/A")
        csv_rows = list(csv.reader(actual["rawCsv"].splitlines()))
        self.assertEqual(csv_rows[0], [
            "Schema_Version", "Sensor_Timestamp_ns", "Timestamp_ms", "Machine_ID", "Point",
            "Source_Kind", "Source_ID", "Channel_ID", "Sensor_Type", "Sensor_Name", "Unit",
            "Val_0", "Val_1", "Val_2", "Val_3", "Val_4", "Val_5",
        ])
        csv_record = dict(zip(csv_rows[0], csv_rows[1]))
        self.assertEqual(csv_record["Source_ID"], "sensor, 1")
        self.assertEqual(csv_record["Sensor_Timestamp_ns"], "9223372036854775000")
        self.assertEqual(csv_record["Val_0"], "0")
        self.assertEqual(csv_record["Val_1"], "")
        self.assertEqual(csv_record["Val_2"], "3")

    @unittest.skipUnless(shutil.which("node"), "Node.js is unavailable; JavaScript syntax check skipped")
    def test_all_inline_dashboard_javascript_parses(self):
        source = DASHBOARD.read_text(encoding="utf-8")
        blocks = re.findall(r"(?is)<script\b[^>]*>(.*?)</script\s*>", source)
        self.assertTrue(blocks, "dashboard must include its scripts")
        with tempfile.TemporaryDirectory() as td:
            for index, block in enumerate(blocks):
                if not block.strip():
                    continue
                script = Path(td) / f"dashboard_{index}.js"
                script.write_text(block, encoding="utf-8")
                result = subprocess.run([shutil.which("node"), "--check", str(script)], capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, msg=f"script block {index}: {result.stderr}")

    def test_android_wire_preserves_nanoseconds_as_decimal_json_string(self):
        source = ANDROID_STREAM.read_text(encoding="utf-8")
        self.assertIn('.append(",\\\"sensorTimestampNs\\\":\\\"")', source)
        self.assertIn('.append(sensorTimestampNs)', source)
        self.assertIn("JavaScript Number cannot", source)
        self.assertIn("exactly represent every signed 64-bit timestamp", source)

    def test_browser_raw_csv_uses_canonical_v2_header_and_nullable_fields(self):
        source = DASHBOARD.read_text(encoding="utf-8")
        headers = [
            "Schema_Version", "Sensor_Timestamp_ns", "Timestamp_ms", "Machine_ID", "Point",
            "Source_Kind", "Source_ID", "Channel_ID", "Sensor_Type", "Sensor_Name", "Unit",
            "Val_0", "Val_1", "Val_2", "Val_3", "Val_4", "Val_5",
        ]
        start = source.index("function downloadRawCSV()")
        end = source.index("function downloadAnalysisCSV()", start)
        function = source[start:end]
        for header in headers:
            self.assertIn(f'"{header}"', function)
        for field in ("Sensor_Timestamp_ns", "Val_0", "Val_1", "Val_2"):
            self.assertIn(field, source[source.index("rawRecording.push({") : source.index("updateSensorTable();", source.index("rawRecording.push({"))])
        self.assertIn("nullableNumber(rawSourceValues[1])", source)
        self.assertRegex(source, r"data\.sensor\s*\|\|\s*data\.sensorName\s*\|\|\s*data\.name")
        self.assertIn("data.sensorTimestampNs ?? \"\"", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
