@echo off
chcp 65001 >nul
title 学伴 App · 构建并装进手机
echo ==============================================
echo   先用数据线连上手机，并在手机上打开
echo   「USB 调试」（设置 → 开发者选项里）
echo ==============================================
echo.

cd /d "%~dp0android"
call gradlew.bat installDebug --no-daemon
if errorlevel 1 (
    echo.
    echo [失败] 常见原因：手机没连上/没开 USB 调试/手机上弹的授权没点允许。
    echo 也可以改用「构建APK.bat」，把 APK 手动传到手机安装。
    pause
    exit /b 1
)

echo.
echo [成功] App 已装进手机，打开「学伴」即可。
pause
