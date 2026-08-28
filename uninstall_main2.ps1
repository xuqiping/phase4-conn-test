$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Run-Uninstall($label, $exe, $argList) {
    Write-Host ">>> $label"
    if ($exe -ne 'msiexec' -and -not (Test-Path $exe)) { Write-Host "    [skip] not found: $exe"; return }
    try {
        $p = Start-Process -FilePath $exe -ArgumentList $argList -Wait -PassThru -ErrorAction Stop
        Write-Host ("    exit code: {0}" -f $p.ExitCode)
    } catch {
        Write-Host ("    [error] {0}" -f $_.Exception.Message)
    }
}

Run-Uninstall "Power BI Desktop (MSI)" "msiexec" '/X{1b402ce6-2b52-4984-bbc9-f77b4d491a87} /qn /norestart'
Run-Uninstall "Microsoft Edge Dev" "C:\Program Files (x86)\Microsoft\Edge Dev\Application\152.0.4191.7\Installer\setup.exe" @('--uninstall','--msedge-dev','--system-level','--force-uninstall','--verbose-logging')
Run-Uninstall "Nsight Compute 2022.3.0" "msiexec" '/X{E9655DE8-6B03-4792-B81D-4D57722ED17C} /qn /norestart'
Run-Uninstall "Nsight Systems 2022.4.2" "msiexec" '/X{000420DE-0A08-46D7-A941-E6120CB6D9CA} /qn /norestart'
Run-Uninstall "Nsight Visual Studio Edition 2022.3.0" "msiexec" '/X{EDE170F5-0149-4255-90C1-2CB7B2EC1E8B} /qn /norestart'
Run-Uninstall "Appium Server GUI" "C:\Program Files\Appium Server GUI\Uninstall Appium Server GUI.exe" @('/allusers','/S')
Run-Uninstall "BlueStacks 5 China" "C:\Program Files\BlueStacks_nxt_cn\BlueStacksUninstaller.exe" @('-tmp','/S')

# retry iFLY roaming leftover
if (Test-Path "$env:APPDATA\iFLYAssistant") {
    Remove-Item "$env:APPDATA\iFLYAssistant" -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host (">>> iFLYAssistant roaming deleted: {0}" -f (-not (Test-Path "$env:APPDATA\iFLYAssistant")))
}

Write-Host ""
Write-Host ("C: free: {0:N2} GB" -f ((Get-PSDrive C).Free/1GB))
Write-Host "=== post-check ==="
foreach ($chk in @(
    "C:\Program Files (x86)\Microsoft\Edge Dev",
    "C:\Program Files\BlueStacks_nxt_cn",
    "C:\Program Files\Appium Server GUI",
    "C:\Program Files\NVIDIA Corporation\Nsight Compute 2022.3.0",
    "C:\Program Files\NVIDIA Corporation\Nsight Systems 2022.4.2",
    "C:\Program Files\Microsoft Power BI Desktop"
)) {
    Write-Host ("{0} : exists={1}" -f $chk, (Test-Path $chk))
}
