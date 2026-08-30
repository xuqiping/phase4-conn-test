@echo off
setlocal EnableDelayedExpansion
REM ============================================================================
REM check_all.bat —— 本地最小质量门（一条命令跑完全部自动检查）
REM
REM 硬规则：commit 前必跑本脚本，全绿（exit code 0）才允许提交。
REM 用法：  scripts\check_all.bat
REM
REM 本项目技术栈：Tauri 2（前端 Vue3 在根目录 + Rust 后端在 src-tauri\）
REM 维护：新增检查（如 E2E、cargo test）按同样结构追加。
REM ============================================================================

set "ROOT=%~dp0.."
cd /d "%ROOT%"
set FAIL=0

echo ============================================================
echo  [check_all] 最小质量门开始  (%DATE% %TIME%)
echo ============================================================

REM ------------------------------ 前端（Vue 3，根目录）------------------------------
if exist "package.json" (
    echo.
    echo [Frontend] 前端项目检测到（根目录），开始检查...
    echo ^> npx vue-tsc --noEmit
    call npx vue-tsc --noEmit || set FAIL=1
    REM 注：本项目 package.json 无 lint/test 脚本；如后续加入，在此追加：
    REM   call npm run lint || set FAIL=1
    REM   call npm run test -- --run || set FAIL=1
) else (
    echo.
    echo [Frontend] 未检测到 package.json，跳过
)

REM ------------------------------ 后端（Rust，src-tauri\）------------------------------
if exist "src-tauri\Cargo.toml" (
    echo.
    echo [Backend] Rust/Tauri 项目检测到（src-tauri\），开始检查...
    cd /d "%ROOT%\src-tauri"
    echo ^> cargo check
    call cargo check || set FAIL=1
    REM 注：当前项目无测试用例；如后续加入单元测试，取消下行注释：
    REM call cargo test || set FAIL=1
    cd /d "%ROOT%"
) else (
    echo.
    echo [Backend] 未检测到 src-tauri\Cargo.toml，跳过
)

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
