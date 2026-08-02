@echo off
echo ===============
echo Building File Keeper v0.1.0 MSI Package (with Proxy)
echo ====================
echo.

REM Configure proxy for downloads
echo Setting up proxy (127.0.0.1:7892)...
set HTTP_PROXY=http://127.0.0.1:7892
set HTTPS_PROXY=http://127.0.0.1:7892
set http_proxy=http://127.0.0.1:7892
set https_proxy=http://127.0.0.1:7892
echo Proxy configured.
echo.

REM Step 1: Setup MSVC environment
echo [1/4] Setting up MSVC environment...
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" x64

REM Manually add Windows SDK paths (vcvarsall failed to do so because vswhere.exe is missing)
set "WINSDK_VER=10.0.26100.0"
set "WINSDK_ROOT=C:\Program Files (x86)\Windows Kits\10"
set "LIB=%LIB%;%WINSDK_ROOT%\Lib\%WINSDK_VER%\um\x64;%WINSDK_ROOT%\Lib\%WINSDK_VER%\ucrt\x64"
set "INCLUDE=%INCLUDE%;%WINSDK_ROOT%\Include\%WINSDK_VER%\um;%WINSDK_ROOT%\Include\%WINSDK_VER%\shared;%WINSDK_ROOT%\Include\%WINSDK_VER%\ucrt;%WINSDK_ROOT%\Include\%WINSDK_VER%\winrt"
set "PATH=%WINSDK_ROOT%\bin\%WINSDK_VER%\x64;%USERPROFILE%\.cargo\bin;%PATH%"

echo MSVC environment configured.
echo.

REM Step 2: Build frontend
echo [2/4] Building Vue frontend...
call npm run build
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Frontend build failed!
    exit /b 1
)
echo Frontend build complete.
echo.

REM Step 3: Build Tauri app with MSI bundle
echo [3/4] Building Tauri app and MSI installer...
call npm run tauri build
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Tauri build failed!
    exit /b 1
)
echo Tauri build complete.
echo.

REM Step 4: Show output location
echo [4/4] Build successful!
echo.
echo MSI installer location:
dir /b "src-tauri\target\release\bundle\msi\*.msi"
echo.
echo Full path:
cd src-tauri\target\release\bundle\msi && cd
echo.
echo =======================
echo Build Complete!
echo ===============
