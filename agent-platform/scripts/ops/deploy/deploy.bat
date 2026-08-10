@echo off
REM ============================================================
REM  deploy.bat - agent-platform 一键发布（运维系统 Step12 / OPS-FR-18~20）
REM  用法（服务器上、需管理员）：
REM    deploy.bat D:\pkg\agent-platform-x.y.z.jar D:\pkg\dist   前后端一起发
REM    deploy.bat D:\pkg\agent-platform-x.y.z.jar               只发后端
REM    deploy.bat NONE D:\pkg\dist                              只发前端
REM  流程：备份当前版 -^> 停服 -^> 拷新包 -^> 起服 -^> 健康轮询(5分钟/5秒)
REM        -^> 超时自动回滚旧包再轮询 -^> 仍失败则发钉钉告警人工介入
REM  Flyway 门禁（OPS-FR-19）：服务器无 Maven，迁移由后端启动时自动执行；
REM        迁移失败 = 启动失败 = 健康轮询超时 = 自动回滚，等价于中止发布。
REM        flyway:repair 对齐 checksum 必须在开发机先做，勿带脏历史上服务器。
REM  DB 纪律（OPS-FR-20）：迁移「只增不删、向后兼容」，旧版代码能跑在新 schema
REM        上，回滚才安全；版本目录只保最近 3 版。
REM  红线：本文件必须 GBK + CRLF + 无 BOM，禁止 chcp 65001；
REM        发给钉钉的 JSON 必须 ASCII（GBK 字节会乱码）。
REM ============================================================
setlocal

set ROOT=C:\agent-platform
set BACKEND_DIR=%ROOT%\backend
set FRONTEND_DIR=%ROOT%\frontend\dist
set VERSIONS=%ROOT%\versions
set HEALTH_URL=http://localhost:8080/actuator/health
set SVC_BACKEND=agent-backend

if "%~1"=="" goto :usage
set NEW_JAR=%~1
set NEW_DIST=%~2
if /I "%NEW_JAR%"=="NONE" set NEW_JAR=
if "%NEW_JAR%"=="" if "%NEW_DIST%"=="" goto :usage
if not "%NEW_JAR%"=="" if not exist "%NEW_JAR%" (
    echo [错误] 找不到 jar：%NEW_JAR%
    exit /b 1
)
if not "%NEW_DIST%"=="" if not exist "%NEW_DIST%\index.html" (
    echo [错误] dist 目录不像前端产物（缺 index.html）：%NEW_DIST%
    exit /b 1
)

REM 需要管理员（停启服务），不足则自动提权重跑
net session >nul 2>&1
if errorlevel 1 (
    echo 需要管理员权限，正在弹出授权窗口...
    powershell -Command "Start-Process '%~f0' -Verb RunAs -ArgumentList '%*'"
    exit /b 0
)

