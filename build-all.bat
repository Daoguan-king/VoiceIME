@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  VoiceIME - build all 4 ABIs and collect renamed APKs
REM
REM  Usage:  build-all.bat [debug|release]
REM          build-all.bat debug    build 4 ABIs as Debug (default, installable)
REM          build-all.bat release  build Release APKs (needs signing config)
REM
REM  Output: build-output\VoiceIME-v<version>-<abi>.apk
REM          abi = arm64-v8a | armeabi-v7a | x86_64 | universal
REM  (local release helper, not committed to main)
REM ============================================================

cd /d "%~dp0"

REM ---- 1. read version from app/build.gradle.kts ----
set "VER="
for /f "tokens=3" %%a in ('findstr "versionName" app\build.gradle.kts 2^>nul') do set "VER=%%a"
set "VER=%VER:"=%"
if "%VER%"=="" set "VER=unknown"

REM ---- 2. build mode ----
set "MODE=%~1"
if "%MODE%"=="" set "MODE=debug"
set "TASK=assemble%MODE%"
set "APKDIR=app\build\outputs\apk\%MODE%"

echo ============================================
echo   VoiceIME multi-ABI build   v%VER%  (%MODE%)
echo ============================================

set "OUT=%~dp0build-output"
if not exist "%OUT%" mkdir "%OUT%"

set /a COUNT=0
for %%A in (arm64-v8a armeabi-v7a x86_64 universal) do (
    set /a COUNT+=1
    echo.
    echo [!COUNT!/4] building %%A ...
    call gradlew.bat :app:%TASK% -Pabi=%%A --console=plain
    if errorlevel 1 (
        echo [ERROR] %%A build failed, abort.
        exit /b 1
    )

    REM locate the APK produced by this build (debug: app-debug.apk / release: app-release-unsigned.apk)
    set "SRC="
    for /f "delims=" %%f in ('dir /b /o-d "%APKDIR%\app-%MODE%*.apk" 2^>nul') do (
        if not defined SRC set "SRC=%APKDIR%\%%f"
    )
    if not defined SRC (
        echo [ERROR] APK not found for %%A in %APKDIR%
        exit /b 1
    )

    set "DST=%OUT%\VoiceIME-v%VER%-%%A.apk"
    copy /y "!SRC!" "!DST!" >nul
    echo [OK] %%A  -^>  !DST!
)

echo.
echo ============================================
echo   All done. Output: %OUT%
echo ============================================
dir /b "%OUT%"
endlocal
