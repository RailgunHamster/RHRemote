package com.limelight.rhremote;
import android.content.Context;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import javax.net.ssl.*;
import java.security.*;
import java.security.cert.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Mutual TLS with the existing Sunshine pairing identity. Never creates or exports credentials. */
public final class RhHost {
    public static JSONObject request(Context context,String host,byte[] pinnedDer,String path,JSONObject body)throws Exception {
        if(host==null||pinnedDer==null)throw new IOException("缺少主机配对信息");
        if(!path.equals("/v1/transport/authorize")){
            try{String endpoint=RhTunnel.identity(context);request(context,host,pinnedDer,"/v1/transport/authorize",RhActions.obj("endpointId",endpoint));}
            catch(Exception|LinkageError e){if("127.77.0.2".equals(host))throw new IOException("独立连接授权失败",e);}
        }
        X509Certificate pinned=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pinnedDer));
        LimelightCryptoProvider crypto=PlatformBinding.getCryptoProvider(context);
        X509KeyManager keys=new X509KeyManager(){
            public String chooseClientAlias(String[] types,Principal[] issuers,Socket socket){return "Sunshine";}
            public String chooseServerAlias(String type,Principal[] issuers,Socket socket){return null;}
            public X509Certificate[] getCertificateChain(String alias){return new X509Certificate[]{crypto.getClientCertificate()};}
            public PrivateKey getPrivateKey(String alias){return crypto.getClientPrivateKey();}
            public String[] getClientAliases(String type,Principal[] issuers){return new String[]{"Sunshine"};}
            public String[] getServerAliases(String type,Principal[] issuers){return null;}
        };
        X509TrustManager trust=new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] chain,String type)throws CertificateException{throw new CertificateException("client trust unsupported");}
            public void checkServerTrusted(X509Certificate[] chain,String type)throws CertificateException{if(chain.length!=1||!pinned.equals(chain[0]))throw new CertificateException("主机证书与配对记录不一致");}
        };
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(new KeyManager[]{keys},new TrustManager[]{trust},null);
        HttpsURLConnection connection=(HttpsURLConnection)new URI("https",null,host,RhTunnel.port(host,47995),path,null,null).toURL().openConnection(Proxy.NO_PROXY);
        connection.setSSLSocketFactory(ssl.getSocketFactory());connection.setHostnameVerifier((name,session)->{try{return session.getPeerCertificates().length==1&&pinned.equals(session.getPeerCertificates()[0]);}catch(Exception e){return false;}});
        connection.setConnectTimeout(2500);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);
        try {
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);connection.setRequestProperty("Content-Type","application/json");try(OutputStream out=connection.getOutputStream()){out.write(bytes);}}
            int code=connection.getResponseCode();InputStream source=code>=400?connection.getErrorStream():connection.getInputStream();if(source==null)throw new IOException("主机辅助服务响应为空");
            try(InputStream in=source){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>131072)throw new IOException("主机响应过大");out.write(b,0,n);}JSONObject result=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));if(!result.optBoolean("ok"))throw new IOException(result.optString("error","主机操作失败"));return result;}
        } catch(ConnectException e){
            throw new IOException("无法连接主机辅助服务（"+host+":47995），请在目标电脑安装或启动 RHRemoteHost",e);
        } catch(SocketTimeoutException e){
            throw new IOException("主机辅助服务响应超时，请检查 RHRemoteHost 和局域网连接",e);
        } finally {connection.disconnect();}
    }
}
