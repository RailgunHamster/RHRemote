"""QSV overlays must preserve pinned inputs, MF, duplicate members and original order."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from test_prepare_mf import archive

spec = importlib.util.spec_from_file_location("prepare_qsv", Path(__file__).with_name("prepare-qsv.py"))
qsv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qsv)


class QsvGuards(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.prepared = self.root / "vendor"
        self.source = self.root / "source"
        self.output = self.root / "output"
        self.parts = {
            "codec": [("qsv.o", b"original QSV"), ("mfenc.o", b"original MF"),
                      ("blockdsp.o", b"generic"), ("blockdsp.o", b"x86")]
                     + [(f"other{n}.o", bytes([n % 256])) for n in range(210)],
            "util": [("hwcontext_qsv.o", b"original utility QSV")]
                    + [(f"other{n}.o", bytes([n % 256])) for n in range(112)]
        }
        include = self.prepared / "include/libavutil"
        include.mkdir(parents=True)
        (include / "avconfig.h").write_bytes(b"original generated avconfig")
        (include.parent / "config.h").write_bytes(b"original generated config")
        (self.prepared / "lib").mkdir()
        self.metadata = {"revision": "fixture", "libraries": {}, "sources": {},
                         "configSha256": qsv.mf.digest((include.parent / "config.h").read_bytes()),
                         "avconfigSha256": qsv.mf.digest((include / "avconfig.h").read_bytes())}
        self.modules = {}
        for kind, filename, member in (("codec", "libavcodec.a", "qsv.o"), ("util", "libavutil.a", "hwcontext_qsv.o")):
            path = self.prepared / "lib" / filename
            path.write_bytes(archive([(n + "/", data) for n, data in self.parts[kind]]))
            self.metadata["libraries"][kind] = {"file": filename, "sha256": qsv.mf.digest(path.read_bytes()),
                "members": len(self.parts[kind]), "member": member,
                "objectSha256": qsv.mf.digest(qsv.member(self.parts[kind], member))}
            self.modules[kind] = self.root / member
            self.modules[kind].write_bytes(("compiler output " + kind).encode())
        self.base = self.root / "libavcodec-mf.a"
        self.base.write_bytes(archive([(n + "/", b"compiled MF" if n == "mfenc.o" else data) for n, data in self.parts["codec"]]))
        self.provenance = self.root / "mf-provenance.json"
        self.provenance.write_text(json.dumps({"originalLibrarySha256": self.metadata["libraries"]["codec"]["sha256"],
            "librarySha256": qsv.mf.digest(self.base.read_bytes()), "modifiedMember": "mfenc.o",
            "compiledMemberSha256": qsv.mf.digest(b"compiled MF"), "unchangedMembers": 213}), encoding="utf-8")
        self.archiver = shutil.which("ar") or shutil.which("llvm-ar") or "C:/msys64/ucrt64/bin/ar.exe"
        self.ranlib = shutil.which("ranlib") or shutil.which("llvm-ranlib") or "C:/msys64/ucrt64/bin/ranlib.exe"
        self.old = {"hwcontext_qsv.c": '''#define MFXUnload(a) do { } while(0)
#endif
    *psession = NULL;

    if (!*ploader) {
MFXVideoCORE_SetHandle(a, b, c);
MFXVideoCORE_SetHandle(d, e, f);
''', "qsv.c": '''#define MFXUnload(a) do { } while(0)
#endif
    int ret;

    err = MFXQueryIMPL(parent_session, &impl);
    ret = qsv_create_mfx_session(avctx, impl, &ver, gpu_copy, &session,
                                 &loader);
    if (ret)
        return ret;
err = MFXVideoCORE_SetHandle(session, handle_type, handle);
if (QSV_RUNTIME_VERSION_ATLEAST(ver, 1, 25)) {
'''}
        for name, text in self.old.items():
            path = self.source / ("libavutil" if name == "hwcontext_qsv.c" else "libavcodec") / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8", newline="\n")
            self.metadata["sources"][name] = {"path": path.relative_to(self.source).as_posix(), "sha256": qsv.mf.digest(path.read_bytes())}

    def rebuild(self, kind="codec", **overrides):
        pin = self.metadata["libraries"][kind]
        parameters = {"kind": kind, "original": self.prepared / "lib" / pin["file"],
                      "base": self.base if kind == "codec" else self.prepared / "lib" / pin["file"],
                      "module": self.modules[kind], "output": self.output / pin["file"],
                      "ar": self.archiver, "ranlib": self.ranlib, "metadata": self.metadata,
                      "mf_provenance": self.provenance if kind == "codec" else None}
        parameters.update(overrides)
        qsv.rebuild(**parameters)
        return parameters["output"]

    def test_actual_archiver_preserves_all_other_members_and_duplicate_order(self):
        self.assertTrue(Path(self.archiver).is_file() and Path(self.ranlib).is_file())
        for kind in ("codec", "util"):
            with self.subTest(kind=kind):
                output = self.rebuild(kind)
                expected = [(n, self.modules[kind].read_bytes() if n == self.metadata["libraries"][kind]["member"]
                             else b"compiled MF" if kind == "codec" and n == "mfenc.o" else data)
                            for n, data in self.parts[kind]]
                self.assertEqual(expected, qsv.mf.members(output.read_bytes()))
                proof = json.loads(output.with_suffix(".provenance.json").read_text())
                self.assertEqual(212 if kind == "codec" else 112, proof["unchangedMembers"])
                self.assertEqual(qsv.mf.digest(output.read_bytes()), proof["librarySha256"])

    def test_source_and_header_provenance_uses_compiler_inputs_without_vendor_changes(self):
        qsv.prepare(self.source, self.prepared, self.output, self.metadata)
        proof = json.loads((self.output / "module-source.json").read_text())
        self.assertEqual((qsv.HERE / "qsv-fast.h").read_bytes(), (self.output / "qsv-fast.h").read_bytes())
        for name, pin in self.metadata["sources"].items():
            self.assertEqual(pin["sha256"], qsv.mf.digest((self.source / pin["path"]).read_bytes()))
            self.assertEqual(proof["sources"][name]["modifiedSha256"], qsv.mf.digest((self.output / name).read_bytes()))
        self.assertEqual(proof["helperSha256"], qsv.mf.digest((self.output / "qsv-fast.h").read_bytes()))

    def test_each_pinned_input_is_rejected_before_generating_any_file(self):
        paths = [self.source / pin["path"] for pin in self.metadata["sources"].values()]
        paths += [self.prepared / "include/config.h", self.prepared / "include/libavutil/avconfig.h"]
        paths += [self.prepared / "lib" / pin["file"] for pin in self.metadata["libraries"].values()]
        for path in paths:
            old = path.read_bytes()
            path.write_bytes(old + b"changed")
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "Pinned FFmpeg input differs"):
                qsv.prepare(self.source, self.prepared, self.output, self.metadata)
            self.assertFalse(self.output.exists())
            path.write_bytes(old)

    def test_changed_patch_anchor_is_rejected_even_with_a_matching_fixture_source_hash(self):
        for name in self.old:
            for changed in (self.old[name].replace("*psession = NULL", "*psession = nullptr") if name == "hwcontext_qsv.c"
                            else self.old[name].replace("int ret;", "long ret;"), self.old[name] + self.old[name]):
                with self.subTest(name=name), self.assertRaisesRegex(ValueError, "anchor differs"):
                    qsv.patch_source(name, changed)

    def test_original_archive_and_target_member_have_independent_guards(self):
        pin = self.metadata["libraries"]["codec"]
        pin["objectSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "inventory differs"):
            self.rebuild()
        for parts in ([], self.parts["codec"] + [("qsv.o", b"duplicate")]):
            with self.subTest(parts=len(parts)), self.assertRaisesRegex(ValueError, "exactly one"):
                qsv.member(parts, "qsv.o")
        self.assertFalse(self.output.exists())

    def test_mf_intermediate_requires_matching_provenance(self):
        original = json.loads(self.provenance.read_text())
        for field in original:
            changed = dict(original, **{field: 0 if field == "unchangedMembers" else "wrong"})
            self.provenance.write_text(json.dumps(changed))
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "MF intermediate provenance differs"):
                self.rebuild()
        with self.assertRaisesRegex(ValueError, "requires the actual MF"):
            self.rebuild(mf_provenance=None)
        self.assertFalse(self.output.exists())

    def test_changes_outside_mf_in_intermediate_are_rejected_even_after_rehash(self):
        parts = qsv.mf.members(self.base.read_bytes())
        parts[4] = (parts[4][0], b"unrelated corruption")
        self.base.write_bytes(archive([(n + "/", data) for n, data in parts]))
        proof = json.loads(self.provenance.read_text())
        proof["librarySha256"] = qsv.mf.digest(self.base.read_bytes())
        self.provenance.write_text(json.dumps(proof))
        with self.assertRaisesRegex(ValueError, "Unrelated intermediate"):
            self.rebuild()
        self.assertFalse(self.output.exists())

    def test_intermediate_order_change_cannot_be_hidden_by_matching_provenance(self):
        parts = qsv.mf.members(self.base.read_bytes())
        parts[2], parts[3] = parts[3], parts[2]
        self.base.write_bytes(archive([(n + "/", data) for n, data in parts]))
        proof = json.loads(self.provenance.read_text())
        proof["librarySha256"] = qsv.mf.digest(self.base.read_bytes())
        self.provenance.write_text(json.dumps(proof))
        with self.assertRaisesRegex(ValueError, "Unrelated intermediate"):
            self.rebuild()

    def test_final_corruption_or_missing_replacement_does_not_publish_an_archive(self):
        for kind in ("codec", "util"):
            def corrupt(command, **kwargs):
                if command[0] == "fixture-ar":
                    parts = self.parts[kind].copy()
                    Path(command[2]).write_bytes(archive([(n + "/", data) for n, data in parts]))
            with self.subTest(kind=kind), patch.object(qsv.subprocess, "run", side_effect=corrupt):
                with self.assertRaisesRegex(ValueError, "Final archive member differs"):
                    self.rebuild(kind, ar="fixture-ar", ranlib="fixture-ranlib")
            self.assertFalse((self.output / self.metadata["libraries"][kind]["file"]).exists())
            self.assertFalse(list(self.output.glob("*.tmp")))

    def test_failed_archiver_cleans_only_its_temporary_and_preserves_inputs(self):
        original = self.base.read_bytes()
        with patch.object(qsv.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "fixture")):
            with self.assertRaises(subprocess.CalledProcessError):
                self.rebuild()
        self.assertEqual(original, self.base.read_bytes())
        self.assertFalse(list(self.output.glob("*.tmp")))
        self.assertFalse((self.output / "libavcodec.a").exists())

    def test_no_input_can_be_used_as_output_and_object_name_must_match(self):
        for target in (self.base, self.modules["codec"], self.prepared / "lib/libavcodec.a"):
            before = target.read_bytes()
            with self.subTest(target=target), self.assertRaisesRegex(ValueError, "must not be overwritten"):
                self.rebuild(output=target)
            self.assertEqual(before, target.read_bytes())
        wrong = self.root / "wrong.o"
        wrong.write_bytes(b"compiler object")
        with self.assertRaisesRegex(ValueError, "filename differs"):
            self.rebuild(module=wrong)


if __name__ == "__main__":
    unittest.main()
