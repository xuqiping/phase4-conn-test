$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-DirSizeGB($path) {
    try {
        $sum = (Get-ChildItem $path -Recurse -File -Force -ErrorAction SilentlyContinue |
                Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } |
                Measure-Object Length -Sum).Sum
        if ($null -eq $sum) { $sum = 0 }
        return [math]::Round($sum / 1GB, 2)
    } catch { return -1 }
}

Write-Host "=== WindowsApps drilldown (top 15) ==="
$w = @()
Get-ChildItem "C:\Program Files\WindowsApps" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $w += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
}
$w | Sort-Object SizeGB -Descending | Select-Object -First 15 | Format-Table -AutoSize

Write-Host "=== Roaming\Code drilldown ==="
$c = @()
Get-ChildItem "$env:APPDATA\Code" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $c += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
}
$c | Sort-Object SizeGB -Descending | Select-Object -First 8 | Format-Table -AutoSize

Write-Host "--- Code\User drilldown ---"
$cu = @()
Get-ChildItem "$env:APPDATA\Code\User" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $cu += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
}
$cu | Sort-Object SizeGB -Descending | Select-Object -First 6 | Format-Table -AutoSize

Write-Host "=== .local\share drilldown ==="
$s = @()
Get-ChildItem "C:\Users\Admin\.local\share" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $s += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
}
$s | Sort-Object SizeGB -Descending | Select-Object -First 8 | Format-Table -AutoSize

Write-Host "=== Local\Google drilldown ==="
$g = @()
Get-ChildItem "$env:LOCALAPPDATA\Google" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $g += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
}
$g | Sort-Object SizeGB -Descending | Select-Object -First 6 | Format-Table -AutoSize

Write-Host "=== .codex\.tmp contents ==="
Get-DirSizeGB "C:\Users\Admin\.codex\.tmp" | ForEach-Object { Write-Host ("{0} GB" -f $_) }

Write-Host "=== Local\Packages drilldown (top 8) ==="
$p = @()
Get-ChildItem "$env:LOCALAPPDATA\Packages" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $p += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
}
$p | Sort-Object SizeGB -Descending | Select-Object -First 8 | Format-Table -AutoSize
