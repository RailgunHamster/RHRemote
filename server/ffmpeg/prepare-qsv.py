"""Recompile two pinned QSV units, preserving MF and every unrelated archive member."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("rhremote_mf_archive", HERE / "prepare-mf.py")
mf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mf)


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("Pinned QSV source anchor differs")
    return text.replace(old, new)


def patch_source(name, text):
    anchor = '#define MFXUnload(a) do { } while(0)\n#endif'
    text = replace_once(text, anchor, anchor + '\n#include "qsv-fast.h"')
    if name == "hwcontext_qsv.c":
        anchor = '    *psession = NULL;\n\n    if (!*ploader) {'
        text = replace_once(text, anchor, '''    *psession = NULL;

#if defined(_WIN32) && CONFIG_D3D11VA
    if (handle_type == MFX_HANDLE_D3D11_DEVICE && implementation != MFX_IMPL_SOFTWARE) {
        mfxLoader fast_loader = *ploader;
        int fast = fast_loader
            ? rhremote_qsv_try_session(fast_loader, handle, pver, 0, NULL, psession)
            : rhremote_qsv_new_session(handle, pver, &fast_loader, psession);
        if (!fast) {
            if (!*ploader)
                *ploader = fast_loader;
            av_log(ctx, AV_LOG_VERBOSE, "RHRemote QSV fast device session ready\\n");
            return 0;
        }
    }
#endif

    if (!*ploader) {''')
        if text.count('MFXVideoCORE_SetHandle(') != 2:
            raise ValueError("Pinned QSV device binding sites differ")
        text = text.replace('MFXVideoCORE_SetHandle(', 'rhremote_qsv_set_handle(')
    elif name == "qsv.c":
        text = replace_once(text,
            '    int ret;\n\n    err = MFXQueryIMPL(parent_session, &impl);',
            '    int ret;\n    int rhremote_joined = 0;\n\n    err = MFXQueryIMPL(parent_session, &impl);')
        text = replace_once(text, '''    ret = qsv_create_mfx_session(avctx, impl, &ver, gpu_copy, &session,
                                 &loader);
    if (ret)
        return ret;''', '''    ret = AVERROR_UNKNOWN;
#if QSV_ONEVPL && defined(_WIN32) && CONFIG_D3D11VA
    if (loader && handle && handle_type == MFX_HANDLE_D3D11_DEVICE) {
        ret = rhremote_qsv_try_session(loader, handle, &ver, impl, NULL, &session);
        if (!ret) {
            err = MFXJoinSession(parent_session, session);
            if (err == MFX_ERR_NONE) {
                rhremote_joined = 1;
                av_log(avctx, AV_LOG_VERBOSE, "RHRemote QSV fast child session ready\\n");
            } else {
                MFXClose(session);
                session = NULL;
                ret = AVERROR_UNKNOWN;
            }
        }
    }
#endif
    if (ret) {
        ret = qsv_create_mfx_session(avctx, impl, &ver, gpu_copy, &session,
                                     &loader);
        if (ret)
            return ret;
    }''')
        text = replace_once(text, 'err = MFXVideoCORE_SetHandle(session, handle_type, handle);',
                            'err = rhremote_qsv_set_handle(session, handle_type, handle);')
        text = replace_once(text, 'if (QSV_RUNTIME_VERSION_ATLEAST(ver, 1, 25)) {',
                            'if (!rhremote_joined && QSV_RUNTIME_VERSION_ATLEAST(ver, 1, 25)) {')
    else:
        raise ValueError("Unexpected QSV translation unit")
    return text


def member(parts, name):
    found = [data for key, data in parts if key == name]
    if len(found) != 1:
        raise ValueError("Expected exactly one QSV archive member")
    return found[0]


def original_archive(path, pin):
    data = mf.checked(path, pin["sha256"])
    parts = mf.members(data)
    if len(parts) != pin["members"] or mf.digest(member(parts, pin["member"])) != pin["objectSha256"]:
        raise ValueError("Pinned QSV archive inventory differs")
    return data, parts


def prepare(source, prepared, output, metadata):
    mf.checked(prepared / "include/config.h", metadata["configSha256"])
    mf.checked(prepared / "include/libavutil/avconfig.h", metadata["avconfigSha256"])
    for pin in metadata["libraries"].values():
        original_archive(prepared / "lib" / pin["file"], pin)
    sources = {}
    generated = {}
    for name, pin in metadata["sources"].items():
        data = mf.checked(source / pin["path"], pin["sha256"])
        generated[name] = patch_source(name, data.decode("utf-8")).encode("utf-8")
        sources[name] = {"originalSha256": mf.digest(data), "modifiedSha256": mf.digest(generated[name])}
    header = (HERE / "qsv-fast.h").read_bytes()
    if b"\r" in header:
        raise ValueError("QSV helper header must use LF line endings")
    output.mkdir(parents=True, exist_ok=True)
    for name, data in generated.items():
        (output / name).write_bytes(data)
    (output / "qsv-fast.h").write_bytes(header)
    result = {"sourceRevision": metadata["revision"], "sources": sources,
              "configSha256": metadata["configSha256"], "avconfigSha256": metadata["avconfigSha256"],
              "helperSha256": mf.digest(header), "change": "validated optional D3D11 create-first device and child sessions; original discovery fallback"}
    (output / "module-source.json").write_text(json.dumps(result, indent=2), encoding="utf-8")


def rebuild(kind, original, base, module, output, ar, ranlib, metadata, mf_provenance=None):
    pin = metadata["libraries"][kind]
    _, before = original_archive(original, pin)
    if module.name != pin["member"] or not module.is_file():
        raise ValueError("Compiler object filename differs")
    if output.resolve() in (original.resolve(), base.resolve(), module.resolve()):
        raise ValueError("Original/intermediate/compiler input must not be overwritten")
    base_data = base.read_bytes()
    base_parts = mf.members(base_data)
    if [n for n, _ in base_parts] != [n for n, _ in before]:
        raise ValueError("Intermediate archive identities/order changed")
    allowed = {pin["member"]: module.read_bytes()}
    if kind == "codec":
        if not mf_provenance:
            raise ValueError("Codec overlay requires the actual MF archive provenance")
        provenance = json.loads(mf_provenance.read_text(encoding="utf-8"))
        if (provenance["originalLibrarySha256"] != pin["sha256"] or
                provenance["librarySha256"] != mf.digest(base_data) or
                provenance["modifiedMember"] != "mfenc.o" or provenance["unchangedMembers"] != 213 or
                provenance["compiledMemberSha256"] != mf.digest(member(base_parts, "mfenc.o"))):
            raise ValueError("MF intermediate provenance differs")
        allowed["mfenc.o"] = member(base_parts, "mfenc.o")
    for (name, old), (_, current) in zip(before, base_parts):
        if (name == "mfenc.o" and kind == "codec"):
            continue
        if old != current:
            raise ValueError("Unrelated intermediate member changed: " + name)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + f".{os.getpid()}.tmp")
    try:
        shutil.copyfile(base, temporary)
        subprocess.run([ar, "r", str(temporary), str(module)], check=True)
        subprocess.run([ranlib, str(temporary)], check=True)
        after = mf.members(temporary.read_bytes())
        if [n for n, _ in before] != [n for n, _ in after]:
            raise ValueError("Final archive member identities/order changed")
        for (name, old), (_, current) in zip(before, after):
            if current != allowed.get(name, old):
                raise ValueError("Final archive member differs: " + name)
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    result = {"originalLibrarySha256": pin["sha256"], "baseLibrarySha256": mf.digest(base_data),
              "librarySha256": mf.digest(output.read_bytes()), "modifiedMember": pin["member"],
              "originalMemberSha256": pin["objectSha256"], "compiledMemberSha256": mf.digest(allowed[pin["member"]]),
              "unchangedMembers": len(before) - len(allowed),
              "modifiedMembersSha256": {name: mf.digest(data) for name, data in allowed.items()}}
    output.with_suffix(".provenance.json").write_text(json.dumps(result, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    stage = sub.add_parser("prepare")
    for name in ("source", "prepared", "output"):
        stage.add_argument("--" + name, type=Path, required=True)
    archive = sub.add_parser("archive")
    archive.add_argument("--kind", choices=("util", "codec"), required=True)
    for name in ("original", "base", "module", "output"):
        archive.add_argument("--" + name, type=Path, required=True)
    for name in ("ar", "ranlib"):
        archive.add_argument("--" + name, required=True)
    archive.add_argument("--mf-provenance", type=Path)
    args = parser.parse_args()
    metadata = json.loads((HERE / "QSV-UPSTREAM.json").read_text(encoding="utf-8"))
    values = vars(args).copy()
    values.pop("command")
    (prepare if args.command == "prepare" else rebuild)(metadata=metadata, **values)


if __name__ == "__main__":
    main()
