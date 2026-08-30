[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptExitCode = 0

try {
    & (Join-Path $PSScriptRoot '关闭.ps1') -SuppressAdminReminder
    if ($LASTEXITCODE -ne 0) {
        throw '关闭阶段失败，已取消重新启动。'
    }

    Start-Sleep -Seconds 2

    & (Join-Path $PSScriptRoot '启动.ps1') -SuppressAdminReminder
    if ($LASTEXITCODE -ne 0) {
        throw '重新启动失败，请查看上方错误信息和日志窗口。'
    }
} catch {
    $scriptExitCode = 1
    Write-Error $_.Exception.Message
} finally {
    Write-Host ''
    Write-Host '提醒：管理后台暂未纳入本脚本，请按需手动启动。' -ForegroundColor Yellow
}

exit $scriptExitCode
