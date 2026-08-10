@echo off
REM ============================================================
REM 运维系统 Step 8：监控四组件 WinSW 服务注册 + 启动
REM 红线：本脚本含中文注释，必须 GBK + CRLF + 无 BOM + 不 chcp 65001
REM 前提：D:\IT\ops\ 下四组件已解压，各目录已放好 agent-*.exe(WinSW 改名) + agent-*.xml
REM 用法：必须管理员身份运行（注册服务需提权）
REM 判存用 sc query（WinSW status 对未安装服务也返回 0，不可用）
REM ============================================================
setlocal
set OPS=D:\IT\ops

net session >nul 2>&1
if errorlevel 1 (
    echo [FAIL] 需要管理员权限运行本脚本
    exit /b 1
)

call :svc prometheus prometheus
if errorlevel 1 exit /b 1
call :svc alertmanager alertmanager
if errorlevel 1 exit /b 1
call :svc grafana grafana
if errorlevel 1 exit /b 1
call :svc windows-exporter windows_exporter
if errorlevel 1 exit /b 1

echo.
echo [OK] 四个服务全部处理完成，用 services.msc 或 sc query 复核状态
exit /b 0

REM ---- 子例程：%1=服务名后缀(agent-%1) %2=组件目录名 ----
:svc
set EXE=%OPS%\%2\agent-%1.exe
if not exist "%EXE%" (
    echo [FAIL] 找不到 %EXE%
    exit /b 1
)
sc query agent-%1 >nul 2>&1
if not errorlevel 1 (
    echo [SKIP] agent-%1 已注册
) else (
    echo [..] install agent-%1
    "%EXE%" install
    if errorlevel 1 (
        echo [FAIL] install agent-%1
        exit /b 1
    )
)
echo [..] start agent-%1
"%EXE%" start >nul 2>&1
if errorlevel 1 (
    echo [WARN] agent-%1 start 返回非零，查 %OPS%\%2\logs 日志
)
exit /b 0
