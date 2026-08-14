# =============================================================================
# security-baseline-check.ps1 — 主机与中间件安全基线核查（K5，只读巡检）
# 安全体系 S4 · SEC-FR-110~112/114；配套文档 workflow_output/docs/deploy/部署手册-主机基线.md
#
# 用法（服务器上）：powershell -ExecutionPolicy Bypass -File security-baseline-check.ps1
# 输出 PASS/FAIL 清单；任一 FAIL → 退出码 1（可接计划任务月跑 + 告警）。
# 铁律：只读不改——修复动作全部人工按基线文档执行。
# =============================================================================
param(
    # 部署约定根（与部署手册终 §四 一致）
    [string]$AppRoot = "C:\agent-platform",
    [string]$NginxConf = "C:\nginx\conf\nginx.conf",
    [string]$PgDataDir = "C:\Program Files\PostgreSQL\16\data",
    # Redis/Memurai 配置（找到哪个算哪个）
    [string[]]$RedisConfCandidates = @("D:\IT\ops\memurai\memurai.conf", "C:\Program Files\Memurai\memurai.conf", "D:\IT\ops\redis\redis.windows.conf"),
    # 对外只允许这些端口（80/443 + RDP 自定端口按实际改）
    [int[]]$AllowedPublicPorts = @(80, 443),
    [int]$RdpPort = 33890   # K1 改过端口后同步改这里；未改端口前先用 3389 跑基线
)

$script:FailCount = 0
function Write-Check {
    param([string]$Name, [bool]$Pass, [string]$Detail = "")
    if ($Pass) {
        Write-Host ("[PASS] {0}" -f $Name) -ForegroundColor Green
    } else {
        $script:FailCount++
        Write-Host ("[FAIL] {0}  {1}" -f $Name, $Detail) -ForegroundColor Red
    }
}

Write-Host "==== 安全基线核查 $(Get-Date -Format 'yyyy-MM-dd HH:mm') ===="

# ---------- K1a 监听端口面：非回环 LISTENING 端口必须 ⊆ AllowedPublicPorts ----------
try {
    $listeners = Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { $_.LocalAddress -notin @("127.0.0.1", "::1", "0.0.0.0", "::") -or $_.LocalAddress -in @("0.0.0.0", "::") }
    # 0.0.0.0/:: = 全接口（含公网）——须在白名单；127.0.0.1 放行
    $public = Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { $_.LocalAddress -in @("0.0.0.0", "::") }
    $bad = $public | Where-Object { $_.LocalPort -notin $AllowedPublicPorts }
    $badList = ($bad | Select-Object -ExpandProperty LocalPort -Unique | Sort-Object) -join ","
    Write-Check "K1a 全接口监听端口 ⊆ 白名单($($AllowedPublicPorts -join ','))" ($null -eq $bad -or $bad.Count -eq 0) "越界端口: $badList"
} catch {
    Write-Check "K1a 监听端口面" $false "查询失败: $($_.Exception.Message)"
}

# 关键内部端口逐个点名（8080 backend / 8090 sidecar / 5432 PG / 6379 Redis / 9090 prometheus）
foreach ($p in @(8080, 8090, 5432, 6379, 9090)) {
    $row = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalAddress -in @("0.0.0.0", "::") }
    Write-Check "K2 端口 $p 仅本机绑定" ($null -eq $row -or $row.Count -eq 0) "发现全接口监听"
}

# ---------- K1b 防火墙：内部端口无 Enabled 的 Any→Any 入站放行 ----------
try {
    foreach ($p in @(8080, 8090, 5432, 6379)) {
        $rules = Get-NetFirewallPortFilter -Protocol TCP -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -eq $p } | Get-NetFirewallRule -ErrorAction SilentlyContinue |
            Where-Object { $_.Direction -eq "Inbound" -and $_.Action -eq "Allow" -and $_.Enabled -eq "True" }
        Write-Check "K1b 防火墙未放行 $p 入站" ($null -eq $rules -or $rules.Count -eq 0) "存在放行规则: $(($rules | Select-Object -First 3 -ExpandProperty DisplayName) -join ';')"
    }
} catch {
    Write-Check "K1b 防火墙规则" $false "查询失败: $($_.Exception.Message)"
}

