"""Focused regression tests for SensorResearchSuite Sprint 32.

Run from the project root:
    python -m unittest LaptopSuite.test_sprint32_gateway_hardening -v
"""
from __future__ import annotations

import asyncio
import csv
import io
import unittest
from pathlib import Path

from LaptopSuite import web_server


class FakeWebSocket:
    remote_address = ("127.0.0.1", 50000)

    def __init__(self, origin=None):
        self.closed = False
        self.close_code = None
        self.close_reason = None
        self.request_headers = {} if origin is None else {"Origin": origin}

    async def close(self, code=None, reason=None):
        self.closed = True
        self.close_code = code
        self.close_reason = reason


class Sprint32GatewayHardeningTests(unittest.TestCase):
    def test_non_loopback_requires_explicit_insecure_opt_in(self):
        self.assertTrue(web_server.can_bind_host("127.0.0.1", False))
        self.assertTrue(web_server.can_bind_host("::1", False))
        self.assertTrue(web_server.can_bind_host("localhost", False))
        self.assertFalse(web_server.can_bind_host("0.0.0.0", False))
        self.assertFalse(web_server.can_bind_host("192.168.1.20", False))
        self.assertTrue(web_server.can_bind_host("192.168.1.20", True))
        self.assertFalse(web_server.can_bind_host("0.0.0.0", True))
        self.assertFalse(web_server.can_bind_host("::", True))
        self.assertFalse(web_server.can_bind_host("8.8.8.8", True))
        self.assertFalse(web_server.can_bind_host("sensor-laptop.local", True))
        self.assertTrue(web_server.can_bind_host("fd12:3456:789a::1", True))

    def test_origin_policy_allows_local_clients_and_blocks_remote_web_origins(self):
        self.assertTrue(web_server.is_allowed_origin(None))  # Native clients may omit Origin.
        self.assertTrue(web_server.is_allowed_origin("null"))  # file:// dashboard origin.
        self.assertTrue(web_server.is_allowed_origin("http://localhost:8080"))
        self.assertTrue(web_server.is_allowed_origin("http://127.0.0.1:8080"))
        self.assertTrue(web_server.is_allowed_origin("http://[::1]:8080"))
        self.assertFalse(web_server.is_allowed_origin("https://example.com"))
        self.assertFalse(web_server.is_allowed_origin("http://192.168.1.8:8080"))
        self.assertFalse(web_server.is_allowed_origin("not-an-origin"))

    def test_remote_origin_connection_is_closed_before_registration(self):
        async def exercise():
            previous = set(web_server.connected_clients)
            client = FakeWebSocket(origin="https://example.com")
            try:
                web_server.connected_clients.clear()
                await web_server.handle_sensor_stream(client)
                self.assertTrue(client.closed)
                self.assertEqual(client.close_code, 1008)
                self.assertNotIn(client, web_server.connected_clients)
            finally:
                web_server.connected_clients.clear()
                web_server.connected_clients.update(previous)

        asyncio.run(exercise())

    def test_numeric_csv_fields_reject_formula_and_nonfinite_values(self):
        self.assertEqual(web_server.numeric_csv_value("12.5"), "12.5")
        self.assertEqual(web_server.numeric_csv_value("=1+1"), "")
        self.assertEqual(web_server.numeric_csv_value("NaN"), "")
        self.assertEqual(web_server.numeric_csv_value("Infinity"), "")
        self.assertEqual(web_server.numeric_csv_value(None, ""), "")

    def test_csv_row_quotes_metadata_and_neutralizes_formula_prefix(self):
        row = web_server.csv_row(
            [1710000000, "asset, west", "=HYPERLINK(\"https://bad\")", 0.125],
            text_fields={1, 2},
        )
        cells = next(csv.reader(io.StringIO(row)))
        self.assertEqual(cells[0], "1710000000")
        self.assertEqual(cells[1], "asset, west")
        self.assertEqual(cells[2], "'=HYPERLINK(\"https://bad\")")
        self.assertEqual(cells[3], "0.125")

    def test_client_limit_rejects_extra_connection(self):
        async def exercise():
            previous = set(web_server.connected_clients)
            client = FakeWebSocket()
            try:
                web_server.connected_clients.clear()
                web_server.connected_clients.update(
                    object() for _ in range(web_server.MAX_CONNECTED_CLIENTS)
                )
                await web_server.handle_sensor_stream(client)
                self.assertTrue(client.closed)
                self.assertEqual(client.close_code, 1013)
                self.assertNotIn(client, web_server.connected_clients)
            finally:
                web_server.connected_clients.clear()
                web_server.connected_clients.update(previous)

        asyncio.run(exercise())

    def test_message_limit_and_health_cli_are_explicit(self):
        self.assertEqual(web_server.MAX_MESSAGE_BYTES, 1024 * 1024)
        self.assertEqual(web_server.MAX_PACKET_LOG_CHARS, 300)
        project_dir = Path(__file__).resolve().parents[1]
        verifier_source = (project_dir / "LaptopSuite" / "verify_suite_health.py").read_text(encoding="utf-8")
        self.assertIn('"--skip-integration"', verifier_source)
        self.assertIn("max_size=MAX_MESSAGE_BYTES", (project_dir / "LaptopSuite" / "web_server.py").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
