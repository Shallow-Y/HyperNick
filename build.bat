@echo off
chcp 65001 >nul
echo ========================================
echo   HyperNick Build Script
echo ========================================
echo.

call "%~dp0gradlew.bat" clean build --no-daemon

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [SUCCESS] Build completed!
    echo Output: build\libs\HyperNick-bukkit-1.2.3.jar
    echo.
) else (
    echo.
    echo [FAILED] Build failed with error code %ERRORLEVEL%
    echo.
)

pause
