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

Write-Host "=== C:\Program Files (top 20) ==="
$a = @()
Get-ChildItem "C:\Program Files" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $a += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
}
$a | Sort-Object SizeGB -Descending | Select-Object -First 20 | Format-Table -AutoSize

Write-Host "=== C:\Program Files (x86) (top 20) ==="
$b = @()
Get-ChildItem "C:\Program Files (x86)" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $b += [PSCustomObject]@{ SizeGB = (Get-DirSizeGB $_.FullName); Name = $_.Name }
}
$b | Sort-Object SizeGB -Descending | Select-Object -First 20 | Format-Table -AutoSize

Write-Host "=== Installed programs from registry (>= 300MB) ==="
$paths = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
Get-ItemProperty $paths -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -and $_.EstimatedSize -ge 307200 } |
    Select-Object @{n='SizeGB';e={[math]::Round($_.EstimatedSize/1MB,2)}}, DisplayName, InstallLocation |
    Sort-Object SizeGB -Descending | Format-Table -AutoSize -Wrap
