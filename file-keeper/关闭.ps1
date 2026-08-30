[CmdletBinding()]
param(
    [switch]$SuppressAdminReminder
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = $PSScriptRoot
$StateFile = Join-Path $ProjectRoot '.file-keeper-dev-processes.json'
$scriptExitCode = 0

function Show-AdminReminder {
    if (-not $SuppressAdminReminder) {
        Write-Host ''
        Write-Host '提醒：管理后台暂未纳入本脚本，请按需手动处理。' -ForegroundColor Yellow
    }
}

function Test-PortListening {
    param([Parameter(Mandatory)][int]$Port)

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return $task.Wait(400) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-PortClosed {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$Name,
        [int]$TimeoutSeconds = 30
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Test-PortListening -Port $Port)) {
            Write-Host "[已关闭] $Name（端口 $Port）" -ForegroundColor Green
            return
        }
        Start-Sleep -Milliseconds 500
    }

    throw "$Name 在 $TimeoutSeconds 秒内未释放端口 $Port。"
}

function Stop-RecordedProcessTree {
    param(
        [AllowNull()][object]$ProcessId,
        [Parameter(Mandatory)][string]$Name
    )

    if (-not $ProcessId) {
        Write-Host "[跳过] 没有记录到 $Name 的启动 PID。" -ForegroundColor DarkGray
        return
    }

    $process = Get-Process -Id ([int]$ProcessId) -ErrorAction SilentlyContinue
    if (-not $process) {
        Write-Host "[跳过] $Name 进程已结束。" -ForegroundColor DarkGray
        return
    }

    Write-Host "[关闭] $Name（PID $ProcessId）"
    & "$env:SystemRoot\System32\taskkill.exe" /PID ([int]$ProcessId) /T /F | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$Name 进程树关闭失败。" }
}

function Get-PostgresService {
    return Get-Service -Name 'postgresql*' -ErrorAction SilentlyContinue | Select-Object -First 1
}

function Get-RedisService {
    foreach ($name in @('Memurai', 'Redis')) {
        $service = Get-Service -Name $name -ErrorAction SilentlyContinue
        if ($service) { return $service }
    }
    return $null
}

function Get-PostgresTools {
    $command = Get-Command pg_ctl.exe, pg_ctl -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    $pgCtl = if ($command) { $command.Source } else {
        Get-ChildItem 'C:\Program Files\PostgreSQL' -Filter pg_ctl.exe -File -Recurse -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $pgCtl) { return $null }

    $dataDirectory = Join-Path (Split-Path (Split-Path $pgCtl -Parent) -Parent) 'data'
    if (-not (Test-Path -LiteralPath $dataDirectory -PathType Container)) { return $null }
    return @{ PgCtl = $pgCtl; DataDirectory = $dataDirectory }
}

try {
    Write-Host '=== File Keeper 开发环境关闭 ===' -ForegroundColor Cyan

    $state = $null
    if (Test-Path -LiteralPath $StateFile -PathType Leaf) {
        try {
            $state = Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json
        } catch {
            Write-Warning '进程状态文件损坏，不会根据模糊进程名强制结束程序。'
        }
    }

    $tauriPid = if ($state -and ($state.PSObject.Properties.Name -contains 'TauriPid')) { $state.TauriPid } else { $null }
    $backendPid = if ($state -and ($state.PSObject.Properties.Name -contains 'BackendPid')) { $state.BackendPid } else { $null }
    $redisPid = if ($state -and ($state.PSObject.Properties.Name -contains 'RedisPid')) { $state.RedisPid } else { $null }

    Stop-RecordedProcessTree -ProcessId $tauriPid -Name 'Tauri 桌面端'
    Stop-RecordedProcessTree -ProcessId $backendPid -Name 'Java 后端'

    if ($tauriPid) { Wait-PortClosed -Port 1420 -Name 'Tauri/Vite' }
    if ($backendPid) { Wait-PortClosed -Port 8088 -Name 'Java 后端' }

    if ((Test-PortListening -Port 1420) -and -not $tauriPid) {
        Write-Warning '1420 端口仍在监听，但该进程不是由启动脚本记录的，已为避免误杀而保留。'
    }
    if ((Test-PortListening -Port 8088) -and -not $backendPid) {
        Write-Warning '8088 端口仍在监听，但该进程不是由启动脚本记录的，已为避免误杀而保留。'
    }

    if (Test-PortListening -Port 5432) {
        $postgresService = Get-PostgresService
        if ($postgresService) {
            Write-Host "[关闭] PostgreSQL 服务 $($postgresService.Name)"
            Stop-Service -Name $postgresService.Name
        } else {
            $postgresTools = Get-PostgresTools
            if (-not $postgresTools) { throw 'PostgreSQL 正在运行，但未找到可用的服务或 pg_ctl/data 目录。' }
            Write-Host '[关闭] PostgreSQL'
            & $postgresTools.PgCtl -D $postgresTools.DataDirectory -w stop
            if ($LASTEXITCODE -ne 0) { throw "pg_ctl 关闭失败，退出码 $LASTEXITCODE。" }
        }
        Wait-PortClosed -Port 5432 -Name 'PostgreSQL'
    } else {
        Write-Host '[跳过] PostgreSQL 未监听端口 5432。' -ForegroundColor DarkGray
    }

    if (Test-PortListening -Port 6379) {
        $redisService = Get-RedisService
        if ($redisService) {
            Write-Host "[关闭] Redis 服务 $($redisService.Name)"
            Stop-Service -Name $redisService.Name
        } elseif ($redisPid) {
            Stop-RecordedProcessTree -ProcessId $redisPid -Name 'Redis/Memurai'
        } else {
            Write-Warning '6379 端口仍在监听，但未找到可安全关闭的 Redis 服务或记录 PID。'
        }
        if ($redisService -or $redisPid) { Wait-PortClosed -Port 6379 -Name 'Redis/Memurai' }
    } else {
        Write-Host '[跳过] Redis/Memurai 未监听端口 6379。' -ForegroundColor DarkGray
    }

    if (Test-Path -LiteralPath $StateFile -PathType Leaf) {
        Remove-Item -LiteralPath $StateFile -Force
    }

    Write-Host ''
    Write-Host 'File Keeper 开发环境关闭操作已完成。' -ForegroundColor Green
} catch {
    $scriptExitCode = 1
    Write-Host ''
    Write-Error $_.Exception.Message
} finally {
    Show-AdminReminder
}

exit $scriptExitCode
