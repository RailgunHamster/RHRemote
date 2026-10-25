param()
$ErrorActionPreference='Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'browser-movie-source.cs')
$root=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-browser-source-test-'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $root
$server=$null;$job=$null;$owner=$null;$passed=$false;$checks=0
function Assert($condition,[string]$message){if(-not$condition){throw $message};$script:checks++}
function Request($url,$method='GET',$range=$null,$body=$null){
 $r=[Net.HttpWebRequest]::Create($url);$r.Proxy=$null;$r.Method=$method;$r.Timeout=5000
 if($range){
  if($range-match'^bytes=([0-9]+)-([0-9]+)$'){$r.AddRange([long]$Matches[1],[long]$Matches[2])}
  elseif($range-match'^bytes=([0-9]+)-$'){$r.AddRange([long]$Matches[1])}
  elseif($range-match'^bytes=-([0-9]+)$'){$r.AddRange(-[long]$Matches[1])}
  else{throw 'Unsupported test request range'}
 }
 if($null-ne$body){$bytes=[Text.Encoding]::UTF8.GetBytes($body);$r.ContentLength=$bytes.Length;$r.ContentType='application/json';$stream=$r.GetRequestStream();$stream.Write($bytes,0,$bytes.Length);$stream.Dispose()}
 $response=$null
 try{$response=$r.GetResponse()}catch [Net.WebException]{if(-not$_.Exception.Response){throw};$response=$_.Exception.Response}
 try{$memory=[IO.MemoryStream]::new();$response.GetResponseStream().CopyTo($memory);@{status=[int]$response.StatusCode;headers=$response.Headers;bytes=$memory.ToArray()}}
 finally{if($response){$response.Dispose()}}
}
try{
 $begin=0L;$end=0L
 Assert ([RhMovieServer]::Range('bytes=-8',100,[ref]$begin,[ref]$end)-and$begin-eq92-and$end-eq99) 'Suffix range failed'
 Assert ([RhMovieServer]::Range('bytes=92-999',100,[ref]$begin,[ref]$end)-and$begin-eq92-and$end-eq99) 'Explicit EOF clipping failed'
 foreach($range in @('bytes=101-','bytes=-0','bytes=3-1','bytes=0-1,3-4','bytes=9223372036854775808-','bytes=+1-3')){
  Assert (-not[RhMovieServer]::Range($range,100,[ref]$begin,[ref]$end)) ('Invalid range accepted: '+$range)
 }
 $movie=Join-Path $root 'asset.bin';[IO.File]::WriteAllBytes($movie,[byte[]](0..99))
 $records=Join-Path $root 'packets.jsonl';$server=[RhMovieServer]::new($movie,'<script nonce="__RH_MOVIE_NONCE__">/* own test */</script>',$records)
 $page=Request $server.Url
 Assert ($page.status-eq200-and$server.Url.StartsWith('http://127.0.0.1:')) 'Unprivileged loopback page failed'
 Assert ($page.headers['Content-Security-Policy']-match "script-src 'nonce-"-and$page.headers['Content-Security-Policy']-notmatch'unsafe-inline') 'Page nonce policy missing'
 $base=$server.Url.Substring(0,$server.Url.Length-'index.html'.Length)
 $asset=Request ($base+'asset.mp4') 'GET' 'bytes=7-13'
 Assert ($asset.status-eq206-and$asset.headers['Content-Range']-eq'bytes 7-13/100'-and($asset.bytes-join',')-eq((7..13)-join',')) 'Actual ranged bytes differ'
 $head=Request ($base+'asset.mp4') 'HEAD' 'bytes=-8'
 Assert ($head.status-eq206-and$head.bytes.Length-eq0-and$head.headers['Content-Length']-eq'8') 'HEAD/range response differs'
 Assert ((Request ($base+'asset.mp4') 'GET' 'bytes=100-').status-eq416) 'Out-of-file range was served'
 Assert ((Request ($base+'../asset.bin')).status-eq404) 'URL traversal escaped fixed routes'
 Assert ((Request ('http://127.0.0.1:'+$server.Port+'/wrong/index.html')).status-eq404) 'Wrong capability was accepted'
 $packet='{"seq":1,"frame":"line\nquote\"","missing":null}'
 $post=Request ($base+'telemetry') 'POST' $null $packet
 Assert ($post.status-eq200-and$server.Latest().Json-ceq$packet-and$server.Latest().Sequence-eq1) 'Telemetry content or sequence changed'
 $server.StopRequested=$true
 $post=Request ($base+'telemetry') 'POST' $null '{"seq":2,"ended":true}'
 Assert (([Text.Encoding]::UTF8.GetString($post.bytes)|ConvertFrom-Json).stop-eq$true) 'Controlled stop did not reach the source'
 $server.Dispose();$server=$null
 $lines=@(Get-Content -LiteralPath $records|ForEach-Object{$_|ConvertFrom-Json})
 Assert ($lines.Count-eq2-and$lines[0].json-ceq$packet-and$lines[1].sequence-eq2-and$lines[1].receivedQpc-ge$lines[0].receivedQpc) 'Raw transport evidence lost its content/clock'
 $job=[RhMovieBrowser]::new($env:ComSpec,'/d /c "ping -n 30 127.0.0.1 >nul"',$root)
 $child=$job.RootPid;Assert ([bool](Get-Process -Id $child -ErrorAction SilentlyContinue)) 'Owned suspended-created process never started'
 Assert ($job.Members()-contains$child) 'Job membership does not contain its actual created root'
 $cpu=$job.CpuSeconds();Assert ($cpu-ge0) 'Job CPU accounting is unavailable'
 Start-Sleep -Milliseconds 100;Assert ($job.CpuSeconds()-ge$cpu) 'Job CPU accounting regressed'
 $job.Dispose();Assert $job.CleanupComplete 'Explicit job cleanup left descendants active';$job=$null
 Assert (-not(Get-Process -Id $child -ErrorAction SilentlyContinue)) 'Owned root process survived job cleanup'
 # Verify the failure path: destroying the owner closes its non-inherited job handle.
 $childFile=Join-Path $root 'child.pid';$ownerScript=Join-Path $root 'owner.ps1'
 $source=(Join-Path $PSScriptRoot 'browser-movie-source.cs').Replace("'","''")
 $code='$ErrorActionPreference="Stop";'+"Add-Type -Path '$source';`n"+'$j=[RhMovieBrowser]::new($env:ComSpec,''/d /c "ping -n 30 127.0.0.1 >nul"'','''+$root.Replace("'","''")+"');Start-Sleep -Milliseconds 500;`n"+'$owned=@($j.Members()|ForEach-Object{$p=Get-Process -Id $_;@{pid=$p.Id;started=$p.StartTime.ToUniversalTime().Ticks}});[IO.File]::WriteAllText('''+$childFile.Replace("'","''")+''',($owned|ConvertTo-Json -Compress));Start-Sleep -Seconds 30'
 [IO.File]::WriteAllText($ownerScript,$code,[Text.UTF8Encoding]::new($true))
 $ownerError=Join-Path $root 'owner-error.txt'
 $owner=Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',('"'+$ownerScript+'"')) -WindowStyle Hidden -PassThru -RedirectStandardError $ownerError
 $until=(Get-Date).AddSeconds(15);while(-not(Test-Path -LiteralPath $childFile)-and-not$owner.HasExited-and(Get-Date)-lt$until){Start-Sleep -Milliseconds 100}
 if(-not(Test-Path -LiteralPath $childFile)){throw ('Job owner did not publish its identity: '+[IO.File]::ReadAllText($ownerError))}
 Assert $true 'Job owner identity published'
 $ownedChildren=Get-Content -LiteralPath $childFile -Raw|ConvertFrom-Json
 Assert ($ownedChildren.Count-ge2) 'Failure-path test did not create a descendant tree'
 foreach($identity in $ownedChildren){Assert ([bool](Get-Process -Id $identity.pid -ErrorAction SilentlyContinue)) 'Owned descendant is absent before owner exit'}
 $owner.Kill();$null=$owner.WaitForExit(5000);$owner.Dispose();$owner=$null
 foreach($identity in $ownedChildren){
  $until=(Get-Date).AddSeconds(5)
  do{$childProcess=Get-Process -Id $identity.pid -ErrorAction SilentlyContinue;$same=($childProcess-and$childProcess.StartTime.ToUniversalTime().Ticks-eq$identity.started);if($same){Start-Sleep -Milliseconds 50}}while($same-and(Get-Date)-lt$until)
  Assert (-not$same) 'Parent failure left an actual owned descendant running'
 }
 $passed=$true
}finally{
 if($server){$server.Dispose()};if($job){$job.Dispose()};if($owner){if(-not$owner.HasExited){$owner.Kill()};$owner.Dispose()}
 if($passed){
  $absolute=[IO.Path]::GetFullPath($root);$prefix=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
  if(-not$absolute.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)-or-not[IO.Path]::GetFileName($absolute).StartsWith('rhremote-browser-source-test-')){throw 'Test cleanup escaped its owned temporary directory'}
  $removed=$false
  for($attempt=0;$attempt-lt15;$attempt++){
   try{Remove-Item -LiteralPath $absolute -Recurse -Force;$removed=$true;break}catch{Start-Sleep -Milliseconds 200}
  }
  if(-not$removed){throw 'Owned test directory is still held after bounded cleanup; retained for inspection'}
 }
}
[pscustomobject]@{checks=$checks;loopbackRangeTelemetry=$true;explicitJobCleanup=$true;ownerFailureCleanup=$true;temporaryFilesRemoved=$passed;powerShell=$PSVersionTable.PSVersion.ToString()}|ConvertTo-Json -Compress
