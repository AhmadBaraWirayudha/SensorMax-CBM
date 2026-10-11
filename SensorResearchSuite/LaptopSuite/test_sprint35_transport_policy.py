"""Sprint 35 transport-policy regression tests.

Run from the project root:
    python -m unittest LaptopSuite.test_sprint35_transport_policy -v
"""
from __future__ import annotations

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from LaptopSuite import web_server

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class Sprint35TransportPolicyTests(unittest.TestCase):
    def test_gateway_default_is_loopback(self):
        source = (ROOT / "LaptopSuite" / "web_server.py").read_text(encoding="utf-8")
        self.assertIn('os.environ.get("SENSORMAX_LISTEN_HOST", "127.0.0.1")', source)
        self.assertTrue(web_server.is_loopback_host("127.0.0.1"))

    def test_lan_mode_requires_specific_private_interface_address(self):
        self.assertFalse(web_server.can_bind_host("192.168.1.20", False))
        self.assertTrue(web_server.can_bind_host("192.168.1.20", True))
        self.assertTrue(web_server.can_bind_host("10.10.1.2", True))
        self.assertTrue(web_server.can_bind_host("fd12:3456:789a::1", True))
        for unsafe in ("0.0.0.0", "::", "8.8.8.8", "169.254.1.10", "router.local"):
            with self.subTest(host=unsafe):
                self.assertFalse(web_server.can_bind_host(unsafe, True))

    def test_release_manifest_disables_cleartext(self):
        tree = ET.parse(ROOT / "AndroidApp" / "app" / "src" / "main" / "AndroidManifest.xml")
        app = tree.getroot().find("application")
        self.assertIsNotNone(app)
        self.assertEqual("false", app.get(ANDROID_NS + "usesCleartextTraffic"))

    def test_debug_overlay_allows_development_ws(self):
        tree = ET.parse(ROOT / "AndroidApp" / "app" / "src" / "debug" / "AndroidManifest.xml")
        app = tree.getroot().find("application")
        self.assertIsNotNone(app)
        self.assertEqual("true", app.get(ANDROID_NS + "usesCleartextTraffic"))

    def test_android_tcp_profile_defaults_to_loopback(self):
        source = (ROOT / "AndroidApp" / "app" / "src" / "main" / "java" / "com" / "research" / "sensormax" / "NetworkAcquisitionSource.kt").read_text(encoding="utf-8")
        self.assertRegex(source, r"data class NetworkAcquisitionProfile\([\s\S]{0,120}val bindHost: String = \"127\.0\.0\.1\"")
        self.assertNotRegex(source, r"data class NetworkAcquisitionProfile\([\s\S]{0,120}val bindHost: String = \"0\.0\.0\.0\"")

    def test_release_websocket_path_still_rejects_plain_ws(self):
        source = (ROOT / "AndroidApp" / "app" / "src" / "main" / "java" / "com" / "research" / "sensormax" / "WebSocketEndpointBuilder.kt").read_text(encoding="utf-8")
        self.assertIn('if (scheme == "ws" && !allowInsecure) return failure(Error.INSECURE_RELEASE)', source)


if __name__ == "__main__":
    unittest.main()
