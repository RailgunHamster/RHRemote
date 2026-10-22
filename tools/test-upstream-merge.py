"""Exercise real subtree updates, including preservation and conflict handling."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parent


def run(root, *args, check=True):
    result = subprocess.run(args, cwd=root, text=True, encoding="utf-8", errors="replace", capture_output=True)
    if check and result.returncode:
        raise AssertionError(f"{args[0]} failed: {result.stdout}\n{result.stderr}")
    return result


def git(root, *args):
    return run(root, "git", *args).stdout.strip()


def commit(root, message):
    git(root, "add", ".")
    git(root, "commit", "-qm", message)
    return git(root, "rev-parse", "HEAD")


class SubtreeUpdate(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="rhremote-merge-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.upstream = self.root / "upstream"
        self.product = self.root / "product"
        for repo in (self.upstream, self.product):
            repo.mkdir()
            git(repo, "init", "-qb", "main")
            git(repo, "config", "user.name", "RHRemote merge regression")
            git(repo, "config", "user.email", "merge-test@invalid.example")
            git(repo, "config", "core.autocrlf", "false")
        (self.upstream / ".gitmodules").write_text("")
        (self.upstream / "capture.txt").write_text("official capture\n")
        (self.upstream / "encoder.txt").write_text("official encoder\n")
        self.base = commit(self.upstream, "Official baseline")
        (self.product / "tools").mkdir()
        (self.product / "server").mkdir()
        for name in ("update-sunshine.ps1", "sync-sunshine-dependencies.py"):
            shutil.copyfile(TOOLS / name, self.product / "tools" / name)
        metadata = {"repository": str(self.upstream), "revision": self.base, "dependencies": []}
        (self.product / "server/UPSTREAM.json").write_text(json.dumps(metadata))
        (self.product / ".gitmodules").write_text("")
        commit(self.product, "Product")
        git(self.product, "subtree", "add", "--prefix=server/sunshine", str(self.upstream), self.base, "--squash")
        self.capture = self.product / "server/sunshine/capture.txt"
        self.capture.write_text("RHRemote capture fix\n")
        commit(self.product, "RHRemote local fix")

    def update(self, revision):
        return run(self.product, "pwsh", "-NoProfile", "-File", "tools/update-sunshine.ps1", "-Ref", revision, check=False)

    def test_upstream_merge_preserves_local_fix(self):
        (self.upstream / "encoder.txt").write_text("new official encoder\n")
        revision = commit(self.upstream, "Official encoder update")
        result = self.update(revision)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.capture.read_text(), "RHRemote capture fix\n")
        self.assertEqual((self.product / "server/sunshine/encoder.txt").read_text(), "new official encoder\n")
        metadata = json.loads((self.product / "server/UPSTREAM.json").read_text())
        self.assertEqual(metadata["revision"], revision)
        self.assertEqual(git(self.product, "remote", "get-url", "sunshine-upstream"), str(self.upstream))

    def test_conflict_stops_without_discarding_either_change(self):
        (self.upstream / "capture.txt").write_text("new official capture\n")
        revision = commit(self.upstream, "Conflicting official fix")
        result = self.update(revision)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("UU server/sunshine/capture.txt", git(self.product, "status", "--porcelain"))
        conflict = self.capture.read_text()
        self.assertIn("RHRemote capture fix", conflict)
        self.assertIn("new official capture", conflict)
        metadata = json.loads((self.product / "server/UPSTREAM.json").read_text())
        self.assertEqual(metadata["revision"], self.base)
        git(self.product, "rev-parse", "--verify", "MERGE_HEAD")

    def test_dirty_tracked_source_is_rejected(self):
        self.capture.write_text("unfinished local work\n")
        result = self.update(self.base)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.capture.read_text(), "unfinished local work\n")
        self.assertNotIn("sunshine-upstream", git(self.product, "remote"))

    def test_wrong_remote_is_rejected(self):
        git(self.product, "remote", "add", "sunshine-upstream", str(self.root / "wrong"))
        before = git(self.product, "rev-parse", "HEAD")
        result = self.update(self.base)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(git(self.product, "rev-parse", "HEAD"), before)


if __name__ == "__main__":
    unittest.main(verbosity=2)
