@echo off
rem AI Gateway PC 静默启动：无窗口 javaw + 托盘图标
cd /d "%~dp0"
start "" javaw -cp "lib\*" dev.aigw.cli.MainKt serve --port 8790
