"""Build a local, offline gallery from app-owned UI render catalogues only."""
import argparse
import base64
import hashlib
import json
from html import escape
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

LABELS = {
    "devices": "远程设备", "devices-list": "设备列表", "search-empty": "搜索无结果",
    "profile-video": "连接设置 · 画面", "profile-audio": "连接设置 · 音频",
    "profile-input": "连接设置 · 输入", "profile-network": "连接设置 · 网络",
    "profile-advanced": "连接设置 · 高级", "application-updates": "设置 · 更新",
    "application-sync": "设置 · 配置同步", "application-appearance": "设置 · 外观",
    "application-about": "设置 · 关于", "device-info": "设备资料", "device-add": "添加电脑",
    "unsaved": "未保存更改", "shortcuts": "快捷键菜单", "disconnect": "断开确认",
    "panel-input": "会话 · 操作", "panel-displays": "会话 · 屏幕", "panel-audio": "会话 · 声音",
    "panel-scroll-mode": "会话 · 滚动模式", "panel-error": "会话 · 错误反馈",
    "connection-waiting": "连接 · 等待画面", "connection-error": "连接 · 失败",
    "pair-result": "配对结果", "connection-first-frame": "连接 · 等待画面",
    "connection-failed": "连接 · 失败", "connection-reconnecting": "连接 · 恢复",
    "connection-pair": "配对码", "connection-paired": "配对结果",
    "toolbar-input": "会话 · 操作", "toolbar-displays": "会话 · 屏幕", "toolbar-audio": "会话 · 声音",
    "toolbar-busy": "会话 · 正在操作", "toolbar-error": "会话 · 错误反馈",
    "toolbar-pending": "会话 · 等待画面", "toolbar-collapsed": "会话 · 已收起",
    "toolbar-recovery-failed": "会话 · 恢复失败",
    "picker-shortcuts": "快捷键菜单", "picker-displays": "显示器菜单",
    "picker-empty": "显示器为空", "picker-feedback": "错误详情",
    "panel-collapsed": "会话 · 已收起", "floating-keyboard": "会话 · 悬浮按键",
    "floating-busy": "会话 · 按键暂不可用", "sync-draft": "配置同步 · 未保存更改",
    "update-idle": "更新 · 尚未检查", "update-checking": "更新 · 正在检查",
    "update-available": "更新 · 发现新版", "update-downloading": "更新 · 正在下载",
    "update-verifying": "更新 · 正在校验", "update-downloaded": "更新 · 下载完成",
    "update-deferred-sync": "更新 · 等待同步结束", "update-check-failed": "更新 · 检查失败",
    "update-download-failed": "更新 · 下载失败", "update-applying": "更新 · 正在安装",
    "update-unmanaged": "更新 · 非托管安装",
}


def normal_name(name):
    parts = name.split("-")
    parts = [p for p in parts if p not in {"light", "dark", "wide", "compact", "large", "bottom"}]
    key = "-".join(parts)
    if key.startswith("form-"):
        key = key[5:]
        if key in {"video", "audio", "input", "network", "advanced"}:
            key = "profile-" + key
        elif key in {"updates", "sync", "appearance", "about"}:
            key = "application-" + key
        elif key == "add":
            key = "device-add"
    return key


