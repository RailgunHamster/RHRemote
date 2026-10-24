"""Reject incorrect committed sources and Windows feeds before APK/credential use."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class ReleaseCandidateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="rhremote-release-candidate-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tool = self.root / "tools/prepare-release-candidate.ps1"
        self.tool.parent.mkdir()
        shutil.copyfile(Path(__file__).with_name(self.tool.name), self.tool)
        self.cargo = self.root / "client/crates/rhremote-ui/Cargo.toml"
        self.cargo.parent.mkdir(parents=True)
        self.cargo.write_text('[package]\nname="rhremote-ui"\nversion = "0.2.7"\n', encoding="utf-8")
        (self.root / ".gitignore").write_text("dist/\n", encoding="utf-8")
        for args in [("init",), ("config", "user.name", "Release Fixture"),
                     ("config", "user.email", "fixture@example.invalid"), ("add", "."),
                     ("commit", "-m", "fixture")]:
            subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True)
        self.windows = self.root / "dist/windows"
        self.windows.mkdir(parents=True)
        self.package = self.windows / "RHRemote-0.2.7-full.nupkg"
        self.package.write_bytes(b"release-guard-fixture")
        self.asset = {"FileName": self.package.name, "Version": "0.2.7",
                      "Size": self.package.stat().st_size,
                      "SHA256": hashlib.sha256(self.package.read_bytes()).hexdigest()}

    def run_candidate(self, assets=None):
        (self.windows / "releases.win.json").write_text(
            json.dumps({"Assets": assets if assets is not None else [self.asset]}), encoding="utf-8")
        return subprocess.run([shutil.which("pwsh") or "powershell", "-NoProfile", "-NonInteractive",
                               "-File", str(self.tool), "-WindowsDirectory", str(self.windows)],
                              capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20)

    def rejected(self, result, reason):
        self.assertNotEqual(0, result.returncode)
        self.assertIn(reason, result.stderr)
        self.assertFalse((self.root / "dist/release-candidates").exists())

    def test_dirty_source_cannot_produce_an_archive_of_a_different_commit(self):
        self.cargo.write_text(self.cargo.read_text() + "# uncommitted\n", encoding="utf-8")
        self.rejected(self.run_candidate(), "clean committed product sources")

    def test_ui_version_must_match_the_release_identity(self):
        self.cargo.write_text(self.cargo.read_text().replace("0.2.7", "0.2.6"), encoding="utf-8")
        subprocess.run(["git", "-C", str(self.root), "commit", "-am", "older UI"], check=True, capture_output=True)
        self.rejected(self.run_candidate(), "Update the Windows UI version")

    def test_feed_version_cannot_point_to_another_windows_release(self):
        self.asset["Version"] = "0.2.6"
        self.rejected(self.run_candidate(), "Unexpected Windows release feed")

    def test_feed_cannot_read_a_package_outside_its_directory(self):
        self.asset["FileName"] = "../outside.nupkg"
        self.rejected(self.run_candidate(), "Unsafe Windows package filename")

    def test_changed_package_bytes_are_rejected(self):
        self.package.write_bytes(b"tampered")
        self.rejected(self.run_candidate(), "Windows package checksum mismatch")

    def test_multiple_full_packages_are_ambiguous(self):
        self.rejected(self.run_candidate([self.asset, self.asset]), "Unexpected Windows release feed")


if __name__ == "__main__":
    unittest.main()
