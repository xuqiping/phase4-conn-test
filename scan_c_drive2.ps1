$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-DirSizeGB($path) {
    try {
        $sum = (Get-ChildItem $path -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        if ($null -eq $sum) { $sum = 0 }
        return [math]::Round($sum / 1GB, 2)
    } catch { return -1 }
}

Write-Host "=== AppData\Local subdirs (top 20) ==="
$r = @()
Get-ChildItem "$env:LOCALAPPDATA" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $r += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Path = $_.FullName }
}
$r | Sort-Object SizeGB -Descending | Select-Object -First 20 | Format-Table -AutoSize

Write-Host "=== AppData\Roaming subdirs (top 15) ==="
$r2 = @()
Get-ChildItem "$env:APPDATA" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $r2 += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Path = $_.FullName }
}
$r2 | Sort-Object SizeGB -Descending | Select-Object -First 15 | Format-Table -AutoSize

Write-Host "=== C:\ root top-level dirs (top 10) ==="
$r3 = @()
Get-ChildItem "C:\" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $r3 += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Path = $_.FullName }
}
$r3 | Sort-Object SizeGB -Descending | Select-Object -First 10 | Format-Table -AutoSize

Write-Host "=== C:\ root large files (pagefile/hiberfil/swapfile) ==="
Get-ChildItem "C:\" -File -Force -ErrorAction SilentlyContinue | Sort-Object Length -Descending | Select-Object -First 8 @{n='SizeGB';e={[math]::Round($_.Length/1GB,2)}}, FullName | Format-Table -AutoSize