def section(key):
    if key.startswith("profile-"):
        return "profile"
    if key.startswith(("application-", "update-")) or key == "sync-draft":
        return "application"
    if key.startswith(("toolbar-", "panel-", "picker-", "floating-")) or key in {"shortcuts", "disconnect"}:
        return "session"
    if key.startswith("connection-") or key in {"pair-result", "unsaved"}:
        return "feedback"
    return "devices"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", nargs=3, action="append", metavar=("PLATFORM", "VIEWPORT", "CATALOG"), required=True)
    parser.add_argument("--out", type=Path, required=True)
    opts = parser.parse_args()
    if opts.out.exists():
        raise SystemExit("Refusing to replace an existing gallery")
    opts.out.mkdir()
    images = []
    for platform, viewport, catalog_file in opts.source:
        source = Path(catalog_file)
        catalog = json.loads(source.read_text(encoding="utf-8-sig"))
        if not catalog.get("visualOnly") or catalog.get("functionalVerification"):
            raise ValueError(f"Not a visual-only catalogue: {source}")
        for entry in catalog.get("images", catalog.get("pages", [])):
            path = Path(entry["path"]) if entry.get("path") else source.parent / entry["file"]
            data = path.read_bytes()
            with Image.open(path) as im:
                width, height = im.size
            name = entry.get("name", path.stem)
            key = normal_name(name)
            fixture = entry.get("fixture", catalog.get("osWindowsCreated") is False)
            theme = entry.get("theme", "dark" if name.endswith("-dark") else "light")
            image_viewport = entry.get("viewport") or (("large" if "-large-" in name else "compact" if "-compact-" in name else "wide") if platform == "Windows" and catalog.get("osWindowsCreated") is False else viewport)
            overview = (image_viewport == "wide" and theme == "light" and "bottom" not in name and (
                (key in {"devices", "profile-video", "application-appearance", "application-updates", "application-about"}) or
                (key in {"toolbar-input", "panel-input"})
            ))
            images.append({"platform":platform,"viewport":image_viewport,"theme":theme,"section":section(key),"key":key,"name":name,"title":entry.get("title",LABELS.get(key,key)) + (" · 底部" if "bottom" in name else ""),"fixture":fixture,"sourceKind":entry.get("sourceKind","fixture" if fixture else "app-view"),"overview":overview,"version":catalog.get("version",""),"width":width,"height":height,"source":str(path),"sha256":hashlib.sha256(data).hexdigest(),"src":"data:image/png;base64,"+base64.b64encode(data).decode("ascii")})
    # A current-data view replaces only its matching generic layout fixture.
    # Keep separate sizes, themes, versions and scrolled-bottom scenes.
    def scene_id(item):
        return (item["platform"], item["viewport"], item["theme"], item["version"],
                item["key"], "bottom" in item["name"].split("-"))
    current_scenes = {scene_id(item) for item in images if not item["fixture"]}
    input_count = len(images)
    images = [item for item in images if not item["fixture"] or scene_id(item) not in current_scenes]
    versions = {platform: sorted({item["version"] for item in images if item["platform"] == platform})
                for platform in dict.fromkeys(item["platform"] for item in images)}
    version_summary = " / ".join(platform + " " + ", ".join(items) for platform, items in versions.items())
    payload=json.dumps(images,ensure_ascii=False).replace("<","\\u003c")
    html=r'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>RHRemote · 界面预览</title><style>
