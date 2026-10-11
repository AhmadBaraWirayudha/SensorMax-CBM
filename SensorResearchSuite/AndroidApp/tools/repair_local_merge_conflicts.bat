@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0repair_local_merge_conflicts.ps1"
if errorlevel 1 (
  echo.
  echo SensorMax merge repair failed. Review the diagnostics above.
  exit /b 1
)
echo.
echo Run:
echo   gradlew.bat --stop
echo   gradlew.bat clean assembleDebug --stacktrace
endlocal
