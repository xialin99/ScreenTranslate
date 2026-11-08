@echo off
rem ---------------------------------------------------------------
rem  Install the built APK to a USB-connected Android device.
rem  Output is also written to install-result.txt so the assistant
rem  can read the result directly (no manual copy/paste needed).
rem ---------------------------------------------------------------
setlocal
set "APK=%~dp0app\build\outputs\apk\debug\app-debug.apk"
set "RESULT=%~dp0install-result.txt"
set "ADB=adb"

where adb >nul 2>nul
if errorlevel 1 set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo === ScreenTranslate install %date% %time% === > "%RESULT%"
echo adb: %ADB% >> "%RESULT%"
echo apk: %APK% >> "%RESULT%"
echo. >> "%RESULT%"

"%ADB%" devices >> "%RESULT%" 2>&1
echo. >> "%RESULT%"

if not exist "%APK%" (
    echo [ERROR] APK not found: %APK% >> "%RESULT%"
    echo Run build-now.bat first. >> "%RESULT%"
    type "%RESULT%"
    pause
    exit /b 1
)

"%ADB%" install -r "%APK%" >> "%RESULT%" 2>&1
echo. >> "%RESULT%"

rem 顺便把悬浮窗 / 通知权限直接授了，省得手动点
"%ADB%" shell appops set com.screentranslate.app SYSTEM_ALERT_WINDOW allow >> "%RESULT%" 2>&1
"%ADB%" shell pm grant com.screentranslate.app android.permission.POST_NOTIFICATIONS >> "%RESULT%" 2>&1

type "%RESULT%"
echo.
echo Result also saved to: %RESULT%
pause
endlocal
