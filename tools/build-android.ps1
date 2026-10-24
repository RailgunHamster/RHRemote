param([string]$SourceDir='D:\RHRemoteTools\moonlight-android-v12.2-source',[switch]$BuildTests,[string]$NdkDir="$env:LOCALAPPDATA\Android\Sdk\ndk\29.0.14206865",
 [ValidatePattern('^\d+\.\d+\.\d+(-preview\.[1-9]\d*)?$')][string]$Version='0.2.7',
 [ValidateRange(1,2100000000)][int]$VersionCode=209)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$cargoTarget=if($env:CARGO_TARGET_DIR){[IO.Path]::GetFullPath($env:CARGO_TARGET_DIR)}else{Join-Path $repo 'target'}
$toolchain=Join-Path $NdkDir 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=Join-Path $toolchain 'aarch64-linux-android26-clang.cmd'
$env:CC_aarch64_linux_android=$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$env:AR_aarch64_linux_android=Join-Path $toolchain 'llvm-ar.exe'
if(-not(Test-Path -LiteralPath $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER)){throw 'Android NDK compiler unavailable'}
Push-Location $repo
try{& cargo build --locked --release -p rhremote-transport --lib --target aarch64-linux-android -j4;if($LASTEXITCODE-ne0){throw 'Rust Android transport build failed'}}finally{Pop-Location}
$jni=Join-Path $SourceDir 'app/src/main/jniLibs/arm64-v8a'
New-Item -ItemType Directory -Force -Path $jni|Out-Null
Copy-Item -LiteralPath (Join-Path $cargoTarget 'aarch64-linux-android/release/librhremote_transport.so') -Destination $jni -Force
& (Join-Path $PSScriptRoot 'prepare-android.ps1') -SourceDir $SourceDir -Version $Version -VersionCode $VersionCode
Push-Location $SourceDir
try {
    $gradle='D:\RHRemoteTools\Gradle\gradle-9.7.1\bin\gradle.bat'
    if(-not(Test-Path -LiteralPath $gradle)){$gradle=Join-Path $SourceDir 'gradlew.bat'}
    # Java does not automatically use the user's existing Windows HTTPS proxy.
    $proxy=[Net.WebRequest]::GetSystemWebProxy().GetProxy([uri]'https://repo.maven.apache.org/maven2/')
    $proxyArgs=@()
    if($proxy.Host -ne 'repo.maven.apache.org'){$proxyArgs=@("-Dhttps.proxyHost=$($proxy.Host)","-Dhttps.proxyPort=$($proxy.Port)","-Dhttp.proxyHost=$($proxy.Host)","-Dhttp.proxyPort=$($proxy.Port)",'-Dhttp.nonProxyHosts=localhost')}
    $tasks=@(':app:assembleNonRootDebug');if($BuildTests){$tasks+=@(':app:assembleNonRootDebugAndroidTest',':app:testNonRootDebugUnitTest')}
    & $gradle --no-daemon --console=plain @proxyArgs @tasks
    if($LASTEXITCODE -ne 0){throw 'Android build failed.'}
    $repo=Split-Path $PSScriptRoot -Parent;$out=Join-Path $repo 'dist/android'
    New-Item -ItemType Directory -Force -Path $out|Out-Null
    $apkName='RHRemote-v'+$Version+'.apk'
    Copy-Item -LiteralPath 'app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk' -Destination (Join-Path $out $apkName) -Force
    Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $out $apkName)|Select-Object Hash,Path
}finally{Pop-Location}
