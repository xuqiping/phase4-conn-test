@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" x64
echo === LIB ===
echo %LIB%
echo === INCLUDE ===
echo %INCLUDE%
echo === PATH-FIRST200 ===
echo %PATH:~0,500%
