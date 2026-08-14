@echo off
setlocal EnableDelayedExpansion
REM ============================================================
REM  Agent Platform offsite backup sync (OPS-FR-17 + security S5 M5)
REM  CHANGED (S5 M5, anti-ransomware): old /MIR mirror mode would
REM  overwrite good offsite copies when source got encrypted.
REM  New mode = point-in-time weekly snapshots:
REM    robocopy /E into %OFFSITE_TARGET%\wk_<timestamp> (add-only),
REM    keep newest KEEP_WEEKS(=8) snapshots, prune older automatically.
REM  Failure -> event log + dingtalk webhook alert.
REM  Scheduled task hint (admin cmd):
REM    schtasks /create /tn "AgentPlatform-OffsiteSync" /tr "\"D:\path\sync-offsite.bat\"" /sc weekly /d SUN /st 04:00 /ru SYSTEM /f
REM ============================================================

if exist "%~dp0backup-env.bat" call "%~dp0backup-env.bat"

if not defined BACKUP_ROOT set "BACKUP_ROOT=D:\backup\agent-platform"
set "LOG_DIR=%BACKUP_ROOT%\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TS=%%i"
REM snapshot dir prefix wk_ (retention relies on it - do not rename)
set "DEST=%OFFSITE_TARGET%\wk_%TS%"

if not defined OFFSITE_TARGET (
    echo [%TS%] [ERROR] OFFSITE_TARGET not configured ^(backup-env.bat^)
    goto :fail
)

echo [%TS%] point-in-time sync %BACKUP_ROOT% -^> %DEST%
robocopy "%BACKUP_ROOT%" "%DEST%" /E /R:3 /W:10 /NP /NDL /LOG+:"%LOG_DIR%\offsite-sync.log"
set "RC=%ERRORLEVEL%"

if %RC% LSS 8 (
    eventcreate /T INFORMATION /ID 120 /L APPLICATION /SO AgentPlatformBackup /D "Offsite sync OK rc=%RC% dst=%DEST%" > nul 2>&1
    echo %TS% OK rc=%RC%> "%BACKUP_ROOT%\last-offsite-sync-status.txt"
    echo [%TS%] offsite sync OK ^(rc=%RC%^)
    REM M5 retention: keep newest KEEP_WEEKS(=8) wk_* snapshots, prune older.
    REM prune failure is logged only - never marks the sync itself failed.
    powershell -NoProfile -Command "$keep=8; if($env:KEEP_WEEKS){$keep=[int]$env:KEEP_WEEKS}; Get-ChildItem -LiteralPath '%OFFSITE_TARGET%' -Directory -Filter 'wk_*' | Sort-Object Name -Descending | Select-Object -Skip $keep | ForEach-Object { Write-Host ('[RETIRE] '+$_.Name); Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }"
    exit /b 0
)

:fail
if not defined RC set "RC=-1"
echo [%TS%] [ERROR] offsite sync FAILED ^(rc=%RC%^)
eventcreate /T ERROR /ID 121 /L APPLICATION /SO AgentPlatformBackup /D "Offsite sync FAILED rc=%RC% dst=%OFFSITE_TARGET%" > nul 2>&1
echo %TS% FAIL rc=%RC%> "%BACKUP_ROOT%\last-offsite-sync-status.txt"
if defined OPS_ALERT_WEBHOOK_URL (
    curl -s -X POST "%OPS_ALERT_WEBHOOK_URL%" -H "Content-Type: application/json" -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[AgentPlatform] Offsite sync FAILED rc=%RC% time=%TS%\"}}" > nul 2>&1
)
exit /b 1
