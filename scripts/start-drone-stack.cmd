@echo off
set FOREST3D_WEB_ONLY=1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-drone-stack.ps1" -SimWorld compact -ShowGazeboGui -SkipBootstrap -NoTelemetry %*
