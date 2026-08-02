@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" x64

REM Manually add Windows SDK paths (vcvarsall failed to do so because vswhere.exe is missing)
set "WINSDK_VER=10.0.26100.0"
set "WINSDK_ROOT=C:\Program Files (x86)\Windows Kits\10"
set "LIB=%LIB%;%WINSDK_ROOT%\Lib\%WINSDK_VER%\um\x64;%WINSDK_ROOT%\Lib\%WINSDK_VER%\ucrt\x64"
set "INCLUDE=%INCLUDE%;%WINSDK_ROOT%\Include\%WINSDK_VER%\um;%WINSDK_ROOT%\Include\%WINSDK_VER%\shared;%WINSDK_ROOT%\Include\%WINSDK_VER%\ucrt;%WINSDK_ROOT%\Include\%WINSDK_VER%\winrt"
set "PATH=%WINSDK_ROOT%\bin\%WINSDK_VER%\x64;%USERPROFILE%\.cargo\bin;%PATH%"

cd /d "C:\AI Projects\file-keeper"
npm run tauri:dev
