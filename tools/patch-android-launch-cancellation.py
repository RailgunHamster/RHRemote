"""Bind Android's pending launch to cancellation, native cleanup and recovery ownership."""
import argparse
from pathlib import Path


def replace(text, before, after):
    if after in text:
        return text
    if text.count(before) != 1:
        raise RuntimeError("Android launch ownership anchor missing or ambiguous: " + before[:85])
    return text.replace(before, after, 1)


def patch(root):
    path = root / "app/src/main/java/com/limelight/nvstream/http/NvHTTP.java"
    text = path.read_text(encoding="utf-8")
    text = replace(text, '    private String uniqueId;', '''    private String uniqueId;
    private final Object rhLaunchLock=new Object();
    private final LimelightCryptoProvider rhLaunchCrypto;
    private volatile boolean rhLaunchCapability;
    private boolean rhLaunchStopped;
    private com.limelight.rhremote.RhLaunchAttempt rhLaunch;
    public boolean awaitRhLaunchCleanup(long timeoutMs)throws InterruptedException {
        com.limelight.rhremote.RhLaunchAttempt attempt;synchronized(rhLaunchLock){attempt=rhLaunch;}
        return attempt==null||attempt.await(timeoutMs);
    }
    private String beginRhLaunch()throws IOException {
        com.limelight.rhremote.RhLaunchAttempt previous;synchronized(rhLaunchLock){previous=rhLaunch;}
        try{if(previous!=null&&!previous.await(3000))throw new IOException("Previous launch cleanup is incomplete");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Launch cancelled");}
        final ComputerDetails.AddressTuple target=new ComputerDetails.AddressTuple(baseUrlHttp.host(),baseUrlHttp.port());
        final int port=httpsPort;final String id=uniqueId;final X509Certificate certificate=serverCert;
        final com.limelight.rhremote.RhLaunchAttempt attempt=new com.limelight.rhremote.RhLaunchAttempt(rhLaunchCapability,credential->{
            NvHTTP cleanup=new NvHTTP(target,port,id,certificate,rhLaunchCrypto,true);
            OkHttpClient bounded=cleanup.httpClientLongConnectTimeout.newBuilder()
                .connectTimeout(1500,java.util.concurrent.TimeUnit.MILLISECONDS).readTimeout(1500,java.util.concurrent.TimeUnit.MILLISECONDS)
                .callTimeout(3000,java.util.concurrent.TimeUnit.MILLISECONDS).build();
            String reply=cleanup.openHttpConnectionToString(bounded,cleanup.getHttpsUrl(true),"rhremote/launch/cancel","rhremoteLaunchToken="+credential);
            return getXmlString(reply,"RHLaunchState",true);
        });
        boolean stop;synchronized(rhLaunchLock){if(rhLaunch!=previous)throw new IOException("Another launch owns this client");rhLaunch=attempt;stop=rhLaunchStopped;}
        if(stop){attempt.cancel();throw new IOException("Launch cancelled");}
        return attempt.query();
    }''')
    text = replace(text, '        this.rhStrictPin = strictPin;', '        this.rhStrictPin = strictPin;\n        this.rhLaunchCrypto = cryptoProvider;')
    text = replace(text, '    public String getServerInfo(boolean likelyOnline) throws IOException, XmlPullParserException {\n        String resp;',
                   '    public String getServerInfo(boolean likelyOnline) throws IOException, XmlPullParserException {\n        rhLaunchCapability=false;\n        String resp;')
    text = replace(text, '                getServerVersion(resp);',
                   '                getServerVersion(resp);\n                rhLaunchCapability=rhStrictPin&&serverCert!=null&&"1".equals(getXmlString(resp,"RHLaunchCancel",false));')
    text = replace(text, '                .addPathSegment(path)\n', '                .addPathSegments(path)\n')
    text = replace(text, '    public void cancelRhRequests() {\n        rhRequests.cancel();', '''    public void cancelRhRequests() {
        com.limelight.rhremote.RhLaunchAttempt attempt;synchronized(rhLaunchLock){rhLaunchStopped=true;attempt=rhLaunch;}
        if(attempt!=null)attempt.cancel();
        rhRequests.cancel();''')
    text = replace(text, '        String rhTraceQuery=com.limelight.rhremote.RhStatistics.launchQuery();',
                   '        String rhLaunchQuery=beginRhLaunch();\n        String rhTraceQuery=com.limelight.rhremote.RhStatistics.launchQuery();')
    text = replace(text, '            rhTraceQuery + rhDisplayQuery + MoonBridge.getLaunchUrlQueryParameters());',
                   '            rhLaunchQuery + rhTraceQuery + rhDisplayQuery + MoonBridge.getLaunchUrlQueryParameters());')
    text = replace(text, 'LimeLog.info(getCompleteUrl(baseUrl, path, query)+" -> "+respString);',
                   'LimeLog.info(com.limelight.rhremote.RhLaunchAttempt.redact(getCompleteUrl(baseUrl, path, query).toString()+" -> "+respString));')
    text = replace(text, 'LimeLog.warning(getCompleteUrl(baseUrl, path, query)+" -> "+e.getMessage());',
                   'LimeLog.warning(com.limelight.rhremote.RhLaunchAttempt.redact(getCompleteUrl(baseUrl, path, query).toString()+" -> "+e.getMessage()));')
    # Preserve TLS/certificate classification, without propagating a URL-bearing cause to UI logs.
    text = replace(text, '        } catch (IOException e) {\n            if (verbose && !path.equals("serverinfo")) {', '''        } catch (IOException e) {
            if(query!=null&&query.contains("rhremoteLaunchToken=")){
                String safe=com.limelight.rhremote.RhLaunchAttempt.redact(e.getMessage());
                IOException redacted=e instanceof HostHttpResponseException?new HostHttpResponseException(((HostHttpResponseException)e).getErrorCode(),safe):
                    e instanceof SSLHandshakeException?new SSLHandshakeException(safe):
                    e instanceof java.net.SocketTimeoutException?new java.net.SocketTimeoutException(safe):new IOException(safe);
                for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof CertificateException){redacted.initCause(new CertificateException("Paired certificate validation failed"));break;}
                throw redacted;
            }
            if (verbose && !path.equals("serverinfo")) {''')
    path.write_text(text, encoding="utf-8", newline="\n")

    path = root / "app/src/main/java/com/limelight/nvstream/NvConnection.java"
    text = path.read_text(encoding="utf-8")
    text = replace(text, 'NvHTTP h = new NvHTTP(context.serverAddress, context.httpsPort, uniqueId, context.serverCert, cryptoProvider);',
                   'NvHTTP h = new NvHTTP(context.serverAddress, context.httpsPort, uniqueId, context.serverCert, cryptoProvider, true);')
    before = '''    public boolean rhAwaitStopped(long timeoutMs){
        Thread starter=rhStarter;if(starter==null||starter==Thread.currentThread())return true;
        try{starter.join(timeoutMs);return !starter.isAlive();}
        catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    }'''
    after = '''    public boolean rhAwaitStopped(long timeoutMs){
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0,timeoutMs));
        Thread starter=rhStarter;if(starter==Thread.currentThread())return false;
        try{
            if(starter!=null&&starter.isAlive()){
                long left=deadline-System.nanoTime();if(left<=0)return false;
                starter.join(Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left)));if(starter.isAlive())return false;
            }
            NvHTTP http=rhHttp;return http==null||http.awaitRhLaunchCleanup(Math.max(0,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
        }catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    }'''
    text = replace(text, before, after)
    path.write_text(text, encoding="utf-8", newline="\n")

    path = root / "app/src/main/java/com/limelight/Game.java"
    text = path.read_text(encoding="utf-8")
    text = replace(text, 'while(!conn.rhAwaitStopped(1000)){}', 'while(!conn.rhAwaitStopped(1000)){Thread.sleep(1000);}')
    path.write_text(text, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    patch(parser.parse_args().source)
