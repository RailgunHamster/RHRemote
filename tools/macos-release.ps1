function Get-RHRemoteMacRelease([string]$Directory,[string]$Version) {
    $feedName = 'releases.osx-arm64.json'
    $packageName = "RHRemote-Moonlight-$Version-osx-arm64-full.nupkg"
    $installerName = 'RHRemote-Moonlight-osx-arm64-Setup.pkg'
    $portableName = 'RHRemote-Moonlight-osx-arm64-Portable.zip'
    $feed = Get-Content -LiteralPath (Join-Path $Directory $feedName) -Raw | ConvertFrom-Json
    $assets = @($feed.Assets)
    $asset = $assets[0]
    if ($assets.Count -ne 1 -or $asset.PackageId -cne 'RHRemote-Moonlight' -or
        $asset.Version -cne $Version -or $asset.Type -cne 'Full' -or
        $asset.FileName -cne $packageName -or $asset.Size -lt 1 -or
        $asset.SHA256 -notmatch '^[0-9a-fA-F]{64}$') {
        throw 'Unexpected macOS arm64 release feed'
    }
    $package = Get-Item -LiteralPath (Join-Path $Directory $packageName)
    if ($package.PSIsContainer -or $package.Length -ne $asset.Size -or
        (Get-FileHash -LiteralPath $package.FullName -Algorithm SHA256).Hash -ine $asset.SHA256) {
        throw 'macOS update package checksum mismatch'
    }
    $names = @($feedName,$packageName)
    $installer = Join-Path $Directory $installerName
    if (Test-Path -LiteralPath $installer) {
        foreach ($name in @($installerName,$portableName)) {
            $file = Get-Item -LiteralPath (Join-Path $Directory $name)
            if ($file.PSIsContainer -or $file.Length -lt 1) { throw "Missing macOS release asset: $name" }
            $names += $name
        }
    }
    return [pscustomobject]@{feed=$feed;feedName=$feedName;fileNames=$names}
}

function Assert-RHRemoteMacPublishPlan([string]$Directory,$Plan) {
    $macFiles = @($Plan.artifacts | Where-Object {$_.fileName -match '(^releases\.osx-arm64\.json$|^RHRemote-Moonlight-.*osx-arm64)'})
    if ($macFiles.Count -eq 0) { return }
    $macVersion = if ($Plan.macOSVersion) { [string]$Plan.macOSVersion } else { [string]$Plan.version }
    if ($macVersion -notmatch '^\d+\.\d+\.\d+$' -or [version]$macVersion -gt [version]$Plan.version) {
        throw 'Unexpected macOS version in the publication plan'
    }
    $release = Get-RHRemoteMacRelease $Directory $macVersion
    if ($macFiles.Count -ne $release.fileNames.Count) { throw 'Unexpected macOS release assets' }
    foreach ($name in $release.fileNames) {
        if (@($macFiles | Where-Object fileName -ceq $name).Count -ne 1) {
            throw 'macOS release assets differ from the publication plan'
        }
    }
}
