@echo off
setlocal EnableExtensions

TITLE SensorMax Suite - First Initialization

echo ============================================================
echo   SENSORMAX SUITE: FIRST INITIALIZATION
echo ============================================================
echo.
echo   Desktop/Web environment only.
echo   AndroidApp is NOT modified by this script.
echo ============================================================
echo.


REM ============================================================
REM 1. Resolve workspace root
REM ============================================================

set "SUITE_DIR=%~dp0"

cd /d "%SUITE_DIR%"

echo [*] Workspace:
echo     %SUITE_DIR%
echo.


REM ============================================================
REM 2. Verify LaptopSuite
REM ============================================================

if not exist "%SUITE_DIR%LaptopSuite\" (
    echo [ERROR] LaptopSuite directory not found.
    echo.
    echo Expected:
    echo     %SUITE_DIR%LaptopSuite\
    echo.
    pause
    exit /b 1
)

echo [OK] LaptopSuite found.
echo.


REM ============================================================
REM 3. Verify Python
REM ============================================================

where python >nul 2>&1

if errorlevel 1 (
    echo [ERROR] Python was not found in PATH.
    echo.
    echo Install Python and enable "Add Python to PATH".
    echo.
    pause
    exit /b 1
)

echo [OK] Python found.
python --version
echo.


REM ============================================================
REM 4. Create project directories
REM ============================================================

echo [*] Creating SensorMax desktop directories...

if not exist "%SUITE_DIR%LaptopSuite\Imported_Records\" (
    mkdir "%SUITE_DIR%LaptopSuite\Imported_Records"
)

if not exist "%SUITE_DIR%LaptopSuite\Exported_Analysis\" (
    mkdir "%SUITE_DIR%LaptopSuite\Exported_Analysis"
)

if not exist "%SUITE_DIR%LaptopSuite\test_output\" (
    mkdir "%SUITE_DIR%LaptopSuite\test_output"
)

echo [OK] Desktop directories ready.
echo.


REM ============================================================
REM 5. Create isolated Python environment
REM ============================================================

if exist "%SUITE_DIR%sensormax_env\Scripts\python.exe" (

    echo [INFO] Existing sensormax_env detected.
    echo        Reusing existing virtual environment.
    echo.

) else (

    echo [*] Creating isolated Python virtual environment...

    python -m venv "%SUITE_DIR%sensormax_env"

    if errorlevel 1 (
        echo.
        echo [ERROR] Failed to create sensormax_env.
        echo.
        pause
        exit /b 1
    )

    echo [OK] sensormax_env created.
    echo.
)


REM ============================================================
REM 6. Activate environment
REM ============================================================

call "%SUITE_DIR%sensormax_env\Scripts\activate.bat"

if errorlevel 1 (
    echo [ERROR] Could not activate sensormax_env.
    echo.
    pause
    exit /b 1
)

echo [OK] sensormax_env activated.
echo.


REM ============================================================
REM 7. Upgrade pip
REM ============================================================

echo [*] Upgrading pip...

python -m pip install --upgrade pip

if errorlevel 1 (
    echo [WARN] pip upgrade failed.
    echo       Continuing with existing pip version.
    echo.
) else (
    echo [OK] pip upgraded.
    echo.
)


REM ============================================================
REM 8. Install LaptopSuite dependencies
REM ============================================================

