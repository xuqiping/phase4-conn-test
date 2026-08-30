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
        Write-Host '提醒：管理后台暂未纳入本脚本，请按需手动启动。' -ForegroundColor Yellow
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

function Wait-PortListening {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$Name,
        [int]$TimeoutSeconds = 60
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-PortListening -Port $Port) {
            Write-Host "[已就绪] $Name（端口 $Port）" -ForegroundColor Green
            return
        }
        Start-Sleep -Milliseconds 500
    }

    throw "$Name 在 $TimeoutSeconds 秒内未监听端口 $Port，请查看对应日志窗口。"
}

function Resolve-Application {
    param([Parameter(Mandatory)][string[]]$Names)

    foreach ($name in $Names) {
        $command = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) {
            return $command.Source
        }
    }
    return $null
}

function ConvertTo-SingleQuotedLiteral {
    param([Parameter(Mandatory)][string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function Start-LogWindow {
    param(
        [Parameter(Mandatory)][string]$Title,
        [Parameter(Mandatory)][string]$ScriptText
    )

    $fullScript = "`$Host.UI.RawUI.WindowTitle = " + (ConvertTo-SingleQuotedLiteral $Title) + "; " + $ScriptText
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($fullScript))
    return Start-Process powershell.exe -ArgumentList '-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded -WindowStyle Normal -PassThru
}

function New-TemporaryJwtSecret {
    $bytes = New-Object byte[] 48
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes)
    } finally {
        $generator.Dispose()
    }
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
    $pgCtl = Resolve-Application -Names @('pg_ctl.exe', 'pg_ctl')
    if (-not $pgCtl) {
        $pgCtl = Get-ChildItem 'C:\Program Files\PostgreSQL' -Filter pg_ctl.exe -File -Recurse -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $pgCtl) { return $null }

    $dataDirectory = Join-Path (Split-Path (Split-Path $pgCtl -Parent) -Parent) 'data'
    if (-not (Test-Path -LiteralPath $dataDirectory -PathType Container)) { return $null }

    return @{ PgCtl = $pgCtl; DataDirectory = $dataDirectory }
}

function Read-State {
    $state = [ordered]@{ BackendPid = $null; TauriPid = $null; RedisPid = $null }
    if (-not (Test-Path -LiteralPath $StateFile -PathType Leaf)) { return $state }

    try {
        $saved = Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json
        foreach ($name in @('BackendPid', 'TauriPid', 'RedisPid')) {
            if ($saved.PSObject.Properties.Name -contains $name) { $state[$name] = $saved.$name }
        }
    } catch {
        Write-Warning '旧的进程状态文件无法读取，将重新生成。'
    }
    return $state
}

function Save-State {
    param([Parameter(Mandatory)]$State)
    $State | ConvertTo-Json | Set-Content -LiteralPath $StateFile -Encoding UTF8
}

