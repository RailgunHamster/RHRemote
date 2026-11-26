param([string]$QtDirectory='D:\RHRemoteTools\Qt\6.7.2\msvc2019_64')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$compiler='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
if(-not(Test-Path -LiteralPath $compiler)-or-not(Test-Path -LiteralPath (Join-Path $QtDirectory 'lib/Qt6Network.lib'))){throw 'MSVC and the Qt 6 Network development runtime are required'}
$out=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-clipboard-sync-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $out 'backend') -Force|Out-Null

# Stub IdentityManager so rhclipboardsync.h compiles standalone against Qt6 Network TLS.
@'
#pragma once
#include <QSslConfiguration>
class IdentityManager {
public:
    static IdentityManager* get() { static IdentityManager instance; return &instance; }
    QSslConfiguration getSslConfig() const { return QSslConfiguration::defaultConfiguration(); }
};
'@ | Set-Content -LiteralPath (Join-Path $out 'backend/identitymanager.h') -Encoding utf8

# Generate an ephemeral self-signed loopback certificate/key for the TLS test server.
$rsa=[Security.Cryptography.RSA]::Create(2048)
try {
    $req=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=127.0.0.1',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $now=[DateTimeOffset]::UtcNow
    $cert=$req.CreateSelfSigned($now.AddMinutes(-5),$now.AddHours(1))
    try {
        [IO.File]::WriteAllText((Join-Path $out 'cert.pem'),$cert.ExportCertificatePem(),[Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $out 'key.pem'),$rsa.ExportPkcs8PrivateKeyPem(),[Text.UTF8Encoding]::new($false))
    } finally { $cert.Dispose() }
} finally { $rsa.Dispose() }

$opensslBin='D:\RHRemoteTools\Moonlight-RHRemote-6.1.0'
$batch=@"
@echo off
call "$compiler" >nul
if errorlevel 1 exit /b 1
cd /d "$out"
cl /nologo /std:c++17 /Zc:__cplusplus /permissive- /EHsc /utf-8 /MD /I"$out" /I"$repo" /I"$QtDirectory\include" /I"$QtDirectory\include\QtCore" /I"$QtDirectory\include\QtNetwork" /Fe:test-clipboard-sync.exe "$repo\tools\test-windows-clipboard-sync.cpp" /link /LIBPATH:"$QtDirectory\lib" Qt6Core.lib Qt6Network.lib user32.lib
if errorlevel 1 exit /b 1
set "PATH=$QtDirectory\bin;$opensslBin;%PATH%"
set "QT_PLUGIN_PATH=$QtDirectory\plugins"
test-clipboard-sync.exe "$out\cert.pem" "$out\key.pem"
exit /b %errorlevel%
"@
$path=Join-Path $out 'test.cmd'
[IO.File]::WriteAllText($path,$batch,[Text.Encoding]::ASCII)
& cmd /d /c $path
if($LASTEXITCODE-ne0){throw ('Native clipboard sync regressions failed; evidence retained: '+$out)}
Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