for /f %%i in ('powershell -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set TS=%%i
set BK=%VERSIONS%\%TS%
echo ============================================================
echo  agent-platform 发布   备份目录：%BK%
echo ============================================================

REM ---- 1. 备份当前版本 ----
echo [1/5] 备份当前 jar/dist
mkdir "%BK%" 2>nul
if exist "%BACKEND_DIR%\*.jar" copy /y "%BACKEND_DIR%\*.jar" "%BK%\" >nul
if exist "%FRONTEND_DIR%" robocopy "%FRONTEND_DIR%" "%BK%\dist" /MIR /NP /NDL /NFL >nul

REM ---- 纯前端发布走快道：不动后端服务 ----
if "%NEW_JAR%"=="" goto :frontend_only

REM ---- 2. 停服 ----
echo [2/5] 停止服务 %SVC_BACKEND%
net stop %SVC_BACKEND% >nul 2>&1

REM ---- 3. 拷新包 ----
echo [3/5] 拷贝新 jar
del /q "%BACKEND_DIR%\*.jar" 2>nul
copy /y "%NEW_JAR%" "%BACKEND_DIR%\" >nul
if errorlevel 1 goto :rollback
if not "%NEW_DIST%"=="" call :deploy_dist

REM ---- 4. 起服 ----
echo [4/5] 启动服务
net start %SVC_BACKEND% >nul 2>&1
if errorlevel 1 goto :rollback

REM ---- 5. 健康轮询 ----
echo [5/5] 健康检查轮询（最长 5 分钟）...
call :wait_health 60
if errorlevel 1 goto :rollback
goto :success

:frontend_only
echo [2/5] 纯前端发布，后端服务不动
call :deploy_dist
goto :success

:success
echo 清理旧版本（保留最近 3 版）...
call :prune_versions 3
echo ============================================================
echo  发布完成：%TS%
echo ============================================================
exit /b 0

:rollback
echo.
echo !!!!!!!! 发布失败，自动回滚 !!!!!!!!
net stop %SVC_BACKEND% >nul 2>&1
if exist "%BK%\*.jar" (
    del /q "%BACKEND_DIR%\*.jar" 2>nul
    copy /y "%BK%\*.jar" "%BACKEND_DIR%\" >nul
)
if exist "%BK%\dist" robocopy "%BK%\dist" "%FRONTEND_DIR%" /MIR /NP /NDL /NFL >nul
net start %SVC_BACKEND% >nul 2>&1
call :wait_health 60
if errorlevel 1 (
    echo !!!!!!!! 回滚后健康检查仍失败，请人工介入 !!!!!!!!
    call :alert "deploy FAILED and rollback FAILED - manual intervention required"
    pause
    exit /b 2
)
echo 回滚完成，旧版本已恢复运行。
call :alert "deploy failed, auto-rollback OK - please investigate"
pause
exit /b 1

:deploy_dist
REM 拷贝前端产物并重载 Nginx（存在的话）
robocopy "%NEW_DIST%" "%FRONTEND_DIR%" /MIR /NP /NDL /NFL >nul
if exist C:\nginx\nginx.exe (
    cd /d C:\nginx
    nginx -s reload >nul 2>&1
    cd /d %~dp0
)
exit /b 0

:wait_health
REM 轮询健康端点：%1=最大次数（5 秒一次，60 次=5 分钟）
set /a N=%1
:wh_loop
curl -s -o nul -w "%%{http_code}" %HEALTH_URL% 2>nul | findstr /C:"200" >nul
if not errorlevel 1 exit /b 0
set /a N-=1
if %N% leq 0 exit /b 1
ping 127.0.0.1 -n 6 >nul
goto :wh_loop

:prune_versions
REM 只保留最近 %1 个版本目录（时间戳命名，按名称倒序即按时间倒序）
for /f "skip=%1" %%d in ('dir /b /ad /o-n "%VERSIONS%" 2^>nul') do rd /s /q "%VERSIONS%\%%d"
exit /b 0

:alert
REM 发告警到钉钉适配器（未配置时静默失败）；内容必须 ASCII
curl -s -X POST "http://127.0.0.1:8060/dingtalk/ops/send" -H "Content-Type: application/json" -d "{\"version\":\"4\",\"status\":\"firing\",\"alerts\":[{\"status\":\"firing\",\"labels\":{\"alertname\":\"DeployFailure\",\"severity\":\"critical\"},\"annotations\":{\"summary\":\"agent-platform deploy\",\"description\":\"%~1\"},\"startsAt\":\"2026-01-01T00:00:00.000Z\"}]}" >nul 2>&1
exit /b 0

:usage
echo 用法: deploy.bat 新jar路径 [新dist目录]
echo   前后端一起发: deploy.bat D:\pkg\agent-platform-x.y.z.jar D:\pkg\dist
echo   只发后端:     deploy.bat D:\pkg\agent-platform-x.y.z.jar
echo   只发前端:     deploy.bat NONE D:\pkg\dist
exit /b 1
