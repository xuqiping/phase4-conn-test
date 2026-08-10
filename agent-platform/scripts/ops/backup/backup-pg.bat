@echo off
setlocal EnableDelayedExpansion
REM ============================================================
REM  Agent Platform PG 每日备份（运维系统 OPS-FR-13/14/16）
REM  · pg_dump -Fc 每日 02:00（Windows 计划任务调用，建议最高权限运行）
REM  · 保留 7 天日备 + 4 周周备（周日 02:00 那一份同时归周备）
REM  · 成功/失败写事件日志 + last-backup-status.txt 巡检标记；失败调告警 webhook
REM  · 红线①：密码由 backup-env.bat（本地不入库）注入，本文件零机密
REM  · 红线②：BACKUP_ROOT 必须全英文路径（中文路径 + 计划任务 = 乱码炸点）
REM  计划任务注册示例（管理员 cmd）：
REM    schtasks /create /tn "AgentPlatform-PgBackup" /tr "\"D:\path\backup-pg.bat\"" /sc daily /st 02:00 /ru SYSTEM /f
REM ============================================================

REM --- 加载本地机密与环境（不入库；模板见 backup-env.example.bat） ---
if exist "%~dp0backup-env.bat" call "%~dp0backup-env.bat"

REM --- 缺省值（模板已给的以模板为准） ---
if not defined PG_BIN set "PG_BIN=D:\IT\postgresql\bin"
if not defined PG_HOST set "PG_HOST=localhost"
if not defined PG_PORT set "PG_PORT=5432"
if not defined PG_USER set "PG_USER=postgres"
if not defined PG_DB set "PG_DB=agent_platform"
if not defined BACKUP_ROOT set "BACKUP_ROOT=D:\backup\agent-platform"

if not defined PG_PASSWORD (
    echo [FATAL] PG_PASSWORD 未注入——请从 backup-env.example.bat 复制出 backup-env.bat 并填密码
    exit /b 2
)

set "DAILY_DIR=%BACKUP_ROOT%\pg\daily"
set "WEEKLY_DIR=%BACKUP_ROOT%\pg\weekly"
set "LOG_DIR=%BACKUP_ROOT%\logs"
if not exist "%DAILY_DIR%" mkdir "%DAILY_DIR%"
if not exist "%WEEKLY_DIR%" mkdir "%WEEKLY_DIR%"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TS=%%i"
set "OUT=%DAILY_DIR%\%PG_DB%_%TS%.dump"

echo [%TS%] pg_dump -Fc %PG_DB% -^> %OUT%
set "PGPASSWORD=%PG_PASSWORD%"
"%PG_BIN%\pg_dump.exe" -h %PG_HOST% -p %PG_PORT% -U %PG_USER% -Fc -f "%OUT%" %PG_DB% 1>>"%LOG_DIR%\pg-backup.log" 2>&1
set "RC=%ERRORLEVEL%"
set "PGPASSWORD="

if not "%RC%"=="0" goto :fail
if not exist "%OUT%" goto :fail

REM --- 周日同时归一份周备（DayOfWeek: Sunday=0） ---
for /f %%i in ('powershell -NoProfile -Command "(Get-Date).DayOfWeek.value__"') do set "DOW=%%i"
if "%DOW%"=="0" (
    copy /y "%OUT%" "%WEEKLY_DIR%\" > nul
    echo [%TS%] 周日副本已归周备目录
)

REM --- 保留策略：日备 7 天、周备 28 天（4 周） ---
powershell -NoProfile -Command "Get-ChildItem '%DAILY_DIR%\*.dump' -ErrorAction SilentlyContinue | Where-Object {$_.LastWriteTime -lt (Get-Date).AddDays(-7)} | Remove-Item -Force"
powershell -NoProfile -Command "Get-ChildItem '%WEEKLY_DIR%\*.dump' -ErrorAction SilentlyContinue | Where-Object {$_.LastWriteTime -lt (Get-Date).AddDays(-28)} | Remove-Item -Force"

REM --- 成功：事件日志 + 巡检标记 ---
eventcreate /T INFORMATION /ID 100 /L APPLICATION /SO AgentPlatformBackup /D "PG backup OK: %OUT%" > nul 2>&1
echo %TS% OK %OUT%> "%BACKUP_ROOT%\last-backup-status.txt"
echo [%TS%] 备份成功
exit /b 0

:fail
echo [%TS%] [ERROR] 备份失败，错误码 %RC%（详见 %LOG_DIR%\pg-backup.log）
eventcreate /T ERROR /ID 101 /L APPLICATION /SO AgentPlatformBackup /D "PG backup FAILED rc=%RC% db=%PG_DB%" > nul 2>&1
echo %TS% FAIL rc=%RC%> "%BACKUP_ROOT%\last-backup-status.txt"
if defined OPS_ALERT_WEBHOOK_URL (
    curl -s -X POST "%OPS_ALERT_WEBHOOK_URL%" -H "Content-Type: application/json" -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[AgentPlatform] PG backup FAILED rc=%RC% db=%PG_DB% time=%TS%\"}}" > nul 2>&1
)
exit /b 1
