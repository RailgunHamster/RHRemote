param(
 [switch]$Apply,
 [ValidateSet('All','Dist','Target')][string]$Scope='All',
 [string[]]$Protect=@(),
 [string]$ReportDirectory=''
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$policy=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'artifact-retention.json') -Raw|ConvertFrom-Json
if(-not$ReportDirectory){$ReportDirectory=Join-Path 'D:\RHRemoteTools\artifact-cleanup' ((Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))}
if(Test-Path -LiteralPath $ReportDirectory){throw 'Use a fresh cleanup report directory'}
New-Item -ItemType Directory -Path $ReportDirectory|Out-Null
$before=(Get-PSDrive -Name ([IO.Path]::GetPathRoot($repo).Substring(0,1))).Free
$records=[Collections.Generic.List[object]]::new();$keep=[Collections.Generic.List[object]]::new();$candidates=[Collections.Generic.List[object]]::new()
$protected=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach($p in $Protect){$null=$protected.Add([IO.Path]::GetFullPath($p))}
foreach($property in $policy.protectedDist.PSObject.Properties){$path=Join-Path $repo ('dist\'+$property.Name);$null=$protected.Add($path);$keep.Add([pscustomobject]@{path=$path;reason=$property.Value})}
$tags=@(& git -C $repo tag --list);if($LASTEXITCODE-ne0){throw 'Cannot identify release tags'}
$tracked=@(& git -C $repo ls-files -- target dist results/runs);if($LASTEXITCODE-ne0){throw 'Cannot protect tracked files'}
$trackedPaths=@($tracked|Where-Object{$_}|ForEach-Object{[IO.Path]::GetFullPath((Join-Path $repo $_))})
function SaveReport {
 $free=(Get-PSDrive -Name ([IO.Path]::GetPathRoot($repo).Substring(0,1))).Free
 [pscustomobject]@{schema='rhremote.artifact-cleanup/v1';apply=[bool]$Apply;scope=$Scope;repo=$repo;freeBytesBefore=$before;freeBytesAfter=$free;freeBytesDelta=($free-$before);deletedLogicalBytes=[long](($records|Where-Object deleted|Measure-Object bytes -Sum).Sum);records=@($records);retained=@($keep)}|ConvertTo-Json -Depth 9|Set-Content -LiteralPath (Join-Path $ReportDirectory 'report.json') -Encoding utf8
}
function Builders {
 @($processSnapshot|Where-Object {$_.Name-in@('cargo.exe','rustc.exe','rustdoc.exe','vpk.exe','cmake.exe','ninja.exe','cl.exe','link.exe')})
}
function Tree([string]$Path) {
 $base=[IO.Path]::GetFullPath($Path);$item=Get-Item -LiteralPath $base -Force
 if($item.FullName-cne$base-or($item.Attributes-band[IO.FileAttributes]::ReparsePoint)){throw 'Unresolved path or reparse point'}
 $allowed=@('target\debug\','dist\','results\runs\')|Where-Object {$base.StartsWith((Join-Path $repo $_),[StringComparison]::OrdinalIgnoreCase)}
 if(-not$allowed){throw 'Deletion target escaped the authorized artifact roots'}
 $files=[Collections.Generic.List[string]]::new();[long]$bytes=0
 $stack=[Collections.Generic.Stack[IO.FileSystemInfo]]::new();$stack.Push($item)
 while($stack.Count){$entry=$stack.Pop();if($entry.Attributes-band[IO.FileAttributes]::ReparsePoint){throw ('Reparse point: '+$entry.FullName)};if($entry.Attributes-band[IO.FileAttributes]::Directory){foreach($child in ([IO.DirectoryInfo]$entry).EnumerateFileSystemInfos()){$stack.Push($child)}}else{$files.Add($entry.FullName);$bytes+=$entry.Length}}
 [pscustomobject]@{path=$base;bytes=$bytes;files=$files.ToArray();directory=$item.PSIsContainer}
}
function Version([string]$Name) {
 $m=[regex]::Match($Name,'(?<v>(?<a>\d+)\.(?<b>\d+)\.(?<c>\d+)(?:-preview\.(?<p>\d+))?)')
 if(-not$m.Success){return $null}
 [pscustomobject]@{text=$m.Groups['v'].Value;key=('{0:D6}.{1:D6}.{2:D6}.{3:D9}'-f[int]$m.Groups['a'].Value,[int]$m.Groups['b'].Value,[int]$m.Groups['c'].Value,$(if($m.Groups['p'].Success){[int]$m.Groups['p'].Value}else{999999999}))}
}
function AddCandidate($Item,[string]$Category){$candidates.Add([pscustomobject]@{path=$Item.FullName;category=$Category})}
function Retain($Item,[string]$Reason){$null=$protected.Add($Item.FullName);$keep.Add([pscustomobject]@{path=$Item.FullName;reason=$Reason})}
function Versioned($Items,[string]$Category,[bool]$Published=$false) {
 $ordered=@($Items|ForEach-Object{$v=Version $_.Name;if($v){[pscustomobject]@{item=$_;version=$v.text;key=$v.key}}}|Sort-Object key,@{e={$_.item.LastWriteTimeUtc};Descending=$true} -Descending)
 $versions=@($ordered.version|Select-Object -Unique|Select-Object -First $policy.keepVersions);$selected=[Collections.Generic.HashSet[string]]::new()
 foreach($entry in $ordered){if($Published-and('v'+$entry.version)-in$tags){Retain $entry.item 'Published/tagged release; never prune';continue};if($entry.version-in$versions-and$selected.Add($entry.version)){Retain $entry.item 'Current or preceding artifact version'}else{AddCandidate $entry.item $Category}}
}
if($Scope-in@('All','Dist')){
 $dist=Join-Path $repo 'dist'
 $servers=@(Get-ChildItem -LiteralPath $dist -Directory -Filter 'server-*'|Sort-Object LastWriteTimeUtc -Descending)
 for($i=0;$i-lt$servers.Count;$i++){if($i-lt$policy.keepVersions){Retain $servers[$i] 'Two newest server experiment builds'}elseif(-not$protected.Contains($servers[$i].FullName)){AddCandidate $servers[$i] 'server-experiment'}}
 foreach($name in @('release-candidates','velopack-candidates')){$path=Join-Path $dist $name;if(Test-Path -LiteralPath $path){Versioned @(Get-ChildItem -LiteralPath $path -Directory) $name}}
 $apks=Join-Path $dist 'android';if(Test-Path -LiteralPath $apks){Versioned @(Get-ChildItem -LiteralPath $apks -File -Filter 'RHRemote-v*.apk') 'android-preview' $true}
 Versioned @(Get-ChildItem -LiteralPath $dist -Directory -Filter 'velopack-releases-*') 'velopack-preview-release' $true
 $stages=@(Get-ChildItem -LiteralPath $dist -Directory -Filter 'velopack-stage-*'|Sort-Object LastWriteTimeUtc -Descending)
 for($i=0;$i-lt$stages.Count;$i++){if($i-lt$policy.keepVersions){Retain $stages[$i] 'Two newest packaging staging trees'}else{AddCandidate $stages[$i] 'packaging-stage'}}
}
if($Scope-in@('All','Target')){
 $target=Join-Path $repo 'target';[long]$size=0
 if(Test-Path -LiteralPath $target){foreach($file in Get-ChildItem -LiteralPath $target -File -Recurse){$size+=$file.Length}}
 if($size-gt$policy.targetLimitGiB*1GB){foreach($name in @('incremental','deps','build','.fingerprint','examples')){$path=Join-Path $target ('debug\'+$name);if(Test-Path -LiteralPath $path){AddCandidate (Get-Item -LiteralPath $path) 'debug-cache'}}}
}
if($Scope-eq'All'){
 foreach($run in Get-ChildItem -LiteralPath (Join-Path $repo 'results\runs') -Directory){
  if(-not(Test-Path -LiteralPath (Join-Path $run.FullName 'conclusion.json'))-or(Test-Path -LiteralPath (Join-Path $run.FullName 'keep-raw'))-or(Test-Path -LiteralPath (Join-Path $run.FullName '.keep'))){continue}
  $raw=Join-Path $run.FullName 'raw';if(Test-Path -LiteralPath $raw){foreach($file in Get-ChildItem -LiteralPath $raw -Recurse -File|Where-Object Length -ge $policy.largeRawBytes){AddCandidate $file 'concluded-run-raw'}}
 }
}
if(-not('RhArtifactLocks' -as[type])){Add-Type -TypeDefinition @'
using System;using System.Text;using System.Runtime.InteropServices;
public static class RhArtifactLocks {
 [StructLayout(LayoutKind.Sequential)] public struct Unique {public int pid;public System.Runtime.InteropServices.ComTypes.FILETIME start;}
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] public struct Info {public Unique process;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=256)] public string app;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=64)] public string service;public uint type,status,session;[MarshalAs(UnmanagedType.Bool)]public bool restartable;}
 [DllImport("rstrtmgr.dll",CharSet=CharSet.Unicode)]static extern int RmStartSession(out uint session,uint flags,StringBuilder key);
 [DllImport("rstrtmgr.dll",CharSet=CharSet.Unicode)]static extern int RmRegisterResources(uint session,uint files,string[] paths,uint apps,IntPtr processes,uint services,string[] names);
 [DllImport("rstrtmgr.dll")]static extern int RmGetList(uint session,out uint needed,ref uint count,[In,Out]Info[] info,ref uint reason);
 [DllImport("rstrtmgr.dll")]static extern int RmEndSession(uint session);
 public static int[] Check(string[] paths){uint s;int e=RmStartSession(out s,0,new StringBuilder(33));if(e!=0)throw new InvalidOperationException("Restart Manager start: "+e);try{for(int i=0;i<paths.Length;i+=1000){int n=Math.Min(1000,paths.Length-i);string[] part=new string[n];Array.Copy(paths,i,part,0,n);e=RmRegisterResources(s,(uint)n,part,0,IntPtr.Zero,0,null);if(e!=0)throw new InvalidOperationException("Restart Manager register: "+e);}uint needed=0,count=0,reason=0;e=RmGetList(s,out needed,ref count,null,ref reason);if(e==0)return new int[0];for(int attempt=0;e==234&&attempt<3;attempt++){Info[] info=new Info[needed];count=needed;e=RmGetList(s,out needed,ref count,info,ref reason);if(e==0){int[] p=new int[count];for(int i=0;i<count;i++)p[i]=info[i].process.pid;return p;}}throw new InvalidOperationException("Restart Manager query: "+e);}finally{RmEndSession(s);}}
}
'@}
$cacheLock=$null;$held=$false
$processSnapshot=@(Get-CimInstance Win32_Process);$snapshotAt=[DateTime]::UtcNow
try{
 foreach($candidate in $candidates){
  $record=[pscustomobject]@{path=$candidate.path;category=$candidate.category;bytes=0L;files=0;deleted=$false;skip='';lockingPids=@()};$records.Add($record)
  try{
   if($protected.Contains($candidate.path)){throw 'Explicitly retained artifact'}
   if(@($trackedPaths|Where-Object{$_-eq$candidate.path-or$_.StartsWith($candidate.path+'\',[StringComparison]::OrdinalIgnoreCase)}).Count){throw 'Tracked file protected'}
   $tree=Tree $candidate.path;$record.bytes=$tree.bytes;$record.files=$tree.files.Count
   if($Apply-and([DateTime]::UtcNow-$snapshotAt).TotalSeconds-ge5){$processSnapshot=@(Get-CimInstance Win32_Process);$snapshotAt=[DateTime]::UtcNow}
   $active=@($processSnapshot|Where-Object {$_.ExecutablePath-and($_.ExecutablePath-eq$tree.path-or$_.ExecutablePath.StartsWith($tree.path+'\',[StringComparison]::OrdinalIgnoreCase))})
   if($active.Count){$record.lockingPids=@($active.ProcessId);throw 'A running process uses this artifact'}
   if(@(Builders).Count){throw 'Build tool active; defer pruning'}
   if($Apply){
    if($candidate.category-eq'debug-cache'){
     if(@($processSnapshot|Where-Object {$_.ExecutablePath-like(Join-Path $repo 'target\debug\*')}).Count){throw 'A process is using debug outputs'}
     if(-not$cacheLock){$cacheLock=[IO.File]::Open((Join-Path $repo 'target\debug\.cargo-lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite);$cacheLock.Lock(0,1);$held=$true}
    }else{$pids=[RhArtifactLocks]::Check($tree.files);if($pids.Count){$record.lockingPids=$pids;throw 'Restart Manager reports an active file user'}}
    SaveReport
    Remove-Item -LiteralPath $tree.path -Recurse:$tree.directory -Force
    $record.deleted=-not(Test-Path -LiteralPath $tree.path)
    if(-not$record.deleted){throw 'Deletion incomplete'}
   }
  }catch{$record.skip=$_.Exception.Message}
  SaveReport
 }
}finally{if($cacheLock){if($held){$cacheLock.Unlock(0,1)};$cacheLock.Dispose()};SaveReport}
$report=Get-Content -LiteralPath (Join-Path $ReportDirectory 'report.json') -Raw|ConvertFrom-Json
[pscustomobject]@{report=(Join-Path $ReportDirectory 'report.json');planned=$records.Count;deleted=@($records|Where-Object deleted).Count;skipped=@($records|Where-Object skip).Count;deletedLogicalBytes=$report.deletedLogicalBytes;freeBytesDelta=$report.freeBytesDelta;freeBytesAfter=$report.freeBytesAfter}
