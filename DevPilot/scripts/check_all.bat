@echo off
REM check_all.bat —— Windows cmd 入口，转发到 Git Bash 版（避免 cmd 中文乱码误判）
bash "%~dp0check_all.sh" %*
