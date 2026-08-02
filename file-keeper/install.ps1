# Node.js 自动安装脚本
# 请以管理员身份运行此脚本

Write-Host "===============" -ForegroundColor Cyan
Write-Host "  File Keeper - Node.js 安装脚本" -ForegroundColor Cyan
Write-Host "===================" -ForegroundColor Cyan
Write-Host ""

# 检查是否已安装 Node.js
$nodeInstalled = Get-Command node -ErrorAction SilentlyContinue
if ($nodeInstalled) {
    Write-Host "✓ Node.js 已安装" -ForegroundColor Green
    Write-Host "  版本: $(node --version)" -ForegroundColor Gray
    Write-Host "  npm 版本: $(npm --version)" -ForegroundColor Gray
    Write-Host ""

    $continue = Read-Host "是否继续安装项目依赖? (Y/N)"
    if ($continue -ne "Y" -and $continue -ne "y") {
        exit
    }
} else {
    Write-Host "× Node.js 未安装，开始安装..." -ForegroundColor Yellow
    Write-Host ""

    Write-Host "正在使用 winget 安装 Node.js LTS..." -ForegroundColor Cyan
    winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements

    if ($LASTEXITCODE -eq 0) {
        Write-Host "✓ Node.js 安装成功！" -ForegroundColor Green
        Write-Host ""
        Write-Host "请关闭此窗口，重新打开 PowerShell，然后再次运行此脚本。" -ForegroundColor Yellow
        Write-Host "（需要重新加载环境变量）" -ForegroundColor Gray
        pause
        exit
    } else {
        Write-Host "× Node.js 安装失败" -ForegroundColor Red
        Write-Host ""
        Write-Host "请手动安装 Node.js:" -ForegroundColor Yellow
        Write-Host "1. 访问 https://nodejs.org/" -ForegroundColor Gray
        Write-Host "2. 下载 LTS 版本" -ForegroundColor Gray
        Write-Host "3. 运行安装程序" -ForegroundColor Gray
        Write-Host "4. 重新运行此脚本" -ForegroundColor Gray
        pause
        exit
    }
}

# 切换到项目目录
Write-Host "切换到项目目录..." -ForegroundColor Cyan
$projectPath = "C:\AI Projects\file-keeper"
Set-Location $projectPath

if (-not (Test-Path $projectPath)) {
    Write-Host "× 项目目录不存在: $projectPath" -ForegroundColor Red
    pause
    exit
}

Write-Host "✓ 当前目录: $projectPath" -ForegroundColor Green
Write-Host ""

# 检查 package.json
if (-not (Test-Path "package.json")) {
    Write-Host "× 未找到 package.json 文件" -ForegroundColor Red
    pause
    exit
}

# 安装依赖
Write-Host "===================" -ForegroundColor Cyan
Write-Host "  开始安装项目依赖..." -ForegroundColor Cyan
Write-Host "================" -ForegroundColor Cyan
Write-Host ""
Write-Host "这可能需要 2-5 分钟，请耐心等待..." -ForegroundColor Yellow
Write-Host ""

npm install

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "=================" -ForegroundColor Green
    Write-Host "  ✓ 依赖安装成功！" -ForegroundColor Green
    Write-Host "=========================" -ForegroundColor Green
    Write-Host ""

    Write-Host "已安装的主要依赖:" -ForegroundColor Cyan
    Write-Host "  - Vue 3" -ForegroundColor Gray
    Write-Host "  - Naive UI" -ForegroundColor Gray
    Write-Host "  - Pinia" -ForegroundColor Gray
    Write-Host "  - lucide-vue-next" -ForegroundColor Gray
    Write-Host "  - @vueuse/core" -ForegroundColor Gray
    Write-Host ""

    Write-Host "=====================" -ForegroundColor Cyan
    Write-Host "  准备启动开发服务器" -ForegroundColor Cyan
    Write-Host "======================" -ForegroundColor Cyan
    Write-Host ""

    $startDev = Read-Host "是否立即启动开发服务器? (Y/N)"
    if ($startDev -eq "Y" -or $startDev -eq "y") {
        Write-Host ""
        Write-Host "正在启动开发服务器..." -ForegroundColor Cyan
        Write-Host "浏览器将自动打开 http://localhost:1420" -ForegroundColor Gray
        Write-Host ""
        Write-Host "按 Ctrl+C 可停止服务器" -ForegroundColor Yellow
        Write-Host ""
        npm run dev
    } else {
        Write-Host ""
        Write-Host "稍后可以运行以下命令启动开发服务器:" -ForegroundColor Cyan
        Write-Host "  cd `"$projectPath`"" -ForegroundColor Gray
        Write-Host "  npm run dev" -ForegroundColor Gray
        Write-Host ""
    }
} else {
    Write-Host ""
    Write-Host "× 依赖安装失败" -ForegroundColor Red
    Write-Host ""
    Write-Host "可能的解决方案:" -ForegroundColor Yellow
    Write-Host "1. 检查网络连接" -ForegroundColor Gray
    Write-Host "2. 尝试使用国内镜像:" -ForegroundColor Gray
    Write-Host "   npm config set registry https://registry.npmmirror.com" -ForegroundColor Gray
    Write-Host "3. 清除缓存后重试:" -ForegroundColor Gray
    Write-Host "   npm cache clean --force" -ForegroundColor Gray
    Write-Host "   npm install" -ForegroundColor Gray
    Write-Host ""
}

pause