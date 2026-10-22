param([int]$Port=48751,[string]$DataDir='D:\RHRemoteTools\rust-client-state',[switch]$ApiOnlyInputTest,[switch]$TestMode,[string]$DisplayOrigin='0,0',[string]$UiPosition='80,80')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$exe=Join-Path $repo 'dist/windows-rust/RHRemote.exe'
if(Get-Process RHRemote -ErrorAction SilentlyContinue|Where-Object {$_.Path -eq $exe}){throw 'This RHRemote test client is already running.'}
$secret='D:\RHRemoteTools\secrets\rhremote-control-token.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $secret -Parent)|Out-Null
if(-not(Test-Path -LiteralPath $secret)){$bytes=New-Object byte[] 32;[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes);[IO.File]::WriteAllText($secret,([BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()))}
if($DisplayOrigin-notmatch'^-?\d+,-?\d+$' -or $UiPosition-notmatch'^-?\d+,-?\d+$'){throw 'Expected x,y display/window coordinates'}
$values=@{RHREMOTE_CONTROL_PORT="$Port";RHREMOTE_CONTROL_TOKEN=[IO.File]::ReadAllText($secret).Trim();RHREMOTE_DATA_DIR=$DataDir;RHREMOTE_NATIVE_ENGINE=(Join-Path $repo 'dist/windows/RHRemote.exe');RHREMOTE_UI_SCREEN='primary';RHREMOTE_UI_POSITION=$UiPosition;RHREMOTE_CLIENT_DISPLAY_ORIGIN=$DisplayOrigin}
if($ApiOnlyInputTest){$values.RHREMOTE_BENCHMARK_NO_INPUT='1';$values.RHREMOTE_DECODED_SNAPSHOT=(Join-Path $DataDir 'decoded-test.png')}
if($ApiOnlyInputTest -or $TestMode){$values.RHREMOTE_TEST_MODE='1'}
$previous=@{}
try{
 foreach($name in $values.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$values[$name],'Process')}
 $p=Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe -Parent) -PassThru -RedirectStandardError 'D:\RHRemoteTools\rust-client-stderr.log' -RedirectStandardOutput 'D:\RHRemoteTools\rust-client-stdout.log'
 [pscustomobject]@{pid=$p.Id;control="http://127.0.0.1:$Port";token_file=$secret;data_directory=$DataDir}
}finally{foreach($name in $values.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}}
