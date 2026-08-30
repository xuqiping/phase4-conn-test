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

Write-Host "=== C:\Users\Admin top-level (junctions excluded) ==="
$t = @()
Get-ChildItem "C:\Users\Admin" -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.PSIsContainer -and -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } |
    ForEach-Object {
        $t += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
    }
$t | Sort-Object SizeGB -Descending | Format-Table -AutoSize

Write-Host "=== AppData\Local\Microsoft drilldown (top 10) ==="
$m = @()
Get-ChildItem "$env:LOCALAPPDATA\Microsoft" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $m += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
}
$m | Sort-Object SizeGB -Descending | Select-Object -First 10 | Format-Table -AutoSize

Write-Host "=== AppData\Local remaining >= 0.2 GB (post-cleanup) ==="
$l = @()
Get-ChildItem "$env:LOCALAPPDATA" -Directory -Force -ErrorAction SilentlyContinue |
    Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } |
    ForEach-Object {
        $l += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
    }
$l | Where-Object { $_.SizeGB -ge 0.2 } | Sort-Object SizeGB -Descending | Format-Table -AutoSize

Write-Host "=== AppData\Roaming >= 0.15 GB (post-cleanup) ==="
$r = @()
Get-ChildItem "$env:APPDATA" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $r += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
}
$r | Where-Object { $_.SizeGB -ge 0.15 } | Sort-Object SizeGB -Descending | Format-Table -AutoSize

Write-Host "=== .vscode / .local / .codex / .lingma drilldown ==="
foreach ($d in @('.vscode','.local','.codex','.lingma')) {
    $base = "C:\Users\Admin\$d"
    if (Test-Path $base) {
        Write-Host "--- $d ---"
        $x = @()
        Get-ChildItem $base -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
            $x += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
        }
        $x | Sort-Object SizeGB -Descending | Select-Object -First 5 | Format-Table -AutoSize
    }
}

Write-Host "=== Documents / Desktop / Videos large files >= 100MB ==="
Get-ChildItem "C:\Users\Admin\Documents","C:\Users\Admin\Desktop","C:\Users\Admin\Videos","C:\Users\Admin\Downloads" -Recurse -File -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.Length -ge 100MB } |
    Sort-Object Length -Descending | Select-Object -First 20 @{n='SizeMB';e={[math]::Round($_.Length/1MB,0)}}, FullName |
    Format-Table -AutoSize -Wrap
