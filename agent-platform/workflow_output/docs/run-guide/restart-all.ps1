# ============================================================
# 一键重启整个项目（PostgreSQL 不动；Redis 随停随起）
# 用法：powershell -File restart-all.ps1
# ============================================================
$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
& "$scriptDir\stop-all.ps1"
Start-Sleep -Seconds 2
& "$scriptDir\start-all.ps1"
