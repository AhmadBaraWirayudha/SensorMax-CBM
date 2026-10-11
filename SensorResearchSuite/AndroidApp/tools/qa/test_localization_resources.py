#!/usr/bin/env python3
"""Regression tests for SensorMax English/Indonesian/Simplified-Chinese resources."""
from __future__ import annotations

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
FORMAT_TOKEN = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])")
MOJIBAKE = ("\ufffd", "Ã", "Â", "锟斤拷", "鈥", "ï»¿")


def read_strings(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    result: dict[str, str] = {}
    for element in root.findall("string"):
        name = element.attrib["name"]
        if name in result:
            raise AssertionError(f"duplicate string key: {name}")
        result[name] = "".join(element.itertext())
    return result


class LocalizationResourcesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.english = read_strings(RES / "values" / "strings.xml")
        cls.locales = {
            "values-id": read_strings(RES / "values-id" / "strings.xml"),
            "values-zh-rCN": read_strings(RES / "values-zh-rCN" / "strings.xml"),
        }

    def test_locale_key_sets_match_english_exactly(self) -> None:
        expected = set(self.english)
        for locale, strings in self.locales.items():
            with self.subTest(locale=locale):
                self.assertEqual(expected, set(strings),
                                 f"missing={sorted(expected-set(strings))}; extra={sorted(set(strings)-expected)}")

    def test_format_placeholders_match_for_every_key(self) -> None:
        for locale, strings in self.locales.items():
            for key, source in self.english.items():
                with self.subTest(locale=locale, key=key):
                    expected = sorted((idx or "", kind.lower()) for idx, kind in FORMAT_TOKEN.findall(source))
                    actual = sorted((idx or "", kind.lower()) for idx, kind in FORMAT_TOKEN.findall(strings[key]))
                    self.assertEqual(expected, actual)

    def test_all_resource_xml_parses(self) -> None:
        for path in sorted(RES.rglob("*.xml")):
            with self.subTest(path=str(path.relative_to(ROOT))):
                ET.parse(path)

    def test_no_common_mojibake_markers_in_localized_strings(self) -> None:
        for locale, strings in self.locales.items():
            for key, value in strings.items():
                with self.subTest(locale=locale, key=key):
                    for marker in MOJIBAKE:
                        self.assertNotIn(marker, value)

    def test_required_dashboard_labels_are_not_english_fallbacks(self) -> None:
        examples = {
            "values-id": {
                "summary_rms_label": "RMS keseluruhan",
                "fault_title": "Skrining gangguan",
                "network_note": "Listener TCP/NDJSON native SensorMax. Timestamp jarak jauh hanya metadata; SensorMax menetapkan timestamp monotonik lokal saat data diterima.",
                "footer_tagline": "Akuisisi getaran triaxial • analisis spektral • bukti lapangan",
            },
            "values-zh-rCN": {
                "summary_rms_label": "总体 RMS",
                "fault_title": "故障筛查",
                "network_note": "SensorMax 原生 TCP/NDJSON 监听器。远端时间戳仅作为元数据；SensorMax 在收到数据时分配本地单调时间戳。",
                "footer_tagline": "三轴振动采集 • 频谱分析 • 现场证据",
            },
        }
        for locale, expected in examples.items():
            for key, text in expected.items():
                with self.subTest(locale=locale, key=key):
                    self.assertEqual(text, self.locales[locale][key])

    def test_engineering_units_and_channel_identifiers_are_preserved(self) -> None:
        protected = {
            "summary_rms_initial": "m/s²",
            "value_hz_initial": "Hz",
            "waveform_note": "±15 m/s²",
            "bearing_ball_label": "mm",
            "bearing_pitch_label": "mm",
            "bearing_angle_label": "°",
            "ble_address_hint": "AA:BB:CC:DD:EE:FF",
        }
        for locale, strings in self.locales.items():
            for key, token in protected.items():
                with self.subTest(locale=locale, key=key):
                    self.assertIn(token, strings[key])
            for key in ("axis_x", "axis_y", "axis_z", "axis_rss"):
                with self.subTest(locale=locale, key=key):
                    self.assertEqual(self.english[key], strings[key])

    def test_locale_content_is_utf8_round_trip(self) -> None:
        for path in (RES / "values-id" / "strings.xml", RES / "values-zh-rCN" / "strings.xml"):
            with self.subTest(path=path.parent.name):
                raw = path.read_bytes()
                self.assertEqual(raw, raw.decode("utf-8").encode("utf-8"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
