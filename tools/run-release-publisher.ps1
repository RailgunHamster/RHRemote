# Inputs injected by Invoke-RemoteScript. The DPAPI token is read only on this host.
$ErrorActionPreference='Stop'
$source=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($PublisherScriptB64))
& ([ScriptBlock]::Create($source)) -Provider gitee -CandidateDirectory $CandidateDirectory -Commit $Commit -BundlePath $BundlePath -MakePublic -SourceOnly:($SourceOnly -eq 'true')
