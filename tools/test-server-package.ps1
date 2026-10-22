$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'server-package.ps1')
$root=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-package-test-'+[guid]::NewGuid().ToString('N'))
$paths=@('sunshine.exe','host/RHRemoteHost.exe','host/scripts/display.ps1','host/scripts/RhDisplayConfig.dll','assets/web/index.html')
function Expect-Rejection([scriptblock]$Action){$rejected=$false;try{& $Action|Out-Null}catch{$rejected=$true};if(-not$rejected){throw 'Invalid package was accepted'}}
try{
    foreach($relative in $paths){$file=Join-Path $root $relative;New-Item -ItemType Directory -Path (Split-Path $file -Parent) -Force|Out-Null;[IO.File]::WriteAllText($file,'package regression')}
    $null=New-RHRPackageManifest $root ('a'*40) ('b'*40)
    $null=Test-RHRPackage $root
    foreach($path in @('../escape.exe','/absolute.exe','host/../../escape.exe')){Expect-Rejection {Get-RHRChildPath $root $path}}
    [IO.File]::WriteAllText((Join-Path $root 'sunshine.exe'),'changed bytes')
    Expect-Rejection {Test-RHRPackage $root}
    $null=New-RHRPackageManifest $root ('a'*40) ('b'*40)
    Remove-Item -LiteralPath (Join-Path $root 'host/scripts/display.ps1')
    Expect-Rejection {Test-RHRPackage $root}
    $null=New-RHRPackageManifest $root ('a'*40) ('b'*40)
    Expect-Rejection {Test-RHRPackage $root}
    New-Item -ItemType Directory -Path (Join-Path $root 'config')|Out-Null
    [IO.File]::WriteAllText((Join-Path $root 'config/sunshine_state.json'),'private state')
    Expect-Rejection {New-RHRPackageManifest $root ('a'*40) ('b'*40)}
    Write-Output 'Server package regressions passed: valid build, traversal, changed bytes, missing file, incomplete manifest, private state exclusion'
}finally{
    $resolved=[IO.Path]::GetFullPath($root)
    $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
    if([IO.Path]::GetDirectoryName($resolved)-ne$temp-or-not[IO.Path]::GetFileName($resolved).StartsWith('rhremote-package-test-')){throw 'Temporary cleanup escaped its intended root'}
    if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
