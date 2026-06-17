@echo off
chcp 65001 >nul
echo ========================================
echo    ImageViewer - Debug APK 构建
echo ========================================
echo.

cd /d "%~dp0"

echo [1/2] 清理旧构建...
call .\gradlew.bat clean
if %errorlevel% neq 0 (
    echo.
    echo ❌ 清理失败！
    pause
    exit /b %errorlevel%
)

echo.
echo [2/2] 构建 Debug APK...
call .\gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo.
    echo ❌ Debug APK 构建失败！
    pause
    exit /b %errorlevel%
)

echo.
echo ========================================
echo    ✅ Debug APK 构建成功！
echo ========================================
echo.
echo    输出路径:
echo    app\build\outputs\apk\debug\app-debug.apk
echo.

REM 自动打开 APK 所在文件夹
start "" "%~dp0app\build\outputs\apk\debug"

pause
