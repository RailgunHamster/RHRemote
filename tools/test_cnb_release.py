"""Exercise the actual CNB URL/ownership guards without credentials or networking."""
from pathlib import Path
import json
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parent

class CnbPublishingTests(unittest.TestCase):
    def test_public_paths_and_confirmation_ownership(self):
        code=r'''
. $args[0]
$base='https://api.cnb.cool/railgunhamster/RHRemote/-/releases/123/asset-upload-confirmation/upload-test/build.json'
$results=@{
 latest=(Get-RHRemoteCnbPublicUrl 'railgunhamster/RHRemote' latest releases.win.json)
 version=(Get-RHRemoteCnbPublicUrl 'railgunhamster/RHRemote' v0.2.11 RHRemote-0.2.11-full.nupkg)
 confirmation=(Get-RHRemoteCnbConfirmationPath 'railgunhamster/RHRemote' 123 ($base+'?ttl=0'))
 rejected=@()
}
foreach($bad in @(($base+'?ttl=1'),($base+'?token=bad'),$base.Replace('api.cnb.cool','example.org'),$base.Replace('/123/','/999/'),$base.Replace('/RHRemote/','/RHFiles/'))){
 try{$null=Get-RHRemoteCnbConfirmationPath 'railgunhamster/RHRemote' 123 $bad;$results.rejected+=,$false}catch{$results.rejected+=,$true}
}
$results|ConvertTo-Json -Compress
'''
        with tempfile.TemporaryDirectory(prefix='rhremote-cnb-test-') as folder:
            script=Path(folder)/'contract.ps1'
            script.write_text("param($path)\n$ErrorActionPreference='Stop'\n"+code.replace('$args[0]','$path'),encoding='utf-8')
            result=subprocess.run(['pwsh','-NoProfile','-NonInteractive','-File',str(script),str(ROOT/'cnb-release.ps1')],
                capture_output=True,text=True,encoding='utf-8',timeout=30)
        self.assertEqual(result.returncode,0,result.stderr)
        value=json.loads(result.stdout.strip())
        self.assertEqual(value['latest'],'https://cnb.cool/railgunhamster/RHRemote/-/releases/latest/download/releases.win.json')
        self.assertEqual(value['version'],'https://cnb.cool/railgunhamster/RHRemote/-/releases/download/v0.2.11/RHRemote-0.2.11-full.nupkg')
        self.assertTrue(value['confirmation'].endswith('?ttl=0'))
        self.assertEqual(value['rejected'],[True]*5)

if __name__=='__main__':unittest.main()
