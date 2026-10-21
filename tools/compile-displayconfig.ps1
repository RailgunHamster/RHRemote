param(
    [Parameter(Mandatory)][string]$ScriptPath,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSEdition-ne'Desktop'){throw 'Compile using Windows PowerShell 5.1 for the host helper runtime'}
$ScriptPath=[IO.Path]::GetFullPath($ScriptPath)
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
$parseTokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($ScriptPath,[ref]$parseTokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'Display configuration script has syntax errors'}
$commands=@($ast.FindAll({param($node)
    $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName()-eq'Add-Type' -and
    @($node.CommandElements|Where-Object {$_ -is [Management.Automation.Language.CommandParameterAst] -and $_.ParameterName-eq'TypeDefinition'}).Count-eq1
},$true))
if($commands.Count-ne1){throw 'Expected one embedded display configuration source'}
$elements=$commands[0].CommandElements
$source=$null
for($i=0;$i-lt$elements.Count-1;$i++){
    if($elements[$i] -is [Management.Automation.Language.CommandParameterAst] -and $elements[$i].ParameterName-eq'TypeDefinition'){$source=$elements[$i+1].Value}
}
if(-not$source-or$source-notmatch'public static class RhDisplayConfig'){throw 'Display configuration source is missing'}
New-Item -ItemType Directory -Force -Path $OutputDirectory|Out-Null
$assembly=Join-Path $OutputDirectory 'RhDisplayConfig.dll'
if(Test-Path -LiteralPath $assembly){throw 'Output assembly already exists; use a fresh build directory'}
Add-Type -TypeDefinition $source -OutputAssembly $assembly -OutputType Library
$manifest=[ordered]@{schema='rhremote.display-assembly/v1';sourceSha256=(Get-FileHash -LiteralPath $ScriptPath -Algorithm SHA256).Hash;assemblySha256=(Get-FileHash -LiteralPath $assembly -Algorithm SHA256).Hash;runtime='Windows PowerShell 5.1 / .NET Framework'}
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'RhDisplayConfig.assembly.json'),($manifest|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$manifest|ConvertTo-Json
