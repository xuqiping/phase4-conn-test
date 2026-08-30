$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

foreach ($p in @("C:\Program Files\Microsoft SQL Server", "C:\Program Files (x86)\Microsoft SQL Server")) {
    Write-Host "=== $p ==="
    if (Test-Path $p) {
        $sum = (Get-ChildItem $p -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        if ($null -eq $sum) { $sum = 0 }
        Write-Host ("  size: {0:N2} GB" -f ($sum/1GB))
        Get-ChildItem $p -ErrorAction SilentlyContinue | Select-Object Name | Format-Table -AutoSize
    } else { Write-Host "  NOT FOUND" }
}
Write-Host "=== SQL data dirs (ProgramData / user) ==="
foreach ($p in @("C:\ProgramData\Microsoft\Microsoft SQL Server", "$env:APPDATA\Microsoft\Microsoft SQL Server", "$env:LOCALAPPDATA\Microsoft\Microsoft SQL Server")) {
    Write-Host ("{0} : {1}" -f $p, (Test-Path $p))
}
