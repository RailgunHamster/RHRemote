# Run signing on the publishing account that owns its DPAPI credential.
function Test-RHRServerUpdateManifest {
 param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$Signature,[string]$PublicKey=(Join-Path (Split-Path $PSScriptRoot -Parent) 'server/update-keys/release-rsa3072-20261109.der'))
 $ErrorActionPreference='Stop'
 if((Get-FileHash -LiteralPath $PublicKey -Algorithm SHA256).Hash-ine'69b99bd07b3ea687d6247d2e2481f4d2ba8d8eff70a6fe6c6ac5010de783afc0'){throw 'Unexpected server release public key'}
 $bytes=[IO.File]::ReadAllBytes($Manifest);$signed=[IO.File]::ReadAllBytes($Signature)
 if($bytes.Length-eq0-or$bytes.Length-gt16384-or$signed.Length-ne384){throw 'Invalid server manifest envelope'}
 $rsa=[Security.Cryptography.RSA]::Create()
 try{$key=[IO.File]::ReadAllBytes($PublicKey);$read=0;$rsa.ImportRSAPublicKey($key,[ref]$read)
  if($read-ne$key.Length-or-not$rsa.VerifyData($bytes,$signed,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)){throw 'Server release signature rejected'}
 }finally{$rsa.Dispose()}
 $feed=[Text.Encoding]::UTF8.GetString($bytes)|ConvertFrom-Json
 if($feed.schema-ne'rhremote.server-update/v1'-or$feed.product-ne'RHRemote-Sunshine'-or$feed.platform-ne'windows-x64'-or$feed.keyId-ne'release-rsa3072-20261109'-or$feed.updaterProtocol-ne1-or$feed.version-notmatch'^\d+\.\d+\.\d+$'-or$feed.releaseTag-notmatch'^v\d+\.\d+\.\d+$'-or$feed.fileName-cne("RHRemote-Sunshine-"+$feed.version+'-win-x64.zip')-or$feed.sha256-notmatch'^[a-fA-F0-9]{64}$'-or$feed.sourceCommit-notmatch'^[a-fA-F0-9]{40}$'-or$feed.size-le0-or$feed.size-gt512MB){throw 'Unsupported signed server release'}
 $feed
}

function Initialize-RHRServerUpdateKey {
 param([string]$Directory=(Join-Path $env:LOCALAPPDATA 'RHRemote-Publishing'))
 $ErrorActionPreference='Stop'
 $null=New-Item -ItemType Directory -Force -Path $Directory
 $owner=[Security.Principal.WindowsIdentity]::GetCurrent().User
 $acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetOwner($owner);$acl.SetAccessRuleProtection($true,$false)
 foreach($sid in @($owner,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'))){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))}
 Set-Acl -LiteralPath $Directory -AclObject $acl
 $private=Join-Path $Directory 'server-update-rsa3072.xml'
 $public=Join-Path $Directory 'server-update-rsa3072.der'
 $rsa=[Security.Cryptography.RSA]::Create(3072)
 try{
  if(Test-Path -LiteralPath $private){
   $secure=Import-Clixml -LiteralPath $private
   try{$encoded=[Net.NetworkCredential]::new('',$secure).Password;$key=[Convert]::FromBase64String($encoded);$read=0;$rsa.ImportPkcs8PrivateKey($key,[ref]$read);if($read-ne$key.Length-or$rsa.KeySize-ne3072){throw 'Unexpected signing key'}}finally{$encoded=$null;if($key){[Array]::Clear($key,0,$key.Length)};$secure.Dispose()}
  }else{
   $key=$rsa.ExportPkcs8PrivateKey()
   try{$encoded=[Convert]::ToBase64String($key);$secure=ConvertTo-SecureString $encoded -AsPlainText -Force;$secure|Export-Clixml -LiteralPath $private}finally{$encoded=$null;[Array]::Clear($key,0,$key.Length);if($secure){$secure.Dispose()}}
  }
  $bytes=$rsa.ExportRSAPublicKey()
  if(Test-Path -LiteralPath $public){if([Convert]::ToBase64String([IO.File]::ReadAllBytes($public))-cne[Convert]::ToBase64String($bytes)){throw 'Existing public key differs; explicit key rotation is required'}}else{[IO.File]::WriteAllBytes($public,$bytes)}
  [pscustomobject]@{publicKey=$public;privateKeyProtected=$true;bits=$rsa.KeySize;publicSha256=(Get-FileHash -LiteralPath $public -Algorithm SHA256).Hash.ToLowerInvariant()}
 }finally{$rsa.Dispose()}
}

function Protect-RHRServerUpdateManifest {
 param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$Signature,[string]$KeyFile=(Join-Path $env:LOCALAPPDATA 'RHRemote-Publishing\server-update-rsa3072.xml'))
 $ErrorActionPreference='Stop'
 $bytes=[IO.File]::ReadAllBytes($Manifest)
 if($bytes.Length-gt16384){throw 'Server manifest exceeds its wire limit'}
 $feed=[Text.Encoding]::UTF8.GetString($bytes)|ConvertFrom-Json
 if($feed.schema-ne'rhremote.server-update/v1'-or$feed.product-ne'RHRemote-Sunshine'-or$feed.platform-ne'windows-x64'-or$feed.keyId-ne'release-rsa3072-20261109'){throw 'Unexpected server update manifest'}
 $secret=Import-Clixml -LiteralPath $KeyFile
 $rsa=[Security.Cryptography.RSA]::Create()
 try{
  $encoded=[Net.NetworkCredential]::new('',$secret).Password;$key=[Convert]::FromBase64String($encoded);$read=0;$rsa.ImportPkcs8PrivateKey($key,[ref]$read)
  if($read-ne$key.Length-or$rsa.KeySize-ne3072){throw 'Unexpected signing key'}
  $signatureBytes=$rsa.SignData($bytes,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
  if(-not$rsa.VerifyData($bytes,$signatureBytes,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)){throw 'Signature self verification failed'}
  if(Test-Path -LiteralPath $Signature){throw 'Signature output already exists'}
  [IO.File]::WriteAllBytes($Signature,$signatureBytes)
  [pscustomobject]@{signed=$true;keyId=$feed.keyId;signatureBytes=$signatureBytes.Length}
 }finally{$encoded=$null;if($key){[Array]::Clear($key,0,$key.Length)};$secret.Dispose();$rsa.Dispose()}
}
