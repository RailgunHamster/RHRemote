"""Apply the RHRemote pairing extension after socket/statistics instrumentation."""
import argparse
from pathlib import Path


def replace(text, before, after):
    if after in text:
        return text
    if text.count(before) != 1:
        raise RuntimeError(f'Pairing patch anchor missing or ambiguous: {before[:80]}')
    return text.replace(before, after)


def windows(root):
    path = root / 'app/backend/computerseeker.cpp'
    text = path.read_text(encoding='utf-8')
    text = replace(text, '    QString value = m_ComputerName.toLower();', '''    // Several remote hosts can share the local transport proxy address.
    // A cached address match must not select another host, even before polling.
    const auto target=qEnvironmentVariable("RHREMOTE_TARGET_UUID");
    if(!target.isEmpty() && computer->uuid.compare(target,Qt::CaseInsensitive)!=0)return false;
    QString value = m_ComputerName.toLower();''')
    path.write_text(text, encoding='utf-8', newline='\n')
    path = root / 'app/cli/pair.cpp'
    text = path.read_text(encoding='utf-8')
    text = replace(text, '#include "backend/computermanager.h"',
                   '#include "backend/computermanager.h"\n#include "gui/rhpairing.h"')
    text = replace(text, '                if (event.computer->pairState == NvComputer::PS_PAIRED) {',
                   '                if (event.computer->pairState == NvComputer::PS_PAIRED && !RhPairing::pendingRegression()) {')
    path.write_text(text, encoding='utf-8', newline='\n')
    path = root / 'app/backend/nvhttp.cpp'
    text = path.read_text(encoding='utf-8')
    text = replace(text, '    url.setQuery("uniqueid=0123456789ABCDEF&uuid=" +', '''    // Pairing uses a fresh ID; streaming retains GameStream's shared quit identity.
    const QString rhPairId=command=="pair"?qEnvironmentVariable("RHREMOTE_PAIR_ID"):QString();
    url.setQuery("uniqueid=" + (rhPairId.isEmpty()?QString("0123456789ABCDEF"):rhPairId) + "&uuid=" +''')
    path.write_text(text, encoding='utf-8', newline='\n')
    path = root / 'app/backend/nvpairingmanager.cpp'
    text = path.read_text(encoding='utf-8')
    text = replace(text, '#include "nvpairingmanager.h"', '#include "nvpairingmanager.h"\n#include "gui/rhpairing.h"')
    text = replace(text, '    QByteArray salt = generateRandomBytes(16);', '''    QByteArray salt = generateRandomBytes(16);
    if(!RhPairing::id().isEmpty()) {
        // Persist before the first request can become pending on the host.
        RhPairing::save(salt.toHex(),signMessage(RhPairing::message(salt.toHex())).toHex());
    }''')
    path.write_text(text, encoding='utf-8', newline='\n')


def android(root):
    path = root / 'app/src/main/java/com/limelight/nvstream/http/NvHTTP.java'
    text = path.read_text(encoding='utf-8')
    text = replace(text, '    private String uniqueId;', '''    private String uniqueId;
    private final com.limelight.rhremote.RhPairingAttempt rhPairing=new com.limelight.rhremote.RhPairingAttempt();
    public void rhPairingCapability(String value){rhPairing.capability(value);}
    public byte[] rhPairingMessage(String salt)throws IOException{return rhPairing.message(salt);}
    public void prepareRhPairing(String salt,String signature)throws IOException{rhPairing.prepare(salt,signature);}
    public void completeRhPairing(){rhPairing.complete();}
    public void cancelRhPairing(){
        rhPairing.cancel(this::cancelRhRequests,(id,salt,signature)->{
            java.net.URL url=baseUrlHttp.newBuilder().addPathSegment("pair")
                .addQueryParameter("uniqueid",id).addQueryParameter("phrase","rhcancel")
                .addQueryParameter("rhcancel_salt",salt).addQueryParameter("rhcancel_sig",signature).build().url();
            java.net.HttpURLConnection c=(java.net.HttpURLConnection)url.openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(1000);c.setReadTimeout(1000);c.setInstanceFollowRedirects(false);
            try{
                if(c.getResponseCode()!=200)return false;
                try(java.io.InputStream in=c.getInputStream()){
                    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;
                    while((n=in.read(buffer))!=-1){if(out.size()+n>4096)return false;out.write(buffer,0,n);}
                    return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8).contains("<cancelled>1</cancelled>");
                }
            }finally{c.disconnect();}
        });
    }''')
    text = replace(text, '.addQueryParameter("uniqueid", uniqueId)', '.addQueryParameter("uniqueid", path.equals("pair")?rhPairing.id():uniqueId)')
    text = replace(text, '        Response response = performAndroidTlsHack(client).newCall(request).execute();', '''        okhttp3.Call rhCall=performAndroidTlsHack(client).newCall(request);
        if(path.equals("pair"))rhPairing.track(rhCall::cancel);
        Response response = rhCall.execute();''')
    path.write_text(text, encoding='utf-8', newline='\n')
    path = root / 'app/src/main/java/com/limelight/nvstream/http/PairingManager.java'
    text = path.read_text(encoding='utf-8')
    text = replace(text, '    public PairState pair(String serverInfo, String pin) throws IOException, XmlPullParserException {', '''    public PairState pair(String serverInfo, String pin) throws IOException, XmlPullParserException {
        http.rhPairingCapability(NvHTTP.getXmlString(serverInfo,"RHPairingCancel",false));
        boolean completed=false;
        try{PairState result=pairRhAttempt(serverInfo,pin);completed=result==PairState.PAIRED;if(completed)http.completeRhPairing();return result;}
        finally{if(!completed)http.cancelRhPairing();}
    }
    private PairState pairRhAttempt(String serverInfo, String pin) throws IOException, XmlPullParserException {''')
    text = replace(text, '        byte[] salt = generateRandomBytes(16);', '''        byte[] salt = generateRandomBytes(16);
        String rhSalt=bytesToHex(salt);
        http.prepareRhPairing(rhSalt,bytesToHex(signData(http.rhPairingMessage(rhSalt),pk)));''')
    path.write_text(text, encoding='utf-8', newline='\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('platform', choices=['windows','android'])
    parser.add_argument('root', type=Path)
    args = parser.parse_args()
    (windows if args.platform == 'windows' else android)(args.root)
