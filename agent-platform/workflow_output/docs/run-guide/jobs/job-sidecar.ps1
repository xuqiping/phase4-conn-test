# runtime-sidecar（8090）作业进程——由 start-all.ps1 拉起。
$ErrorActionPreference = 'Stop'  # 仅准备阶段；启动原生进程前切 Continue（PS5.1 把原生 stderr 包成 NativeCommandError，Stop 会秒杀作业）
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))))   # agent-platform 根
. "$root\local-dev-env.ps1"
Set-Location "$root\runtime-sidecar"
$ErrorActionPreference = 'Continue'
python -m uvicorn app.main:app --host 0.0.0.0 --port 8090 *> "$root\logs\sidecar.log"
