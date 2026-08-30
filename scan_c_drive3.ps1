$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-DirSizeGB($path) {
    try {
        # Skip reparse points (junctions/symlinks) to avoid double counting
        $sum = (Get-ChildItem $path -Recurse -File -Force -ErrorAction SilentlyContinue |
                Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } |
                Measure-Object Length -Sum).Sum
        if ($null -eq $sum) { $sum = 0 }
        return [math]::Round($sum / 1GB, 2)
    } catch { return -1 }
}

Write-Host "=== AppData\Local ALL subdirs >= 0.15 GB (junctions excluded) ==="
$r = @()
Get-ChildItem "$env:LOCALAPPDATA" -Directory -Force -ErrorAction SilentlyContinue |
    Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } |
    ForEach-Object {
        $r += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Path = $_.FullName }
    }
$r | Where-Object { $_.SizeGB -ge 0.15 } | Sort-Object SizeGB -Descending | Format-Table -AutoSize
Write-Host ("Local real total: {0} GB" -f [math]::Round(($r | Measure-Object SizeGB -Sum).Sum,2))

Write-Host "=== AppData\LocalLow subdirs ==="
$r0 = @()
Get-ChildItem "$env:USERPROFILE\AppData\LocalLow" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $r0 += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Path = $_.FullName }
}
$r0 | Sort-Object SizeGB -Descending | Select-Object -First 8 | Format-Table -AutoSize

Write-Host "=== C:\Windows subdirs (top 15) ==="
$w = @()
Get-ChildItem "C:\Windows" -Directory -Force -ErrorAction SilentlyContinue |
    Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } |
    ForEach-Object {
        $w += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Path = $_.FullName }
    }
$w | Sort-Object SizeGB -Descending | Select-Object -First 15 | Format-Table -AutoSize

Write-Host "=== Large files >= 300MB under C:\Users\Admin (top 25) ==="
Get-ChildItem "C:\Users\Admin" -Recurse -File -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.Length -ge 300MB -and -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } |
    Sort-Object Length -Descending | Select-Object -First 25 @{n='SizeGB';e={[math]::Round($_.Length/1GB,2)}}, FullName |
    Format-Table -AutoSize -Wrap
