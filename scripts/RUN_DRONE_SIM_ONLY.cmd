@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "DRONE_LAUNCHER=%SCRIPT_DIR%start-drone-stack.ps1"

if not exist "%DRONE_LAUNCHER%" (
    echo [DRONE] Cannot find start-drone-stack.ps1 next to this launcher.
    exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%DRONE_LAUNCHER%" -SimWorld compact -NoTelemetry %*
endlocal
