@echo off
chcp 65001 >nul
cd /d "%~dp0"
set ELECTRON_ENABLE_LOGGING=1
set ELECTRON_RUN_AS_NODE=

echo ============================================
echo  GMCH IDE 诊断启动
echo ============================================
echo  启动日志: %APPDATA%\gmch-ide\startup.log
echo  Chromium 日志: %APPDATA%\gmch-ide\chromium.log
echo.
echo 正在启动...
"%~dp0GMCH IDE.exe" --no-sandbox --enable-logging --log-file="%APPDATA%\gmch-ide\chromium.log"

echo.
echo 进程已退出，退出码: %ERRORLEVEL%
echo 请把上面两个日志文件发给开发者。
pause
