$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Run-Uninstall($label, $exe, $args) {
    Write-Host ">>> $label"
    if ($exe -ne 'msiexec' -and -not (Test-Path $exe)) { Write-Host "    [skip] not found: $exe"; return }
    try {
        $p = Start-Process -FilePath $exe -ArgumentList $args -Wait -PassThru -ErrorAction Stop
        Write-Host ("    exit code: {0}" -f $p.ExitCode)
    } catch {
        Write-Host ("    [error] {0}" -f $_.Exception.Message)
    }
}

# 1. Power BI Desktop (MSI)
Run-Uninstall "Power BI Desktop (MSI)" "msiexec" '/X{1b402ce6-2b52-4984-bbc9-f77b4d491a87} /qn /norestart'

# 2. Power BI Desktop (Store version)
Write-Host ">>> Power BI Desktop (Store)"
$pbi = Get-AppxPackage -ErrorAction SilentlyContinue | Where-Object { $_.Name -like '*PowerBI*' }
if ($pbi) {
    $pbi | ForEach-Object {
        Write-Host ("    removing {0}" -f $_.PackageFullName)
        Remove-AppxPackage -Package $_.PackageFullName -ErrorAction SilentlyContinue
    }
    Write-Host "    done"
} else { Write-Host "    [skip] no Appx package found" }

# 3. Edge Dev
Run-Uninstall "Microsoft Edge Dev" "C:\Program Files (x86)\Microsoft\Edge Dev\Application\152.0.4191.7\Installer\setup.exe" @('--uninstall','--msedge-dev','--system-level','--force-uninstall','--verbose-logging')

# 4. NVIDIA Nsight Compute / Systems / VS Edition
Run-Uninstall "Nsight Compute 2022.3.0" "msiexec" '/X{E9655DE8-6B03-4792-B81D-4D57722ED17C} /qn /norestart'
Run-Uninstall "Nsight Systems 2022.4.2" "msiexec" '/X{000420DE-0A08-46D7-A941-E6120CB6D9CA} /qn /norestart'
Run-Uninstall "Nsight Visual Studio Edition 2022.3.0" "msiexec" '/X{EDE170F5-0149-4255-90C1-2CB7B2EC1E8B} /qn /norestart'

# 5. Appium Server GUI (NSIS silent)
Run-Uninstall "Appium Server GUI" "C:\Program Files\Appium Server GUI\Uninstall Appium Server GUI.exe" @('/allusers','/S')

# 6. BlueStacks 5 China (try silent)
Run-Uninstall "BlueStacks 5 China" "C:\Program Files\BlueStacks_nxt_cn\BlueStacksUninstaller.exe" @('-tmp','/S')

# 7. iFLYAssistant remnant folders (no registry entry, no uninstaller)
foreach ($f in @("C:\Program Files (x86)\iFLYAssistant4", "$env:APPDATA\iFLYAssistant")) {
    if (Test-Path $f) {
        Remove-Item $f -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host (">>> deleted {0} : {1}" -f $f, (-not (Test-Path $f)))
    } else { Write-Host ">>> [skip] $f not found" }
}

Write-Host ""
Write-Host ("C: free: {0:N2} GB" -f ((Get-PSDrive C).Free/1GB))

# Post-check: what remains
Write-Host "=== post-check ==="
foreach ($chk in @(
    "C:\Program Files (x86)\Microsoft\Edge Dev",
    "C:\Program Files\BlueStacks_nxt_cn",
    "C:\Program Files\Appium Server GUI",
    "C:\Program Files\NVIDIA Corporation\Nsight Compute 2022.3.0",
    "C:\Program Files\NVIDIA Corporation\Nsight Systems 2022.4.2",
    "C:\Program Files (x86)\Microsoft SQL Server",
    "C:\Program Files\Microsoft Power BI Desktop"
)) {
    Write-Host ("{0} : {1}" -f $chk, (Test-Path $chk))
}
