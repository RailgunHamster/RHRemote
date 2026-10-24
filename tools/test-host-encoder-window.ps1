param([switch]$SystemGroup)
# Pure config edit and file replacement regression; does not call a service or scheduler.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'host-encoder-window.ps1') -LibraryOnly
function Assert($condition,$label) { if(-not $condition) { throw $label } }
foreach($budget in @(@(480,300),@(120,60),@(600,300))) { Assert-RHEncoderWindowBudget $budget[0] $budget[1] }
foreach($budget in @(@(120,61),@(119,25),@(601,25),@(480,9),@(480,301))) {
    $rejected=$false
    try { Assert-RHEncoderWindowBudget $budget[0] $budget[1] } catch { $rejected=$true }
    Assert $rejected 'Startup observation was allowed to consume the recovery margin or exceed bounds'
}
$utf8=[Text.UTF8Encoding]::new($false)
$cases=@(
    "encoder = software`r`nother = value`r`n",
    "# encoder = ignored`nencoder=software`n",
    ("other="+[char]0x4e2d+[char]0x6587+"`n  encoder `t= software  # keep this`n"),
    "encoder=software",
    ([char]0xFEFF+"encoder = software`r`n# last line")
)
foreach($text in $cases) {
  foreach($candidate in @('quicksync','mediafoundation')) {
    [byte[]]$before=$utf8.GetBytes($text)
    [byte[]]$after=New-RHEncoderBytes $before $candidate
    Assert ($utf8.GetString($after) -ceq $text.Replace('software',$candidate)) 'Unrelated bytes changed'
    Assert ((Get-RHBytesHash (New-RHEncoderBytes $after 'software')) -ceq (Get-RHBytesHash $before)) 'Round-trip is not byte exact'
  }
}
$otherText="# keep software`nother=software`nencoder = software # software note`n"
$expectedText="# keep software`nother=software`nencoder = mediafoundation # software note`n"
Assert ($utf8.GetString((New-RHEncoderBytes $utf8.GetBytes($otherText) 'mediafoundation')) -ceq $expectedText) 'An unrelated matching value/comment changed'
foreach($text in @('other=software',"encoder=software`nencoder=software",'encoder = unknown')) {
    $rejected=$false
    try { $null=New-RHEncoderBytes $utf8.GetBytes($text) 'quicksync' } catch { $rejected=$true }
    Assert $rejected 'Ambiguous/missing/unsupported key was accepted'
}
$rejected=$false
try { $null=New-RHEncoderBytes ([byte[]](255,254,101,0)) 'quicksync' } catch { $rejected=$true }
Assert $rejected 'UTF-16 was silently corrupted'
$folder=Join-Path ([IO.Path]::GetTempPath()) ('rh-encoder-config-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder|Out-Null
$path=Join-Path $folder 'sunshine.conf'
try {
  foreach($protected in @($false,$true)) {
    [byte[]]$before=$utf8.GetBytes($cases[2]);[IO.File]::WriteAllBytes($path,$before)
    $descriptor=Get-Acl -LiteralPath $path
    $descriptor.SetAccessRuleProtection($protected,$true)
    Set-Acl -LiteralPath $path -AclObject $descriptor
    if($SystemGroup) {
        $descriptor=Get-Acl -LiteralPath $path
        $descriptor.SetGroup([Security.Principal.SecurityIdentifier]::new('S-1-5-18'))
        Set-Acl -LiteralPath $path -AclObject $descriptor
        Assert ((Get-Acl -LiteralPath $path).GetGroup([Security.Principal.SecurityIdentifier]).Value -eq 'S-1-5-18') 'SYSTEM group fixture was not applied'
    }
    $originalHash=Get-RHBytesHash $before;$sddl=(Get-Acl -LiteralPath $path).Sddl
    [byte[]]$after=New-RHEncoderBytes $before 'quicksync'
    Set-RHConfigBytes $path $after $originalHash
    Assert ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ceq (Get-RHBytesHash $after)) 'Trial file differs'
    Assert ((Get-Acl -LiteralPath $path).Sddl -ceq $sddl) 'File ACL changed'
    $rejected=$false
    try { Set-RHConfigBytes $path $before $originalHash } catch { $rejected=$true }
    Assert $rejected 'Concurrent edit was overwritten'
    Assert ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ceq (Get-RHBytesHash $after)) 'Rejected edit changed bytes'
    Set-RHConfigBytes $path $before (Get-RHBytesHash $after)
    Assert ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ceq $originalHash) 'Exact original bytes not restored'
  }
} finally {
    # Only this exact test file and then the empty, uniquely created test directory.
    if(Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    Remove-Item -LiteralPath $folder
}
'PASS: 8 recovery-budget checks, 10 byte-preserving round trips, unrelated-value preservation, 4 rejection cases, inherited/protected ACL replacement/CAS/exact restore; SYSTEM group fixture='+[bool]$SystemGroup
