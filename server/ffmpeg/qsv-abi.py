"""Compare actual COFF/DWARF storage layouts before mixing rebuilt and vendor objects."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("qsv_abi_archive", HERE / "prepare-mf.py")
mf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mf)

COMMON = ("AVBufferRef", "AVFrame", "AVHWDeviceContext", "AVHWFramesContext", "AVQSVDeviceContext",
          "AVQSVFramesContext", "mfxFrameSurface1", "mfxFrameInfo", "mfxFrameData", "mfxVariant",
          "mfxVersion", "mfxFrameAllocRequest", "mfxFrameAllocResponse", "mfxExtBuffer", "mfxImplDescription")
ROOTS = {"codec": (*COMMON, "AVCodecContext", "mfxEncodeCtrl", "QSVMid", "QSVFrame", "QSVSession", "QSVFramesContext"),
         "util": (*COMMON, "mfxVideoParam", "QSVDevicePriv", "QSVDeviceContext", "QSVFramesContext")}


def parse(text):
    nodes, stack, current = {}, {}, None
    for line in text.splitlines():
        found = re.match(r"\s*<(\d+)><([0-9a-f]+)>: Abbrev Number: \d+(?: \((DW_TAG_\w+)\))?", line)
        if found:
            depth, identity, tag = int(found[1]), int(found[2], 16), found[3]
            current = None
            if tag:
                current = {"tag": tag, "attributes": {}, "children": []}
                nodes[identity] = current
                if depth and depth - 1 in stack:
                    stack[depth - 1]["children"].append(current)
                stack[depth] = current
            for key in list(stack):
                if key > depth or (key == depth and not tag):
                    del stack[key]
            continue
        found = re.match(r"\s*<[0-9a-f]+>\s+DW_AT_(\w+)\s*:\s*(.*)", line)
        if found and current is not None:
            current["attributes"][found[1]] = found[2].rsplit("): ", 1)[-1]
    return nodes


def number(value):
    found = re.fullmatch(r"(0x[0-9a-fA-F]+|[0-9]+)(?:\s+.*)?", value)
    if not found:
        raise ValueError("Nonconstant or unsupported DWARF storage layout")
    return int(found[1], 16 if found[1].startswith("0x") else 10)


def reference(nodes, value):
    found = re.fullmatch(r"<0x([0-9a-f]+)>", value)
    if not found or int(found[1], 16) not in nodes:
        raise ValueError("Unresolved DWARF type")
    return nodes[int(found[1], 16)]


def resolve(nodes, node):
    visited = set()
    while node["tag"] in ("DW_TAG_typedef", "DW_TAG_const_type", "DW_TAG_volatile_type", "DW_TAG_restrict_type"):
        if id(node) in visited:
            raise ValueError("Recursive DWARF alias")
        visited.add(id(node))
        node = reference(nodes, node["attributes"]["type"])
    return node


def shape(nodes, node, seen=()):
    node = resolve(nodes, node)
    attributes, tag = node["attributes"], node["tag"]
    size = number(attributes["byte_size"]) if "byte_size" in attributes else None
    if id(node) in seen:
        raise ValueError("Nonpointer DWARF recursion")
    seen = (*seen, id(node))
    if tag == "DW_TAG_atomic_type":
        return {"kind": "atomic", "value": shape(nodes, reference(nodes, attributes["type"]), seen)}
    if tag == "DW_TAG_pointer_type":
        if size is None:
            raise ValueError("Missing pointer size")
        return {"kind": "pointer", "size": size}
    if tag == "DW_TAG_base_type":
        return {"kind": "scalar", "size": size, "encoding": number(attributes["encoding"])}
    if tag == "DW_TAG_enumeration_type":
        return {"kind": "enum", "size": size}
    if tag == "DW_TAG_array_type":
        bounds = []
        for child in node["children"]:
            if child["tag"] == "DW_TAG_subrange_type":
                item = child["attributes"]
                bounds.append(number(item["count"]) if "count" in item else
                              number(item["upper_bound"]) - number(item.get("lower_bound", "0")) + 1)
        if not bounds:
            raise ValueError("Missing array bounds")
        return {"kind": "array", "bounds": bounds, "element": shape(nodes, reference(nodes, attributes["type"]), seen)}
    if tag in ("DW_TAG_structure_type", "DW_TAG_union_type"):
        if size is None:
            raise ValueError("Missing aggregate size")
        fields = []
        for child in node["children"]:
            if child["tag"] != "DW_TAG_member":
                continue
            item = child["attributes"]
            fields.append({"name": item.get("name", ""), "offset": number(item.get("data_member_location", "0")),
                           "bit_size": item.get("bit_size"), "bit_offset": item.get("data_bit_offset", item.get("bit_offset")),
                           "type": shape(nodes, reference(nodes, item["type"]), seen)})
        return {"kind": tag, "size": size, "fields": fields}
    raise ValueError("Unsupported DWARF storage type: " + tag)


def named(nodes, name):
    matches = [node for node in nodes.values() if node["attributes"].get("name") == name and
               node["tag"] in ("DW_TAG_typedef", "DW_TAG_structure_type", "DW_TAG_union_type")]
    layouts = [shape(nodes, node) for node in matches if "byte_size" in resolve(nodes, node)["attributes"]]
    if not layouts or any(item != layouts[0] for item in layouts):
        raise ValueError("Missing or conflicting ABI type: " + name)
    return layouts[0]


def compare_models(original, compiled, names):
    result = {}
    for name in names:
        before, after = named(original, name), named(compiled, name)
        if before != after:
            raise ValueError("Recompiled storage layout differs: " + name)
        encoded = json.dumps(before, sort_keys=True, separators=(",", ":")).encode("utf-8")
        result[name] = {"bytes": before["size"], "layoutSha256": hashlib.sha256(encoded).hexdigest()}
    return result


def audit(kind, vendor, module, objdump):
    metadata = json.loads((HERE / "QSV-UPSTREAM.json").read_text(encoding="utf-8"))
    pin = metadata["libraries"][kind]
    data = mf.checked(vendor, pin["sha256"])
    matches = [blob for name, blob in mf.members(data) if name == pin["member"]]
    if len(matches) != 1 or mf.digest(matches[0]) != pin["objectSha256"] or module.name != pin["member"]:
        raise ValueError("ABI audit inputs differ from pinned object identities")
    compiled_sha = mf.digest(module.read_bytes())
    with tempfile.TemporaryDirectory(prefix="rhremote-qsv-abi-") as directory:
        original = Path(directory) / pin["member"]
        original.write_bytes(matches[0])
        models = []
        for path in (original, module):
            run = subprocess.run([objdump, "--dwarf=info", str(path)], check=True, capture_output=True,
                                 timeout=30, encoding="utf-8", errors="strict")
            models.append(parse(run.stdout))
    layouts = compare_models(*models, ROOTS[kind])
    if compiled_sha != mf.digest(module.read_bytes()):
        raise ValueError("Compiler object changed during ABI audit")
    return {"schema": "rhremote.ffmpeg-qsv-storage-abi/v1", "result": "PASS", "kind": kind,
            "originalObjectSha256": pin["objectSha256"], "compiledObjectSha256": compiled_sha,
            "types": layouts, "typeCount": len(layouts),
            "scope": "Actual object storage sizes, member offsets, scalar encodings, array bounds and atomic wrappers; pointer pointees and calling convention are not inferred."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=tuple(ROOTS), required=True)
    for name in ("vendor", "module", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--objdump", required=True)
    args = parser.parse_args()
    if args.output.resolve() in (args.vendor.resolve(), args.module.resolve()):
        raise ValueError("ABI evidence must not overwrite an input")
    proof = audit(args.kind, args.vendor, args.module, args.objdump)
    args.output.write_text(json.dumps(proof, indent=2), encoding="utf-8")
    print("PASS: actual QSV " + args.kind + " object storage layouts match for " + str(proof["typeCount"]) + " required types")


if __name__ == "__main__":
    main()
