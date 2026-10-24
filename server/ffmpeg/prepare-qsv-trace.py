"""Compile an opt-in QSV call observer, preserving prior MF/QSV and unrelated members."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('qsv_trace_archive',HERE/'prepare-mf.py')
mf=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mf)

def member(parts,name):
    found=[data for key,data in parts if key==name]
    if len(found)!=1:raise ValueError('Expected one QSV trace archive member: '+name)
    return found[0]

def pinned_archive(path,metadata):
    pin=metadata['library']
    parts=mf.members(mf.checked(path,pin['sha256']))
    if len(parts)!=pin['members'] or mf.digest(member(parts,pin['member']))!=pin['objectSha256']:
        raise ValueError('Pinned QSV trace archive inventory differs')
    return parts

def patch_source(text):
    anchor='#include "libavutil/refstruct.h"'
    sleep='            av_usleep(500);'
    if text.count(anchor)!=1 or text.count(sleep)!=1 or text.count('MFXVideoENCODE_EncodeFrameAsync(')!=1 or text.count('MFXVideoCORE_SyncOperation(')!=1:
        raise ValueError('Pinned QSV encode call anchors differ')
    return text.replace(anchor,anchor+'\n#include "qsv-trace-impl.h"').replace(sleep,'            rhremote_qsv_busy_sleep(500);')

def prepare(source,prepared,output,metadata):
    mf.checked(prepared/'include/config.h',metadata['configSha256'])
    mf.checked(prepared/'include/libavutil/avconfig.h',metadata['avconfigSha256'])
    pinned_archive(prepared/'lib'/metadata['library']['file'],metadata)
    original={name:mf.checked(source/pin['path'],pin['sha256']) for name,pin in metadata['sources'].items()}
    modified=patch_source(original['qsvenc.c'].decode('utf-8')).encode('utf-8')
    helpers={name:(HERE/name).read_bytes() for name in ('qsv-trace.h','qsv-trace-impl.h')}
    if any(b'\r' in data for data in helpers.values()):raise ValueError('QSV trace headers require LF')
    output.mkdir(parents=True,exist_ok=True)
    (output/'qsvenc.c').write_bytes(modified)
    for name,data in helpers.items():(output/name).write_bytes(data)
    proof=dict(sourceRevision=metadata['revision'],sources={name:mf.digest(data) for name,data in original.items()},
               modifiedSourceSha256=mf.digest(modified),helpers={name:mf.digest(data) for name,data in helpers.items()},
               configSha256=metadata['configSha256'],avconfigSha256=metadata['avconfigSha256'],
               change='Opt-in actual EncodeFrameAsync, SyncOperation and original busy sleep call spans; original retry logic retained')
    (output/'module-source.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')

def rebuild(original,base,base_provenance,module,output,ar,ranlib,metadata):
    before=pinned_archive(original,metadata)
    pin=metadata['library']
    if module.name!=pin['member'] or not module.is_file():raise ValueError('QSV trace compiler object identity differs')
    if output.resolve() in (original.resolve(),base.resolve(),module.resolve(),base_provenance.resolve()):
        raise ValueError('QSV trace input must not be overwritten')
    base_bytes=base.read_bytes();parts=mf.members(base_bytes)
    provenance=json.loads(base_provenance.read_text(encoding='utf-8'))
    hashes=provenance.get('modifiedMembersSha256',{})
    if (provenance.get('originalLibrarySha256')!=pin['sha256'] or provenance.get('librarySha256')!=mf.digest(base_bytes)
            or provenance.get('modifiedMember')!='qsv.o' or provenance.get('unchangedMembers')!=212
            or provenance.get('compiledMemberSha256')!=hashes.get('qsv.o')
            or set(hashes)!={'qsv.o','mfenc.o'} or any(hashes[name]!=mf.digest(member(parts,name)) for name in hashes)):
        raise ValueError('QSV/MF intermediate provenance differs')
    if [n for n,_ in before]!=[n for n,_ in parts]:raise ValueError('QSV trace intermediate member order differs')
    for (name,old),(_,current) in zip(before,parts):
        if name not in hashes and current!=old:raise ValueError('Unrelated QSV trace intermediate member changed: '+name)
    module_bytes=module.read_bytes()
    allowed={name:member(parts,name) for name in hashes}
    allowed[pin['member']]=module_bytes
    output.parent.mkdir(parents=True,exist_ok=True)
    temporary=output.with_name(output.name+f'.{os.getpid()}.tmp')
    try:
        shutil.copyfile(base,temporary)
        subprocess.run([ar,'r',str(temporary),str(module)],check=True)
        subprocess.run([ranlib,str(temporary)],check=True)
        after=mf.members(temporary.read_bytes())
        if [n for n,_ in before]!=[n for n,_ in after]:raise ValueError('Final QSV trace member identities/order differ')
        for (name,old),(_,current) in zip(before,after):
            if current!=allowed.get(name,old):raise ValueError('Final QSV trace member differs: '+name)
        temporary.replace(output)
    finally:temporary.unlink(missing_ok=True)
    proof=dict(originalLibrarySha256=pin['sha256'],baseLibrarySha256=mf.digest(base_bytes),librarySha256=mf.digest(output.read_bytes()),
               modifiedMember=pin['member'],originalMemberSha256=pin['objectSha256'],compiledMemberSha256=mf.digest(module_bytes),
               unchangedMembers=len(before)-len(allowed),modifiedMembersSha256={name:mf.digest(data) for name,data in allowed.items()})
    output.with_suffix('.provenance.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')

def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
    stage=sub.add_parser('prepare')
    for name in ('source','prepared','output'):stage.add_argument('--'+name,type=Path,required=True)
    archive=sub.add_parser('archive')
    for name in ('original','base','base-provenance','module','output'):archive.add_argument('--'+name,type=Path,required=True)
    for name in ('ar','ranlib'):archive.add_argument('--'+name,required=True)
    args=vars(p.parse_args());command=args.pop('command')
    metadata=json.loads((HERE/'QSV-TRACE-UPSTREAM.json').read_text(encoding='utf-8'))
    (prepare if command=='prepare' else rebuild)(metadata=metadata,**args)

if __name__=='__main__':main()
