$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-DirSizeGB($path) {
    if (-not (Test-Path $path)) { return 0 }
    $sum = (Get-ChildItem $path -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    if ($null -eq $sum) { $sum = 0 }
    return $sum / 1GB
}

Write-Host "=== What is in the 110 folders (SQL 2012-era, check before touching) ==="
foreach ($p in @("C:\Program Files\Microsoft SQL Server\110", "C:\Program Files (x86)\Microsoft SQL Server\110")) {
    Write-Host "--- $p ---"
    Get-ChildItem $p -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        Write-Host ("  {0,-30} {1,6:N2} GB" -f $_.Name, (Get-DirSizeGB $_.FullName))
    }
}
Write-Host "=== x86 subfolder sizes ==="
Get-ChildItem "C:\Program Files (x86)\Microsoft SQL Server" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host ("  {0,-30} {1,6:N2} GB" -f $_.Name, (Get-DirSizeGB $_.FullName))
}

Write-Host ""
Write-Host ">>> Deleting SQL 2000/2005/2008R2 leftovers (80/90/100)"
foreach ($p in @(
    "C:\Program Files (x86)\Microsoft SQL Server\80",
    "C:\Program Files (x86)\Microsoft SQL Server\90",
    "C:\Program Files (x86)\Microsoft SQL Server\100",
    "C:\Program Files\Microsoft SQL Server\90",
    "C:\Program Files\Microsoft SQL Server\100",
    "$env:APPDATA\Microsoft\Microsoft SQL Server"
)) {
    if (Test-Path $p) {
        $b = Get-DirSizeGB $p
        Remove-Item $p -Recurse -Force -ErrorAction SilentlyContinue
        $a = Get-DirSizeGB $p
        Write-Host ("  [ok] {0} freed {1:N2} GB" -f $p, ($b - $a))
    } else { Write-Host "  [skip] $p" }
}

Write-Host ""
Write-Host ">>> Removing SQL 2008 R2 orphan registry entries"
$regRoots = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall'
)
foreach ($root in $regRoots) {
    Get-ChildItem $root -ErrorAction SilentlyContinue | ForEach-Object {
        $props = Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue
        $name = $props.DisplayName
        if ($name -and ($name -like '*SQL Server 2008*' -or $name -eq 'Microsoft SQL Server 10')) {
            Remove-Item $_.PSPath -Recurse -Force -ErrorAction SilentlyContinue
            Write-Host ("    removed: {0}" -f $name)
        }
    }
}

# Instance registration for the dead MSSQL10_50 instance
if (Test-Path 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL10_50.MSSQLSERVER') {
    Remove-Item 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\MSSQL10_50.MSSQLSERVER' -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "    removed: HKLM MSSQL10_50.MSSQLSERVER instance key"
}
$in = 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\Instance Names\SQL'
if (Test-Path $in) {
    Remove-ItemProperty $in -Name 'MSSQLSERVER' -ErrorAction SilentlyContinue
    Write-Host "    removed: Instance Names\MSSQLSERVER value"
}

Write-Host ""
Write-Host ("C: free: {0:N2} GB" -f ((Get-PSDrive C).Free/1GB))
