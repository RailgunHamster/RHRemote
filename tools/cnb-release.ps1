# Adapted from the existing RHFiles CNB publisher. Credentials are never added to public download or object-storage requests.
function Assert-RHRemoteCnbRepository([string]$Repository) {
    $parts=$Repository.Split('/')
    if($parts.Count -lt 2 -or $parts.Count -gt 10 -or @($parts|Where-Object {$_ -notmatch '^[A-Za-z0-9_.-]+$' -or $_ -in @('.','..','-')}).Count){throw 'Invalid CNB repository path'}
}

function Get-RHRemoteCnbPublicUrl([string]$Repository,[string]$Tag,[string]$Name) {
    Assert-RHRemoteCnbRepository $Repository
    if($Tag -notmatch '^[A-Za-z0-9_.-]{1,180}$' -or $Tag -in @('.','..') -or $Name -notmatch '^[A-Za-z0-9_.-]{1,180}$' -or $Name -in @('.','..')){throw 'Invalid CNB download path'}
    if($Tag -eq 'latest'){return "https://cnb.cool/$Repository/-/releases/latest/download/$Name"}
    return "https://cnb.cool/$Repository/-/releases/download/$Tag/$Name"
}

function New-RHRemoteCnbHttpClient {
    $handler=[Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect=$false
    $client=[Net.Http.HttpClient]::new($handler)
    $client.Timeout=[TimeSpan]::FromMinutes(30)
    $client.DefaultRequestHeaders.UserAgent.ParseAdd('RHRemote-Local-Publisher')
    return $client
}

function Assert-RHRemoteCnbHttps([uri]$Url) {
    if(-not $Url.IsAbsoluteUri -or $Url.Scheme -ne 'https' -or $Url.Port -ne 443 -or $Url.UserInfo -or $Url.Fragment){throw 'Refusing an unsafe publishing/download URL'}
}

function Receive-RHRemoteCnbPublicFile($Client,[string]$Url,[string]$Output,[long]$Limit,[switch]$AllowMissing) {
    $current=[uri]$Url
    $timeout=[Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($(if($Limit -le 2MB){45}else{1800})))
    try {
        for($redirects=0;$redirects -le 5;$redirects++){
            Assert-RHRemoteCnbHttps $current
            $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get,$current)
            $request.Headers.CacheControl=[Net.Http.Headers.CacheControlHeaderValue]::new()
            $request.Headers.CacheControl.NoCache=$true
            $response=$null
            try {
                $response=$Client.SendAsync($request,[Net.Http.HttpCompletionOption]::ResponseHeadersRead,$timeout.Token).GetAwaiter().GetResult()
                $status=[int]$response.StatusCode
                if($status -in @(301,302,303,307,308)){
                    if($redirects -eq 5 -or -not $response.Headers.Location){throw 'Public CNB download redirect limit exceeded'}
                    $current=[uri]::new($current,$response.Headers.Location)
                    continue
                }
                if($status -eq 404 -and $AllowMissing){return $false}
                if(-not $response.IsSuccessStatusCode){throw "Anonymous CNB download failed (HTTP $status)"}
                if($response.Content.Headers.ContentLength -gt $Limit){throw 'CNB download exceeds expected size limit'}
                $stream=$response.Content.ReadAsStreamAsync($timeout.Token).GetAwaiter().GetResult()
                $sink=$null
                try {
                    $sink=[IO.File]::Open($Output,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
                    $buffer=[byte[]]::new(128KB);$total=0L
                    while(($count=$stream.ReadAsync($buffer,0,$buffer.Length,$timeout.Token).GetAwaiter().GetResult()) -gt 0){
                        $total+=$count
                        if($total -gt $Limit){throw 'CNB download exceeds expected size limit'}
                        $sink.Write($buffer,0,$count)
                    }
                    $sink.Flush($true)
                } finally {if($sink){$sink.Dispose()};$stream.Dispose()}
                return $true
            } finally {if($response){$response.Dispose()};$request.Dispose()}
        }
    } finally {$timeout.Dispose()}
}

function Invoke-RHRemoteCnbApi($Client,[string]$Token,[string]$Path,[ValidateSet('GET','POST','PATCH')][string]$Method='GET',$Body=$null,[switch]$AllowMissing) {
    $url=[uri]::new([uri]'https://api.cnb.cool/',$Path)
    Assert-RHRemoteCnbHttps $url
    if($url.Host -cne 'api.cnb.cool'){throw 'Publishing credential may only be sent to api.cnb.cool'}
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method),$url)
    $request.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$Token)
    $request.Headers.Accept.ParseAdd('application/vnd.cnb.api+json')
    if($null -ne $Body){$request.Content=[Net.Http.StringContent]::new(($Body|ConvertTo-Json -Depth 30 -Compress),[Text.Encoding]::UTF8,'application/json')}
    $timeout=[Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(45))
    $response=$null
    try {
        $response=$Client.SendAsync($request,[Net.Http.HttpCompletionOption]::ResponseHeadersRead,$timeout.Token).GetAwaiter().GetResult()
        if([int]$response.StatusCode -eq 404 -and $AllowMissing){return $null}
        if(-not $response.IsSuccessStatusCode){throw "CNB API $Method failed (HTTP $([int]$response.StatusCode)); no write was retried"}
        if($response.Content.Headers.ContentLength -gt 2MB){throw 'CNB API response exceeds 2 MiB'}
        $stream=$response.Content.ReadAsStreamAsync($timeout.Token).GetAwaiter().GetResult()
        $memory=[IO.MemoryStream]::new()
        try {
            $buffer=[byte[]]::new(16KB)
            while(($count=$stream.ReadAsync($buffer,0,$buffer.Length,$timeout.Token).GetAwaiter().GetResult()) -gt 0){if($memory.Length+$count -gt 2MB){throw 'CNB API response exceeds 2 MiB'};$memory.Write($buffer,0,$count)}
            $text=[Text.Encoding]::UTF8.GetString($memory.ToArray())
            if($text.Trim()){return $text|ConvertFrom-Json -Depth 60}
        } finally {$stream.Dispose();$memory.Dispose()}
    } finally {if($response){$response.Dispose()};$request.Dispose();$timeout.Dispose()}
}

