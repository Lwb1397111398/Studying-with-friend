@echo off
chcp 65001 >nul
title 学伴 App · 构建 APK
echo ==============================================
echo   学伴 App 调试版构建（装手机用这个）
echo   首次或隔了几天构建约 5~10 分钟，请耐心等
echo ==============================================
echo.

where java >nul 2>nul
if errorlevel 1 (
    echo [失败] 电脑上没找到 Java，先告诉我一声。
    pause
    exit /b 1
)

cd /d "%~dp0android"
call gradlew.bat assembleDebug --no-daemon
if errorlevel 1 (
    echo.
    echo [失败] 构建出错，把上面窗口里的报错截图发给老板……发给 AI 看。
    pause
    exit /b 1
)

echo.
echo [成功] APK 已生成，正在帮你打开所在文件夹：
echo   android\app\build\outputs\apk\debug\app-debug.apk
start "" explorer "%~dp0android\app\build\outputs\apk\debug"
echo.
echo 把 app-debug.apk 传到手机上点开安装即可。
pause
