# Shared by the package builder, installer and isolated package regressions.
function Get-RHRChildPath([string]$Root,[string]$Relative){
    $base=[IO.Path]::GetFullPath($Root).TrimEnd('\','/')
    if([IO.Path]::IsPathRooted($Relative)-or$Relative-match'(^|[\\/])\.\.([\\/]|$)'){throw 'Package path must be relative and remain inside its root'}
    $path=[IO.Path]::GetFullPath((Join-Path $base $Relative))
    if(-not$path.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Package path escaped its root'}
    $check=$path
    while($check.Length-ge$base.Length){
        if((Test-Path -LiteralPath $check)-and((Get-Item -LiteralPath $check -Force).Attributes-band[IO.FileAttributes]::ReparsePoint)){throw 'Package reparse points are not allowed'}
        if($check-eq$base){break};$check=[IO.Path]::GetDirectoryName($check)
    }
    $path
}
function New-RHRPackageManifest([string]$Root,[string]$Commit,[string]$EngineCommit){
    $base=[IO.Path]::GetFullPath($Root).TrimEnd('\','/')
    $files=@(Get-ChildItem -LiteralPath $base -Recurse -File|Where-Object Name -ne 'server-package.json'|ForEach-Object {
        $relative=$_.FullName.Substring($base.Length+1).Replace('\','/')
        $path=Get-RHRChildPath $base $relative
        if($relative-match'(?i)(^|/)(config|credentials)/|\.(pem|key|log)$'){throw 'Private state cannot be included in a server package'}
        @{path=$relative;size=$_.Length;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
    })
    $metadata=@{schema='rhremote.server-package/v1';product='RHRemote-Sunshine';commit=$Commit;engineCommit=$EngineCommit;service='SunshineService';serviceMode='unified';files=$files}
    [IO.File]::WriteAllText((Join-Path $base 'server-package.json'),($metadata|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    $metadata
}
function Test-RHRPackage([string]$Root,[switch]$AllowRuntimeFiles){
    $metadata=Get-Content -LiteralPath (Join-Path $Root 'server-package.json') -Raw|ConvertFrom-Json
    if($metadata.schema-ne'rhremote.server-package/v1'-or$metadata.product-ne'RHRemote-Sunshine'-or$metadata.serviceMode-ne'unified'){throw 'Unexpected server package identity'}
    $seen=@{}
    foreach($file in $metadata.files){
        if($seen.ContainsKey([string]$file.path)){throw 'Duplicate package path'};$seen[[string]$file.path]=$true
        $path=Get-RHRChildPath $Root $file.path
        if(-not(Test-Path -LiteralPath $path -PathType Leaf)-or(Get-Item -LiteralPath $path).Length-ne$file.size){throw ('Missing or changed package file: '+$file.path)}
        if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash-ne$file.sha256){throw ('Package hash mismatch: '+$file.path)}
    }
    foreach($required in @('sunshine.exe','host/RHRemoteHost.exe','host/scripts/display.ps1','host/scripts/RhDisplayConfig.dll','assets/web/index.html')){if(-not$seen.ContainsKey($required)){throw ('Incomplete package: '+$required)}}
    $base=[IO.Path]::GetFullPath($Root).TrimEnd('\','/')
    foreach($file in @($(if(-not$AllowRuntimeFiles){Get-ChildItem -LiteralPath $base -Recurse -File|Where-Object Name -ne 'server-package.json'}))){
        $relative=$file.FullName.Substring($base.Length+1).Replace('\','/')
        if(-not$seen.ContainsKey($relative)){throw ('Unlisted package file: '+$relative)}
    }
    $metadata
}
