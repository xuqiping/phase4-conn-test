@echo off
chcp 65001 > nul
cd /d "%~dp0"
echo 正在启动 File Keeper HTTP 调试工具...
node server.js
pause
