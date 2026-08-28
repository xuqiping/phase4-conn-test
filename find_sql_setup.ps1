$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Write-Host "=== setup binaries under bootstrap ==="
Get-ChildItem 'C:\Program Files\Microsoft SQL Server\100\Setup Bootstrap' -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'setup*' -or $_.Name -like '*ARP*' } |
    Select-Object FullName | Format-Table -AutoSize -Wrap
Write-Host "=== bootstrap top-level ==="
Get-ChildItem 'C:\Program Files\Microsoft SQL Server\100\Setup Bootstrap' -ErrorAction SilentlyContinue | Select-Object Name | Format-Table -AutoSize
