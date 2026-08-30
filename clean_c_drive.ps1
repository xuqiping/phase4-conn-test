$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$freedTotal = 0.0

function Measure-GB($path) {
    if (-not (Test-Path $path)) { return 0 }
    $item = Get-Item $path -Force -ErrorAction SilentlyContinue
    if (-not $item) { return 0 }
    if ($item.PSIsContainer) {
        $sum = (Get-ChildItem $path -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        if ($null -eq $sum) { $sum = 0 }
        return $sum / 1GB
    }
    return $item.Length / 1GB
}

function Remove-Target($path, $label) {
    if (-not (Test-Path $path)) { Write-Host "[skip] $label (not found)"; return }
    $before = Measure-GB $path
    Remove-Item $path -Recurse -Force -ErrorAction SilentlyContinue
    $after = Measure-GB $path
    $freed = $before - $after
    $script:freedTotal += $freed
    Write-Host ("[ok] {0,-50} freed {1,6:N2} GB" -f $label, $freed)
}

Write-Host "=== Free space BEFORE ==="
$c0 = Get-PSDrive C
Write-Host ("Free: {0:N2} GB" -f ($c0.Free/1GB))
Write-Host ""

# 1. Recycle bin
try {
    Clear-RecycleBin -DriveLetter C -Force -ErrorAction Stop
    Write-Host "[ok] Recycle Bin cleared"
} catch { Write-Host "[skip] Recycle Bin: $($_.Exception.Message)" }

# 2. Crash dumps
Remove-Target "$env:LOCALAPPDATA\ShadowBot\CrashDumps" "ShadowBot CrashDumps"
Remove-Target "$env:LOCALAPPDATA\CrashDumps" "Local CrashDumps"

# 3. Old updater installers / caches
Remove-Target "$env:LOCALAPPDATA\app_shell_cache_2079" "app_shell_cache_2079"
Remove-Target "$env:LOCALAPPDATA\teleagent-updater" "teleagent-updater"
Remove-Target "$env:LOCALAPPDATA\webcast_mate-updater" "webcast_mate-updater"
Remove-Target "$env:LOCALAPPDATA\coze-updater" "coze-updater"
Remove-Target "$env:LOCALAPPDATA\appium-desktop-updater" "appium-desktop-updater"
Remove-Target "$env:LOCALAPPDATA\maodou-updater" "maodou-updater"

# 4. Qianniu temp
Remove-Target "$env:LOCALAPPDATA\QianniuTemp" "QianniuTemp"

# 5. Codex runtime tarballs
Remove-Target "$env:USERPROFILE\.cache\codex-runtimes" "codex-runtimes cache"

# 6. VS Code caches (keep extensions/settings)
$codeCaches = @('Cache','CachedData','CachedExtensionVSIXs','Code Cache','GPUCache','logs','Service Worker\CacheStorage','Service Worker\ScriptCache')
foreach ($sub in $codeCaches) {
    Remove-Target "$env:APPDATA\Code\$sub" "VSCode $sub"
}

# 7. OpenAI Codex old bin versions (keep newest folder)
$codexBin = "$env:LOCALAPPDATA\OpenAI\Codex\bin"
if (Test-Path $codexBin) {
    $versions = Get-ChildItem $codexBin -Directory -Force -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
    if ($versions.Count -gt 1) {
        $versions | Select-Object -Skip 1 | ForEach-Object {
            Remove-Target $_.FullName "Codex old bin $($_.Name)"
        }
    } else { Write-Host "[skip] Codex bin: only one version" }
}

# 8. Playwright old browser versions (keep newest per browser type)
$pw = "$env:LOCALAPPDATA\ms-playwright"
if (Test-Path $pw) {
    Get-ChildItem $pw -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(.+?)-(\d+)$' } |
        Group-Object { $_.Name -replace '-\d+$','' } |
        ForEach-Object {
            $keep = $_.Group | Sort-Object { [int]($_.Name -replace '.*-(\d+)$','$1') } -Descending | Select-Object -First 1
            $_.Group | Where-Object { $_.FullName -ne $keep.FullName } | ForEach-Object {
                Remove-Target $_.FullName "Playwright old $($_.Name)"
            }
        }
}

# 9. Yarn cache
Remove-Target "$env:LOCALAPPDATA\Yarn\Cache" "Yarn cache"

# 10. Browser caches
Remove-Target "$env:LOCALAPPDATA\Google\Chrome\User Data\Default\Cache" "Chrome cache"
Remove-Target "$env:LOCALAPPDATA\Google\Chrome\User Data\Default\Code Cache" "Chrome code cache"
Remove-Target "$env:LOCALAPPDATA\Microsoft\Edge\User Data\Default\Cache" "Edge cache"
Remove-Target "$env:LOCALAPPDATA\Microsoft\Edge\User Data\Default\Code Cache" "Edge code cache"

# 11. Windows Update download cache
Remove-Target "C:\Windows\SoftwareDistribution\Download" "Windows Update cache"

# 12. Temp folders
Get-ChildItem "$env:LOCALAPPDATA\Temp" -Force -ErrorAction SilentlyContinue | ForEach-Object {
    Remove-Target $_.FullName "Temp\$($_.Name)"
}
Get-ChildItem "C:\Windows\Temp" -Force -ErrorAction SilentlyContinue | ForEach-Object {
    Remove-Target $_.FullName "Windows\Temp\$($_.Name)"
}

Write-Host ""
Write-Host "=== SUMMARY ==="
$c1 = Get-PSDrive C
Write-Host ("Total freed by this script: {0:N2} GB" -f $freedTotal)
Write-Host ("Free space NOW: {0:N2} GB (before: {1:N2} GB)" -f ($c1.Free/1GB), ($c0.Free/1GB))
