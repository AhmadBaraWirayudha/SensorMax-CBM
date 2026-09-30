@echo off
setlocal EnableExtensions

TITLE SensorMax Web HMI

echo ============================================================
echo   SENSORMAX WEB HMI
echo   Android -> WebSocket Gateway -> Browser Dashboard
echo ============================================================
echo.

REM ------------------------------------------------------------
REM Resolve the SensorResearchSuite directory from this script.
REM This prevents the script from depending on the current
REM Windows working directory.
REM ------------------------------------------------------------

set "SUITE_DIR=%~dp0"

cd /d "%SUITE_DIR%"

echo [*] Suite directory:
echo     %SUITE_DIR%
echo.


REM ------------------------------------------------------------
REM Python virtual environment
REM ------------------------------------------------------------

set "VENV_DIR=%SUITE_DIR%sensormax_env"

if not exist "%VENV_DIR%\Scripts\python.exe" (
    echo [ERROR] Python virtual environment not found:
    echo         %VENV_DIR%
    echo.
    echo Create/install the environment before running this HMI.
    echo.
    pause
    exit /b 1
)

call "%VENV_DIR%\Scripts\activate.bat"

if errorlevel 1 (
    echo [ERROR] Failed to activate sensormax_env.
    echo.
    pause
    exit /b 1
)

echo [OK] Python environment activated.
echo.


REM ------------------------------------------------------------
REM Verify Python
REM ------------------------------------------------------------

python --version

if errorlevel 1 (
    echo [ERROR] Python could not be executed.
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo.


REM ------------------------------------------------------------
REM Verify ADB
REM ------------------------------------------------------------

where adb >nul 2>&1

if errorlevel 1 (
    echo [ERROR] ADB was not found in PATH.
    echo.
    echo Install Android SDK Platform Tools or add adb.exe to PATH.
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo [OK] ADB found.
echo.


REM ------------------------------------------------------------
REM Start ADB
REM ------------------------------------------------------------

echo [*] Starting ADB server...

adb start-server

if errorlevel 1 (
    echo [ERROR] ADB server could not start.
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo.


REM ------------------------------------------------------------
REM USB WebSocket tunnel
REM
REM Android is the WebSocket client.
REM Laptop is the WebSocket server on port 8765.
REM
REM Therefore USB debugging uses:
REM
REM     adb reverse tcp:8765 tcp:8765
REM
REM Android:
REM     ws://127.0.0.1:8765
REM
REM Laptop:
REM     0.0.0.0:8765
REM ------------------------------------------------------------

echo [*] Checking Android device connection...

adb devices

echo.

adb reverse tcp:8765 tcp:8765

if errorlevel 1 (
    echo [WARN] ADB reverse could not be established.
    echo.
    echo USB streaming will not work until an authorized Android
    echo device is connected with USB debugging enabled.
    echo.
) else (
    echo [OK] USB WebSocket reverse tunnel established:
    echo      Android 127.0.0.1:8765
    echo              ->
    echo      Laptop  127.0.0.1:8765
    echo.
)


REM ------------------------------------------------------------
REM Clean up stale process using port 8765 if possible.
REM
REM We deliberately do NOT kill every python.exe process here.
REM The old script did that and could terminate unrelated Python
REM applications.
REM ------------------------------------------------------------

echo [*] Checking port 8765...

for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":8765" ^| findstr "LISTENING"') do (
    echo [WARN] Port 8765 is already in use by PID %%P.
    echo        The gateway may fail to start.
)

echo.


REM ------------------------------------------------------------
REM Start WebSocket gateway
REM ------------------------------------------------------------

echo [*] Launching SensorMax WebSocket gateway...

start "SensorMax Gateway" /MIN cmd /k ^
    ""%VENV_DIR%\Scripts\python.exe" "%SUITE_DIR%LaptopSuite\web_server.py""

if errorlevel 1 (
    echo [ERROR] Failed to launch the WebSocket gateway.
    echo.
    adb reverse --remove tcp:8765 >nul 2>&1
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo [OK] Gateway process launched.
echo.


REM ------------------------------------------------------------
REM Wait for server startup
REM ------------------------------------------------------------

echo [*] Waiting for gateway startup...

timeout /t 2 /nobreak >nul

echo.


REM ------------------------------------------------------------
REM Launch browser dashboard
REM ------------------------------------------------------------

if not exist "%SUITE_DIR%LaptopSuite\web_dashboard.html" (
    echo [ERROR] web_dashboard.html not found.
    echo:
    echo         %SUITE_DIR%LaptopSuite\web_dashboard.html
    echo.
    adb reverse --remove tcp:8765 >nul 2>&1
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo [*] Launching SensorMax browser dashboard...

start "" "%SUITE_DIR%LaptopSuite\web_dashboard.html"

echo.


REM ------------------------------------------------------------
REM Runtime information
REM ------------------------------------------------------------

echo ============================================================
echo   SENSORMAX WEB HMI IS RUNNING
echo ============================================================
echo.
echo   Gateway:
echo       ws://127.0.0.1:8765
echo.
echo   Browser:
echo       %SUITE_DIR%LaptopSuite\web_dashboard.html
echo.
echo   USB Android bridge:
echo       adb reverse tcp:8765 tcp:8765
echo.
echo   Android WebSocket endpoint:
echo       ws://127.0.0.1:8765
echo.
echo   Data logs:
echo       %SUITE_DIR%LaptopSuite\Imported_Records
echo.
echo ============================================================
echo.
echo   IMPORTANT:
echo   The Android SensorMax app must be configured to use:
echo.
echo       ws://127.0.0.1:8765
echo.
echo   for USB-reverse mode.
echo.
echo ============================================================
echo.
echo Press any key to stop the Web HMI.
pause >nul


REM ============================================================
REM CLEANUP
REM ============================================================

echo.
echo [*] Stopping SensorMax Web HMI...

REM Remove only the ADB reverse tunnel created by this script.
adb reverse --remove tcp:8765 >nul 2>&1

echo [OK] ADB reverse tunnel removed.

REM Ask the dedicated gateway window to terminate.
taskkill /FI "WINDOWTITLE eq SensorMax Gateway*" /T /F >nul 2>&1

echo [OK] SensorMax gateway stopped.

call deactivate >nul 2>&1

echo [OK] Python environment deactivated.
echo.
echo SensorMax Web HMI terminated.

endlocal
exit /b 0