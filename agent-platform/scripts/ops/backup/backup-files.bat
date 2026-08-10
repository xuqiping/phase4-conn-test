@echo off
setlocal EnableDelayedExpansion
REM ============================================================
REM  Agent Platform 上传文件镜像备份（运维系统 OPS-FR-15）
REM  · robocopy /MIR 增量镜像 uploads/ → BACKUP_ROOT\files\uploads（每日 02:30 计划任务）
REM  · /MIR 是镜像语义：源端删除 → 目标端同步删除（与「文件以应用删除为准」一致）
REM  · robocopy 退出码语义特殊：0~7 均为成功（0=无变化 1=已复制 2=有多余 3=1+2…），>=8 才是失败
REM  · 失败写事件日志 + 调告警 webhook；巡检标记与 PG 备份共用 last-backup-status.txt 不合写，独立 files 标记
REM  计划任务注册示例（管理员 cmd）：
REM    schtasks /create /tn "AgentPlatform-FilesBackup" /tr "\"D:\path\backup-files.bat\"" /sc daily /st 02:30 /ru SYSTEM /f
REM ============================================================

if exist "%~dp0backup-env.bat" call "%~dp0backup-env.bat"

if not defined UPLOADS_DIR set "UPLOADS_DIR=D:\IT\AI-Projects\AI-Projects\agent-platform\backend\uploads"
if not defined BACKUP_ROOT set "BACKUP_ROOT=D:\backup\agent-platform"

set "DEST=%BACKUP_ROOT%\files\uploads"
set "LOG_DIR=%BACKUP_ROOT%\logs"
if not exist "%DEST%" mkdir "%DEST%"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TS=%%i"

if not exist "%UPLOADS_DIR%" (
    echo [%TS%] [ERROR] 源目录不存在：%UPLOADS_DIR%
    goto :fail
)

echo [%TS%] robocopy 镜像 %UPLOADS_DIR% -^> %DEST%
robocopy "%UPLOADS_DIR%" "%DEST%" /MIR /R:2 /W:5 /NP /NDL /LOG+:"%LOG_DIR%\files-backup.log"
set "RC=%ERRORLEVEL%"

REM robocopy 0~7 = 成功
if %RC% LSS 8 (
    eventcreate /T INFORMATION /ID 110 /L APPLICATION /SO AgentPlatformBackup /D "Files backup OK rc=%RC% src=%UPLOADS_DIR%" > nul 2>&1
    echo %TS% OK rc=%RC%> "%BACKUP_ROOT%\last-files-backup-status.txt"
    echo [%TS%] 文件镜像成功（rc=%RC%）
    exit /b 0
)

:fail
if not defined RC set "RC=-1"
echo [%TS%] [ERROR] 文件镜像失败，rc=%RC%（详见 %LOG_DIR%\files-backup.log）
eventcreate /T ERROR /ID 111 /L APPLICATION /SO AgentPlatformBackup /D "Files backup FAILED rc=%RC% src=%UPLOADS_DIR%" > nul 2>&1
echo %TS% FAIL rc=%RC%> "%BACKUP_ROOT%\last-files-backup-status.txt"
if defined OPS_ALERT_WEBHOOK_URL (
    curl -s -X POST "%OPS_ALERT_WEBHOOK_URL%" -H "Content-Type: application/json" -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[AgentPlatform] Files backup FAILED rc=%RC% time=%TS%\"}}" > nul 2>&1
)
exit /b 1