try {
    Write-Host '=== File Keeper 开发环境启动 ===' -ForegroundColor Cyan
    $state = Read-State

    if (Test-PortListening -Port 6379) {
        Write-Host '[跳过] Redis/Memurai 已监听端口 6379。' -ForegroundColor DarkGray
    } else {
        $redisService = Get-RedisService
        if ($redisService) {
            Write-Host "[启动] Redis 服务 $($redisService.Name)"
            Start-Service -Name $redisService.Name
        } else {
            $redisExecutable = Resolve-Application -Names @('memurai.exe', 'redis-server.exe')
            if (-not $redisExecutable) {
                $redisExecutable = @(
                'C:\Program Files\Memurai\memurai.exe',
                'C:\Program Files\Redis\redis-server.exe'
                ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
            }

            if (-not $redisExecutable) {
                throw '未找到 Memurai/Redis Windows 服务或可执行文件。请先安装 Memurai，或将 Redis 加入 PATH。'
            }
            Write-Host "[启动] Redis 进程 $redisExecutable"
            $redisProcess = Start-Process -FilePath $redisExecutable -PassThru
            $state.RedisPid = $redisProcess.Id
            Save-State -State $state
        }
        Wait-PortListening -Port 6379 -Name 'Redis/Memurai' -TimeoutSeconds 30
    }

    if (Test-PortListening -Port 5432) {
        Write-Host '[跳过] PostgreSQL 已监听端口 5432。' -ForegroundColor DarkGray
    } else {
        $postgresService = Get-PostgresService
        if ($postgresService) {
            Write-Host "[启动] PostgreSQL 服务 $($postgresService.Name)"
            Start-Service -Name $postgresService.Name
        } else {
            $postgresTools = Get-PostgresTools
            if (-not $postgresTools) {
                throw '未找到 PostgreSQL Windows 服务或 pg_ctl/data 目录。'
            }
            Write-Host "[启动] PostgreSQL $($postgresTools.DataDirectory)"
            & $postgresTools.PgCtl -D $postgresTools.DataDirectory -w start
            if ($LASTEXITCODE -ne 0) { throw "pg_ctl 启动失败，退出码 $LASTEXITCODE。" }
        }
        Wait-PortListening -Port 5432 -Name 'PostgreSQL' -TimeoutSeconds 30
    }

    if (Test-PortListening -Port 8088) {
        Write-Host '[跳过] Java 后端已监听端口 8088。' -ForegroundColor DarkGray
    } else {
        $maven = Resolve-Application -Names @('mvn.cmd', 'mvn.exe', 'mvn')
        if (-not $maven) { throw '未找到 Maven（mvn），请先安装并加入 PATH。' }

        $jwtSecret = $env:FILE_KEEPER_JWT_SECRET
        if ([string]::IsNullOrWhiteSpace($jwtSecret)) {
            $jwtSecret = New-TemporaryJwtSecret
            Write-Host '[提示] 未设置 FILE_KEEPER_JWT_SECRET，本次后端使用临时内存密钥。' -ForegroundColor Yellow
        }

        $backendScript = @(
            "Set-Location -LiteralPath $(ConvertTo-SingleQuotedLiteral (Join-Path $ProjectRoot 'server'))",
            "& $(ConvertTo-SingleQuotedLiteral $maven) spring-boot:run"
        ) -join '; '

        Write-Host '[启动] Java 后端（新窗口）'
        $originalJwtSecret = $env:FILE_KEEPER_JWT_SECRET
        try {
            $env:FILE_KEEPER_JWT_SECRET = $jwtSecret
            $backendProcess = Start-LogWindow -Title 'File Keeper - Java Backend' -ScriptText $backendScript
        } finally {
            if ([string]::IsNullOrWhiteSpace($originalJwtSecret)) {
                Remove-Item Env:FILE_KEEPER_JWT_SECRET -ErrorAction SilentlyContinue
            } else {
                $env:FILE_KEEPER_JWT_SECRET = $originalJwtSecret
            }
        }
        $state.BackendPid = $backendProcess.Id
        Save-State -State $state
        Wait-PortListening -Port 8088 -Name 'Java 后端' -TimeoutSeconds 90
    }

    if ((Test-PortListening -Port 1420) -or (Get-Process -Name 'file-keeper' -ErrorAction SilentlyContinue)) {
        Write-Host '[跳过] Tauri 桌面端已运行。' -ForegroundColor DarkGray
    } else {
        $npm = Resolve-Application -Names @('npm.cmd', 'npm.exe')
        if (-not $npm) { throw '未找到 npm.cmd，请先安装 Node.js 并加入 PATH。' }

        $tauriScript = @(
            "Set-Location -LiteralPath $(ConvertTo-SingleQuotedLiteral $ProjectRoot)",
            "& $(ConvertTo-SingleQuotedLiteral $npm) run tauri:dev -- -- --bin file-keeper"
        ) -join '; '

        Write-Host '[启动] Tauri 桌面端（新窗口）'
        $tauriProcess = Start-LogWindow -Title 'File Keeper - Tauri Desktop' -ScriptText $tauriScript
        $state.TauriPid = $tauriProcess.Id
        Save-State -State $state
        Wait-PortListening -Port 1420 -Name 'Tauri/Vite' -TimeoutSeconds 90
    }

    Write-Host ''
    Write-Host 'File Keeper 核心开发环境已就绪。' -ForegroundColor Green
} catch {
    $scriptExitCode = 1
    Write-Host ''
    Write-Error $_.Exception.Message
} finally {
    Show-AdminReminder
}

exit $scriptExitCode
