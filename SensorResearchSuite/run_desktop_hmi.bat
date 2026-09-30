@echo off
setlocal EnableExtensions

TITLE SensorMax Desktop HMI

echo ============================================================
echo   SENSORMAX DESKTOP HMI
echo ============================================================
echo.
echo   Current architecture:
echo.
echo       Android SensorMax
echo              |
echo              | WebSocket :8765
echo              v
echo       SensorMax Gateway
echo              |
echo              v
echo       Browser Dashboard
echo.
echo   AndroidApp is NOT modified.
echo ============================================================
echo.


REM ============================================================
REM Resolve project directory
REM ============================================================

set "SUITE_DIR=%~dp0"

cd /d "%SUITE_DIR%"

echo [*] SensorMax suite:
echo     %SUITE_DIR%
echo.


REM ============================================================
REM Verify Web HMI launcher
REM ============================================================

if not exist "%SUITE_DIR%run_web_hmi.bat" (

    echo [ERROR] run_web_hmi.bat was not found.
    echo.
    echo Expected:
    echo     %SUITE_DIR%run_web_hmi.bat
    echo.
    pause
    exit /b 1
)


REM ============================================================
REM Launch the current Web HMI
REM ============================================================

echo [*] Launching current SensorMax Web HMI...
echo.

call "%SUITE_DIR%run_web_hmi.bat"

set "RESULT=%ERRORLEVEL%"


REM ============================================================
REM Exit status
REM ============================================================

echo.
echo ============================================================
echo   SENSORMAX DESKTOP HMI TERMINATED
echo ============================================================
echo.

if not "%RESULT%"=="0" (

    echo [ERROR] Web HMI exited with code %RESULT%.
    echo.

) else (

    echo [OK] Web HMI exited normally.
    echo.
)

endlocal
exit /b %RESULT%