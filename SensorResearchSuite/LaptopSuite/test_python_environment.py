"""Unit tests for the SensorMax Python environment policy."""
from __future__ import annotations

import unittest
from pathlib import Path

from LaptopSuite import verify_environment as env


class PythonEnvironmentPolicyTests(unittest.TestCase):
    def test_supports_only_declared_python_minor(self):
        self.assertTrue(env.python_supported((3, 13, 5)))
        self.assertFalse(env.python_supported((3, 12, 9)))
        self.assertFalse(env.python_supported((3, 14, 0)))

    def test_direct_pin_parser_canonicalizes_names(self):
        pins = env.parse_direct_pins("numpy==2.3.5\nPy-Serial==3.5 # comment\n")
        self.assertEqual(pins, {"numpy": "2.3.5", "py-serial": "3.5"})

    def test_direct_pin_parser_rejects_ranges_and_duplicates(self):
        with self.assertRaises(ValueError):
            env.parse_direct_pins("numpy>=2.0\n")
        with self.assertRaises(ValueError):
            env.parse_direct_pins("numpy==2.3.5\nnumpy==2.3.4\n")

    def test_pin_check_reports_missing_and_mismatched_versions(self):
        actual = {"numpy": "2.3.4"}

        def lookup(name: str) -> str:
            if name not in actual:
                from importlib.metadata import PackageNotFoundError
                raise PackageNotFoundError(name)
            return actual[name]

        failures = env.check_pins({"numpy": "2.3.5", "pandas": "2.2.3"}, lookup)
        self.assertEqual(len(failures), 2)
        self.assertTrue(any("found 2.3.4" in x for x in failures))
        self.assertTrue(any("not installed" in x for x in failures))

    def test_pin_file_covers_every_required_distribution(self):
        pins = env.parse_direct_pins(env.PIN_FILE.read_text(encoding="utf-8"))
        expected = {name.lower() for name in env.REQUIRED_IMPORTS}
        self.assertEqual(set(pins), expected)

    def test_broad_requirements_names_match_direct_pin_profile(self):
        requirements = env.SUITE_DIR / "requirements.txt"
        declared = set()
        for raw in requirements.read_text(encoding="utf-8").splitlines():
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            name = line.split("<", 1)[0].split(">", 1)[0].split("=", 1)[0].strip()
            declared.add(name.replace("_", "-").lower())
        pins = set(env.parse_direct_pins(env.PIN_FILE.read_text(encoding="utf-8")))
        self.assertEqual(declared, pins)
        self.assertIn("NOT a lockfile", requirements.read_text(encoding="utf-8"))

    def test_bootstrap_and_shell_launcher_use_environment_gate(self):
        root = Path(__file__).resolve().parents[1]
        windows_setup = (root / "first_initialize.bat").read_text(encoding="utf-8")
        shell_setup = (root / "run_laptop_suite.sh").read_text(encoding="utf-8")
        self.assertIn("requirements-direct-pins-py313.txt", windows_setup)
        self.assertIn("sys.version_info[:2] == (3,13)", windows_setup)
        self.assertIn("verify_environment.py\" --check-pins", windows_setup)
        self.assertIn("verify_environment.py\" --imports-only", shell_setup)


if __name__ == "__main__":
    unittest.main()
