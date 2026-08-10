@echo off
rem WMI 长驻启动器：由 Invoke-CimMethod Win32_Process.Create 调用（父进程=WmiPrvSE，脱离会话树防连坐杀）
cd /d d:\IT\AI-Projects\AI-Projects\agent-platform
powershell -NoProfile -ExecutionPolicy Bypass -File start-backend.ps1 > backend\target\backend-console.log 2>&1
