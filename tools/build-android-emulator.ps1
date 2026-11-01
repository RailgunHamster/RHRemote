param(
 [string]$SourceDir='D:\RHRemoteTools\moonlight-android-v12.2-source',
 [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+(-preview\.[1-9]\d*)?$')][string]$Version,
 [Parameter(Mandatory)][ValidateRange(1,2100000000)][int]$VersionCode,
 [switch]$BuildTests,
 [string]$NdkDir="$env:LOCALAPPDATA\Android\Sdk\ndk\29.0.14206865"
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$cargoTarget=if($env:CARGO_TARGET_DIR){[IO.Path]::GetFullPath($env:CARGO_TARGET_DIR)}else{Join-Path $repo 'target'}
$target='x86_64-linux-android';$abi='x86_64'
Push-Location $repo
try{$installed=@(& rustup target list --installed);if($LASTEXITCODE-ne0-or$target-notin$installed){throw 'Install the x86_64-linux-android Rust target before building emulator packages'}}finally{Pop-Location}
$toolchain=Join-Path $NdkDir 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$linker=Join-Path $toolchain 'x86_64-linux-android26-clang.cmd'
if(-not(Test-Path -LiteralPath $linker)){throw 'Android x86_64 NDK compiler unavailable'}
$settings=@{CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=$linker;CC_x86_64_linux_android=$linker;AR_x86_64_linux_android=(Join-Path $toolchain 'llvm-ar.exe')}
$previous=@{}
foreach($name in $settings.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$settings[$name],'Process')}
try{
 Push-Location $repo
 try{& cargo build --locked --release -p rhremote-transport --lib --target $target -j4;if($LASTEXITCODE-ne0){throw 'Rust emulator transport build failed'}}finally{Pop-Location}
 $jni=Join-Path $SourceDir ('app/src/main/jniLibs/'+$abi);New-Item -ItemType Directory -Force -Path $jni|Out-Null
 Copy-Item -LiteralPath (Join-Path $cargoTarget ($target+'/release/librhremote_transport.so')) -Destination $jni -Force
 & (Join-Path $PSScriptRoot 'prepare-android.ps1') -SourceDir $SourceDir -Version $Version -VersionCode $VersionCode -Abi $abi
 Push-Location $SourceDir
 try{
  $gradle='D:\RHRemoteTools\Gradle\gradle-9.7.1\bin\gradle.bat';if(-not(Test-Path -LiteralPath $gradle)){$gradle=Join-Path $SourceDir 'gradlew.bat'}
  $proxy=[Net.WebRequest]::GetSystemWebProxy().GetProxy([uri]'https://repo.maven.apache.org/maven2/');$proxyArgs=@()
  if($proxy.Host-ne'repo.maven.apache.org'){$proxyArgs=@("-Dhttps.proxyHost=$($proxy.Host)","-Dhttps.proxyPort=$($proxy.Port)","-Dhttp.proxyHost=$($proxy.Host)","-Dhttp.proxyPort=$($proxy.Port)",'-Dhttp.nonProxyHosts=localhost')}
  $tasks=@(':app:assembleNonRootDebug');if($BuildTests){$tasks+=@(':app:assembleNonRootDebugAndroidTest',':app:testNonRootDebugUnitTest')}
  & $gradle --no-daemon --console=plain @proxyArgs @tasks;if($LASTEXITCODE-ne0){throw 'Android emulator build failed'}
 }finally{Pop-Location}
 $apk=Join-Path $SourceDir 'app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk'
 $zip=[IO.Compression.ZipFile]::OpenRead($apk);$packagedTransportSha=''
 try{
  foreach($library in @('libmoonlight-core.so','librhremote_transport.so')){
   $entry=$zip.GetEntry('lib/'+$abi+'/'+$library);if($null-eq$entry){throw ('Missing emulator native component: '+$library)}
   $stream=$entry.Open();try{$header=[byte[]]::new(20);$count=0;while($count-lt$header.Length){$read=$stream.Read($header,$count,$header.Length-$count);if($read-le0){throw 'Truncated native ELF header'};$count+=$read};if($header[0]-ne127-or$header[1]-ne69-or$header[2]-ne76-or$header[3]-ne70-or$header[4]-ne2-or$header[5]-ne1-or[BitConverter]::ToUInt16($header,18)-ne62){throw ('Wrong emulator ELF architecture: '+$library)}}finally{$stream.Dispose()}
   if($library-eq'librhremote_transport.so'){$stream=$entry.Open();$hash=[Security.Cryptography.SHA256]::Create();try{$packagedTransportSha=([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','')}finally{$hash.Dispose();$stream.Dispose()}}
  }
  if(@($zip.Entries|Where-Object {$_.FullName.StartsWith('lib/')-and-not$_.FullName.StartsWith('lib/x86_64/')}).Count){throw 'Unexpected secondary ABI in emulator package'}
 }finally{$zip.Dispose()}
 # Emulator APKs stay in the existing Gradle output; they never replace the ARM64 dist package.
 [pscustomobject]@{version=$Version;versionCode=$VersionCode;buildType='debug';abi=$abi;apk=$apk;sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash;transportSourceSha256=(Get-FileHash -LiteralPath (Join-Path $jni 'librhremote_transport.so') -Algorithm SHA256).Hash;transportPackagedSha256=$packagedTransportSha;testsBuilt=[bool]$BuildTests;published=$false}
}finally{foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}}
