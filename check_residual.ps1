$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-DirSizeGB($path) {
    if (-not (Test-Path $path)) { return -1 }
    $sum = (Get-ChildItem $path -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    if ($null -eq $sum) { $sum = 0 }
    return [math]::Round($sum / 1GB, 2)
}

Write-Host "=== Folder sizes after uninstall ==="
foreach ($p in @("C:\Program Files\BlueStacks_nxt_cn", "C:\Program Files\Appium Server GUI", "$env:APPDATA\iFLYAssistant")) {
    Write-Host ("{0} GB  {1}" -f (Get-DirSizeGB $p), $p)
}

Write-Host ""
Write-Host "=== BlueStacks folder top-level ==="
Get-ChildItem "C:\Program Files\BlueStacks_nxt_cn" -ErrorAction SilentlyContinue | Select-Object -First 15 Name | Format-Table -AutoSize

Write-Host "=== BlueStacks services/processes ==="
Get-Service -Name "*BlueStacks*","*Bstk*" -ErrorAction SilentlyContinue | Select-Object Name, Status | Format-Table -AutoSize

Write-Host "=== Registry entries still present? ==="
$paths = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
Get-ItemProperty $paths -ErrorAction SilentlyContinue | Where-Object {
    $_.DisplayName -and ($_.DisplayName -like '*BlueStacks*' -or $_.DisplayName -like '*Appium*' -or $_.DisplayName -like '*Power BI*' -or $_.DisplayName -like '*Edge Dev*')
} | ForEach-Object { Write-Host ("  still registered: {0} [{1}]" -f $_.DisplayName, $_.InstallLocation) }

Write-Host ""
Write-Host "=== Searching for PBIDesktop.exe (Power BI MSI install location) ==="
foreach ($root in @("C:\Program Files", "C:\Program Files (x86)", "D:\Program Files", "D:\software", "D:\IT")) {
    Get-ChildItem $root -Recurse -Filter "PBIDesktop.exe" -Depth 3 -ErrorAction SilentlyContinue | Select-Object -First 3 FullName
}

Write-Host "=== iFLYAssistant roaming contents (locked files?) ==="
Get-ChildItem "$env:APPDATA\iFLYAssistant" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 10 FullName | Format-Table -AutoSize -Wrap
