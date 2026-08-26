# ============================================================
# 一键启动整个项目：PostgreSQL(服务) + Redis(服务) + sidecar(8090) + backend(8080) + frontend(5173)
# 用法：powershell -File start-all.ps1
#   已监听的组件自动跳过；backend/sidecar/frontend 以隐藏后台进程跑，日志在 logs\。
# ============================================================
$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $scriptDir)))   # agent-platform 根

function Test-Port([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}
function Wait-Port([int]$Port, [string]$Name, [int]$TimeoutSec = 120) {
    $t = 0
    while (-not (Test-Port $Port)) {
        Start-Sleep -Seconds 3; $t += 3
        if ($t -ge $TimeoutSec) { Write-Host "  [超时] $Name 端口 $Port ${TimeoutSec}s 未就绪，查 logs\" -ForegroundColor Red; return $false }
    }
    Write-Host "  [就绪] $Name 端口 $Port（${t}s）" -ForegroundColor Green
    return $true
}
function Start-Job-Process([string]$JobScript, [string]$Name) {
    $p = Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File', $JobScript `
        -WindowStyle Hidden -PassThru
    Write-Host "  [拉起] $Name（PID=$($p.Id)，隐藏窗口）"
}

Write-Host '== 1/5 PostgreSQL（5432，Windows 服务） ==' -ForegroundColor Cyan
if (Test-Port 5432) {
    Write-Host '  [跳过] 已在监听'
} else {
    Start-Service postgresql-x64-16
    [void](Wait-Port 5432 'PostgreSQL' 60)
}
if (-not (Test-Port 5432)) { Write-Host 'PostgreSQL 未就绪，中止（backend 依赖）' -ForegroundColor Red; exit 1 }

Write-Host '== 2/5 Redis（6379，Windows 服务） ==' -ForegroundColor Cyan
if (Test-Port 6379) {
    Write-Host '  [跳过] 已在监听'
} else {
    Start-Service Redis
    [void](Wait-Port 6379 'Redis' 30)
}
if (-not (Test-Port 6379)) { Write-Host 'Redis 未就绪，中止（JWT 黑名单依赖）' -ForegroundColor Red; exit 1 }

Write-Host '== 3/5 runtime-sidecar（8090） ==' -ForegroundColor Cyan
if (Test-Port 8090) {
    Write-Host '  [跳过] 已在监听'
} else {
    Start-Job-Process "$scriptDir\jobs\job-sidecar.ps1" 'sidecar'
    [void](Wait-Port 8090 'sidecar' 60)
}

Write-Host '== 4/5 backend（8080，mvn spring-boot:run 首启编译较慢） ==' -ForegroundColor Cyan
if (Test-Port 8080) {
    Write-Host '  [跳过] 已在监听'
} else {
    Start-Job-Process "$scriptDir\jobs\job-backend.ps1" 'backend'
    [void](Wait-Port 8080 'backend' 300)
}

Write-Host '== 5/5 frontend（5173，Vite dev） ==' -ForegroundColor Cyan
if (Test-Port 5173) {
    Write-Host '  [跳过] 已在监听'
} else {
    Start-Job-Process "$scriptDir\jobs\job-frontend.ps1" 'frontend'
    [void](Wait-Port 5173 'frontend' 90)
}

Write-Host ''
Write-Host '== 启动总览 ==' -ForegroundColor Cyan
'PostgreSQL 5432', 'Redis 6379', 'sidecar 8090', 'backend 8080', 'frontend 5173' | ForEach-Object {
    $port = [int]($_ -split ' ')[1]
    $ok = Test-Port $port
    "{0,-14} {1,-6} {2}" -f ($_ -split ' ')[0], $port, $(if ($ok) { 'UP' } else { 'DOWN' })
}
Write-Host '浏览器开 http://localhost:5173 ；日志：logs\backend.log / sidecar.log / frontend.log'
