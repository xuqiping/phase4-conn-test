$s = (Get-ChildItem 'C:\$Recycle.Bin' -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
if ($null -eq $s) { $s = 0 }
Write-Host ("Recycle Bin size: {0:N2} GB" -f ($s/1GB))
Write-Host ("C: free: {0:N2} GB" -f ((Get-PSDrive C).Free/1GB))
