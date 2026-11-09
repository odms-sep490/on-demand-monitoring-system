@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-ui-camera-stack.ps1" %*
endlocal
