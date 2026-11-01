"""Reproduce the existing native pacer changes from the pinned public upstream.

Generated files are backed up before replacement. This also works when reapplying
statistics: the pacer is reconstructed first, then instrumentation is reapplied.
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

REVISION='f786e94c7b2f943e24e65d7d74deb539b827fc84'
FILES=['app/streaming/video/ffmpeg-renderers/pacer/pacer.cpp',
       'app/streaming/video/ffmpeg-renderers/pacer/pacer.h']

def prepare(root):
    repo=Path(__file__).resolve().parent.parent
    head=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
    if head!=REVISION:
        raise RuntimeError('Native pacer requires the pinned Moonlight Qt revision')
    with tempfile.TemporaryDirectory(prefix='rhremote-pacer-') as folder:
        stage=Path(folder)
        for name in FILES:
            target=stage/name
            target.parent.mkdir(parents=True,exist_ok=True)
            original=subprocess.check_output(['git','-C',str(root),'show',REVISION+':'+name])
            target.write_bytes(original)
        subprocess.run(['git','apply','--check',str(repo/'client/patches/moonlight-qt-pacer.patch')],cwd=stage,check=True)
        subprocess.run(['git','apply',str(repo/'client/patches/moonlight-qt-pacer.patch')],cwd=stage,check=True)
        for name in FILES:
            current=root/name
            backup=root/'.rhremote-pacer-originals'/name
            if not backup.exists():
                backup.parent.mkdir(parents=True,exist_ok=True)
                shutil.copyfile(current,backup)
            shutil.copyfile(stage/name,current)
    shutil.copyfile(repo/'client/StableCadence.h',root/'app/streaming/video/ffmpeg-renderers/pacer/StableCadence.h')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('source',type=Path)
    prepare(parser.parse_args().source.resolve())
