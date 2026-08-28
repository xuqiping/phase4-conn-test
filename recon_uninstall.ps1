$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "=== SQL Server instance names ==="
$inst = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\Instance Names\SQL' -ErrorAction SilentlyContinue
if ($inst) { $inst.PSObject.Properties | Where-Object { $_.Name -notlike 'PS*' } | ForEach-Object { Write-Host ("  {0} -> {1}" -f $_.Name, $_.Value) } } else { Write-Host "  (none found)" }

Write-Host "=== SQL Server services ==="
Get-Service -Name "MSSQL*","SQLAgent*","MsDtsServer*","ReportServer*","MSSQLServerOLAPService" -ErrorAction SilentlyContinue | Select-Object Name, Status, StartType | Format-Table -AutoSize

Write-Host "=== Search registry for iFLY ==="
$paths = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
Get-ItemProperty $paths -ErrorAction SilentlyContinue | Where-Object {
    ($_.DisplayName -and ($_.DisplayName -like '*iFLY*' -or $_.DisplayName -like '*讯飞*' -or $_.DisplayName -like '*科大*')) -or
    ($_.InstallLocation -and $_.InstallLocation -like '*iFLY*') -or
    ($_.Publisher -and $_.Publisher -like '*iFLY*')
} | ForEach-Object {
    Write-Host ("NAME: {0}" -f $_.DisplayName)
    Write-Host ("  KEY:  {0}" -f $_.PSChildName)
    Write-Host ("  UNI:  {0}" -f $_.UninstallString)
    Write-Host ("  LOC:  {0}" -f $_.InstallLocation)
}

Write-Host "=== iFLYAssistant4 folder contents (top level) ==="
Get-ChildItem "C:\Program Files (x86)\iFLYAssistant4" -ErrorAction SilentlyContinue | Select-Object Name | Format-Table -AutoSize

Write-Host "=== BlueStacks uninstaller exists? ==="
Test-Path "C:\Program Files\BlueStacks_nxt_cn\BlueStacksUninstaller.exe"
Get-ChildItem "C:\Program Files\BlueStacks_nxt_cn" -Filter "*ninstall*" -ErrorAction SilentlyContinue | Select-Object Name

Write-Host "=== setup100.exe exists for SQL 2008 R2? ==="
Test-Path "C:\Program Files\Microsoft SQL Server\100\Setup Bootstrap\SQLServer2008R2\x64\setup100.exe"
