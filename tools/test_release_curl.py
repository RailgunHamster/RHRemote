"""Exercise the Windows upload fallback without a network or production credential."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.name == "nt", "Windows publisher ACL and PowerShell runtime")
class ReleaseCurlTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.asset = self.root / "source.zip"
        self.asset.write_bytes(b"immutable fixture")
        self.wrapper = self.root / "exercise.ps1"
        self.wrapper.write_text(r'''param([string]$Publisher,[string]$Asset,[string]$Mode)
$ErrorActionPreference='Stop'
$ast=[Management.Automation.Language.Parser]::ParseFile($Publisher,[ref]$null,[ref]$null)
$names=@('Test-ReleaseCurlRetry','Invoke-ReleaseCurlProcess','Invoke-ReleaseCurlUpload','Upload')
$functions=$ast.FindAll({param($node) $node-is[Management.Automation.Language.FunctionDefinitionAst]-and$names-contains$node.Name},$true)
foreach($function in $functions){Invoke-Expression $function.Extent.Text}
if($Mode-eq'process-real'){
 $config=Join-Path (Split-Path -Parent $Asset) 'native-curl.cfg'
 $text=@(('url = "'+([uri]$Asset).AbsoluteUri+'"'),'silent','show-error','max-time = 5','write-out = "\n%{http_code}"')-join "`n"
 [IO.File]::WriteAllText($config,$text,[Text.UTF8Encoding]::new($false))
 Invoke-ReleaseCurlProcess (Get-Command curl.exe).Source $config|ConvertTo-Json -Compress
 return
}
if($Mode-eq'policy'){
 $body='{"message":"AttachFile::upload_attach_file raise: File upload error"}'
 @{gitee400=(Test-ReleaseCurlRetry 'gitee' 400 $body);github400=(Test-ReleaseCurlRetry 'github' 400 $body);
   auth=(Test-ReleaseCurlRetry 'gitee' 401 $body);timeout=(Test-ReleaseCurlRetry 'gitee' 0 $body);
   conflict=(Test-ReleaseCurlRetry 'gitee' 409 $body);other=(Test-ReleaseCurlRetry 'gitee' 400 '{"message":"other failure"}');
   invalid=(Test-ReleaseCurlRetry 'gitee' 400 'not JSON')}|ConvertTo-Json -Compress
 return
}
$script:seen=$null;$script:observations=@{};$script:curlCalls=0;$script:netCalls=0;$script:fixturePath=$Asset;$testToken='fixture-credential-never-print'
function Invoke-ReleaseCurlProcess([string]$Curl,[string]$ConfigPath){
 $script:curlCalls++
 $script:seen=$ConfigPath;$bytes=[IO.File]::ReadAllBytes($ConfigPath)
 $text=[Text.Encoding]::UTF8.GetString($bytes);$acl=Get-Acl -LiteralPath $ConfigPath
 $owner=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
 $rules=@($acl.GetAccessRules($true,$false,[Security.Principal.SecurityIdentifier]))
 $script:observations=@{privateAcl=$acl.AreAccessRulesProtected;onlyOwner=(@($rules|Where-Object {$_.IdentityReference.Value-ne$owner}).Count-eq0);
  noBom=(-not($bytes.Length-ge3-and$bytes[0]-eq239-and$bytes[1]-eq187-and$bytes[2]-eq191));
  endpoint=$text.Contains('https://gitee.com/api/v5/repos/railgunhamster/RHRemote/releases/1235002/attach_files');
  credential=$text.Contains($testToken);file=$text.Contains('file=@'+$script:fixturePath.Replace([char]92,[char]47));
  direct=$text.Contains('noproxy = "*"');bounded=$text.Contains('max-time = 90');
  filename=$text.Contains('filename=source.zip;type=application/zip');curlFound=[IO.File]::Exists($Curl)}
 if($Mode-eq'throw'){throw ('fixture error '+$testToken)}
 if($Mode-eq'process-fail'){return [pscustomobject]@{ExitCode=26;StatusCode=0}}
 if($Mode-eq'http-fail'-or$Mode-eq'workflow-fail'){return [pscustomobject]@{ExitCode=0;StatusCode=400}}
 return [pscustomobject]@{ExitCode=0;StatusCode=201}
}
if($Mode-eq'newline'){$testToken+="`nInjected"}
if($Mode-eq'path'){$Asset+=';injected'}
$failed=$false;$safeError=''
try{
 if($Mode.StartsWith('workflow')){
  Add-Type -AssemblyName System.Net.Http
  $status=if($Mode-eq'workflow-auth'){[Net.HttpStatusCode]::Unauthorized}else{[Net.HttpStatusCode]::BadRequest}
  $response=New-Object Net.Http.HttpResponseMessage($status)
  $response.Content=New-Object Net.Http.StringContent('{"message":"AttachFile::upload_attach_file raise: File upload error"}')
  $awaiter=New-Object PSObject -Property @{Response=$response};$awaiter|Add-Member ScriptMethod GetResult {$this.Response}
  $script:task=New-Object PSObject -Property @{Awaiter=$awaiter};$script:task|Add-Member ScriptMethod GetAwaiter {$this.Awaiter}
  $client=New-Object PSObject;$client|Add-Member ScriptMethod PostAsync {param($uri,$content) $script:netCalls++;return $script:task}
  function Api {return @()}
  $Provider='gitee';$api='https://gitee.com/api/v5/repos/railgunhamster/RHRemote';$candidate=Split-Path -Parent $Asset;$token=$testToken
  $release=[pscustomobject]@{id=1235002};$metadata=[pscustomobject]@{fileName='source.zip';size=(Get-Item -LiteralPath $Asset).Length}
  $output=@(Upload $release $metadata)
 }else{Invoke-ReleaseCurlUpload 1235002 $Asset 'source.zip' $testToken}
}catch{$failed=$true;$safeError=$_.Exception.Message}
@{failed=$failed;error=$safeError;observations=$script:observations;
 deleted=($null-eq$script:seen-or-not(Test-Path -LiteralPath $script:seen));processCalled=($null-ne$script:seen);
 networkCalls=$script:netCalls;curlCalls=$script:curlCalls}|ConvertTo-Json -Depth 5 -Compress
''', encoding="utf-8")

    def exercise(self, mode):
        command = [os.environ.get("RHREMOTE_TEST_POWERSHELL") or shutil.which("pwsh") or "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                   "-File", str(self.wrapper), "-Publisher", str(Path(__file__).with_name("publish-release.ps1").resolve()),
                   "-Asset", str(self.asset), "-Mode", mode]
        environment = os.environ.copy()
        if Path(command[0]).name.lower() in ("powershell", "powershell.exe"):
            # A child 5.1 process must not import PowerShell 7's Security module.
            environment["PSModulePath"] = str(Path(environment["WINDIR"]) / "System32/WindowsPowerShell/v1.0/Modules")
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20, env=environment)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("fixture-credential-never-print", result.stdout + result.stderr)
        return json.loads(result.stdout)

    def test_retry_only_for_the_observed_gitee_rejection(self):
        policy = self.exercise("policy")
        self.assertTrue(policy.pop("gitee400"))
        self.assertFalse(any(policy.values()))

    def test_actual_curl_process_reads_a_bom_free_config_without_network(self):
        value = self.exercise("process-real")
        self.assertEqual(0, value["ExitCode"])
        self.assertEqual(0, value["StatusCode"])  # file:// has no HTTP response status

    def test_private_config_correct_asset_and_successful_cleanup(self):
        value = self.exercise("success")
        self.assertFalse(value["failed"])
        self.assertTrue(value["deleted"])
        self.assertTrue(value["processCalled"])
        self.assertTrue(all(value["observations"].values()), value)

    def test_process_failure_is_visible_and_config_is_removed(self):
        value = self.exercise("process-fail")
        self.assertTrue(value["failed"])
        self.assertTrue(value["deleted"])
        self.assertTrue(value["processCalled"])
        self.assertIn("credentials suppressed", value["error"])

    def test_http_failure_is_not_reported_as_a_success(self):
        value = self.exercise("http-fail")
        self.assertTrue(value["failed"])
        self.assertTrue(value["deleted"])
        self.assertTrue(value["processCalled"])

    def test_exception_with_credential_is_not_printed_and_config_is_removed(self):
        value = self.exercise("throw")
        self.assertTrue(value["failed"])
        self.assertTrue(value["deleted"])
        self.assertTrue(value["processCalled"])
        self.assertNotIn("fixture error", value["error"])

    def test_newline_and_form_path_injection_fail_before_process_start(self):
        for mode in ("newline", "path"):
            with self.subTest(mode=mode):
                value = self.exercise(mode)
                self.assertTrue(value["failed"])
                self.assertFalse(value["processCalled"])
                self.assertTrue(value["deleted"])

    def test_real_upload_branch_retries_the_same_asset_once(self):
        value = self.exercise("workflow")
        self.assertFalse(value["failed"], value)
        self.assertEqual(1, value["networkCalls"])
        self.assertEqual(1, value["curlCalls"])
        self.assertTrue(value["deleted"])
        self.assertTrue(all(value["observations"].values()), value)
        self.assertEqual(b"immutable fixture", self.asset.read_bytes())

    def test_real_upload_branch_stops_after_failed_fallback(self):
        value = self.exercise("workflow-fail")
        self.assertTrue(value["failed"])
        self.assertEqual(1, value["networkCalls"])
        self.assertEqual(1, value["curlCalls"])
        self.assertTrue(value["deleted"])

    def test_real_upload_branch_never_retries_an_authentication_error(self):
        value = self.exercise("workflow-auth")
        self.assertTrue(value["failed"])
        self.assertEqual(1, value["networkCalls"])
        self.assertEqual(0, value["curlCalls"])
        self.assertTrue(value["deleted"])


if __name__ == "__main__":
    unittest.main()
