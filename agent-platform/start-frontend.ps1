# 独立窗口启动 frontend（5173，Vite dev）。进程不随 Claude 会话退出。
# 用法：powershell -File start-frontend.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location "$root\frontend"
npm run dev