# ---------- K1c RDP 端口非默认 ----------
try {
    $rdp = (Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp" -Name PortNumber -ErrorAction Stop).PortNumber
    Write-Check "K1c RDP 端口=配置值($RdpPort)" ($rdp -eq $RdpPort) "实际: $rdp"
} catch {
    Write-Check "K1c RDP 端口" $false "读取注册表失败"
}

# ---------- K1d 账户锁定策略 ----------
try {
    $lock = net accounts | Select-String "锁定阈值|Lockout threshold"
    $threshold = if ($lock -match "(\d+)") { [int]$Matches[1] } else { 0 }
    Write-Check "K1d 账户锁定阈值>0（防爆破）" ($threshold -gt 0 -and $threshold -le 10) "当前: $threshold"
} catch {
    Write-Check "K1d 账户锁定" $false "net accounts 失败"
}

# ---------- K2 Redis bind+requirepass ----------
$redisConf = $RedisConfCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($redisConf) {
    $txt = Get-Content $redisConf -Raw
    $bindOk = $txt -match '(?m)^\s*bind\s+.*127\.0\.0\.1'
    $passOk = $txt -match '(?m)^\s*requirepass\s+\S+'
    Write-Check "K2 Redis bind 127.0.0.1（$redisConf）" $bindOk
    Write-Check "K2 Redis requirepass 已设" $passOk
} else {
    Write-Check "K2 Redis 配置文件存在" $false "候选路径均不存在: $($RedisConfCandidates -join ';')"
}

# ---------- K2 PG listen_addresses + pg_hba ----------
$pgConf = Join-Path $PgDataDir "postgresql.conf"
$pgHba = Join-Path $PgDataDir "pg_hba.conf"
if (Test-Path $pgConf) {
    $pg = Get-Content $pgConf -Raw
    # 只 FAIL「显式放开」：注释态默认即 localhost；显式 */0.0.0.0/非 localhost 才 FAIL
    $explicit = [regex]::Match($pg, "(?m)^\s*listen_addresses\s*=\s*'?([^'\s]+)")
    $listenOk = -not $explicit.Success -or $explicit.Groups[1].Value -in @("localhost", "127.0.0.1")
    Write-Check "K2 PG listen_addresses=localhost（含注释默认）" $listenOk "显式值: $($explicit.Groups[1].Value)"
} else {
    Write-Check "K2 PG postgresql.conf 存在" $false "未找到: $pgConf"
}
if (Test-Path $pgHba) {
    $hba = Get-Content $pgHba -Raw
    $wideOpen = $hba -match "(?m)^host.*\s(0\.0\.0\.0/0|::/0)\s"
    Write-Check "K2 pg_hba 无 0.0.0.0/0 全网放行" (-not $wideOpen)
} else {
    Write-Check "K2 pg_hba.conf 存在" $false "未找到: $pgHba"
}

# ---------- K3 Nginx：server_tokens / autoindex / 无 uploads 直达 ----------
if (Test-Path $NginxConf) {
    $ng = Get-Content $NginxConf -Raw
    Write-Check "K3 Nginx server_tokens off" ($ng -match "(?m)^\s*server_tokens\s+off\s*;")
    Write-Check "K3 Nginx autoindex off" ($ng -match "(?m)^\s*autoindex\s+off\s*;" -or $ng -notmatch "(?m)^\s*autoindex\s+on")
    $uploadsRoute = $ng -match "(?m)^\s*location\s+(\^~\s+)?/uploads"
    Write-Check "K3 Nginx 无 /uploads 直达路由（SEC-FR-034）" (-not $uploadsRoute)
} else {
    Write-Check "K3 Nginx 配置存在" $false "未找到: $NginxConf"
}

# ---------- K4 目录 ACL 抽查：Everyone 不应有访问；uploads 目录独立存在 ----------
$uploadsDir = Join-Path $AppRoot "uploads"
if (Test-Path $uploadsDir) {
    $acl = icacls $uploadsDir 2>$null | Out-String
    $everyone = $acl -match "Everyone"
    Write-Check "K4 uploads 无 Everyone 权限" (-not $everyone) "icacls 输出含 Everyone"
    # uploads 不得在 dist/jar 同级嵌套之外——独立于 frontend\dist（F-5② 抽查）
    $distDir = Join-Path $AppRoot "frontend\dist"
    if (Test-Path $distDir) {
        $nested = Test-Path (Join-Path $distDir "uploads")
        Write-Check "K4 uploads 独立于 frontend\dist（F-5②）" (-not $nested)
    }
} else {
    Write-Check "K4 uploads 目录存在" $false "未找到: $uploadsDir"
}

# ---------- 汇总 ----------
Write-Host "==== 核查完成：FAIL $FailCount 项 ===="
if ($FailCount -gt 0) {
    Write-Host "按 workflow_output/docs/deploy/部署手册-主机基线.md 修复后复跑" -ForegroundColor Yellow
    exit 1
}
exit 0
