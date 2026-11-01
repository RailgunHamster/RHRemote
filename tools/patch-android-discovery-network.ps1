param([Parameter(Mandatory=$true)][string]$Text)
$ErrorActionPreference='Stop'
$Text=$Text.Replace("`r`n","`n")
foreach($entry in @(@{method='onAvailable';operation='available'},@{method='onLost';operation='lost'})){
    $before='                public void '+$entry.method+'(Network network) {'
    if(([regex]::Matches($Text,[regex]::Escape($before))).Count-ne1){throw ('Expected one default-network callback: '+$entry.method)}
    $after=$before+"`n                    if (!com.limelight.rhremote.RhNetworkObservation."+$entry.operation+'(network)) return;'
    $Text=$Text.Replace($before,$after)
}
return $Text
