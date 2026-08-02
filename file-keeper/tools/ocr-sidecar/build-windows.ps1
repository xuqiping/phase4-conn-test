$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Venv = Join-Path $Root ".venv"
$Dist = Join-Path $Root "dist"

python -m venv $Venv
& "$Venv\Scripts\python.exe" -m pip install --upgrade pip
& "$Venv\Scripts\pip.exe" install -r (Join-Path $Root "requirements.txt")
& "$Venv\Scripts\pyinstaller.exe" --onefile --name file-keeper-ocr (Join-Path $Root "file_keeper_ocr.py")

Write-Host "Built OCR sidecar at $Dist\file-keeper-ocr.exe"
Write-Host "Copy it to: <File Keeper install directory>\ocr\file-keeper-ocr.exe"
