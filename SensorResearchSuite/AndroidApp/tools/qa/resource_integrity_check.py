#!/usr/bin/env python3
"""Guard SensorMax Android resources against broken XML, unresolved strings,
placeholder drift, duplicate values, and common UTF-8 mojibake.

This is a static quality gate, not a translation-quality certification.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
SRC = ROOT / "app" / "src" / "main" / "java"
MANIFESTS = [ROOT / "app" / "src" / "main" / "AndroidManifest.xml", ROOT / "app" / "src" / "debug" / "AndroidManifest.xml"]

# Android's common positional format syntax, for example %1$.3f or %2$s.
FORMAT_TOKEN = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])")
RESOURCE_REF = re.compile(r"R\.string\.([A-Za-z_][A-Za-z0-9_]*)")
KOTLIN_RESOURCE_REF = re.compile(r"(?<!android\.)R\.([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_.]*)")
XML_RESOURCE_REF = re.compile(r"@(?:\+)?([A-Za-z][A-Za-z0-9_]*)/([A-Za-z_][A-Za-z0-9_.]*)")
XML_STRING_REF = re.compile(r"@string/([A-Za-z_][A-Za-z0-9_]*)")
MOJIBAKE_MARKERS = ("\ufffd", "Ã", "Â", "锟斤拷", "鈥", "ï»¿")
REQUIRED_LOCALE_KEYS = {
    "measurement_service_connecting",
    "experiment_import_failed",
    "translation_exported",
    "translation_export_failed",
    "asset_point_required",
    "asset_point_save_failed",
    "preferences_title",
    "preferences_language_label",
    "language_selector_heading",
    "language_selector_hint",
    "language_selector_content_description",
    "preferences_units_label",
    "numeric_input_invalid",
    "network_allow_lan_label",
    "network_note_loopback",
    "network_note_lan",
    "network_listener_port_invalid",
    "network_websocket_port_invalid",
    "network_wss_required",
    "waveform_note_format",
    "chart_acceleration_label_format",
    "chart_envelope_label_format",
    "chart_rms_trend_label_format",
    "bearing_ball_label_format",
    "bearing_pitch_label_format",
    "reference_warn_label_format",
    "reference_alert_label_format",
    "summary_detail_format",
    "impact_event_format",
    "dominant_rss_detail_format",
    "dominant_axis_detail_format",
    "capture_interval_format",
    "source_android_sensor",
    "source_ble_gatt",
    "source_audio_microphone",
    "source_location_gnss",
    "source_camera_photometric",
    "source_depth_tof",
    "source_network_tcp_ndjson",
    "profile_import_dialog_title",
    "profile_summary_format",
    "profile_unmapped",
    "profile_no_sensor_bindings",
    "profile_none",
    "profile_close",
    "profile_export",
    "profile_apply",
    "depth_acquisition_note",
    "ble_profile_note_format",
    "ble_profile_unavailable",
    "otg_title",
    "otg_note",
    "otg_reader_error",
    "permission_bluetooth_required",
    "permission_microphone_required",
    "location_services_enable",
    "permission_location_required",
    "permission_camera_photometric_required",
    "permission_camera_depth_required",
    "measurement_session_required",
    "measurement_service_unavailable",
    "snapshot_captured_format",
    "spectrum_not_ready",
    "enter_ble_address",
    "enter_websocket_host",
    "deployment_rejected",
    "sensor_required",
    "action_stop_stream_log",
    "action_stop_offline_log",
    "status_active_stream_format",
    "status_active_offline_format",
    "status_stopped",
    "notification_channel_name",
    "notification_channel_description",
    "notification_title",
    "notification_ready_summary",
    "notification_measurement_active_stream",
    "notification_measurement_active_offline",
    "notification_streaming_active",
    "notification_offline_active",
}


def get_format_tokens(text: str) -> list[tuple[str | None, str]]:
    return sorted((index, kind.lower()) for index, kind in FORMAT_TOKEN.findall(text))


def parse_values_file(path: Path) -> tuple[str, dict[tuple[str, str], ET.Element]]:
    tree = ET.parse(path)
    folder = path.parent.name
    resources: dict[tuple[str, str], ET.Element] = {}
    for child in tree.getroot():
        name = child.attrib.get("name")
        kind = child.attrib.get("type", child.tag)
        if name:
            key = (kind, name)
            if key in resources:
                raise ValueError(f"duplicate {kind}/{name} in {path.relative_to(ROOT)}")
            resources[key] = child
    return folder, resources


def inner_text(element: ET.Element) -> str:
    return "".join(element.itertext())


def main() -> int:
    errors: list[str] = []
    xml_files = sorted(RES.rglob("*.xml"))
    parsed_values: dict[str, dict[tuple[str, str], ET.Element]] = defaultdict(dict)

    for path in xml_files:
        try:
            ET.parse(path)
        except (ET.ParseError, OSError) as error:
            errors.append(f"invalid XML {path.relative_to(ROOT)}: {error}")
            continue
        if path.parent.name.startswith("values"):
            try:
                folder, values = parse_values_file(path)
                for key, element in values.items():
                    if key in parsed_values[folder]:
                        errors.append(f"duplicate {key[0]}/{key[1]} across {folder} XML values files")
                    parsed_values[folder][key] = element
            except (ET.ParseError, ValueError) as error:
                errors.append(str(error))

    for path in MANIFESTS:
        if path.exists():
            try:
                manifest_text = path.read_text(encoding="utf-8")
                ET.fromstring(manifest_text)
                if r"\n" in manifest_text:
                    errors.append(f"literal backslash-n found in {path.relative_to(ROOT)}")
            except (ET.ParseError, OSError) as error:
                errors.append(f"invalid manifest {path.relative_to(ROOT)}: {error}")

    base = parsed_values.get("values", {})
    base_strings = {name: element for (kind, name), element in base.items() if kind == "string"}
    base_keys = set(base_strings)

    # Ensure XML references such as @string/foo also resolve against the base locale.
    for path in [*xml_files, *MANIFESTS]:
        if not path.exists():
            continue
        xml_text = path.read_text(encoding="utf-8", errors="replace")
        for name in XML_STRING_REF.findall(xml_text):
            if name not in base_keys:
                errors.append(f"unresolved @string/{name} in {path.relative_to(ROOT)}")

    for folder in ("values-id", "values-zh-rCN"):
        locale_values = parsed_values.get(folder, {})
        locale_strings = {name: element for (kind, name), element in locale_values.items() if kind == "string"}
        missing = sorted(base_keys - set(locale_strings))
        for name in missing:
            errors.append(f"{folder} is missing localized string {name}")
        missing_required = sorted(REQUIRED_LOCALE_KEYS - set(locale_strings))
        for name in missing_required:
            errors.append(f"{folder} is missing required localized string {name}")
        for name, element in locale_strings.items():
            if name not in base_strings:
                errors.append(f"{folder} has string {name} without an English fallback")
                continue
            expected = get_format_tokens(inner_text(base_strings[name]))
            actual = get_format_tokens(inner_text(element))
            if expected != actual:
                errors.append(f"format placeholder mismatch for {folder}:{name}: expected {expected}, got {actual}")

    # Guard the Product Owner's top-level language-selector UX contract.
    # It must be pinned outside the scrolling diagnostic body, labeled for accessibility,
    # and exist only once so users never have to hunt through Developer/Device settings.
    activity_layout = RES / "layout" / "activity_main.xml"
    if activity_layout.exists():
        try:
            layout_root = ET.parse(activity_layout).getroot()
            android_ns = "{http://schemas.android.com/apk/res/android}"
            ids = {
                element.attrib.get(android_ns + "id"): element
                for element in layout_root.iter()
                if element.attrib.get(android_ns + "id")
            }
            top_bar = ids.get("@+id/llLanguageTopBar")
            spinner = ids.get("@+id/spinnerLanguagePreference")
            scroll = ids.get("@+id/scrollDiagnosticBody")
            if top_bar is None:
                errors.append("activity_main.xml is missing the pinned app-language top bar")
            elif top_bar.attrib.get(android_ns + "layout_alignParentTop") != "true":
                errors.append("language top bar must be aligned to the top of the screen")
            if spinner is None or top_bar is None or spinner not in list(top_bar.iter()):
                errors.append("language selector spinner must be inside the pinned top bar")
            elif spinner.attrib.get(android_ns + "contentDescription") != "@string/language_selector_content_description":
                errors.append("top language selector is missing its localized accessibility description")
            if spinner is not None:
                if spinner.attrib.get(android_ns + "layout_height") != "@dimen/sm_control_height":
                    errors.append("top language selector must keep the shared 48dp+ touch target")
                if spinner.attrib.get(android_ns + "entries") != "@array/language_options":
                    errors.append("top language selector must use the localized language options array")
            if scroll is None or scroll.attrib.get(android_ns + "layout_below") != "@id/llLanguageTopBar":
                errors.append("diagnostic ScrollView must start below the pinned language top bar")
            heading = ids.get("@+id/tvLanguageSelectorHeading")
            if heading is None or heading.attrib.get(android_ns + "labelFor") != "@id/spinnerLanguagePreference":
                errors.append("top language selector heading must label the language spinner")
            elif heading.attrib.get(android_ns + "maxLines") != "2":
                errors.append("top language selector heading must allow two lines on narrow screens")
            if spinner is not None:
                # One selector only; an identical ID in another layout causes confusing UI and binding.
                debug_layout = RES / "layout" / "section_device_debug.xml"
                if debug_layout.exists() and "@+id/spinnerLanguagePreference" in debug_layout.read_text(encoding="utf-8"):
                    errors.append("duplicate language selector remains in the Device/Debug section")
            arrays_path = RES / "values" / "arrays.xml"
            if arrays_path.exists():
                arrays_root = ET.parse(arrays_path).getroot()
                language_array = next((node for node in arrays_root.findall("string-array") if node.attrib.get("name") == "language_options"), None)
                expected_options = [
                    "@string/language_english",
                    "@string/language_indonesian",
                    "@string/language_chinese_simplified",
                    "@string/language_system",
                ]
                actual_options = [item.text for item in language_array.findall("item")] if language_array is not None else []
                if actual_options != expected_options:
                    errors.append("language_options must remain English, Indonesian, Simplified Chinese, System default in preference-mapping order")
        except (ET.ParseError, OSError) as error:
            errors.append(f"cannot validate top language selector: {error}")

    # Build resource symbol tables once to catch broken @type/name and R.type.name references
    # before an Android SDK/Gradle build is available.
    available: dict[str, set[str]] = defaultdict(set)
    file_resource_types = ("drawable", "mipmap", "layout", "color", "anim", "animator", "menu", "xml", "raw", "font", "navigation")
    for resource_type in file_resource_types:
        folders = [
            folder for folder in RES.iterdir()
            if folder.is_dir() and (folder.name == resource_type or folder.name.startswith(resource_type + "-"))
        ]
        for folder in folders:
            available[resource_type].update(item.stem for item in folder.rglob("*") if item.is_file())
    for values_folder in RES.glob("values*"):
        if not values_folder.is_dir():
            continue
        for path in values_folder.glob("*.xml"):
            try:
                values_root = ET.parse(path).getroot()
            except ET.ParseError:
                continue
            for element in values_root:
                name = element.attrib.get("name")
                if not name:
                    continue
                resource_type = element.attrib.get("type", element.tag)
                if resource_type in {"array", "string-array"}:
                    resource_type = "array"
                available[resource_type].add(name)
                if resource_type == "style":
                    available["style_r"].add(name.replace(".", "_"))

    # Catch unresolved XML references as well as Kotlin R.* references. IDs are
    # handled separately below because @id/foo may refer to @+id/foo elsewhere.
    for path in [*xml_files, *MANIFESTS]:
        if not path.exists():
            continue
        xml_text = path.read_text(encoding="utf-8", errors="replace")
        for resource_type, name in XML_RESOURCE_REF.findall(xml_text):
            if resource_type in {"android", "id"}:
                continue
            if name not in available.get(resource_type, set()):
                errors.append(f"unresolved @{resource_type}/{name} in {path.relative_to(ROOT)}")

    for path in sorted(SRC.rglob("*.kt")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for name in RESOURCE_REF.findall(text):
            if name not in base_keys:
                errors.append(f"unresolved R.string.{name} in {path.relative_to(ROOT)}")
        for resource_type, name in KOTLIN_RESOURCE_REF.findall(text):
            if resource_type == "id":
                continue
            lookup_type = "style_r" if resource_type == "style" else resource_type
            if lookup_type in available and name not in available[lookup_type]:
                errors.append(f"unresolved R.{resource_type}.{name} in {path.relative_to(ROOT)}")
        # Only flag user-visible Toast literals; technical errors and provenance strings may remain dynamic.
        if re.search(r'Toast\.makeText\s*\(\s*this\s*,\s*"[^"\n]+"', text):
            errors.append(f"hard-coded Toast text remains in {path.relative_to(ROOT)}")
        if re.search(r'\.set(?:Title|Message|PositiveButton|NegativeButton|NeutralButton)\s*\(\s*"[^"\n]+"', text):
            errors.append(f"hard-coded AlertDialog text remains in {path.relative_to(ROOT)}")
        if re.search(r"sourceLabels\s*=\s*listOf\s*\(\s*\"", text):
            errors.append(f"hard-coded acquisition-source labels remain in {path.relative_to(ROOT)}")

    # Catch unresolved Android view IDs early; a Kotlin-only source scan cannot detect these.
    defined_view_ids: set[str] = set()
    for path in RES.rglob("*.xml"):
        try:
            xml_text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as error:
            errors.append(f"cannot read resource {path.relative_to(ROOT)}: {error}")
            continue
        defined_view_ids.update(re.findall(r"android:id=\"@\+id/([A-Za-z_][A-Za-z0-9_]*)\"", xml_text))
    for path in [*xml_files, *MANIFESTS]:
        if not path.exists():
            continue
        xml_text = path.read_text(encoding="utf-8", errors="replace")
        for name in re.findall(r"@id/([A-Za-z_][A-Za-z0-9_]*)", xml_text):
            if name not in defined_view_ids:
                errors.append(f"unresolved @id/{name} in {path.relative_to(ROOT)}")
    for path in sorted(SRC.rglob("*.kt")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for name in re.findall(r"R\.id\.([A-Za-z_][A-Za-z0-9_]*)", text):
            if name not in defined_view_ids:
                errors.append(f"unresolved R.id.{name} in {path.relative_to(ROOT)}")

    source_text = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in sorted(SRC.rglob("*.kt"))
    )
    for declaration in ("WebSocketEndpointBuilder", "SensorMaxPreferences", "SensorMaxLanguage", "MeasurementSystem"):
        if not re.search(r"\b(class|object|enum\s+class|data\s+class|sealed\s+class|interface)\s+" + re.escape(declaration) + r"\b", source_text):
            errors.append(f"missing production Kotlin declaration {declaration}")

    service_file = SRC / "com" / "research" / "sensormax" / "SensorCaptureService.kt"
    if service_file.exists():
        service_text = service_file.read_text(encoding="utf-8", errors="replace")
        for literal in (
            'setContentTitle("SensorMax measurement")',
            '"Measurement active · stream + log"',
            '"Measurement active · offline log"',
            '"Streaming + local logging active"',
            '"Offline local logging active"',
            '"Ready for sensor acquisition"',
            '"Shows when SensorMax is actively acquiring sensor data."',
        ):
            if literal in service_text:
                errors.append(f"hard-coded foreground-notification text remains: {literal}")
        if "fun refreshNotificationLocale(" not in service_text:
            errors.append("SensorCaptureService must support foreground-notification locale refresh")

    main_activity_file = SRC / "com" / "research" / "sensormax" / "MainActivity.kt"
    if main_activity_file.exists():
        activity_text = main_activity_file.read_text(encoding="utf-8", errors="replace")
        for function_name in ("toggleUIState", "haltDeployment", "resetDeploymentUiState"):
            declaration = re.search(r"\bprivate\s+fun\s+" + re.escape(function_name) + r"\s*\(", activity_text)
            call_count = len(re.findall(r"\b" + re.escape(function_name) + r"\s*\(", activity_text))
            if declaration is None:
                errors.append(f"MainActivity is missing required deployment UI helper {function_name}")
            elif call_count < 2 and function_name != "resetDeploymentUiState":
                errors.append(f"deployment UI helper {function_name} is declared but no longer wired to deployment flows")

    for path in sorted([*RES.rglob("*.xml"), *SRC.rglob("*.kt")]):
        text = path.read_text(encoding="utf-8", errors="replace")
        for marker in MOJIBAKE_MARKERS:
            if marker in text:
                errors.append(f"possible mojibake marker {marker!r} in {path.relative_to(ROOT)}")
                break
        if "\ufffd" in text:
            # The marker is already reported above; this explicit branch is kept for clarity.
            pass

    if errors:
        print("RESOURCE_INTEGRITY_FAIL")
        for error in errors:
            print(f"- {error}")
        return 1

    print("RESOURCE_INTEGRITY_PASS")
    print(f"Parsed {len(xml_files)} resource XML files; checked string references, Kotlin R.id references, required preference/network declarations, complete locale key coverage, locale placeholders, encoding markers, pinned top-language-selector UX, deployment UI helpers, and foreground-notification localization.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
