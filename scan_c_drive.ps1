$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-DirSizeGB($path) {
    if (-not (Test-Path $path)) { return $null }
    try {
        $sum = (Get-ChildItem $path -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        if ($null -eq $sum) { $sum = 0 }
        return [math]::Round($sum / 1GB, 2)
    } catch { return -1 }
}

Write-Host "=== C drive space ==="
$c = Get-PSDrive C
Write-Host ("Used: {0} GB  Free: {1} GB" -f [math]::Round($c.Used/1GB,1), [math]::Round($c.Free/1GB,1))

Write-Host ""
Write-Host "=== Common temp/cache locations ==="
$targets = @(
    "$env:LOCALAPPDATA\Temp",
    "C:\Windows\Temp",
    "C:\Windows\SoftwareDistribution\Download",
    "C:\Windows\Logs\CBS",
    "$env:USERPROFILE\Downloads",
    "$env:LOCALAPPDATA\pip\cache",
    "$env:LOCALAPPDATA\npm-cache",
    "$env:APPDATA\npm-cache",
    "$env:USERPROFILE\.npm",
    "$env:USERPROFILE\.cache",
    "$env:USERPROFILE\.cargo",
    "$env:USERPROFILE\.rustup",
    "$env:LOCALAPPDATA\Yarn\Cache",
    "$env:LOCALAPPDATA\pnpm-cache",
    "$env:USERPROFILE\.gradle",
    "$env:USERPROFILE\.m2",
    "$env:LOCALAPPDATA\Google\Chrome\User Data\Default\Cache",
    "$env:LOCALAPPDATA\Microsoft\Edge\User Data\Default\Cache",
    "C:\Windows\Installer",
    "$env:LOCALAPPDATA\Microsoft\Windows\INetCache",
    "C:\ProgramData\Package Cache",
    "C:\hiberfil.sys",
    "C:\pagefile.sys",
    "C:\swapfile.sys"
)
$results = @()
foreach ($t in $targets) {
    if (Test-Path $t) {
        $item = Get-Item $t -Force
        if ($item.PSIsContainer) {
            $size = Get-DirSizeGB $t
        } else {
            $size = [math]::Round($item.Length / 1GB, 2)
        }
        $results += [PSCustomObject]@{ SizeGB = $size; Path = $t }
    }
}
$results | Sort-Object SizeGB -Descending | Format-Table -AutoSize

Write-Host ""
Write-Host "=== Top-level dirs under C:\Users\Admin (this may take a while) ==="
$u = @()
Get-ChildItem "C:\Users\Admin" -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $size = Get-DirSizeGB $_.FullName
    $u += [PSCustomObject]@{ SizeGB = $size; Path = $_.FullName }
}
$u | Sort-Object SizeGB -Descending | Select-Object -First 15 | Format-Table -AutoSize
