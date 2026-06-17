@echo off
chcp 65001 >nul
title ImageViewer APK 构建工具
echo ========================================
echo    ImageViewer - APK 一键构建工具
echo ========================================
echo.
echo    1. 构建 Debug APK（可调试，未混淆）
echo    2. 构建 Release APK（优化，需签名）
echo    3. 同时构建 Debug 和 Release
echo    0. 退出
echo.
set /p choice="请输入选项 (0-3): "

if "%choice%"=="1" goto debug
if "%choice%"=="2" goto release
if "%choice%"=="3" goto all
if "%choice%"=="0" exit /b
echo 无效选项，请重新运行！
pause
exit /b

:debug
echo.
echo ▶ 开始构建 Debug APK...
call .\gradlew.bat assembleDebug
if %errorlevel% equ 0 (
    echo.
    echo ✅ Debug APK 成功: app\build\outputs\apk\debug\app-debug.apk
    start "" "%~dp0app\build\outputs\apk\debug"
) else (
    echo ❌ Debug APK 构建失败！
)
pause
exit /b

:release
echo.
echo ▶ 开始构建 Release APK...
call .\gradlew.bat assembleRelease
if %errorlevel% equ 0 (
    echo.
    echo ✅ Release APK 成功: app\build\outputs\apk\release\app-release-unsigned.apk
    start "" "%~dp0app\build\outputs\apk\release"
) else (
    echo ❌ Release APK 构建失败！
)
pause
exit /b

:all
echo.
echo ▶ 构建 Debug APK...
call .\gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo ❌ Debug APK 构建失败，跳过 Release...
    pause
    exit /b
)
echo.
echo ▶ 构建 Release APK...
call .\gradlew.bat assembleRelease
echo.
echo ✅ 全部构建完成！
echo    Debug:   app\build\outputs\apk\debug\app-debug.apk
echo    Release: app\build\outputs\apk\release\app-release-unsigned.apk
start "" "%~dp0app\build\outputs\apk"
pause
exit /b
