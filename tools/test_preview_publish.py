"""Release preflight regressions. No credentials or network requests are used."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class PreviewPublishTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.publisher = Path(__file__).with_name("publish-release.ps1").resolve()
        self.wrapper = self.directory / "validate.ps1"
        self.wrapper.write_text("""param([string]$Publisher,[string]$Candidate,[string]$Provider='github',[switch]$Preview)
$ErrorActionPreference='Stop'
& $Publisher -Provider $Provider -CandidateDirectory $Candidate -Commit ('a'*40) -Preview:$Preview -ValidateOnly | ConvertTo-Json -Compress
""", encoding="utf-8")
        (self.directory / "fixture.apk").write_bytes(b"preflight-only-fixture")
        self.plan = {
            "version": "0.2.4-preview.1", "tag": "v0.2.4-preview.1", "preview": True,
            "commit": "a" * 40,
            "artifacts": [{"fileName": "fixture.apk", "size": 22,
                           "sha256": hashlib.sha256(b"preflight-only-fixture").hexdigest()}],
        }
        self.plan["artifacts"][0]["size"] = (self.directory / "fixture.apk").stat().st_size

    def run_preflight(self, preview=True, provider="github"):
        (self.directory / "publish-plan.json").write_text(json.dumps(self.plan), encoding="utf-8")
        command = [shutil.which("pwsh") or "powershell", "-NoProfile", "-NonInteractive",
                   "-File", str(self.wrapper), "-Publisher", str(self.publisher),
                   "-Candidate", str(self.directory), "-Provider", provider]
        if preview:
            command.append("-Preview")
        return subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20)

    def assert_rejected(self, result, reason):
        self.assertNotEqual(0, result.returncode)
        self.assertIn(reason, result.stderr)
        self.assertNotIn("credential lookup", result.stderr)

    def test_preview_explicitly_is_not_latest(self):
        result = self.run_preflight()
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads(result.stdout)
        self.assertTrue(value["prerelease"])
        self.assertFalse(value["makeLatest"])

    def test_stable_behavior_stays_stable(self):
        self.plan.update(version="0.2.3", tag="v0.2.3")
        del self.plan["preview"]
        result = self.run_preflight(preview=False)
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads(result.stdout)
        self.assertFalse(value["prerelease"])
        self.assertTrue(value["makeLatest"])

    def test_preview_cannot_be_promoted_by_omitting_switch(self):
        self.assert_rejected(self.run_preflight(preview=False), "Unexpected release identity")

    def test_stable_source_commit_must_match_when_recorded(self):
        self.plan.update(version="0.2.4", tag="v0.2.4", preview=False, commit="b" * 40)
        self.assert_rejected(self.run_preflight(preview=False), "Release plan source commit differs")

    def test_switch_cannot_turn_unmarked_plan_into_preview(self):
        del self.plan["preview"]
        self.assert_rejected(self.run_preflight(), "Preview requires both")

    def test_preview_never_uploads_stable_update_feed(self):
        self.plan["artifacts"][0]["fileName"] = "releases.android.json"
        self.assert_rejected(self.run_preflight(), "Preview must contain assets")

    def test_gitee_latest_behavior_requires_separate_acceptance(self):
        self.assert_rejected(self.run_preflight(provider="gitee"), "Preview publication currently supports GitHub")

    def test_changed_asset_fails_before_credentials(self):
        (self.directory / "fixture.apk").write_bytes(b"tampered")
        self.assert_rejected(self.run_preflight(), "Asset changed")

    def test_another_source_commit_cannot_publish_this_preview(self):
        self.plan["commit"] = "b" * 40
        self.assert_rejected(self.run_preflight(), "Preview plan source commit differs")


if __name__ == "__main__":
    unittest.main()
