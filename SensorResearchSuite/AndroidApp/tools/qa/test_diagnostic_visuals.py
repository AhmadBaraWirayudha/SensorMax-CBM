"""Static regression guards for Android triaxial waveform and diagnostic-log UI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
MAIN_ACTIVITY = ROOT / "AndroidApp/app/src/main/java/com/research/sensormax/MainActivity.kt"
DEBUG_LAYOUT = ROOT / "AndroidApp/app/src/main/res/layout/section_device_debug.xml"
COLORS = ROOT / "AndroidApp/app/src/main/res/values/colors.xml"


class DiagnosticVisualsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = MAIN_ACTIVITY.read_text(encoding="utf-8")
        cls.layout = DEBUG_LAYOUT.read_text(encoding="utf-8")
        cls.colors = COLORS.read_text(encoding="utf-8")

    def test_runtime_triaxial_series_use_semantic_rgb_tokens(self):
        for dataset, token in (("setX", "sm_axis_x"), ("setY", "sm_axis_y"), ("setZ", "sm_axis_z")):
            start = self.main.index(f"val {dataset} =")
            end = self.main.index("val set", start + 8) if dataset != "setZ" else self.main.index("val setRss", start)
            block = self.main[start:end]
            self.assertIn(f"color = getColor(R.color.{token})", block)
            self.assertIn(f"setCircleColor(getColor(R.color.{token}))", block)
            self.assertIn(f"highLightColor = getColor(R.color.{token})", block)

    def test_axis_refresh_reapplies_the_rgb_palette(self):
        start = self.main.index("private fun applyWaveformAxisColors")
        end = self.main.index("// ============================================================\n    // WATCHERS", start)
        block = self.main[start:end]
        for token in ("sm_axis_x", "sm_axis_y", "sm_axis_z", "sm_axis_rss"):
            self.assertIn(f"R.color.{token}", block)
        self.assertIn("vibrationChart.legend.textColor", block)
        self.assertIn("applyWaveformAxisColors(data)", self.main[self.main.index("private fun applyWaveformAxisView"):start])

    def test_auto_mode_keeps_all_three_physical_axes_visible(self):
        start = self.main.index("private fun applyWaveformAxisView")
        end = self.main.index("private fun applyWaveformAxisColors", start)
        block = self.main[start:end]
        auto = block[block.index("AxisView.AUTO ->"):block.index("AxisView.X ->")]
        for axis in ("xSet", "ySet", "zSet"):
            self.assertIn(f"{axis}.isVisible = true", auto)
        self.assertIn("rssSet.isVisible = false", auto)

    def test_log_renders_spans_and_keeps_actual_messages(self):
        self.assertIn("tvLog.text = buildColorCodedDiagnosticLog(lines)", self.main)
        self.assertIn("SpannableStringBuilder", self.main)
        self.assertIn("ForegroundColorSpan", self.main)
        self.assertIn("StyleSpan(Typeface.BOLD)", self.main)
        self.assertIn("takeLast(MAX_DIAGNOSTIC_LOG_LINES)", self.main)
        self.assertIn("currentText == getString(R.string.log_initial)", self.main)

    def test_log_has_severity_and_subsystem_color_coding(self):
        for tag in ('"FATAL", "ERROR", "CRITICAL"', '"WARN", "WARNING", "ALERT"', '"NETWORK", "NETWORK-IN", "WEBSOCKET"', '"SENSOR", "SOURCE", "ACQUISITION"', '"PROFILE", "EXPORT", "IMPORT"'):
            self.assertIn(tag, self.main)
        for token in ("sm_text_bad", "sm_text_warn", "sm_text_secondary", "sm_axis_y", "sm_axis_z", "sm_purple"):
            self.assertIn(f"R.color.{token}", self.main)

    def test_log_text_is_high_contrast_and_top_aligned(self):
        start = self.layout.index('android:id="@+id/tvLog"')
        block = self.layout[start:self.layout.index("/>", start) + 2]
        self.assertIn('android:textColor="@color/sm_text_primary"', block)
        self.assertIn('android:gravity="top|start"', block)
        self.assertIn('android:lineSpacingExtra="2dp"', block)
        self.assertIn('android:scrollbars="vertical"', block)

    def test_axis_tokens_are_distinct_red_green_blue_hues(self):
        expected = {
            "sm_axis_x": "#F87171",
            "sm_axis_y": "#4ADE80",
            "sm_axis_z": "#60A5FA",
        }
        for name, hex_value in expected.items():
            self.assertRegex(self.colors, rf'<color name="{name}">{hex_value}</color>')


if __name__ == "__main__":
    unittest.main()
