"""Reject missing or changed actual storage layouts rather than guessing compatibility."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("qsv_abi", Path(__file__).with_name("qsv-abi.py"))
abi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(abi)

DWARF = """
 <0><0>: Abbrev Number: 1 (DW_TAG_compile_unit)
 <1><10>: Abbrev Number: 2 (DW_TAG_base_type)
    <11> DW_AT_byte_size : 4
    <12> DW_AT_encoding : 5 (signed)
 <1><20>: Abbrev Number: 3 (DW_TAG_pointer_type)
    <21> DW_AT_byte_size : 8
    <22> DW_AT_type : <0x50>
 <1><30>: Abbrev Number: 4 (DW_TAG_array_type)
    <31> DW_AT_type : <0x10>
 <2><32>: Abbrev Number: 5 (DW_TAG_subrange_type)
    <33> DW_AT_upper_bound : 1
 <2><34>: Abbrev Number: 0
 <1><40>: Abbrev Number: 6 (DW_TAG_atomic_type)
    <41> DW_AT_type : <0x10>
 <1><50>: Abbrev Number: 7 (DW_TAG_structure_type)
    <51> DW_AT_name : Sample
    <52> DW_AT_byte_size : 24
 <2><53>: Abbrev Number: 8 (DW_TAG_member)
    <54> DW_AT_name : values
    <55> DW_AT_type : <0x30>
    <56> DW_AT_data_member_location : 0
 <2><57>: Abbrev Number: 8 (DW_TAG_member)
    <58> DW_AT_name : next
    <59> DW_AT_type : <0x20>
    <5a> DW_AT_data_member_location : 8
 <2><5b>: Abbrev Number: 8 (DW_TAG_member)
    <5c> DW_AT_name : count
    <5d> DW_AT_type : <0x40>
    <5e> DW_AT_data_member_location : 16
 <2><5f>: Abbrev Number: 8 (DW_TAG_member)
    <60> DW_AT_name : flags
    <61> DW_AT_type : <0x10>
    <62> DW_AT_data_member_location : 20
 <2><63>: Abbrev Number: 0
 <1><70>: Abbrev Number: 9 (DW_TAG_typedef)
    <71> DW_AT_name : (indirect string, offset: 0x99): Sample
    <72> DW_AT_type : <0x50>
"""


class StorageGuards(unittest.TestCase):
    def compare(self, changed):
        return abi.compare_models(abi.parse(DWARF), abi.parse(changed), ("Sample",))

    def test_aliases_nested_arrays_atomics_and_recursive_pointers_are_compared(self):
        proof = self.compare(DWARF)
        self.assertEqual(24, proof["Sample"]["bytes"])
        self.assertRegex(proof["Sample"]["layoutSha256"], "^[0-9a-f]{64}$")
        root = abi.named(abi.parse(DWARF), "Sample")
        self.assertEqual([2], root["fields"][0]["type"]["bounds"])
        self.assertEqual("atomic", root["fields"][2]["type"]["kind"])

    def test_debug_reference_numbers_do_not_affect_layout_identity(self):
        changed = DWARF.replace("<10>", "<110>").replace("<0x10>", "<0x110>")
        self.assertEqual(self.compare(DWARF), self.compare(changed))

    def test_changed_size_member_offset_and_encoding_are_rejected(self):
        for changed in (DWARF.replace("byte_size : 24", "byte_size : 32"),
                        DWARF.replace("member_location : 20", "member_location : 21"),
                        DWARF.replace("encoding : 5 (signed)", "encoding : 7 (unsigned)")):
            with self.subTest(changed=changed), self.assertRaisesRegex(ValueError, "layout differs"):
                self.compare(changed)

    def test_changed_array_bounds_and_pointer_size_are_rejected(self):
        for changed in (DWARF.replace("upper_bound : 1", "upper_bound : 2"),
                        DWARF.replace("byte_size : 8", "byte_size : 4")):
            with self.subTest(changed=changed), self.assertRaisesRegex(ValueError, "layout differs"):
                self.compare(changed)

    def test_atomic_wrapper_cannot_disappear_silently(self):
        with self.assertRaisesRegex(ValueError, "layout differs"):
            self.compare(DWARF.replace("<5d> DW_AT_type : <0x40>", "<5d> DW_AT_type : <0x10>"))

    def test_missing_type_or_conflicting_duplicate_is_not_accepted(self):
        duplicate = """
 <1><80>: Abbrev Number: 7 (DW_TAG_structure_type)
    <81> DW_AT_name : Sample
    <82> DW_AT_byte_size : 100
"""
        for changed in ("", DWARF + duplicate):
            with self.subTest(changed=changed), self.assertRaisesRegex(ValueError, "Missing or conflicting"):
                self.compare(changed)

    def test_unresolved_dynamic_and_unknown_layouts_fail_closed(self):
        for changed in (DWARF.replace("<0x30>", "<0xffff>"),
                        DWARF.replace("member_location : 20", "member_location : (DW_OP_plus_uconst: 20)"),
                        DWARF.replace("DW_TAG_atomic_type", "DW_TAG_unspecified_type")):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                self.compare(changed)

    def test_recursive_alias_is_rejected_without_infinite_recursion(self):
        nodes = abi.parse(DWARF)
        nodes[0x70]["attributes"]["type"] = "<0x70>"
        with self.assertRaisesRegex(ValueError, "Recursive DWARF alias"):
            abi.named(nodes, "Sample")

    def test_required_kind_inventory_has_forty_explicit_type_checks(self):
        self.assertEqual(21, len(abi.ROOTS["codec"]))
        self.assertEqual(19, len(abi.ROOTS["util"]))
        self.assertEqual(22, len(abi.ROOTS["encode"]))
        self.assertIn("QSVEncContext", abi.ROOTS["encode"])
        self.assertIn("QSVPacket", abi.ROOTS["encode"])
        for kind, names in abi.ROOTS.items():
            self.assertEqual(len(names), len(set(names)))
            self.assertIn("mfxFrameSurface1", names)
            if kind != "encode":
                self.assertIn("AVQSVDeviceContext", names)


if __name__ == "__main__":
    unittest.main()
