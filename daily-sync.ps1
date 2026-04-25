# Daily Git Sync Script for E:\workspace
# Pulls remote updates, commits any local changes, and pushes to GitHub.
# Designed to be invoked by Windows Task Scheduler.

$ErrorActionPreference = 'Continue'
$projectDir = 'E:\workspace'
$logFile = Join-Path $projectDir '.git-sync.log'
$maxLogBytes = 1MB

# Ensure UTF-8 output so Chinese paths render correctly in log
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Log {
    param([string]$Message)
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    "$stamp $Message" | Out-File -FilePath $logFile -Append -Encoding UTF8
}

function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)] [string[]]$GitArgs)
    $output = & git $GitArgs 2>&1
    $code = $LASTEXITCODE
    foreach ($line in $output) { Write-Log "  git $($GitArgs[0]): $line" }
    return $code
}

# Rotate log if it grew too large
if ((Test-Path $logFile) -and ((Get-Item $logFile).Length -gt $maxLogBytes)) {
    Move-Item -Path $logFile -Destination "$logFile.old" -Force
}

Set-Location -Path $projectDir
Write-Log '=== Daily sync started ==='

# 1) Pull (rebase keeps history linear; autostash protects uncommitted edits)
$pullCode = Invoke-Git pull --rebase --autostash
if ($pullCode -ne 0) {
    Write-Log "ERROR: git pull failed (exit $pullCode). Aborting any in-flight rebase."
    Invoke-Git rebase --abort | Out-Null
    Write-Log '=== Daily sync ended (with errors) ==='
    exit 1
}

# 2) Stage everything that respects .gitignore
Invoke-Git add -A | Out-Null

# 3) Commit only if something is staged
& git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
    $commitMsg = "Auto sync: $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
    $commitCode = Invoke-Git commit -m $commitMsg
    if ($commitCode -ne 0) {
        Write-Log "ERROR: git commit failed (exit $commitCode)."
        Write-Log '=== Daily sync ended (with errors) ==='
        exit 1
    }
} else {
    Write-Log 'No local changes to commit.'
}

# 4) Push (no-op if local is already in sync with remote)
$pushCode = Invoke-Git push
if ($pushCode -ne 0) {
    Write-Log "ERROR: git push failed (exit $pushCode). Check proxy/credentials."
    Write-Log '=== Daily sync ended (with errors) ==='
    exit 1
}

Write-Log '=== Daily sync ended (ok) ==='
exit 0
