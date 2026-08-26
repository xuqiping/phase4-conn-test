# ============================================================
# 一键关闭整个项目：frontend(5173) + backend(8080) + sidecar(8090) + Redis(服务)
# ⚠️ PostgreSQL 永不动（数据安全，常驻 Windows 服务）。
# 用法：powershell -File stop-all.ps1 [-KeepRedis]
#   -KeepRedis：Redis 也不关（默认连同 Redis 一起停，启动时再 Start-Service）
# ============================================================
param([switch]$KeepRedis)
$ErrorActionPreference = 'Continue'

function Stop-PortProcess([int]$Port, [string]$Name) {
    $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) { Write-Host "  [跳过] $Name 端口 $Port 无监听" ; return }
    $pids = $conns | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $pids) {
        $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($p) {
            Write-Host "  [停止] $Name 端口 $Port ← PID=$procId ($($p.ProcessName))，整棵树 taskkill"
            & taskkill /PID $procId /T /F | Out-Null
        }
    }
    $t = 0
    while (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        Start-Sleep -Seconds 1; $t += 1
        if ($t -ge 20) { Write-Host "  [警告] $Name 端口 $Port 仍在监听" -ForegroundColor Yellow; break }
    }
}

Write-Host '== 1/4 frontend（5173） ==' -ForegroundColor Cyan
Stop-PortProcess 5173 'frontend'
Write-Host '== 2/4 backend（8080） ==' -ForegroundColor Cyan
Stop-PortProcess 8080 'backend'
Write-Host '== 3/4 sidecar（8090） ==' -ForegroundColor Cyan
Stop-PortProcess 8090 'sidecar'
Write-Host '== 4/4 Redis（6379，Windows 服务） ==' -ForegroundColor Cyan
if ($KeepRedis) {
    Write-Host '  [跳过] -KeepRedis 指定保留'
} elseif (Get-NetTCPConnection -LocalPort 6379 -State Listen -ErrorAction SilentlyContinue) {
    Stop-Service Redis -Force -ErrorAction SilentlyContinue
    Write-Host '  [停止] Redis 服务已 Stop-Service（PostgreSQL 未动）'
} else {
    Write-Host '  [跳过] 未在监听'
}
Write-Host ''
Write-Host 'PostgreSQL（5432）按约定未动，持续运行中。' -ForegroundColor Green
