"""Pinned source and archive preservation guards, including duplicate basenames."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("prepare_mf", Path(__file__).with_name("prepare-mf.py"))
mf = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mf)


def archive(parts):
    result = b"!<arch>\n"
    for name, payload in parts:
        header = (name.ljust(16) + "0".ljust(12) + "0".ljust(6) + "0".ljust(6) +
                  "100644".ljust(8) + str(len(payload)).ljust(10) + "`\n").encode("ascii")
        result += header + payload + (b"\n" if len(payload) % 2 else b"")
    return result


class ModuleGuards(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = self.root / "source"
        self.prepared = self.root / "vendor"
        self.output = self.root / "generated"
        source = self.source / "libavcodec/mfenc.c"
        source.parent.mkdir(parents=True)
        source.write_text(mf.OLD + "\n", encoding="utf-8")
        config = self.prepared / "include/config.h"
        config.parent.mkdir(parents=True)
        config.write_bytes(b"generated config")
        avconfig = self.prepared / "include/libavutil/avconfig.h"
        avconfig.parent.mkdir()
        avconfig.write_bytes(b"generated avconfig")
        self.library = self.prepared / "lib/libavcodec.a"
        self.library.parent.mkdir()
        self.parts = [("blockdsp.o", b"generic"), ("mfenc.o", b"original MF"),
                      ("blockdsp.o", b"x86 version")]
        self.library.write_bytes(archive([(n + "/", p) for n, p in self.parts]))
        self.metadata = {
            "revision": "fixture source revision",
            "sourceFileSha256": mf.digest(source.read_bytes()),
            "configSha256": mf.digest(config.read_bytes()),
            "avconfigSha256": mf.digest(avconfig.read_bytes()),
            "libavcodecSha256": mf.digest(self.library.read_bytes()),
            "mfencObjectSha256": mf.digest(b"original MF"),
        }
        self.module = self.root / "mfenc.o"
        self.module.write_bytes(b"compiler output")

    def test_duplicate_basenames_are_retained_in_original_order(self):
        self.assertEqual(self.parts, mf.members(self.library.read_bytes()))
        mf.prepare(self.source, self.prepared, self.output, self.metadata)
        self.assertEqual(mf.NEW + "\n", (self.output / "mfenc.c").read_text())
        provenance = json.loads((self.output / "module-source.json").read_text())
        self.assertEqual(mf.digest((self.output / "mfenc.c").read_bytes()), provenance["modifiedSourceSha256"])
        self.assertEqual(self.parts, mf.members(self.library.read_bytes()))

    def test_gnu_bsd_names_and_linker_indexes(self):
        data = archive([("/", b"linker index"), ("//", b"very-long-member.o/\n"),
                        ("/0", b"long"), ("#1/8", b"mfenc.o\0payload"), ("/SYM64/", b"index")])
        self.assertEqual([("very-long-member.o", b"long"), ("mfenc.o", b"payload")], mf.members(data))

    def test_malformed_archives_fail_before_output(self):
        valid = self.library.read_bytes()
        for data in (b"", valid[:-2], b"!<arch>\ninvalid", archive([("/99", b"bad")])):
            with self.subTest(data=data[:20]), self.assertRaises(ValueError):
                mf.members(data)

    def test_mf_member_must_be_unique_even_if_other_duplicates_are_valid(self):
        for parts in ([], self.parts + [("mfenc.o", b"duplicate")]):
            with self.subTest(parts=parts), self.assertRaisesRegex(ValueError, "exactly one"):
                mf.mf_object(parts)

    def test_each_pinned_input_is_checked_before_any_generated_output(self):
        paths = [self.source / "libavcodec/mfenc.c", self.prepared / "include/config.h",
                 self.prepared / "include/libavutil/avconfig.h", self.library]
        for path in paths:
            original = path.read_bytes()
            path.write_bytes(original + b"changed")
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "Pinned FFmpeg input differs"):
                mf.prepare(self.source, self.prepared, self.output, self.metadata)
            self.assertFalse(self.output.exists())
            path.write_bytes(original)

    def test_patch_anchor_and_original_member_have_independent_guards(self):
        source = self.source / "libavcodec/mfenc.c"
        source.write_text(mf.OLD + mf.OLD)
        self.metadata["sourceFileSha256"] = mf.digest(source.read_bytes())
        with self.assertRaisesRegex(ValueError, "anchor differs"):
            mf.prepare(self.source, self.prepared, self.output, self.metadata)
        source.write_text(mf.OLD)
        self.metadata["sourceFileSha256"] = mf.digest(source.read_bytes())
        self.metadata["mfencObjectSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "Pinned MF object differs"):
            mf.prepare(self.source, self.prepared, self.output, self.metadata)
        self.assertFalse(self.output.exists())

    def test_vendor_archive_cannot_be_the_output(self):
        original = self.library.read_bytes()
        with self.assertRaisesRegex(ValueError, "must not be overwritten"):
            mf.rebuild(self.library, self.module, self.library, "unused", "unused", self.metadata)
        self.assertEqual(original, self.library.read_bytes())

    def test_archive_rebuild_preserves_duplicate_payloads_and_order(self):
        # Real system ar/ranlib on Linux CI and LLVM equivalents on Windows.
        archiver = shutil.which("ar") or shutil.which("llvm-ar")
        ranlib = shutil.which("ranlib") or shutil.which("llvm-ranlib")
        if not archiver or not ranlib:
            llvm = Path(r"C:\Program Files\LLVM\bin")
            archiver, ranlib = str(llvm / "llvm-ar.exe"), str(llvm / "llvm-ranlib.exe")
        if not Path(archiver).is_file() or not Path(ranlib).is_file():
            self.skipTest("ar and ranlib unavailable")
        original = self.library.read_bytes()
        output = self.output / "libavcodec.a"
        mf.rebuild(self.library, self.module, output, archiver, ranlib, self.metadata)
        self.assertEqual([(n, self.module.read_bytes() if n == "mfenc.o" else p) for n, p in self.parts],
                         mf.members(output.read_bytes()))
        self.assertEqual(original, self.library.read_bytes())
        self.assertEqual(2, json.loads(output.with_suffix(".provenance.json").read_text())["unchangedMembers"])

    def test_unrelated_member_changes_or_order_changes_never_publish(self):
        for changed in ([self.parts[2], self.parts[1], self.parts[0]],
                        [("blockdsp.o", b"corruption"), self.parts[1], self.parts[2]],
                        self.parts + [("extra.o", b"extra")], self.parts):
            def run(command, **kwargs):
                if command[0] == "fixture-ar":
                    parts = [(n, self.module.read_bytes() if n == "mfenc.o" and changed != self.parts else p)
                             for n, p in changed]
                    Path(command[2]).write_bytes(archive([(n + "/", p) for n, p in parts]))
            with self.subTest(changed=changed), patch.object(mf.subprocess, "run", side_effect=run):
                with self.assertRaises(ValueError):
                    mf.rebuild(self.library, self.module, self.output / "libavcodec.a", "fixture-ar", "fixture-ranlib", self.metadata)
            self.assertFalse((self.output / "libavcodec.a").exists())
            self.assertEqual([], list(self.output.glob("*.tmp")))


if __name__ == "__main__":
    unittest.main()
