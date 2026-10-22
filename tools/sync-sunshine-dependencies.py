"""Keep monorepo submodule registrations aligned with the maintained subtree."""
import argparse
import configparser
import io
import json
import subprocess
from pathlib import Path

PREFIX = "server/sunshine/"

def configuration(root):
    upstream = configparser.ConfigParser()
    upstream.read(root / PREFIX / ".gitmodules", encoding="utf-8")
    existing = configparser.ConfigParser()
    existing.read(root / ".gitmodules", encoding="utf-8")
    for section in list(existing.sections()):
        if existing[section].get("path", "").startswith(PREFIX):
            existing.remove_section(section)
    links = {}
    for line in subprocess.check_output(["git", "-C", str(root), "ls-files", "--stage", "--", PREFIX], text=True).splitlines():
        fields, path = line.split("\t", 1)
        mode, sha, stage = fields.split()
        if mode == "160000" and stage == "0":
            links[path] = sha
    dependencies = []
    for section in upstream.sections():
        module = upstream[section]
        relative, url = module["path"], module["url"]
        path = PREFIX + relative
        if path not in links:
            raise ValueError(f"Missing or conflicted pinned submodule: {path}")
        existing[f'submodule "sunshine/{relative}"'] = {"path": path, "url": url}
        dependencies.append(dict(path=relative, repository=url, revision=links[path]))
    result = io.StringIO()
    existing.write(result, space_around_delimiters=True)
    return result.getvalue(), dependencies

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    text, dependencies = configuration(root)
    metadata_path = root / "server/UPSTREAM.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if args.check:
        current = configparser.ConfigParser()
        current.read_string((root / ".gitmodules").read_text(encoding="utf-8"))
        expected = configparser.ConfigParser()
        expected.read_string(text)
        if current != expected or metadata["dependencies"] != dependencies:
            raise ValueError("Sunshine dependencies changed; run sync-sunshine-dependencies.py")
    else:
        if args.revision:
            if len(args.revision) != 40 or any(c not in "0123456789abcdef" for c in args.revision):
                raise ValueError("Expected full upstream commit SHA")
            metadata["revision"] = args.revision
        metadata["dependencies"] = dependencies
        metadata["integration"] = {"method": "git-subtree", "prefix": PREFIX.rstrip("/"), "history": "squash"}
        (root / ".gitmodules").write_text(text, encoding="utf-8", newline="\n")
        metadata_path.write_text(json.dumps(metadata, indent=2)+"\n", encoding="utf-8", newline="\n")
    print(f"Sunshine dependency pins verified: {len(dependencies)}")

if __name__ == "__main__":
    main()
