<#
.SYNOPSIS
    C: drive cleaner and disk-usage scanner (use when C: is full)
.DESCRIPTION
    Phase 1 = emergency free-up (only safe temp/cache files + recycle bin)
    Phase 2 = scan largest folders and write a report
.NOTES
    Run in PowerShell:
      powershell -ExecutionPolicy Bypass -File "d:\AI Projects\C盘清理工具.ps1"
#>

$ErrorActionPreference = 'SilentlyContinue'
$report = "d:\AI Projects\disk_report.txt"
"===== C: Drive Cleanup Report =====" | Out-File $report -Force

function Get-Size($path) {
    if (-not (Test-Path $path)) { return 0 }
    $s = (Get-ChildItem $path -Recurse -File -Force -EA SilentlyContinue | Measure-Object Length -Sum).Sum
    if ($null -eq $s) { 0 } else { $s }
}
function fmt($b) { "{0,9:N2} GB" -f ($b / 1GB) }

# ============ Phase 1: Emergency free-up ============
Write-Host "`n========== Phase 1: Emergency free-up ==========`n" -ForegroundColor Cyan

function CleanDir($name, $path, $pattern = '*') {
    if (Test-Path $path) {
        $size = Get-Size $path
        Write-Host ("  {0,-24} before {1}" -f $name, (fmt $size)) -ForegroundColor White
        Get-ChildItem $path -Filter $pattern -Force -EA SilentlyContinue |
            Remove-Item -Recurse -Force -EA SilentlyContinue
        $after = Get-Size $path
        $freed = $size - $after
        Write-Host ("  {0,-24} freed  {1}`n" -f '', (fmt $freed)) -ForegroundColor Green
        "Cleaned $name : freed $(fmt $freed)" | Out-File $report -Append
    } else {
        Write-Host ("  {0,-24} not found, skip`n" -f $name) -ForegroundColor DarkGray
    }
}

CleanDir "User Temp"             "$env:TEMP"
CleanDir "Claude Temp"           "$env:LOCALAPPDATA\Temp\claude"
CleanDir "Windows Temp"          "C:\Windows\Temp"
CleanDir "Windows Update DL"     "C:\Windows\SoftwareDistribution\Download"
CleanDir "Delivery Optimization" "C:\Windows\SoftwareDistribution\DeliveryOptimization"
CleanDir "Error Reports (WER)"   "C:\ProgramData\Microsoft\Windows\WER"
CleanDir "Thumbnail Cache"       "$env:LOCALAPPDATA\Microsoft\Windows\Explorer" "thumbcache_*.db"
CleanDir "Font Cache"            "C:\Windows\ServiceProfiles\LocalService\AppData\Local\FontCache"

Write-Host "  Recycle Bin... clearing" -NoNewline -ForegroundColor White
Clear-RecycleBin -Force -EA SilentlyContinue
Write-Host "  done`n" -ForegroundColor Green
"Cleaned Recycle Bin" | Out-File $report -Append

Write-Host "Phase 1 done. Now scanning largest folders.`n" -ForegroundColor Cyan

# ============ Phase 2: Scan ============
Write-Host "========== Phase 2: Scanning (about 1-2 minutes) ==========`n" -ForegroundColor Cyan

function ScanDir($title, $path, $top = 20) {
    "`n`n========== $title ($path) ==========" | Out-File $report -Append
    Write-Host "`n--- $title ---" -ForegroundColor Yellow
    Get-ChildItem $path -Directory -Force -EA SilentlyContinue | ForEach-Object {
        $s = Get-Size $_.FullName
        [PSCustomObject]@{ Folder = $_.FullName; Size = fmt $s; Bytes = $s }
    } | Sort-Object Bytes -Descending | Select-Object -First $top | ForEach-Object {
        $line = "{0,-70} {1}" -f $_.Folder, $_.Size
        $line | Out-File $report -Append
        Write-Host $line
    }
}

ScanDir "C: top-level"     'C:\'                 15
ScanDir "User profile"     $env:USERPROFILE      20
ScanDir "AppData\Local"    $env:LOCALAPPDATA     25
ScanDir "AppData\Roaming"  $env:APPDATA          20
ScanDir "ProgramData"      'C:\ProgramData'      20

$d = Get-PSDrive C
"`n`n===== Current C: state =====" | Out-File $report -Append
$state = "Used {0:N2} GB / Free {1:N2} GB / Total {2:N2} GB" -f ($d.Used/1GB), ($d.Free/1GB), (($d.Used+$d.Free)/1GB)
$state | Out-File $report -Append
Write-Host "`n$state" -ForegroundColor Green

Write-Host "`nReport saved: $report" -ForegroundColor Green
Write-Host "Send me the report content (or just the top folders), and I will tell you what is safe to delete.`n" -ForegroundColor Cyan
