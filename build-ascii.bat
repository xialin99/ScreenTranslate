@echo off
rem ---------------------------------------------------------------
rem  Fallback builder: copy the project to an ASCII-only path and
rem  build there. Use this if AGP still rejects the Chinese path.
rem ---------------------------------------------------------------
setlocal
set "SRC=%~dp0"
set "DST=D:\projects\screen-translate"

echo [1/3] copying project to %DST%
if exist "%DST%" rmdir /s /q "%DST%"
xcopy "%SRC%*" "%DST%\" /E /I /Y /Q >nul
if errorlevel 1 (
    echo copy failed
    pause
    exit /b 1
)

echo [2/3] building in %DST%
cd /d "%DST%"
call build-now.bat

echo [3/3] copying result APK back
if exist "%DST%\ScreenTranslate-debug.apk" (
    copy /Y "%DST%\ScreenTranslate-debug.apk" "%SRC%ScreenTranslate-debug.apk" >nul
    echo APK copied back to %SRC%ScreenTranslate-debug.apk
) else (
    echo APK was not produced, see the log above
)

endlocal
