@echo off
setlocal EnableDelayedExpansion
REM ============================================================================
REM check_all.bat —— 本地最小质量门（一条命令跑完全部自动检查）
REM
REM 硬规则：commit 前必跑本脚本，全绿（exit code 0）才允许提交。
REM 用法：  scripts\check_all.bat
REM 维护：  按本项目实际技术栈调整各段命令；项目没有的段整段删除（不要留"跳过"），
REM         新增检查（如桌面端、E2E）按同样结构追加。
REM ============================================================================

set "ROOT=%~dp0.."
cd /d "%ROOT%"
set FAIL=0

echo ============================================================
echo  [check_all] 最小质量门开始  (%DATE% %TIME%)
echo ============================================================

REM ------------------------------ 后端 ------------------------------
if exist "PROJECT\backend\pom.xml" (
    echo.
    echo [Backend] Maven 项目检测到，开始检查...
    cd /d "%ROOT%\PROJECT\backend"
    echo ^> mvn -q compile
    call mvn -q compile || set FAIL=1
    echo ^> mvn -q test
    call mvn -q test || set FAIL=1
    cd /d "%ROOT%"
) else if exist "PROJECT\backend\build.gradle" (
    echo.
    echo [Backend] Gradle 项目检测到，开始检查...
    cd /d "%ROOT%\PROJECT\backend"
    echo ^> gradlew.bat test
    call gradlew.bat test || set FAIL=1
    cd /d "%ROOT%"
) else (
    echo.
    echo [Backend] 未检测到后端项目，跳过（若项目有后端，请在此补充对应命令）
)

REM ------------------------------ 前端 ------------------------------
if exist "PROJECT\frontend\package.json" (
    echo.
    echo [Frontend] 前端项目检测到，开始检查...
    cd /d "%ROOT%\PROJECT\frontend"
    echo ^> npx tsc --noEmit
    call npx tsc --noEmit || set FAIL=1
    echo ^> npm run lint
    call npm run lint || set FAIL=1
    echo ^> npm run test -- --run
    call npm run test -- --run || set FAIL=1
    cd /d "%ROOT%"
) else (
    echo.
    echo [Frontend] 未检测到前端项目，跳过（若项目有前端，请在此补充对应命令）
)

REM ------------------------------ 桌面端（若有，解除注释并调整） ------------------------------
REM if exist "PROJECT\desktop\package.json" (
REM     echo.
REM     echo [Desktop] 桌面端项目检测到，开始检查...
REM     cd /d "%ROOT%\PROJECT\desktop"
REM     call npm run lint || set FAIL=1
REM     call npm run test -- --run || set FAIL=1
REM     cd /d "%ROOT%"
REM )

REM ------------------------------ 文档规则 ------------------------------
if exist "workflow_output" (
    echo.
    echo [Docs] 文档规则检查（tokens 上限 / 失效链接）...
    echo ^> python scripts\check_docs.py --quiet
    call python scripts\check_docs.py --quiet || set FAIL=1
)

echo.
echo ============================================================
if "%FAIL%"=="0" (
    echo  [check_all] 全绿 PASS —— 可以 commit
    echo ============================================================
    exit /b 0
) else (
    echo  [check_all] 存在失败项 FAIL —— 修复前禁止 commit
    echo  提示：把失败日志贴回给 AI，让它修（铁律 #9）
    echo ============================================================
    exit /b 1
)
