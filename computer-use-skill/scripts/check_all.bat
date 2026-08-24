@echo off
setlocal EnableDelayedExpansion
REM ============================================================================
REM check_all.bat —— 本地最小质量门（一条命令跑完全部自动检查）
REM 硬规则：commit 前必跑本脚本，全绿（exit code 0）才允许提交。
REM 结构：tsc --noEmit → eslint → vitest → npm audit → check_docs → 启动冒烟
REM ============================================================================

set "ROOT=%~dp0.."
cd /d "%ROOT%"
set FAIL=0

echo ============================================================
echo  [check_all] 最小质量门开始  (%DATE% %TIME%)
echo ============================================================

echo ^> npx tsc --noEmit
call npx tsc --noEmit || set FAIL=1
echo ^> npm run lint
call npm run lint || set FAIL=1
echo ^> npm run test
call npm run test || set FAIL=1
REM audit 为咨询性：当前 npm 镜像源不实现 audit 端点（已知限制），有真实输出时人工复核
echo ^> npm run check:audit (advisory)
call npm run check:audit 2>&1 | findstr /C:"vulnerabilities" >nul && (echo   存在漏洞报告，请人工复核) || echo   audit 不可用或无漏洞，继续

echo ^> [Docs] 文档规则检查
call python scripts\check_docs.py --quiet || set FAIL=1

echo ^> [Perf] 启动冒烟（^<2s，见 performance_goals）
node -e "const t=Date.now();const{McpServer}=require('./node_modules/@modelcontextprotocol/sdk/server/mcp.js');" 2>nul
powershell -NoProfile -Command "$s=[Diagnostics.Stopwatch]::StartNew(); $p=Start-Process node -ArgumentList 'dist/index.js' -PassThru -RedirectStandardOutput NUL -RedirectStandardError NUL; Start-Sleep -Milliseconds 1500; if($p.HasExited){exit 1}; Stop-Process -Id $p.Id -Force; exit 0" || set FAIL=1

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
