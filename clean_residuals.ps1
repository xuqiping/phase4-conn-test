$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-DirSizeGB($path) {
    if (-not (Test-Path $path)) { return 0 }
    $sum = (Get-ChildItem $path -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    if ($null -eq $sum) { $sum = 0 }
    return $sum / 1GB
}
function Remove-Target($path, $label) {
    if (-not (Test-Path $path)) { Write-Host "[skip] $label"; return }
    $before = Get-DirSizeGB $path
    Remove-Item $path -Recurse -Force -ErrorAction SilentlyContinue
    $after = Get-DirSizeGB $path
    Write-Host ("[ok] {0,-45} freed {1,6:N2} GB" -f $label, ($before - $after))
}

# 1. BlueStacks leftover folder + ProgramData
Get-Process -Name "*BlueStacks*","*HD-*","*Bstk*" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Remove-Target "C:\Program Files\BlueStacks_nxt_cn" "BlueStacks_nxt_cn folder"
Remove-Target "C:\ProgramData\BlueStacks_nxt_cn" "BlueStacks ProgramData"
Remove-Target "C:\ProgramData\BlueStacks" "BlueStacks ProgramData (alt)"

# 2. Appium: try uninstaller once more, then force-remove folder
Write-Host ">>> Appium uninstaller retry"
$ap = "C:\Program Files\Appium Server GUI\Uninstall Appium Server GUI.exe"
if (Test-Path $ap) {
    $proc = Start-Process -FilePath $ap -ArgumentList '/allusers','/S' -PassThru
    $proc.WaitForExit(120000)
    Start-Sleep -Seconds 5
}
Remove-Target "C:\Program Files\Appium Server GUI" "Appium folder"
Remove-Target "$env:APPDATA\Appium Server GUI" "Appium roaming data"

# 3. Power BI MSI version on D:
Remove-Target "D:\software\powerBI" "Power BI Desktop (D:\software\powerBI)"
Remove-Target "$env:LOCALAPPDATA\Microsoft\Power BI Desktop" "Power BI local data"
Remove-Target "$env:LOCALAPPDATA\Microsoft\Power BI Desktop Store App" "Power BI store data"
Remove-Target "$env:APPDATA\Microsoft\Power BI Desktop" "Power BI roaming data"

# 4. iFLY roaming retry
Remove-Target "$env:APPDATA\iFLYAssistant" "iFLYAssistant roaming"

# 5. Remove orphaned registry uninstall entries
Write-Host ""
Write-Host ">>> Removing orphan registry entries"
$regRoots = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall'
)
foreach ($root in $regRoots) {
    Get-ChildItem $root -ErrorAction SilentlyContinue | ForEach-Object {
        $props = Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue
        $name = $props.DisplayName
        if ($name -and ($name -like '*BlueStacks*' -or $name -like '*Appium Server GUI*' -or $name -eq 'Microsoft Power BI Desktop (x64)' -or $name -like '*Edge Dev*')) {
            Remove-Item $_.PSPath -Recurse -Force -ErrorAction SilentlyContinue
            Write-Host ("    removed reg entry: {0}" -f $name)
        }
    }
}

Write-Host ""
Write-Host ("C: free: {0:N2} GB" -f ((Get-PSDrive C).Free/1GB))
$d = Get-PSDrive D -ErrorAction SilentlyContinue
if ($d) { Write-Host ("D: free: {0:N2} GB" -f ($d.Free/1GB)) }
