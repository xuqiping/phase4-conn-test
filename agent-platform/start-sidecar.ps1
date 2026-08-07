# 独立窗口启动 runtime-sidecar（8090，FastAPI）。进程不随 Claude 会话退出。
# 用法：powershell -File start-sidecar.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
. "$root\local-dev-env.ps1"
Set-Location "$root\runtime-sidecar"
python -m uvicorn app.main:app --host 0.0.0.0 --port 8090
