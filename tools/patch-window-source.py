"""Wire transient, peer-bound single-window selections into the pinned native clients."""
import argparse
from pathlib import Path
from importlib.machinery import SourceFileLoader

change = SourceFileLoader("rh_initial_display", str(Path(__file__).with_name("patch-initial-display.py"))).load_module().change


def android(root):
    base = root / "app/src/main/java/com/limelight"
    change(base / "nvstream/http/NvHTTP.java", [
        ("    public LinkedList<NvApp> getAppList()", '''    public String getRhWindowsRaw() throws IOException, XmlPullParserException {
        return openHttpConnectionToString(httpClientLongConnectTimeout,getHttpsUrl(true),"rhremote/windows",null);
    }
    public LinkedList<NvApp> getAppList()'''),
    ])
    change(base / "PcView.java", [
        ("            public void add(String address)", '''            public org.json.JSONObject windows(ComputerDetails d,com.limelight.rhremote.RhAdmission operation)throws Exception {
                if(managerBinder==null)throw new IOException("连接管理器尚未准备好");
                NvHTTP http=new NvHTTP(ServerHelper.getCurrentAddressFromComputer(d),d.httpsPort,managerBinder.getUniqueId(),d.serverCert,PlatformBinding.getCryptoProvider(PcView.this),true);
                try(com.limelight.rhremote.RhAdmission.Watch watch=operation.watch(http::cancelRhRequests)){
                    return com.limelight.rhremote.RhWindows.parse(http.getRhWindowsRaw());
                }
            }
            public Runnable prepareWindowConnect(ComputerDetails d,com.limelight.rhremote.RhAdmission operation,org.json.JSONObject window)throws Exception {
                return prepareRhDesktop(d,operation,window);
            }
            public void add(String address)'''),
        ("    private Runnable prepareRhDesktop(ComputerDetails computer,com.limelight.rhremote.RhAdmission operation) throws Exception {", '''    private Runnable prepareRhDesktop(ComputerDetails computer,com.limelight.rhremote.RhAdmission operation) throws Exception {
        return prepareRhDesktop(computer,operation,null);
    }
    private Runnable prepareRhDesktop(ComputerDetails computer,com.limelight.rhremote.RhAdmission operation,org.json.JSONObject window) throws Exception {'''),
        ('ServerHelper.doStart(this,selected,computer,binder);', '''android.content.Intent intent=ServerHelper.createStartIntent(this,selected,computer,binder);if(window!=null)intent.putExtra(com.limelight.rhremote.RhWindows.EXTRA,window.toString());startActivity(intent);'''),
    ])
    change(base / "Game.java", [
        ('        rhProfile = com.limelight.rhremote.RhProfile.load(this,getIntent().getStringExtra(EXTRA_PC_UUID));', '''        rhProfile = com.limelight.rhremote.RhProfile.load(this,getIntent().getStringExtra(EXTRA_PC_UUID));
        if(getIntent().hasExtra(com.limelight.rhremote.RhWindows.EXTRA)){
            try{int[] size=com.limelight.rhremote.RhWindows.resolution(new org.json.JSONObject(getIntent().getStringExtra(com.limelight.rhremote.RhWindows.EXTRA)),rhProfile.width,rhProfile.height);rhProfile.width=size[0];rhProfile.height=size[1];}
            catch(Exception invalid){com.limelight.rhremote.RhActions.connectionError("窗口选择无效，请重新选择");finish();return;}
        }'''),
        ('        conn.setRhDisplay(new com.limelight.rhremote.RhDisplay(this,host,getIntent().getByteArrayExtra(EXTRA_SERVER_CERT),rhProfile));', '''        conn.setRhDisplay(new com.limelight.rhremote.RhDisplay(this,host,getIntent().getByteArrayExtra(EXTRA_SERVER_CERT),rhProfile));
        if(getIntent().hasExtra(com.limelight.rhremote.RhWindows.EXTRA)){
            try{conn.getRhDisplay().setWindow(new org.json.JSONObject(getIntent().getStringExtra(com.limelight.rhremote.RhWindows.EXTRA)));}
            catch(Exception invalid){com.limelight.rhremote.RhActions.connectionError("窗口选择无效，请重新选择");finish();return;}
        }'''),
    ])


def windows(root):
    change(root / "app/backend/nvhttp.h", [
        ("    getAppList();", "    getAppList();\n\n    QString getRhWindowsRaw();"),
    ])
    change(root / "app/backend/nvhttp.cpp", [
        ("NvHTTP::getAppList()", "NvHTTP::getAppList()"),
        ("QVector<NvApp>\nNvHTTP::getAppList()", '''QString NvHTTP::getRhWindowsRaw() {
    const auto xml=openConnectionToString(m_BaseUrlHttps,"rhremote/windows",nullptr,REQUEST_TIMEOUT_MS,NvLogLevel::NVLL_ERROR);
    verifyResponseStatus(xml);return xml;
}

QVector<NvApp>
NvHTTP::getAppList()'''),
    ])
    change(root / "app/cli/listapps.cpp", [
        ('#include "gui/rhhost.h"', '#include "gui/rhhost.h"\n#include "gui/rhwindowsource.h"'),
        ('if(path=="/v1/network/diagnose")result=', 'if(path=="/rhremote/windows"){NvHTTP http(event.computer);result=RhWindowSource::parse(http.getRhWindowsRaw());}else if(path=="/v1/network/diagnose")result='),
    ])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=["windows", "android"])
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    (windows if args.platform == "windows" else android)(args.source)
