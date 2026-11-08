@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-now.ps1"
endlocal
