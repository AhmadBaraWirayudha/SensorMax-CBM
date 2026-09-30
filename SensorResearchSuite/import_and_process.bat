@echo off
setlocal EnableExtensions EnableDelayedExpansion

TITLE SensorMax Offline Import and Processing Pipeline

echo ============================================================
echo   SENSORMAX OFFLINE IMPORT + PROCESSING PIPELINE
echo ============================================================
echo.
echo   Android -> ADB -> Imported_Records -> Analysis tools
echo.
echo ============================================================
echo.


REM ============================================================
REM 1. Resolve project paths
REM ============================================================

set "SUITE_DIR=%~dp0"
cd /d "%SUITE_DIR%"

set "VENV_DIR=%SUITE_DIR%sensormax_env"
set "LAPTOP_DIR=%SUITE_DIR%LaptopSuite"
set "LOCAL_DIR=%LAPTOP_DIR%\Imported_Records"


echo [*] Suite directory:
echo     %SUITE_DIR%
echo.

echo [*] Laptop processing directory:
echo     %LAPTOP_DIR%
echo.

echo [*] Local import directory:
echo     %LOCAL_DIR%
echo.


REM ============================================================
REM 2. Verify Python environment
REM ============================================================

if not exist "%VENV_DIR%\Scripts\python.exe" (
    echo [ERROR] Python virtual environment not found:
    echo         %VENV_DIR%
    echo.
    echo Run first:
    echo     first_initialize.bat
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


REM ============================================================
REM 3. Verify ADB
REM ============================================================

where adb >nul 2>&1

if errorlevel 1 (
    echo [ERROR] adb.exe was not found in PATH.
    echo.
    echo Android SDK Platform Tools must be installed and
    echo adb must be available from the command line.
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo [OK] ADB found.
echo.


REM ============================================================
REM 4. Start ADB
REM ============================================================

echo [*] Starting ADB server...

adb start-server

if errorlevel 1 (
    echo [ERROR] ADB server failed to start.
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo [OK] ADB server running.
echo.


REM ============================================================
REM 5. Show connected devices
REM ============================================================

echo ------------------------------------------------------------
echo [*] Connected Android devices
echo ------------------------------------------------------------

adb devices

echo ------------------------------------------------------------
echo.

echo [*] Checking for an authorized device...

set "DEVICE_COUNT=0"

for /f "skip=1 tokens=1,2" %%A in ('adb devices') do (
    if "%%B"=="device" (
        set /a DEVICE_COUNT+=1
    )
)

if "!DEVICE_COUNT!"=="0" (
    echo [ERROR] No authorized Android device detected.
    echo.
    echo Check:
    echo   1. USB cable
    echo   2. USB debugging
    echo   3. RSA authorization dialog on the phone
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo [OK] Authorized Android device detected.
echo.


REM ============================================================
REM 6. Create local import directory
REM ============================================================

if not exist "%LOCAL_DIR%" (
    echo [*] Creating local import directory...
    mkdir "%LOCAL_DIR%"
)

if not exist "%LOCAL_DIR%" (
    echo [ERROR] Could not create:
    echo         %LOCAL_DIR%
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo [OK] Import directory ready.
echo.


REM ============================================================
REM 7. Current Android storage locations
REM
REM Current Android project:
REM
REM   applicationId:
REM       com.research.sensormax
REM
REM   primary app-private external location:
REM       /sdcard/Android/data/com.research.sensormax/files/Documents/SensorMax_Master_Logs/
REM
REM
REM   published Downloads location:
REM       /sdcard/Download/SensorMax_Master_Logs/
REM
REM
REM The Downloads path is preferred because the Android engine
REM explicitly publishes the closed CSV files there.
REM ============================================================

set "DOWNLOAD_PATH=/sdcard/Download/SensorMax_Master_Logs/"
set "APP_PATH=/sdcard/Android/data/com.research.sensormax/files/Documents/SensorMax_Master_Logs/"

set "REMOTE_PATH="


REM ============================================================
REM 8. Prefer published Downloads folder
REM ============================================================

echo [*] Checking published Downloads storage...

adb shell "ls -d /sdcard/Download/SensorMax_Master_Logs 2>/dev/null"

if not errorlevel 1 (
    set "REMOTE_PATH=%DOWNLOAD_PATH%"
    echo [OK] Found:
    echo      %DOWNLOAD_PATH%
) else (
    echo [INFO] Published Downloads folder not found.
)

echo.


REM ============================================================
REM 9. Fallback to app-private external storage
REM ============================================================

if not defined REMOTE_PATH (

    echo [*] Checking Android app-private external storage...

    adb shell "ls -d /sdcard/Android/data/com.research.sensormax/files/Documents/SensorMax_Master_Logs 2>/dev/null"

    if not errorlevel 1 (
        set "REMOTE_PATH=%APP_PATH%"
        echo [OK] Found:
        echo      %APP_PATH%
    ) else (
        echo [ERROR] SensorMax log directory was not found on Android.
        echo.
        echo Expected one of:
        echo.
        echo   %DOWNLOAD_PATH%
        echo   %APP_PATH%
        echo.
        echo Run a SensorMax deployment and stop it once so the
        echo Android application creates and publishes its logs.
        echo.
        call deactivate >nul 2>&1
        pause
        exit /b 1
    )
)

echo.


REM ============================================================
REM 10. Create a unique local import subdirectory
REM ============================================================

for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do (
    set "IMPORT_STAMP=%%T"
)

set "SESSION_IMPORT_DIR=%LOCAL_DIR%\Import_!IMPORT_STAMP!"

mkdir "!SESSION_IMPORT_DIR!"

if not exist "!SESSION_IMPORT_DIR!" (
    echo [ERROR] Failed to create:
    echo         !SESSION_IMPORT_DIR!
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo [OK] Import session:
echo      !SESSION_IMPORT_DIR!
echo.


REM ============================================================
REM 11. Pull current SensorMax records
REM ============================================================

echo ------------------------------------------------------------
echo [*] Pulling SensorMax CSV records
echo ------------------------------------------------------------
echo.
echo     Remote:
echo       !REMOTE_PATH!
echo.
echo     Local:
echo       !SESSION_IMPORT_DIR!
echo.

adb pull "!REMOTE_PATH!." "!SESSION_IMPORT_DIR!"

if errorlevel 1 (
    echo.
    echo [ERROR] ADB pull failed.
    echo.
    echo The phone may have:
    echo   - disconnected
    echo   - denied access
    echo   - no published records
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 1
)

echo.
echo [OK] ADB import completed.
echo.


REM ============================================================
REM 12. Count imported CSV files
REM ============================================================

set "CSV_COUNT=0"

for %%F in ("!SESSION_IMPORT_DIR!\*.csv") do (
    set /a CSV_COUNT+=1
)

if "!CSV_COUNT!"=="0" (
    echo [WARN] No CSV files were imported.
    echo.
    echo Nothing will be processed.
    echo.
    call deactivate >nul 2>&1
    pause
    exit /b 0
)

echo [OK] Imported !CSV_COUNT! CSV file(s).
echo.


REM ============================================================
REM 13. Display imported files
REM ============================================================

echo ------------------------------------------------------------
echo [*] Imported files
echo ------------------------------------------------------------

for %%F in ("!SESSION_IMPORT_DIR!\*.csv") do (
    echo     %%~nxF
)

echo ------------------------------------------------------------
echo.


REM ============================================================
REM 14. Inspect CSV headers
REM
REM This step intentionally does not modify the files.
REM It helps distinguish:
REM
REM   *_raw.csv
REM   *_analysis.csv
REM
REM Current Android raw header:
REM   Timestamp_ms,Machine_ID,Point,Sensor_Type,Sensor_Name,
REM   Val_0,Val_1,...
REM
REM Current Android analysis header:
REM   Timestamp_ms,Machine_ID,Point,Sample_Rate_Hz,...
REM ============================================================

echo ------------------------------------------------------------
echo [*] Inspecting imported CSV structure
echo ------------------------------------------------------------
echo.

for %%F in ("!SESSION_IMPORT_DIR!\*.csv") do (

    echo [FILE] %%~nxF

    powershell -NoProfile -Command ^
        "$line = Get-Content -LiteralPath '%%~fF' -TotalCount 1; Write-Host ('  HEADER: ' + $line)"

    echo.
)

echo ------------------------------------------------------------
echo.


REM ============================================================
REM 15. Run current processing tools
REM
REM IMPORTANT:
REM
REM The Android CSV schema is the authoritative source.
REM Processing tools are therefore called only when the
REM corresponding script is present.
REM
REM export_converter.py
REM export_json.py
REM batch_dataset_processor.py
REM
REM are kept as separate tools because each produces a
REM different research artifact.
REM ============================================================

echo ============================================================
echo   PROCESSING IMPORTED DATA
echo ============================================================
echo.


REM ------------------------------------------------------------
REM 15A. Per-file Excel conversion
REM ------------------------------------------------------------

if exist "%LAPTOP_DIR%\export_converter.py" (

    echo --------------------------------------------------------
    echo [*] Excel conversion stage
    echo --------------------------------------------------------
    echo.

    for %%F in ("!SESSION_IMPORT_DIR!\*_raw.csv") do (

        echo [*] Processing raw dataset:
        echo     %%~nxF

        python "%LAPTOP_DIR%\export_converter.py" "%%~fF"

        if errorlevel 1 (
            echo [WARN] Excel conversion failed for:
            echo       %%~nxF
            echo.
        ) else (
            echo [OK] Excel conversion completed.
            echo.
        )
    )

) else (

    echo [WARN] export_converter.py not found.
    echo       Excel conversion skipped.
    echo.
)


REM ------------------------------------------------------------
REM 15B. Per-file JSON conversion
REM ------------------------------------------------------------

if exist "%LAPTOP_DIR%\export_json.py" (

    echo --------------------------------------------------------
    echo [*] JSON export stage
    echo --------------------------------------------------------
    echo.

    for %%F in ("!SESSION_IMPORT_DIR!\*_raw.csv") do (

        echo [*] Processing raw dataset:
        echo     %%~nxF

        python "%LAPTOP_DIR%\export_json.py" "%%~fF"

        if errorlevel 1 (
            echo [WARN] JSON export failed for:
            echo       %%~nxF
            echo.
        ) else (
            echo [OK] JSON export completed.
            echo.
        )
    )

) else (

    echo [WARN] export_json.py not found.
    echo       JSON conversion skipped.
    echo.
)


REM ------------------------------------------------------------
REM 15C. Batch comparison
REM ------------------------------------------------------------

if exist "%LAPTOP_DIR%\batch_dataset_processor.py" (

    echo --------------------------------------------------------
    echo [*] Batch comparison stage
    echo --------------------------------------------------------
    echo.

    python "%LAPTOP_DIR%\batch_dataset_processor.py" ^
        "!SESSION_IMPORT_DIR!" ^
        --output "!SESSION_IMPORT_DIR!\master_batch_comparison.xlsx"

    if errorlevel 1 (
        echo [WARN] Batch comparison returned an error.
        echo.
    ) else (
        echo [OK] Batch comparison completed.
        echo.
    )

) else (

    echo [WARN] batch_dataset_processor.py not found.
    echo       Batch comparison skipped.
    echo.
)


REM ============================================================
REM 16. Final file inventory
REM ============================================================

echo ============================================================
echo   IMPORT + PROCESSING COMPLETE
echo ============================================================
echo.

echo Local session directory:
echo   !SESSION_IMPORT_DIR!
echo.

echo Generated / imported artifacts:

for /r "!SESSION_IMPORT_DIR!" %%F in (*) do (
    echo   %%~nxF
)

echo.
echo ============================================================


REM ============================================================
REM 17. Cleanup
REM ============================================================

call deactivate >nul 2>&1

echo.
echo Python environment deactivated.
echo.

pause

endlocal
exit /b 0