"""The call observer may replace qsvenc.o only, preserving both earlier corrections."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from test_prepare_mf import archive

spec=importlib.util.spec_from_file_location('trace_overlay',Path(__file__).with_name('prepare-qsv-trace.py'))
t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)

class QsvTraceGuards(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup)
        self.root=Path(self.directory.name);self.vendor=self.root/'vendor';self.source=self.root/'source';self.output=self.root/'output'
        self.parts=[('qsvenc.o',b'original encode'),('qsv.o',b'original qsv'),('mfenc.o',b'original mf'),
                    ('blockdsp.o',b'generic'),('blockdsp.o',b'x86')]+[(f'other{n}.o',bytes([n%256])) for n in range(209)]
        (self.vendor/'lib').mkdir(parents=True);(self.vendor/'include/libavutil').mkdir(parents=True)
        (self.vendor/'include/config.h').write_bytes(b'generated config')
        (self.vendor/'include/libavutil/avconfig.h').write_bytes(b'generated avconfig')
        self.original=self.vendor/'lib/libavcodec.a';self.original.write_bytes(archive([(n+'/',v) for n,v in self.parts]))
        self.metadata=dict(revision='fixture',configSha256=t.mf.digest(b'generated config'),avconfigSha256=t.mf.digest(b'generated avconfig'),
            library=dict(file='libavcodec.a',sha256=t.mf.digest(self.original.read_bytes()),members=214,member='qsvenc.o',objectSha256=t.mf.digest(b'original encode')),sources={})
        self.code='#include "libavutil/refstruct.h"\nMFXVideoENCODE_EncodeFrameAsync(a,b,c,d,e);\nMFXVideoCORE_SyncOperation(a,b,1000);\n            av_usleep(500);\n'
        for name,value in (('qsvenc.c',self.code.encode()),('qsvenc.h',b'private header')):
            p=self.source/'libavcodec'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(value)
            self.metadata['sources'][name]=dict(path='libavcodec/'+name,sha256=t.mf.digest(value))
        self.base=self.root/'libavcodec-qsv-mf.a'
        self.base_parts=[(n,b'compiled qsv' if n=='qsv.o' else b'compiled mf' if n=='mfenc.o' else v) for n,v in self.parts]
        self.base.write_bytes(archive([(n+'/',v) for n,v in self.base_parts]))
        self.proof=self.root/'base-provenance.json'
        hashes={n:t.mf.digest(v) for n,v in self.base_parts if n in ('qsv.o','mfenc.o')}
        self.provenance=dict(originalLibrarySha256=self.metadata['library']['sha256'],librarySha256=t.mf.digest(self.base.read_bytes()),
            modifiedMember='qsv.o',compiledMemberSha256=hashes['qsv.o'],unchangedMembers=212,modifiedMembersSha256=hashes)
        self.proof.write_text(json.dumps(self.provenance))
        self.module=self.root/'qsvenc.o';self.module.write_bytes(b'compiled encode observer')
        self.ar=shutil.which('ar') or 'C:/msys64/ucrt64/bin/ar.exe';self.ranlib=shutil.which('ranlib') or 'C:/msys64/ucrt64/bin/ranlib.exe'
    def rebuild(self,**changes):
        args=dict(original=self.original,base=self.base,base_provenance=self.proof,module=self.module,
                  output=self.output/'libavcodec.a',ar=self.ar,ranlib=self.ranlib,metadata=self.metadata)
        args.update(changes);t.rebuild(**args);return args['output']
    def test_actual_archiver_keeps_prior_fixes_and_duplicate_order(self):
        out=self.rebuild();expected=[(n,self.module.read_bytes() if n=='qsvenc.o' else v) for n,v in self.base_parts]
        self.assertEqual(expected,t.mf.members(out.read_bytes()))
        proof=json.loads(out.with_suffix('.provenance.json').read_text())
        self.assertEqual(211,proof['unchangedMembers'])
        self.assertEqual({'qsvenc.o','qsv.o','mfenc.o'},set(proof['modifiedMembersSha256']))
        self.assertEqual(t.mf.digest(out.read_bytes()),proof['librarySha256'])
    def test_prepared_source_preserves_retry_conditions_and_sdk_arguments(self):
        t.prepare(self.source,self.vendor,self.output,self.metadata)
        source=(self.output/'qsvenc.c').read_text()
        self.assertIn('#include "qsv-trace-impl.h"',source)
        self.assertIn('MFXVideoCORE_SyncOperation(a,b,1000)',source)
        self.assertIn('rhremote_qsv_busy_sleep(500)',source)
        for name in ('qsv-trace.h','qsv-trace-impl.h'):self.assertEqual((t.HERE/name).read_bytes(),(self.output/name).read_bytes())
        self.assertEqual(self.code,(self.source/'libavcodec/qsvenc.c').read_text())
    def test_pinned_source_headers_config_and_vendor_corruption_are_rejected_before_output(self):
        paths=[self.original,self.vendor/'include/config.h',self.vendor/'include/libavutil/avconfig.h']+[self.source/v['path'] for v in self.metadata['sources'].values()]
        for path in paths:
            before=path.read_bytes();path.write_bytes(before+b'corrupt')
            with self.subTest(path=path),self.assertRaisesRegex(ValueError,'Pinned FFmpeg input differs'):t.prepare(self.source,self.vendor,self.output,self.metadata)
            self.assertFalse(self.output.exists());path.write_bytes(before)
    def test_missing_or_duplicate_call_anchors_fail_even_with_matching_source_hash(self):
        for text in (self.code*2,self.code.replace('av_usleep(500)','av_usleep(700)'),self.code.replace('MFXVideoCORE_SyncOperation','other_sync')):
            with self.subTest(text=text),self.assertRaisesRegex(ValueError,'anchors differ'):t.patch_source(text)
    def test_all_intermediate_provenance_fields_and_allowed_hash_set_are_required(self):
        for key in self.provenance:
            changed=dict(self.provenance);changed[key]=0 if key=='unchangedMembers' else {} if key=='modifiedMembersSha256' else 'wrong'
            self.proof.write_text(json.dumps(changed))
            with self.subTest(field=key),self.assertRaisesRegex(ValueError,'provenance differs'):self.rebuild()
        self.assertFalse(self.output.exists())
    def test_unrelated_intermediate_change_is_rejected_even_after_rehash(self):
        changed=list(self.base_parts);changed[5]=(changed[5][0],b'corrupt')
        self.base.write_bytes(archive([(n+'/',v) for n,v in changed]));p=dict(self.provenance,librarySha256=t.mf.digest(self.base.read_bytes()));self.proof.write_text(json.dumps(p))
        with self.assertRaisesRegex(ValueError,'Unrelated QSV trace intermediate'):self.rebuild()
    def test_intermediate_order_and_duplicate_required_member_are_rejected(self):
        for changed in (self.base_parts[:5]+list(reversed(self.base_parts[5:])),self.base_parts+[('qsv.o',b'extra')]):
            self.base.write_bytes(archive([(n+'/',v) for n,v in changed]));p=dict(self.provenance,librarySha256=t.mf.digest(self.base.read_bytes()));self.proof.write_text(json.dumps(p))
            with self.subTest(count=len(changed)),self.assertRaises(ValueError):self.rebuild()
    def test_final_corruption_never_publishes_and_only_temporary_is_removed(self):
        def corrupt(command,**kwargs):
            if command[0]=='fixture-ar':Path(command[2]).write_bytes(self.original.read_bytes())
        before=self.base.read_bytes()
        with patch.object(t.subprocess,'run',side_effect=corrupt),self.assertRaisesRegex(ValueError,'Final QSV trace member differs'):
            self.rebuild(ar='fixture-ar',ranlib='fixture-ranlib')
        self.assertEqual(before,self.base.read_bytes());self.assertFalse((self.output/'libavcodec.a').exists());self.assertFalse(list(self.output.glob('*.tmp')))
    def test_failed_archiver_preserves_every_input(self):
        before={p:p.read_bytes() for p in (self.original,self.base,self.module,self.proof)}
        with patch.object(t.subprocess,'run',side_effect=subprocess.CalledProcessError(1,'fixture')),self.assertRaises(subprocess.CalledProcessError):self.rebuild()
        for p,data in before.items():self.assertEqual(data,p.read_bytes())
        self.assertFalse(list(self.output.glob('*.tmp')))
    def test_input_outputs_and_wrong_compiler_object_are_rejected(self):
        for p in (self.original,self.base,self.module,self.proof):
            before=p.read_bytes()
            with self.subTest(path=p),self.assertRaisesRegex(ValueError,'must not be overwritten'):self.rebuild(output=p)
            self.assertEqual(before,p.read_bytes())
        wrong=self.root/'wrong.o';wrong.write_bytes(b'compiled')
        with self.assertRaisesRegex(ValueError,'identity differs'):self.rebuild(module=wrong)

if __name__=='__main__':unittest.main()
