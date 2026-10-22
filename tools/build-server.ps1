param(
    [string]$MsysRoot='C:\msys64',
    [string]$BuildDirectory='',
    [string]$OutputDirectory='',
    [string]$Version='0.2.1',
    [int]$Jobs=3,
    [switch]$InitializeDependencies
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if(-not $BuildDirectory){$BuildDirectory=Join-Path $repo '.deps/server-build'}
if(-not $OutputDirectory){$OutputDirectory=Join-Path $repo 'dist/server'}
$cmake=Join-Path $MsysRoot 'ucrt64/bin/cmake.exe'
if(-not(Test-Path -LiteralPath $cmake)){throw 'MSYS2 UCRT64 CMake is required; see server/README.md for dependencies.'}
if($Version -notmatch '^\d+\.\d+\.\d+$' -or $Jobs-lt1){throw 'Expected a numeric version and a positive job count'}
$dependencies=@('build-deps','libdisplaydevice','libvirtualhid','lizardbyte-common','moonlight-common-c','nvapi','Simple-Web-Server','ViGEmClient')
Push-Location $repo
$names=@('PATH','MSYSTEM','PKG_CONFIG_PATH','BRANCH','BUILD_VERSION','COMMIT')
$previous=@{}
foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    & python (Join-Path $PSScriptRoot 'sync-sunshine-dependencies.py') --check
    if($LASTEXITCODE-ne0){throw 'Sunshine dependency pins need review'}
    if($InitializeDependencies){
        $paths=@($dependencies|ForEach-Object {'server/sunshine/third-party/'+$_})
        & git submodule update --init --depth 1 -- @paths
        if($LASTEXITCODE-ne0){throw 'Sunshine dependency checkout failed'}
        foreach($nested in @('moonlight-common-c','libvirtualhid')){
            & git -C "server/sunshine/third-party/$nested" submodule update --init --recursive --depth 1
            if($LASTEXITCODE-ne0){throw "Nested dependency checkout failed: $nested"}
        }
    }
    foreach($dependency in $dependencies){
        if(-not(Test-Path -LiteralPath "server/sunshine/third-party/$dependency/.git")){
            throw "Missing dependency $dependency. Use -InitializeDependencies for the first build."
        }
    }
    $env:PATH=(Join-Path $MsysRoot 'ucrt64/bin')+';'+(Join-Path $MsysRoot 'usr/bin')+';'+$env:PATH
    $env:MSYSTEM='UCRT64'
    $env:PKG_CONFIG_PATH=Join-Path $MsysRoot 'ucrt64/lib/pkgconfig'
    $env:BRANCH='main';$env:BUILD_VERSION=$Version;$env:COMMIT=(& git rev-parse HEAD).Trim()
    & $cmake -S (Join-Path $repo 'server/sunshine') -B $BuildDirectory -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo -DBUILD_WERROR=OFF -DBUILD_TESTS=OFF -DBUILD_DOCS=OFF -DSUNSHINE_ENABLE_TRAY=OFF -DSUNSHINE_ASSETS_DIR=assets
    if($LASTEXITCODE-ne0){throw 'RHRemote-Sunshine configuration failed'}
    & $cmake --build $BuildDirectory --target sunshine sunshinesvc dxgi-info audio-info web-ui --parallel $Jobs
    if($LASTEXITCODE-ne0){throw 'RHRemote-Sunshine build failed'}
    & $cmake --install $BuildDirectory --prefix $OutputDirectory
    if($LASTEXITCODE-ne0){throw 'RHRemote-Sunshine staging failed'}
    foreach($file in @('sunshine.exe','tools/sunshinesvc.exe','assets/web/index.html')){
        if(-not(Test-Path -LiteralPath (Join-Path $OutputDirectory $file))){throw "Server output is incomplete: $file"}
    }
    Copy-Item -LiteralPath server/UPSTREAM.json,server/sunshine/LICENSE -Destination $OutputDirectory -Force
    Get-FileHash -LiteralPath (Join-Path $OutputDirectory 'sunshine.exe') -Algorithm SHA256 | Select-Object Path,Hash
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
    Pop-Location
}
