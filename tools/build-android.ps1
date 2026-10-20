param([string]$SourceDir='D:\RHRemoteTools\moonlight-android-v12.2-source',[switch]$BuildTests)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'prepare-android.ps1') -SourceDir $SourceDir
Push-Location $SourceDir
try {
    $gradle='D:\RHRemoteTools\Gradle\gradle-9.7.1\bin\gradle.bat'
    if(-not(Test-Path -LiteralPath $gradle)){$gradle=Join-Path $SourceDir 'gradlew.bat'}
    # Java does not automatically use the user's existing Windows HTTPS proxy.
    $proxy=[Net.WebRequest]::GetSystemWebProxy().GetProxy([uri]'https://repo.maven.apache.org/maven2/')
    $proxyArgs=@()
    if($proxy.Host -ne 'repo.maven.apache.org'){$proxyArgs=@("-Dhttps.proxyHost=$($proxy.Host)","-Dhttps.proxyPort=$($proxy.Port)","-Dhttp.proxyHost=$($proxy.Host)","-Dhttp.proxyPort=$($proxy.Port)",'-Dhttp.nonProxyHosts=localhost')}
    $tasks=@(':app:assembleNonRootDebug');if($BuildTests){$tasks+=':app:assembleNonRootDebugAndroidTest'}
    & $gradle --no-daemon --console=plain @proxyArgs @tasks
    if($LASTEXITCODE -ne 0){throw 'Android build failed.'}
    $repo=Split-Path $PSScriptRoot -Parent;$out=Join-Path $repo 'dist/android'
    New-Item -ItemType Directory -Force -Path $out|Out-Null
    Copy-Item -LiteralPath 'app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk' -Destination (Join-Path $out 'RHRemote-v0.2.0.apk') -Force
    Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $out 'RHRemote-v0.2.0.apk')|Select-Object Hash,Path
}finally{Pop-Location}
