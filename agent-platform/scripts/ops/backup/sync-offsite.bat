@echo off
setlocal EnableDelayedExpansion
REM ============================================================
REM  Agent Platform 备份异地副本同步（运维系统 OPS-FR-17）
REM  · 每周日 04:00 计划任务：BACKUP_ROOT 整体镜像 → OFFSITE_TARGET（防单机损毁）
REM  · OFFSITE_TARGET 须先在服务器手工跑通一次（凭据/挂载/防火墙），再注册计划任务
REM  · 失败写事件日志 + 调告警 webhook
REM  计划任务注册示例（管理员 cmd）：
REM    schtasks /create /tn "AgentPlatform-OffsiteSync" /tr "\"D:\path\sync-offsite.bat\"" /sc weekly /d SUN /st 04:00 /ru SYSTEM /f
REM ============================================================

if exist "%~dp0backup-env.bat" call "%~dp0backup-env.bat"

if not defined BACKUP_ROOT set "BACKUP_ROOT=D:\backup\agent-platform"
set "LOG_DIR=%BACKUP_ROOT%\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TS=%%i"

if not defined OFFSITE_TARGET (
    echo [%TS%] [ERROR] OFFSITE_TARGET 未配置（backup-env.bat）
    goto :fail
)

echo [%TS%] 异地同步 %BACKUP_ROOT% -^> %OFFSITE_TARGET%
robocopy "%BACKUP_ROOT%" "%OFFSITE_TARGET%" /MIR /R:3 /W:10 /NP /NDL /LOG+:"%LOG_DIR%\offsite-sync.log"
set "RC=%ERRORLEVEL%"

if %RC% LSS 8 (
    eventcreate /T INFORMATION /ID 120 /L APPLICATION /SO AgentPlatformBackup /D "Offsite sync OK rc=%RC% dst=%OFFSITE_TARGET%" > nul 2>&1
    echo %TS% OK rc=%RC%> "%BACKUP_ROOT%\last-offsite-sync-status.txt"
    echo [%TS%] 异地同步成功（rc=%RC%）
    exit /b 0
)

:fail
if not defined RC set "RC=-1"
echo [%TS%] [ERROR] 异地同步失败，rc=%RC%
eventcreate /T ERROR /ID 121 /L APPLICATION /SO AgentPlatformBackup /D "Offsite sync FAILED rc=%RC% dst=%OFFSITE_TARGET%" > nul 2>&1
echo %TS% FAIL rc=%RC%> "%BACKUP_ROOT%\last-offsite-sync-status.txt"
if defined OPS_ALERT_WEBHOOK_URL (
    curl -s -X POST "%OPS_ALERT_WEBHOOK_URL%" -H "Content-Type: application/json" -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[AgentPlatform] Offsite sync FAILED rc=%RC% time=%TS%\"}}" > nul 2>&1
)
exit /b 1