*{box-sizing:border-box}body{margin:0;background:#f6f7fa;color:#34415b;font:14px 'Segoe UI','Microsoft YaHei UI',sans-serif}header{padding:28px 32px 20px;background:white;border-bottom:1px solid #e5eaf4}h1{font-size:25px;margin:0 0 8px}header p{margin:0;color:#69778e;line-height:1.7}.filters{position:sticky;top:0;background:#fffffff2;backdrop-filter:blur(14px);border-bottom:1px solid #e5eaf4;padding:16px 32px;display:flex;gap:14px;align-items:center;flex-wrap:wrap;z-index:1}label{color:#69778e;display:flex;gap:8px;align-items:center}select,button{font:inherit;border:1px solid #dce3f0;border-radius:8px;background:white;color:#34415b;padding:8px 12px;cursor:pointer}button:hover,select:hover{border-color:#5274e8}#count{margin-left:auto;color:#69778e}.grid{padding:24px 32px;display:grid;gap:22px;grid-template-columns:repeat(auto-fit,minmax(min(420px,100%),1fr))}.card{background:white;border:1px solid #e5eaf4;border-radius:12px;overflow:hidden}.meta{padding:15px 17px;display:flex;gap:8px;align-items:center;justify-content:space-between}.meta strong{display:block;font-size:15px}.meta small{display:block;color:#69778e;margin-top:5px}.tag{font-size:11px;background:#edf2ff;color:#5274e8;border-radius:5px;padding:4px 6px;white-space:nowrap}.image{width:100%;height:330px;border:0;border-radius:0;padding:12px;background:repeating-conic-gradient(#f2f4f9 0% 25%,#f8f9fc 0% 50%) 50%/16px 16px;display:block}.image img{width:100%;height:100%;object-fit:contain;transition:transform .15s}.image:hover img{transform:scale(1.015)}.empty{grid-column:1/-1;text-align:center;padding:60px;color:#69778e}dialog{width:96vw;height:94vh;max-width:none;max-height:none;border:1px solid #303c53;border-radius:12px;padding:0;background:#151b29;color:#e1e8f6}dialog::backdrop{background:#101722c9}.dialogbar{display:flex;gap:12px;align-items:center;padding:12px 16px;border-bottom:1px solid #303c53}.dialogbar span{flex:1}.dialogbar button{background:#242e41;color:#e1e8f6;border-color:#303c53}#viewer{height:calc(100% - 64px);overflow:auto;padding:18px;text-align:center}#full{max-width:100%;max-height:100%;object-fit:contain}#viewer.actual #full{max-width:none;max-height:none}footer{padding:0 32px 28px;color:#69778e;line-height:1.7}@media(max-width:700px){header,.filters{padding:18px}.grid{padding:18px;gap:16px}.image{height:290px}#count{margin-left:0}}
</style><header><h1>RHRemote · 界面预览</h1><p>Windows / Android，包含浅深色、平板横竖屏及窄屏大字体。点界面图可放大。连接状态和悬浮面板中的示例标为“布局场景”。</p><p>__VERSIONS__</p></header>
<div class="filters"><label>平台<select id="platform"><option value="">全部</option><option>Windows</option><option>Android</option></select></label><label>页面<select id="section"><option value="overview">概览</option><option value="">全部页面</option><option value="devices">远程设备</option><option value="profile">连接设置</option><option value="application">应用设置</option><option value="session">会话控制</option><option value="feedback">连接与提示</option></select></label><label>尺寸<select id="viewport"><option value="">全部</option><option value="wide">横屏 / 宽窗口</option><option value="portrait">平板竖屏</option><option value="narrow">窄屏 · 大字体</option><option value="compact">Windows 窄窗口</option><option value="large">Windows 大窗口</option><option value="wide-font200">横屏 · 200% 字号</option></select></label><label>主题<select id="theme"><option value="light">浅色</option><option value="dark">深色</option><option value="">全部</option></select></label><span id="count"></span></div><main class="grid" id="grid"></main><footer>来源为客户端自身 View / Slint 绘制，未使用系统整屏截图。此页用于检查界面效果；布局场景不代表已建立远程连接。更新与同步的完整操作验收继续单独进行。</footer>
<dialog id="dialog"><div class="dialogbar"><button id="previous" aria-label="上一张">←</button><button id="next" aria-label="下一张">→</button><span id="caption"></span><button id="scale">原始尺寸</button><button id="close">关闭</button></div><div id="viewer"><img id="full" alt=""></div></dialog>
<script>const images=__IMAGES__;const $=id=>document.getElementById(id);let shown=[],index=0;const filters=['platform','section','viewport','theme'];const viewportNames={wide:'横屏 / 宽窗口',portrait:'平板竖屏',narrow:'窄屏 · 大字体',compact:'Windows 窄窗口',large:'Windows 大窗口','wide-font200':'横屏 · 200% 字号'};
function render(){shown=images.filter(i=>filters.every(k=>!$(k).value||(k==='section'&&$(k).value==='overview'?i.overview:i[k]===$(k).value)));$('grid').replaceChildren();$('count').textContent=shown.length+' 张';for(const [n,i]of shown.entries()){const card=document.createElement('article');card.className='card';const meta=document.createElement('div');meta.className='meta';const text=document.createElement('div');const title=document.createElement('strong');title.textContent=i.title;const small=document.createElement('small');small.textContent=i.platform+' · '+viewportNames[i.viewport]+' · '+(i.theme==='dark'?'深色':'浅色')+' · '+i.version;text.append(title,small);const tag=document.createElement('span');tag.className='tag';tag.textContent=i.sourceKind==='config-copy'?'配置副本':i.fixture?'布局场景':'当前控件';meta.append(text,tag);const button=document.createElement('button');button.className='image';button.setAttribute('aria-label','放大 '+i.title);const img=document.createElement('img');img.loading='lazy';img.alt=i.title;img.src=i.src;button.append(img);button.onclick=()=>open(n);card.append(meta,button);$('grid').append(card)}if(!shown.length){const p=document.createElement('p');p.className='empty';p.textContent='当前筛选下没有界面图';$('grid').append(p)}}
function display(){const i=shown[index];$('full').src=i.src;$('full').alt=i.title;$('caption').textContent=i.platform+' · '+i.title+' · '+(index+1)+' / '+shown.length;$('viewer').classList.remove('actual');$('scale').textContent='原始尺寸'}function open(n){index=n;display();$('dialog').showModal()}filters.forEach(k=>$(k).onchange=render);$('close').onclick=()=>$('dialog').close();$('previous').onclick=()=>{index=(index-1+shown.length)%shown.length;display()};$('next').onclick=()=>{index=(index+1)%shown.length;display()};$('scale').onclick=()=>{$('viewer').classList.toggle('actual');$('scale').textContent=$('viewer').classList.contains('actual')?'适应窗口':'原始尺寸'};document.addEventListener('keydown',e=>{if(!$('dialog').open)return;if(e.key==='ArrowLeft')$('previous').click();if(e.key==='ArrowRight')$('next').click()});render();</script></html>'''
    (opts.out/'gallery.html').write_text(html.replace('__IMAGES__',payload).replace('__VERSIONS__',escape(version_summary)),encoding='utf-8')
    (opts.out/'catalog.json').write_text(json.dumps([{k:v for k,v in i.items() if k!='src'} for i in images],ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    font_path=Path('C:/Windows/Fonts/msyh.ttc')
    font=ImageFont.truetype(str(font_path),19)
    title_font=ImageFont.truetype(str(font_path),27)
    keys=['devices','profile-video','application-appearance','panel-input']
    pairs=[]
    for key in keys:
        pair=[]
        for platform in ['Windows','Android']:
            wanted='toolbar-input' if platform=='Windows' and key=='panel-input' else key
            matches=[i for i in images if i['platform']==platform and i['viewport']=='wide' and i['theme']=='light' and i['key']==wanted and 'bottom' not in i['name']]
            if not matches:
                raise ValueError(f"Missing overview scene: {platform} / {wanted}")
            pair.append(min(matches,key=lambda i:i['fixture']))
        pairs.append(pair)
    canvas=Image.new('RGB',(1280,1820),'#f6f7fa');draw=ImageDraw.Draw(canvas);draw.text((28,20),'RHRemote · Windows / Android',font=title_font,fill='#34415b');draw.text((28,52),version_summary,font=font,fill='#69778e')
    for row,pair in enumerate(pairs):
        for col,item in enumerate(pair):
            x=24+col*636;y=76+row*430
            draw.rounded_rectangle((x,y,x+612,y+408),radius=10,fill='white',outline='#e5eaf4')
            draw.text((x+14,y+12),item['platform']+' · '+item['title'],font=font,fill='#34415b')
            with Image.open(item['source']) as im:
                thumb=ImageOps.contain(im.convert('RGBA'),(588,350))
                canvas.paste(thumb,(x+12+(588-thumb.width)//2,y+46+(350-thumb.height)//2),thumb)
    canvas.save(opts.out/'overview.png')
    print(json.dumps({'inputImages':input_count,'images':len(images),'replacedFixtures':input_count-len(images),'versions':versions,'gallery':str(opts.out/'gallery.html'),'overview':str(opts.out/'overview.png')},ensure_ascii=False))


if __name__=='__main__':
    main()
