@echo off
REM ===============================================================================
REM LaunDRoid Android Deployment Script
REM For Windows - Deploys APK to connected Android device via ADB
REM ===============================================================================

setlocal enabledelayedexpansion

title LaunDRoid Deployment Tool

echo.
echo ╔═══════════════════════════════════════════════════════════════╗
echo ║              LaunDRoid Android Deployment Tool                ║
echo ║                    v1.7.0 - Crusty Agitate Goblin             ║
echo ╚═══════════════════════════════════════════════════════════════╝
echo.

set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."
set "APK_PATH=%PROJECT_DIR%\releases\android\LaunDRoid-v1.7.0.apk"

REM -------------------------------------------------------------------------------
REM Check for ADB
REM -------------------------------------------------------------------------------
echo [1/5] Checking for ADB...

where adb >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    for /f "tokens=*" %%i in ('adb version 2^>^&1 ^| findstr /r "version"') do set "ADB_VER=%%i"
    echo   √ ADB found: !ADB_VER!
    goto :check_apk
)

echo   X ADB not found!
echo.
echo   Checking common installation paths...

REM Check common ADB locations
set "ADB_PATHS=%LOCALAPPDATA%\Android\Sdk\platform-tools;%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools;C:\Android\platform-tools;C:\Program Files\Android\platform-tools"

for %%p in (%ADB_PATHS%) do (
    if exist "%%p\adb.exe" (
        echo   Found ADB at: %%p
        set "PATH=%%p;%PATH%"
        goto :check_apk
    )
)

echo.
echo ╔═══════════════════════════════════════════════════════════════╗
echo ║                      ADB NOT FOUND                            ║
echo ╠═══════════════════════════════════════════════════════════════╣
echo ║  Please install Android Platform Tools:                       ║
echo ║                                                                ║
echo ║  1. Download from:                                             ║
echo ║     https://developer.android.com/studio/releases/platform-tools ║
echo ║                                                                ║
echo ║  2. Extract to C:\Android\platform-tools                      ║
echo ║                                                                ║
echo ║  3. Add to PATH:                                               ║
echo ║     - Open System Properties ^> Advanced ^> Environment Variables ║
echo ║     - Add C:\Android\platform-tools to Path                    ║
echo ║                                                                ║
echo ║  4. Run this script again                                      ║
echo ╚═══════════════════════════════════════════════════════════════╝
echo.
pause
exit /b 1

:check_apk
REM -------------------------------------------------------------------------------
REM Check APK exists
REM -------------------------------------------------------------------------------
echo [2/5] Checking for APK...

if exist "%APK_PATH%" (
    echo   √ APK found: %APK_PATH%
    goto :check_device
)

echo   X APK not found at: %APK_PATH%
echo   Looking for APK in releases folder...

for /r "%PROJECT_DIR%\releases" %%f in (*.apk) do (
    set "APK_PATH=%%f"
    echo   √ Found: %%f
    goto :check_device
)

echo   X No APK found in releases folder
echo   Please build the Android app first or download the release.
pause
exit /b 1

:check_device
REM -------------------------------------------------------------------------------
REM Check for connected devices
REM -------------------------------------------------------------------------------
echo [3/5] Checking for connected device...

adb start-server >nul 2>&1

adb devices | findstr /r "device$" >nul
if %ERRORLEVEL% NEQ 0 (
    echo   X No device connected!
    echo.
    echo ╔═══════════════════════════════════════════════════════════════╗
    echo ║               Enable USB Debugging on Phone                   ║
    echo ╠═══════════════════════════════════════════════════════════════╣
    echo ║  1. Open Settings → About Phone                               ║
    echo ║  2. Tap 'Build Number' 7 times ^(enables Developer Options^)    ║
    echo ║  3. Go back to Settings → Developer Options                   ║
    echo ║  4. Enable 'USB Debugging'                                    ║
    echo ║  5. Connect phone via USB cable                               ║
    echo ║  6. When prompted, tap 'Allow USB debugging'                  ║
    echo ╚═══════════════════════════════════════════════════════════════╝
    echo.
    echo Waiting for device... ^(Press Ctrl+C to cancel^)
    adb wait-for-device
)

for /f "tokens=1" %%d in ('adb devices ^| findstr /r "device$"') do set "DEVICE_SERIAL=%%d"

for /f "tokens=*" %%m in ('adb -s %DEVICE_SERIAL% shell getprop ro.product.model 2^>nul') do set "DEVICE_MODEL=%%m"
for /f "tokens=*" %%v in ('adb -s %DEVICE_SERIAL% shell getprop ro.build.version.release 2^>nul') do set "ANDROID_VER=%%v"

echo   √ Device: !DEVICE_MODEL! ^(Android !ANDROID_VER!^)
echo   √ Serial: %DEVICE_SERIAL%

REM -------------------------------------------------------------------------------
REM Install APK
REM -------------------------------------------------------------------------------
echo [4/5] Installing LaunDRoid...

adb -s %DEVICE_SERIAL% shell pm list packages 2>nul | findstr "com.laundr.droid" >nul
if %ERRORLEVEL% EQU 0 (
    echo   App already installed - replacing...
    set "INSTALL_FLAGS=-r"
) else (
    echo   Fresh install...
    set "INSTALL_FLAGS="
)

echo   Installing APK ^(this may take a moment^)...

adb -s %DEVICE_SERIAL% install %INSTALL_FLAGS% "%APK_PATH%" 2>&1 | findstr /i "success" >nul
if %ERRORLEVEL% EQU 0 (
    echo   √ Installation successful!
) else (
    echo   X Installation failed!
    echo.
    echo   Troubleshooting:
    echo     - Ensure USB debugging is enabled
    echo     - Accept any prompts on your phone
    echo     - Try: adb uninstall com.laundr.droid
    pause
    exit /b 1
)

REM -------------------------------------------------------------------------------
REM Launch app
REM -------------------------------------------------------------------------------
echo [5/5] Launching LaunDRoid...

adb -s %DEVICE_SERIAL% shell am start -n com.laundr.droid/.MainActivity >nul 2>&1

echo   √ App launched!

echo.
echo ╔═══════════════════════════════════════════════════════════════╗
echo ║              LaunDRoid installed successfully!                ║
echo ╚═══════════════════════════════════════════════════════════════╝
echo.
echo   For authorized security research only.
echo   CVE-2025-46018 ^| CVE-2025-46019
echo.

pause
exit /b 0
