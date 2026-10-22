param([Parameter(Mandatory)][string]$Ref)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    if((& git status --porcelain --untracked-files=no)){throw 'Commit or stash product changes before merging Sunshine'}
    $metadata=Get-Content -LiteralPath server/UPSTREAM.json -Raw|ConvertFrom-Json
    $remoteName='sunshine-upstream'
    $remotes=@(& git remote)
    if($remotes -notcontains $remoteName){
        & git remote add $remoteName $metadata.repository
        if($LASTEXITCODE-ne0){throw 'Could not register the official Sunshine remote'}
    }else{
        $remoteUrl=(& git remote get-url $remoteName)
        if($LASTEXITCODE-ne0 -or $remoteUrl.Trim()-ne$metadata.repository){throw 'sunshine-upstream points to a different repository'}
    }
    & git fetch --no-tags $remoteName $Ref
    if($LASTEXITCODE-ne0){throw 'Upstream fetch failed'}
    $revision=(& git rev-parse --verify 'FETCH_HEAD^{commit}').Trim()
    if($LASTEXITCODE-ne0){throw 'Upstream ref is not a commit'}
    & git subtree merge --prefix=server/sunshine $revision --squash -m "Merge Sunshine upstream $revision"
    if($LASTEXITCODE-ne0){throw 'Upstream merge did not complete. Inspect git status and resolve conflicts; local changes have not been reset.'}
    & python (Join-Path $PSScriptRoot 'sync-sunshine-dependencies.py') --revision $revision
    if($LASTEXITCODE-ne0){throw 'Review the merged dependency registrations before continuing'}
    Write-Host 'Upstream merged. Review .gitmodules and server/UPSTREAM.json, initialize required dependencies, build and run regressions before publishing.'
} finally {Pop-Location}
