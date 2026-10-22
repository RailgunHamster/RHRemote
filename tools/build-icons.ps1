$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$qt='D:\RHRemoteTools\Qt\6.7.2\msvc2019_64'
$helper='D:\RHRemoteTools\icon-builder'
New-Item -ItemType Directory -Force -Path $helper|Out-Null
$batch=Join-Path $helper 'build.cmd'
$commands=@"
@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
if errorlevel 1 exit /b 1
cd /d "$helper"
cl /nologo /EHsc /std:c++17 /Zc:__cplusplus /permissive- /MD /I"$qt\include" /I"$qt\include\QtCore" /I"$qt\include\QtGui" /I"$qt\include\QtSvg" "$repo\tools\render-icons.cpp" /Fe:render-icons.exe /link /LIBPATH:"$qt\lib" Qt6Core.lib Qt6Gui.lib Qt6Svg.lib
exit /b %errorlevel%
"@
[IO.File]::WriteAllText($batch,$commands,[Text.Encoding]::ASCII)
& cmd /d /c $batch
if($LASTEXITCODE -ne 0){throw 'Icon renderer build failed'}
$previousPath=$env:PATH
try{$env:PATH="$qt\bin;$previousPath";& (Join-Path $helper 'render-icons.exe') (Join-Path $repo 'client/shared/icons/rhremote.svg') (Join-Path $repo 'client/shared/icons/rhremote.png');if($LASTEXITCODE -ne 0){throw 'SVG rendering failed'}}finally{$env:PATH=$previousPath}
& C:\tools\Miniconda3\python.exe (Join-Path $repo 'tools/derive-icons.py')
if($LASTEXITCODE -ne 0){throw 'Icon derivation failed'}
