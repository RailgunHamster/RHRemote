"""Carry a prepared monitor identity into paired GameStream launch/resume requests."""
import argparse
from pathlib import Path


def change(path, replacements):
    text = path.read_text(encoding="utf-8")
    for before, after in replacements:
        if after in text:
            continue
        if text.count(before) != 1:
            raise RuntimeError(f"Initial display anchor missing or ambiguous: {path.name}: {before[:80]}")
        text = text.replace(before, after, 1)
    path.write_text(text, encoding="utf-8", newline="\n")


def windows(root):
    change(root / "app/backend/nvhttp.cpp", [
        ('#include "nvcomputer.h"', '#include "nvcomputer.h"\n#include "gui/rhdisplaylaunch.h"'),
        ('    int riKeyId;', '''    QString rhInitialDisplay;
    {auto& runtime=RhRuntime::get();QMutexLocker lock(&runtime.mutex);rhInitialDisplay=runtime.selectedDisplay["gdi"].toString();}
    if(!rhInitialDisplay.isEmpty()&&!RhDisplayLaunch::valid(rhInitialDisplay))
        throw GfeHttpResponseException(502,"The prepared display source is invalid");
    int riKeyId;'''),
        ('                                   LiGetLaunchUrlQueryParameters(),',
         '                                   RhDisplayLaunch::query(rhInitialDisplay)+LiGetLaunchUrlQueryParameters(),'),
        ('    rtspSessionUrl = getXmlString(response, "sessionUrl0");', '''    const auto rhAck=getXmlString(response,"RHInitialDisplay");
    const auto rhSelection=RhDisplayLaunch::acknowledge(rhInitialDisplay,rhAck);
    if(rhSelection==RhDisplayLaunch::Reply::Invalid)
        throw GfeHttpResponseException(502,"The host acknowledged a different initial display");
    if(rhSelection==RhDisplayLaunch::Reply::Selected){
        auto& runtime=RhRuntime::get();QMutexLocker lock(&runtime.mutex);runtime.initialDisplay=0;
    }
    RhStatistics::event("initial_display_launch",-1,{{"requested",!rhInitialDisplay.isEmpty()},{"beforeCapture",rhSelection==RhDisplayLaunch::Reply::Selected}});
    rtspSessionUrl = getXmlString(response, "sessionUrl0");'''),
    ])


def android(root):
    base = root / "app/src/main/java/com/limelight/nvstream"
    change(base / "http/NvHTTP.java", [
        ('    public boolean launchApp(ConnectionContext context, String verb, int appId, boolean enableHdr)', '''    private com.limelight.rhremote.RhDisplay rhInitialDisplay;
    public void setRhInitialDisplay(com.limelight.rhremote.RhDisplay display){rhInitialDisplay=display;}
    public boolean launchApp(ConnectionContext context, String verb, int appId, boolean enableHdr)'''),
        ('        String xmlStr = openHttpConnectionToString(httpClientLongConnectNoReadTimeout, getHttpsUrl(true), verb,', '''        String rhDisplayQuery=rhInitialDisplay==null?"":rhInitialDisplay.launchQuery();
        String xmlStr = openHttpConnectionToString(httpClientLongConnectNoReadTimeout, getHttpsUrl(true), verb,'''),
        ('            MoonBridge.getLaunchUrlQueryParameters());', '            rhDisplayQuery + MoonBridge.getLaunchUrlQueryParameters());'),
        ('            context.rtspSessionUrl = getXmlString(xmlStr, "sessionUrl0", false);', '''            if(rhInitialDisplay!=null)rhInitialDisplay.acknowledgeInitial(getXmlString(xmlStr,"RHInitialDisplay",false));
            context.rtspSessionUrl = getXmlString(xmlStr, "sessionUrl0", false);'''),
    ])
    change(base / "NvConnection.java", [
        ('        if(rhDisplay!=null)rhDisplay.prepare();',
         '        if(rhDisplay!=null){rhDisplay.prepare();h.setRhInitialDisplay(rhDisplay);}'),
    ])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=["windows", "android"])
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    (windows if args.platform == "windows" else android)(args.source)
