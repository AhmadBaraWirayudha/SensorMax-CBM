"""Focused regression tests for SensorResearchSuite Sprint 31.

Run from the project root:
    python -m unittest LaptopSuite.test_sprint31_regressions -v
"""
from __future__ import annotations

import ast
import ipaddress
import unittest
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
SUITE_DIR = PROJECT_DIR / "LaptopSuite"


class Sprint31RegressionTests(unittest.TestCase):
    def test_all_laptop_suite_python_files_parse(self) -> None:
        files = sorted(
            path for path in SUITE_DIR.rglob("*.py")
            if "__pycache__" not in path.parts
        )
        self.assertTrue(files, "No Python files were discovered")
        for path in files:
            with self.subTest(path=str(path.relative_to(PROJECT_DIR))):
                ast.parse(path.read_text(encoding="utf-8"), filename=str(path))

    def test_dashboard_uses_single_archive_root_location(self) -> None:
        dashboard = PROJECT_DIR / "web_dashboard.html"
        self.assertTrue(dashboard.is_file(), "Root dashboard is missing")
        self.assertFalse(
            (SUITE_DIR / "web_dashboard.html").exists(),
            "A second dashboard copy could drift from the canonical root file",
        )

        windows_launcher = (PROJECT_DIR / "run_web_hmi.bat").read_text(
            encoding="utf-8"
        )
        shell_launcher = (PROJECT_DIR / "run_laptop_suite.sh").read_text(
            encoding="utf-8"
        )
        health_verifier = (SUITE_DIR / "verify_suite_health.py").read_text(
            encoding="utf-8"
        )
        self.assertIn(r"%SUITE_DIR%web_dashboard.html", windows_launcher)
        self.assertIn('WEB_DASHBOARD="$SCRIPT_DIR/web_dashboard.html"', shell_launcher)
        self.assertIn('PROJECT_DIR /\n        "web_dashboard.html"', health_verifier)

    def test_gateway_defaults_to_loopback_and_warns_for_non_loopback(self) -> None:
        source = (SUITE_DIR / "web_server.py").read_text(encoding="utf-8")
        module = ast.parse(source, filename="web_server.py")
        host_assignment = next(
            node for node in module.body
            if isinstance(node, ast.Assign)
            and any(isinstance(target, ast.Name) and target.id == "SERVER_HOST"
                    for target in node.targets)
        )
        env_call = next(
            node for node in ast.walk(host_assignment.value)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and node.func.attr == "get"
        )
        self.assertEqual(env_call.args[0].value, "SENSORMAX_LISTEN_HOST")
        self.assertEqual(env_call.args[1].value, "127.0.0.1")
        self.assertIn("SECURITY WARNING: insecure non-loopback mode explicitly enabled.", source)
        self.assertIn("no TLS/authentication", source)
        self.assertIn("SENSORMAX_ALLOW_INSECURE_LAN", source)
        self.assertIn("Refusing this WebSocket bind target", source)
        self.assertIn("Wildcard (0.0.0.0/::), public IPs, and ", source)
        self.assertIn("hostnames are rejected", source)

        helper = next(
            node for node in module.body
            if isinstance(node, ast.FunctionDef) and node.name == "is_loopback_host"
        )
        isolated_module = ast.Module(body=[helper], type_ignores=[])
        namespace: dict[str, object] = {"ipaddress": ipaddress}
        exec(compile(isolated_module, "web_server.py:is_loopback_host", "exec"), namespace)
        check = namespace["is_loopback_host"]
        self.assertTrue(check("127.0.0.1"))  # type: ignore[operator]
        self.assertTrue(check("::1"))  # type: ignore[operator]
        self.assertTrue(check("localhost"))  # type: ignore[operator]
        self.assertFalse(check("0.0.0.0"))  # type: ignore[operator]
        self.assertFalse(check("192.168.1.20"))  # type: ignore[operator]

    def test_setup_and_health_gates_include_cbm_dashboard(self) -> None:
        setup = (PROJECT_DIR / "first_initialize.bat").read_text(encoding="utf-8")
        health = (SUITE_DIR / "verify_suite_health.py").read_text(encoding="utf-8")
        self.assertIn(r"LaptopSuite\cbm_live_dashboard.py", setup)
        self.assertIn('"cbm_live_dashboard.py"', health)


if __name__ == "__main__":
    unittest.main(verbosity=2)