if not exist "%SUITE_DIR%LaptopSuite\requirements.txt" (

    echo [ERROR] requirements.txt not found.
    echo.
    echo Expected:
    echo     %SUITE_DIR%LaptopSuite\requirements.txt
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo [*] Installing SensorMax LaptopSuite dependencies...
echo.

python -m pip install -r "%SUITE_DIR%LaptopSuite\requirements.txt"

if errorlevel 1 (
    echo.
    echo [ERROR] Dependency installation failed.
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo.
echo [OK] SensorMax Python dependencies installed.
echo.


REM ============================================================
REM 9. Verify critical Python imports
REM ============================================================

echo ------------------------------------------------------------
echo [*] Checking critical Python dependencies
echo ------------------------------------------------------------

python -c "import numpy; print('  numpy       : OK')"
if errorlevel 1 goto DEPENDENCY_FAIL

python -c "import pandas; print('  pandas      : OK')"
if errorlevel 1 goto DEPENDENCY_FAIL

python -c "import matplotlib; print('  matplotlib  : OK')"
if errorlevel 1 goto DEPENDENCY_FAIL

python -c "import plotly; print('  plotly      : OK')"
if errorlevel 1 goto DEPENDENCY_FAIL

python -c "import streamlit; print('  streamlit   : OK')"
if errorlevel 1 goto DEPENDENCY_FAIL

python -c "import openpyxl; print('  openpyxl    : OK')"
if errorlevel 1 goto DEPENDENCY_FAIL

python -c "import serial; print('  pyserial    : OK')"
if errorlevel 1 goto DEPENDENCY_FAIL

python -c "import websockets; print('  websockets   : OK')"
if errorlevel 1 goto DEPENDENCY_FAIL

python -c "import aiohttp; print('  aiohttp      : OK')"
if errorlevel 1 goto DEPENDENCY_FAIL

echo ------------------------------------------------------------
echo [OK] Critical dependencies verified.
echo.


REM ============================================================
REM 10. Compile-check current SensorMax Python components
REM ============================================================

echo ------------------------------------------------------------
echo [*] Running Python syntax checks
echo ------------------------------------------------------------

python -m py_compile ^
    "%SUITE_DIR%LaptopSuite\web_server.py" ^
    "%SUITE_DIR%LaptopSuite\export_converter.py" ^
    "%SUITE_DIR%LaptopSuite\export_json.py" ^
    "%SUITE_DIR%LaptopSuite\batch_dataset_processor.py" ^
    "%SUITE_DIR%LaptopSuite\test_all_pipelines.py"

if errorlevel 1 (
    echo.
    echo [ERROR] One or more SensorMax Python files contain
    echo         a syntax error.
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo [OK] SensorMax Python syntax verified.
echo.


REM ============================================================
REM 11. Run integration self-test when available
REM ============================================================

if exist "%SUITE_DIR%LaptopSuite\test_all_pipelines.py" (

    echo ------------------------------------------------------------
    echo [*] Running SensorMax desktop integration self-test
    echo ------------------------------------------------------------
    echo.

    python "%SUITE_DIR%LaptopSuite\test_all_pipelines.py"

    if errorlevel 1 (
        echo.
        echo [WARN] Integration self-test reported failures.
        echo       The Python environment itself is installed,
        echo       but the desktop pipeline still requires repair.
        echo.
    ) else (
        echo.
        echo [OK] Integration self-test passed.
        echo.
    )

) else (

    echo [WARN] test_all_pipelines.py not found.
    echo       Integration self-test skipped.
    echo.
)


REM ============================================================
REM 12. Final status
REM ============================================================

echo ============================================================
echo   INITIALIZATION COMPLETE
echo ============================================================
echo.
echo   Environment:
echo       %SUITE_DIR%sensormax_env\
echo.
echo   Desktop suite:
echo       %SUITE_DIR%LaptopSuite\
echo.
echo   Imported records:
echo       %SUITE_DIR%LaptopSuite\Imported_Records\
echo.
echo   Exported analysis:
echo       %SUITE_DIR%LaptopSuite\Exported_Analysis\
echo.
echo   Test output:
echo       %SUITE_DIR%LaptopSuite\test_output\
echo.
echo ============================================================
echo.
echo   AndroidApp was not modified.
echo.
echo ============================================================
echo.

call deactivate >nul 2>&1

pause

endlocal
exit /b 0


REM ============================================================
REM Dependency failure
REM ============================================================

:DEPENDENCY_FAIL

echo.
echo [ERROR] One or more critical Python dependencies could
echo         not be imported.
echo.
echo Re-run this script after checking:
echo     LaptopSuite\requirements.txt
echo.

call deactivate >nul 2>&1

pause

endlocal
exit /b 1