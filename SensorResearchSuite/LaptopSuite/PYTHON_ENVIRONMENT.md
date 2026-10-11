# SensorMax LaptopSuite — Python environment

## Current baseline

- Runtime baseline: **CPython 3.13.x**. The code was syntax-checked in CPython 3.13.5 in the review environment; other patch/minor versions have not been independently tested here.
- Runtime dependencies are described in `requirements.txt` with compatibility ranges.
- `requirements-direct-pins-py313.txt` pins the nine direct runtime dependencies for a repeatable direct-dependency baseline.
- The direct-pin file is **not a complete dependency lock**: transitive packages are currently resolved by pip. Do not describe the environment as fully reproducible until a transitive lock with hashes is generated and tested from a network-enabled clean environment.

## Windows setup

Run `first_initialize.bat` from the project root. The initializer creates `sensormax_env`, installs the direct-pinned Python 3.13 profile, checks imports and runs syntax/regression checks. Keep Android Gradle setup separate.

Manual setup from a project root:

```powershell
py -3.13 -m venv sensormax_env
.\sensormax_env\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r LaptopSuite\requirements-direct-pins-py313.txt
python -m pip check
python LaptopSuite\verify_environment.py --check-pins
```

Linux/macOS:

```bash
python3.13 -m venv sensormax_env
. sensormax_env/bin/activate
python -m pip install --upgrade pip
python -m pip install -r LaptopSuite/requirements-direct-pins-py313.txt
python -m pip check
python LaptopSuite/verify_environment.py --check-pins
```

## Test commands

Run focused regression tests from the project root:

```bash
python -m unittest \
  LaptopSuite.test_sprint3_measurement_contract \
  LaptopSuite.test_sprint31_regressions \
  LaptopSuite.test_sprint32_gateway_hardening \
  LaptopSuite.test_sprint35_transport_policy \
  LaptopSuite.test_python_environment -v
```

Run syntax and bounded desktop health checks:

```bash
python -m compileall -q LaptopSuite
python LaptopSuite/verify_suite_health.py --skip-integration
```

The health check deliberately skips `test_all_pipelines.py` when `--skip-integration` is used. A pass on that command is not an integration-test pass. Run the full pipeline separately when its time and environment prerequisites are available.

## Full lock generation still required

From a clean environment with access to the approved Python package index, install pip-tools and resolve the exact transitive graph for the target platform/Python version. For example:

```bash
python -m pip install pip-tools
pip-compile --resolver=backtracking --generate-hashes \
  --output-file LaptopSuite/requirements-py313.lock.txt \
  LaptopSuite/requirements-direct-pins-py313.txt
```

Review the generated diff, install from `requirements-py313.lock.txt` into a fresh venv, run `pip check`, focused tests, the health verifier and integration pipeline, then commit the resulting lock. Do not hand-create or guess transitive versions. If Windows and Linux need different transitive wheels, produce and validate platform-specific lock files or document the supported marker strategy.

## Important caveat

The authoring environment used in this sprint could not resolve the Python package index (`Temporary failure in name resolution`). The lock generator therefore could not resolve the transitive graph, and `streamlit`/`pyserial` could not be installed here. This is a real outstanding acceptance item, not a green result.
