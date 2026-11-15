@echo off
rem AI Gateway PC 静默启动：无窗口 javaw + 系统托盘图标（由 launcher.vbs / 自启动 VBS 调用）
cd /d "%~dp0"
set "LOGDIR=%USERPROFILE%\.aigw"
set "LOG=%LOGDIR%\gateway.log"
if not exist "%LOGDIR%" mkdir "%LOGDIR%" >nul 2>&1
where javaw >nul 2>&1
if errorlevel 1 (
  echo %DATE% %TIME% ERROR: javaw.exe not found in PATH >> "%LOG%"
  exit /b 1
)
start "" javaw -cp "lib\*" dev.aigw.cli.MainKt serve --port 8790
echo %DATE% %TIME% javaw launched from %CD% >> "%LOG%"
