$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Delete requested data folders (items 8-12)
$freedTotal = 0.0
function Remove-Target($path, $label) {
    if (-not (Test-Path $path)) { Write-Host "[skip] $label (not found)"; return }
    $sum = (Get-ChildItem $path -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    if ($null -eq $sum) { $sum = 0 }
    Remove-Item $path -Recurse -Force -ErrorAction SilentlyContinue
    $after = (Get-ChildItem $path -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    if ($null -eq $after) { $after = 0 }
    $freed = ($sum - $after) / 1GB
    $script:freedTotal += $freed
    Write-Host ("[ok] {0,-40} freed {1,6:N2} GB" -f $label, $freed)
}

Remove-Target "$env:APPDATA\Code\Crashpad" "Code Crashpad"
Remove-Target "C:\Users\Admin\.codex\.tmp" ".codex .tmp"
Remove-Target "C:\Users\Admin\.local\share\TeleAgent" ".local share TeleAgent"
Remove-Target "$env:LOCALAPPDATA\Microsoft\Olk" "New Outlook (Olk) data"
Remove-Target "C:\Users\Admin\.lingma\vscode" ".lingma vscode"

Write-Host ("Folders freed: {0:N2} GB" -f $freedTotal)
Write-Host ("C: free: {0:N2} GB" -f ((Get-PSDrive C).Free/1GB))