function Get-RHRemoteCnbConfirmationPath([string]$Repository,[string]$ReleaseId,[string]$Url) {
    Assert-RHRemoteCnbRepository $Repository
    if($ReleaseId -notmatch '^[A-Za-z0-9_-]+$'){throw 'Invalid CNB release ID'}
    $parsed=[uri]::new([uri]'https://api.cnb.cool/',$Url)
    Assert-RHRemoteCnbHttps $parsed
    $prefix="/$Repository/-/releases/$ReleaseId/asset-upload-confirmation/"
    # The API returns ?ttl=0 for the non-expiring asset requested by this publisher.
    if($parsed.Host -cne 'api.cnb.cool' -or -not $parsed.AbsolutePath.StartsWith($prefix,[StringComparison]::Ordinal) -or $parsed.AbsolutePath.Length -le $prefix.Length -or $parsed.Query -cnotin @('','?ttl=0')){throw 'Upload confirmation does not belong to the expected repository/release'}
    return $parsed.PathAndQuery
}

function Send-RHRemoteCnbAsset($Client,[string]$Token,[string]$Repository,[string]$ReleaseId,[IO.FileInfo]$File) {
    $upload=Invoke-RHRemoteCnbApi $Client $Token "/$Repository/-/releases/$ReleaseId/asset-upload-url" POST @{asset_name=$File.Name;size=$File.Length;overwrite=$false;ttl=0}
    $confirmation=Get-RHRemoteCnbConfirmationPath $Repository $ReleaseId $upload.verify_url
    $url=[uri]$upload.upload_url
    Assert-RHRemoteCnbHttps $url
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Put,$url)
    $response=$null
    try {
        # Signed object-storage URL: never add the API Authorization header.
        $request.Content=[Net.Http.StreamContent]::new([IO.File]::OpenRead($File.FullName))
        $request.Content.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('application/octet-stream')
        $request.Content.Headers.ContentLength=$File.Length
        $response=$Client.SendAsync($request).GetAwaiter().GetResult()
        if(-not $response.IsSuccessStatusCode){throw "CNB asset PUT failed (HTTP $([int]$response.StatusCode)); not retried"}
    } finally {if($response){$response.Dispose()};$request.Dispose()}
    # The OpenAPI specifies POST. Do not infer GET from an older plugin version.
    $null=Invoke-RHRemoteCnbApi $Client $Token $confirmation POST
}

function Assert-RHRemoteCnbFile([IO.FileInfo]$File,[long]$Size,[string]$Hash) {
    if($File.Length -ne $Size -or (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash -ine $Hash){throw "Payload checksum/size mismatch: $($File.Name)"}
}
