try {
    Clear-RecycleBin -Force -ErrorAction Stop
    Write-Host "Clear-RecycleBin: OK"
} catch {
    Write-Host "Clear-RecycleBin failed: $($_.Exception.Message)"
    # Fallback: delete contents of each user's recycle bin folder
    Get-ChildItem 'C:\$Recycle.Bin' -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
        Get-ChildItem $_.FullName -Force -ErrorAction SilentlyContinue | ForEach-Object {
            Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    Write-Host "Manual cleanup attempted"
}
$s = (Get-ChildItem 'C:\$Recycle.Bin' -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
if ($null -eq $s) { $s = 0 }
Write-Host ("Recycle Bin size now: {0:N2} GB" -f ($s/1GB))
Write-Host ("C: free: {0:N2} GB" -f ((Get-PSDrive C).Free/1GB))
