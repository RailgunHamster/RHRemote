"""Derive ICO and Android vector assets from the shared SVG; no separate artwork."""
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image

repo=Path(__file__).resolve().parents[1]
icons=repo/'client/shared/icons'
svg=ET.parse(icons/'rhremote.svg').getroot()
ns={'s':'http://www.w3.org/2000/svg'}
paths=svg.findall('s:path',ns)
image=Image.open(icons/'rhremote.png')
image.save(icons/'rhremote.ico',format='ICO',sizes=[(s,s) for s in (16,20,24,32,40,48,64,128,256)])
resources=repo/'client/android/overlay/app/src/main/res'
gradient='<aapt:attr name="android:fillColor"><gradient android:startX="0" android:startY="0" android:endX="256" android:endY="256" android:startColor="#5274e8" android:endColor="#8b5be2" android:type="linear"/></aapt:attr>'
def vector(foreground=False):
    body=[]
    if foreground:body.append('<group android:scaleX="0.35" android:scaleY="0.35" android:translateX="9.2" android:translateY="9.2">')
    else:body.append('<path android:pathData="M60,0 H196 Q256,0 256,60 V196 Q256,256 196,256 H60 Q0,256 0,196 V60 Q0,0 60,0 Z">'+gradient+'</path>')
    for path in paths:
        body.append('<path android:pathData="'+path.attrib['d']+'" android:fillColor="#00000000" android:strokeColor="#ffffff" android:strokeWidth="14" android:strokeLineCap="round" android:strokeLineJoin="round" android:strokeAlpha="'+path.get('stroke-opacity','1')+'"/>')
    if foreground:body.append('</group>')
    viewport='108' if foreground else '256'
    return '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="108dp" android:height="108dp" android:viewportWidth="'+viewport+'" android:viewportHeight="'+viewport+'">\n'+'\n'.join(body)+'\n</vector>\n'
(resources/'drawable/rh_icon.xml').write_text(vector(),encoding='utf-8')
(resources/'drawable/rh_icon_foreground.xml').write_text(vector(True),encoding='utf-8')
(resources/'drawable/rh_icon_background.xml').write_text('<shape xmlns:android="http://schemas.android.com/apk/res/android"><gradient android:angle="315" android:startColor="#5274e8" android:endColor="#8b5be2"/></shape>\n',encoding='utf-8')
adaptive=resources/'mipmap-anydpi-v26';adaptive.mkdir(exist_ok=True)
(adaptive/'rh_launcher.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/rh_icon_background"/><foreground android:drawable="@drawable/rh_icon_foreground"/></adaptive-icon>\n',encoding='utf-8')
(repo/'client/windows/overlay/app/res/rhremote.svg').write_bytes((icons/'rhremote.svg').read_bytes())
(repo/'client/windows/overlay/app/res/rhremote.ico').write_bytes((icons/'rhremote.ico').read_bytes())
print('Derived one ICO, Android adaptive/legacy vectors and native SVG from the shared icon.')
