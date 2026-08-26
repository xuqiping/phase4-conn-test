# frontend（5173，Vite dev）作业进程——由 start-all.ps1 拉起。
$ErrorActionPreference = 'Stop'  # 仅准备阶段；启动原生进程前切 Continue（PS5.1 把原生 stderr 包成 NativeCommandError，Stop 会秒杀作业）
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))))   # agent-platform 根
Set-Location "$root\frontend"
$ErrorActionPreference = 'Continue'
npm run dev *> "$root\logs\frontend.log"
