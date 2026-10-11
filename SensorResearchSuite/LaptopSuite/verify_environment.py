#!/usr/bin/env python3
"""Validate the SensorMax LaptopSuite Python runtime without importing app modules."""
from __future__ import annotations

import argparse
import importlib.metadata
import importlib.util
import re
import sys
from pathlib import Path
from typing import Mapping, Sequence

SUITE_DIR = Path(__file__).resolve().parent
PIN_FILE = SUITE_DIR / "requirements-direct-pins-py313.txt"

# Distribution name -> import name.
REQUIRED_IMPORTS: Mapping[str, str] = {
    "numpy": "numpy",
    "pandas": "pandas",
    "matplotlib": "matplotlib",
    "plotly": "plotly",
    "pyserial": "serial",
    "websockets": "websockets",
    "aiohttp": "aiohttp",
    "openpyxl": "openpyxl",
    "streamlit": "streamlit",
}

SUPPORTED_PYTHON = ((3, 13), (3, 14))
PIN_PATTERN = re.compile(r"^([A-Za-z0-9_.-]+)==([A-Za-z0-9_.+-]+)$")


def python_supported(version: Sequence[int]) -> bool:
    """Return whether version is in [3.13, 3.14)."""
    major_minor = tuple(version[:2])
    return SUPPORTED_PYTHON[0] <= major_minor < SUPPORTED_PYTHON[1]


def parse_direct_pins(text: str) -> dict[str, str]:
    """Parse only exact package pins; ignore blank lines and comments."""
    pins: dict[str, str] = {}
    for line_number, raw in enumerate(text.splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        match = PIN_PATTERN.fullmatch(line)
        if not match:
            raise ValueError(f"Unsupported requirement at line {line_number}: {line}")
        name, version = match.groups()
        canonical_name = re.sub(r"[-_.]+", "-", name).lower()
        if canonical_name in pins:
            raise ValueError(f"Duplicate direct pin for {canonical_name}")
        pins[canonical_name] = version
    return pins


def check_imports(find_spec=importlib.util.find_spec) -> list[str]:
    """Return missing import names (dependency injection keeps this testable)."""
    missing: list[str] = []
    for import_name in REQUIRED_IMPORTS.values():
        try:
            found = find_spec(import_name)
        except (ImportError, ModuleNotFoundError, ValueError):
            found = None
        if found is None:
            missing.append(import_name)
    return missing


def check_pins(
    pins: Mapping[str, str],
    version_lookup=importlib.metadata.version,
) -> list[str]:
    """Return human-readable missing/mismatched direct distribution pins."""
    failures: list[str] = []
    for package, expected in pins.items():
        try:
            actual = version_lookup(package)
        except importlib.metadata.PackageNotFoundError:
            failures.append(f"{package}: not installed (expected {expected})")
            continue
        except Exception as exc:  # defensive: preserve environment diagnosis
            failures.append(f"{package}: version lookup failed: {type(exc).__name__}")
            continue
        if actual != expected:
            failures.append(f"{package}: found {actual}, expected {expected}")
    return failures


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--imports-only",
        action="store_true",
        help="Check Python baseline and runtime imports, but do not compare exact direct pins.",
    )
    parser.add_argument(
        "--check-pins",
        action="store_true",
        help="Also require exact versions in requirements-direct-pins-py313.txt.",
    )
    args = parser.parse_args(argv)

    failed = False
    version = sys.version_info
    if python_supported(version):
        print(f"[PASS] Python baseline: {version.major}.{version.minor}.{version.micro}")
    else:
        print(
            "[FAIL] Unsupported Python version: "
            f"{version.major}.{version.minor}.{version.micro}; supported baseline is CPython 3.13.x"
        )
        failed = True

    missing = check_imports()
    if missing:
        print("[FAIL] Missing runtime imports: " + ", ".join(missing))
        failed = True
    else:
        print(f"[PASS] Required runtime imports: {len(REQUIRED_IMPORTS)} present")

    if args.check_pins or not args.imports_only:
        try:
            pins = parse_direct_pins(PIN_FILE.read_text(encoding="utf-8"))
            pin_failures = check_pins(pins)
        except (OSError, ValueError) as exc:
            print(f"[FAIL] Could not validate direct pins: {exc}")
            failed = True
        else:
            if pin_failures:
                print("[FAIL] Direct dependency pin mismatch:")
                for item in pin_failures:
                    print(f"  - {item}")
                failed = True
            else:
                print(f"[PASS] Exact direct dependency pins: {len(pins)} matched")

    if failed:
        print("Environment status: FAIL")
        return 1
    print("Environment status: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
