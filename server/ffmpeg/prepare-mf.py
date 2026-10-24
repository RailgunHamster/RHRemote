"""Rebuild one pinned FFmpeg C translation unit with the original ABI configuration.

The compiler produces the replacement object. This tool never edits executable
bytes. All other static archive members must remain byte-identical.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

HERE=Path(__file__).resolve().parent
OLD='ICodecAPI_SetValue(c->codec_api, &ff_CODECAPI_AVLowLatencyMode, FF_VAL_VT_UI4(1));'
NEW='ICodecAPI_SetValue(c->codec_api, &ff_CODECAPI_AVLowLatencyMode, FF_VAL_VT_BOOL(VARIANT_TRUE));'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def checked(path, expected):
    data=Path(path).read_bytes()
    if digest(data)!=expected:raise ValueError(f'Pinned FFmpeg input differs: {path}')
    return data

def members(data):
    """Read GNU/BSD ar member payloads, excluding regenerated linker indexes."""
    if not data.startswith(b'!<arch>\n'):raise ValueError('Not an ar archive')
    position=8;strings=b'';result=[]
    while position<len(data):
        header=data[position:position+60]
        if len(header)!=60 or header[58:]!=b'`\n':raise ValueError('Invalid ar header')
        size=int(header[48:58]);start=position+60;end=start+size
        if size<0 or end>len(data):raise ValueError('Invalid ar member size')
        name=header[:16].decode('ascii').strip();payload=data[start:end]
        position=end+(size%2)
        if name=='//':strings=payload;continue
        if name in ('/','/SYM64/'):continue
        if name.startswith('#1/'):
            length=int(name[3:])
            if not 0<length<=len(payload):raise ValueError('Invalid BSD ar name')
            name=payload[:length].rstrip(b'\0').decode('utf-8');payload=payload[length:]
            if name.startswith('__.SYMDEF'):continue
        elif name.startswith('/') and name[1:].isdigit():
            offset=int(name[1:]);limit=strings.find(b'/\n',offset)
            if offset>=len(strings) or limit<offset:raise ValueError('Invalid GNU ar long name')
            name=strings[offset:limit].decode('utf-8')
        else:name=name.removesuffix('/')
        if not name:raise ValueError('Missing ar member name')
        result.append((name,payload))
    if position!=len(data):raise ValueError('Invalid ar padding')
    return result

def mf_object(parts):
    """Other FFmpeg subdirectories legitimately share basenames; MF must be unique."""
    matches=[payload for name,payload in parts if name=='mfenc.o']
    if len(matches)!=1:raise ValueError('Expected exactly one MF archive member')
    return matches[0]

def prepare(source, prepared, output, metadata):
    original=checked(source/'libavcodec/mfenc.c',metadata['sourceFileSha256'])
    checked(prepared/'include/config.h',metadata['configSha256'])
    checked(prepared/'include/libavutil/avconfig.h',metadata['avconfigSha256'])
    archive=checked(prepared/'lib/libavcodec.a',metadata['libavcodecSha256'])
    parts=members(archive)
    if digest(mf_object(parts))!=metadata['mfencObjectSha256']:raise ValueError('Pinned MF object differs')
    text=original.decode('utf-8')
    if text.count(OLD)!=1:raise ValueError('MF low-latency anchor differs')
    output.mkdir(parents=True,exist_ok=True)
    (output/'mfenc.c').write_text(text.replace(OLD,NEW),encoding='utf-8',newline='\n')
    (output/'module-source.json').write_text(json.dumps({
        'sourceRevision':metadata['revision'],'originalSourceSha256':digest(original),
        'modifiedSourceSha256':digest((output/'mfenc.c').read_bytes()),
        'configSha256':metadata['configSha256'],'avconfigSha256':metadata['avconfigSha256'],
        'libavcodecSha256':metadata['libavcodecSha256'],'change':'encoder low-latency uses canonical VARIANT_BOOL'},indent=2),encoding='utf-8')

def rebuild(original, module, output, ar, ranlib, metadata):
    original_bytes=checked(original,metadata['libavcodecSha256']);before=members(original_bytes)
    if digest(mf_object(before))!=metadata['mfencObjectSha256']:raise ValueError('Original MF member differs')
    if module.name!='mfenc.o' or not module.is_file():raise ValueError('Compiler object must be named mfenc.o')
    if output.resolve()==original.resolve():raise ValueError('Vendor archive must not be overwritten')
    temporary=output.with_name(output.name+f'.{os.getpid()}.tmp')
    output.parent.mkdir(parents=True,exist_ok=True)
    try:
        shutil.copyfile(original,temporary)
        subprocess.run([ar,'r',str(temporary),str(module)],check=True)
        subprocess.run([ranlib,str(temporary)],check=True)
        after=members(temporary.read_bytes())
        if [n for n,_ in before]!=[n for n,_ in after]:raise ValueError('Archive member identities/order changed')
        compiled=mf_object(after)
        if compiled!=module.read_bytes():raise ValueError('Replacement object was not installed')
        for (name,prior),(_,current) in zip(before,after):
            if name!='mfenc.o' and prior!=current:raise ValueError(f'Unrelated FFmpeg member changed: {name}')
        temporary.replace(output)
        output.with_suffix('.provenance.json').write_text(json.dumps({
            'originalLibrarySha256':digest(original_bytes),'librarySha256':digest(output.read_bytes()),
            'modifiedMember':'mfenc.o','originalMemberSha256':metadata['mfencObjectSha256'],
            'compiledMemberSha256':digest(compiled),'unchangedMembers':len(before)-1},indent=2),encoding='utf-8')
    finally:
        if temporary.exists():temporary.unlink()

def main():
    parser=argparse.ArgumentParser(description=__doc__);commands=parser.add_subparsers(dest='command',required=True)
    p=commands.add_parser('prepare');p.add_argument('--source',type=Path,required=True);p.add_argument('--prepared',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p=commands.add_parser('archive');p.add_argument('--original',type=Path,required=True);p.add_argument('--module',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--ar',required=True);p.add_argument('--ranlib',required=True)
    args=parser.parse_args();metadata=json.loads((HERE/'UPSTREAM.json').read_text(encoding='utf-8'))
    if args.command=='prepare':prepare(args.source,args.prepared,args.output,metadata)
    else:rebuild(args.original,args.module,args.output,args.ar,args.ranlib,metadata)

if __name__=='__main__':main()
