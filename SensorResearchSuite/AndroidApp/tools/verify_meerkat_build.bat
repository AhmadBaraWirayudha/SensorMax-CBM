@echo off
setlocal
call gradlew.bat --stop
if errorlevel 1 exit /b 1
call gradlew.bat clean assembleDebug --refresh-dependencies --stacktrace
exit /b %errorlevel%
