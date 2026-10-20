@echo off
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
cl /nologo /EHsc /std:c++14 /W4 /O2 D:\git\RHRemote\client\test-cadence.cpp /Fe:D:\RHRemoteTools\test-cadence.exe /Fo:D:\RHRemoteTools\test-cadence.obj
if errorlevel 1 exit /b 1
D:\RHRemoteTools\test-cadence.exe
