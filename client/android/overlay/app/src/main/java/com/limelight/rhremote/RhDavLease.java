package com.limelight.rhremote;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** An operation-scoped, finite WebDAV write lock. Tokens never enter public state. */
final class RhDavLease implements AutoCloseable {
    interface Opener {HttpURLConnection open(URL url,String method,String authorization)throws IOException;}
    private static final String BODY="<?xml version=\"1.0\" encoding=\"utf-8\"?><D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype><D:owner>RHRemote</D:owner></D:lockinfo>";
    private static final class Clients {
        // Independent from the streaming client's host-specific certificate policy.
        static final OkHttpClient NORMAL=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
            .writeTimeout(15,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build();
        static final OkHttpClient RELEASE=NORMAL.newBuilder().callTimeout(3,TimeUnit.SECONDS).build();
    }
    static boolean supported(URL url,String authorization,Opener opener)throws IOException {
        HttpURLConnection connection=opener.open(url,"OPTIONS",authorization);
        try {
            connection.setConnectTimeout(5000);connection.setReadTimeout(5000);int code=connection.getResponseCode();
            if(code==403||code==404||code==405||code==501)return false;
            if(code<200||code>=300)throw new IOException("WebDAV HTTP "+code);
            String value=connection.getHeaderField("DAV");if(value==null)return false;
            for(String part:value.split(","))if(part.trim().equals("2"))return true;
            return false;
        } finally {connection.disconnect();}
    }
    private final OkHttpClient client;
    private final URL url;
    private final String authorization,token;
    private final boolean created;
    private boolean released;
    private RhDavLease(URL url,String authorization,String token,boolean created,OkHttpClient client){this.client=client;this.url=url;this.authorization=authorization;this.token=token;this.created=created;}
    static RhDavLease acquire(URL url,String authorization)throws IOException {return acquire(url,authorization,java.net.Proxy.NO_PROXY);}
    static RhDavLease acquire(URL url,String authorization,java.net.Proxy proxy)throws IOException {
        OkHttpClient client=Clients.NORMAL.newBuilder().proxy(proxy).build();
        Request request=new Request.Builder().url(url).header("Authorization",authorization).header("Depth","0")
            .header("Timeout","Second-60").method("LOCK",RequestBody.create(BODY.getBytes(StandardCharsets.UTF_8),MediaType.get("application/xml; charset=utf-8"))).build();
        try(Response response=client.newCall(request).execute()){
            int code=response.code();if(code!=200&&code!=201)throw new IOException("WebDAV HTTP "+code);
            String token=response.header("Lock-Token");token=token==null?"":token.trim();
            if(!valid(token))throw new IOException("WebDAV 未返回有效的同步保护凭据，请稍后重试");
            return new RhDavLease(url,authorization,token,code==201,client);
        }
    }
    private static boolean valid(String token){
        if(token.length()<3||token.length()>2048||!token.startsWith("<")||!token.endsWith(">"))return false;
        String value=token.substring(1,token.length()-1);for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<=32||c>=127||c=='<'||c=='>')return false;}
        return true;
    }
    String condition(){return "("+token+")";}
    boolean createdResource(){return created;}
    @Override public void close()throws IOException {
        if(released)return;released=true;
        Request request=new Request.Builder().url(url).header("Authorization",authorization).header("Lock-Token",token).method("UNLOCK",null).build();
        try(Response response=client.newBuilder().callTimeout(3,TimeUnit.SECONDS).build().newCall(request).execute()){
            if(response.code()<200||response.code()>=300)throw new IOException("WebDAV 同步保护未能释放，请稍后手动同步核对");
        }catch(IOException failure){throw new IOException("WebDAV 同步保护未能释放，请稍后手动同步核对",failure);}
    }
}
