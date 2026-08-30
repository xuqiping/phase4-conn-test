$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$paths = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
$keywords = @('Power BI','Edge Dev','BlueStacks','Nsight','SQL Server 2008','Appium','iFLY','讯飞')
$entries = Get-ItemProperty $paths -ErrorAction SilentlyContinue | Where-Object {
    $n = $_.DisplayName
    if (-not $n) { return $false }
    foreach ($k in $keywords) { if ($n -like "*$k*") { return $true } }
    return $false
}
$entries | Sort-Object DisplayName | ForEach-Object {
    Write-Host ("NAME: {0}" -f $_.DisplayName)
    Write-Host ("  KEY:  {0}" -f $_.PSChildName)
    Write-Host ("  UNI:  {0}" -f $_.UninstallString)
    Write-Host ("  QUIET:{0}" -f $_.QuietUninstallString)
    Write-Host ""
}

Write-Host "=== Appx: Power BI / Codex store packages ==="
Get-AppxPackage -Name "*PowerBI*","*Codex*" -ErrorAction SilentlyContinue | Select-Object Name, PackageFullName | Format-List
