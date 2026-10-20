package com.limelight.rhremote;
import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** WebDAV conditional writes and three-way merge, compatible with the Windows schema. */
public final class RhSync {
    private final Context context;private final File file;private volatile JSONObject local;
    private JSONObject pendingLocal,pendingRemote;private String pendingEtag;
    private volatile JSONArray conflicts=new JSONArray();
    public RhSync(Context c){context=c.getApplicationContext();file=new File(c.getFilesDir(),"webdav-local.json");try{local=new JSONObject(new String(new AtomicFile(file).readFully(),StandardCharsets.UTF_8));}catch(Exception e){local=new JSONObject();}}
    public JSONObject state(){JSONObject l=local;return RhActions.obj("url",l.optString("url"),"username",l.optString("username"),"automatic",l.optBoolean("automatic"),"intervalMinutes",l.optInt("intervalMinutes",5),"hasPassword",l.has("ciphertext"),"lastSyncMs",l.optLong("lastSyncMs"),"conflicts",conflicts);}
    static URL endpoint(String text)throws Exception{URI uri=new URI(text.trim());if(!"https".equalsIgnoreCase(uri.getScheme())&&!"http".equalsIgnoreCase(uri.getScheme()))throw new IOException("WebDAV 请使用 HTTP 或 HTTPS 地址");if(uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IOException("WebDAV 地址无效");return new URL(text.trim().replaceAll("/+$","")+"/rhremote-config-v1.json");}
    static int interval(JSONObject args,int current)throws Exception{if(!args.has("intervalMinutes"))return Arrays.asList(5,15,30,60).contains(current)?current:5;Object v=args.get("intervalMinutes");if(!(v instanceof Number)||((Number)v).doubleValue()!=((Number)v).intValue()||!Arrays.asList(5,15,30,60).contains(((Number)v).intValue()))throw new IOException("自动同步间隔只能为 5、15、30 或 60 分钟");return ((Number)v).intValue();}
    private static javax.crypto.SecretKey key()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(!store.containsAlias("RHRemote.WebDAV")){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder("RHRemote.WebDAV",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();}return (javax.crypto.SecretKey)store.getKey("RHRemote.WebDAV",null);}
    public void configure(JSONObject args)throws Exception{
        String address=args.getString("url").trim(),user=args.getString("username").trim(),password=args.optString("password");endpoint(address);int intervalMinutes=interval(args,local.optInt("intervalMinutes",5));if(user.isEmpty()||user.contains(":")||user.contains("\n")||user.contains("\r"))throw new IOException("WebDAV 用户名无效");
        JSONObject next=new JSONObject(local.toString());boolean same=address.equals(next.optString("url"))&&user.equals(next.optString("username"));if(password.isEmpty()&&(!same||!next.has("ciphertext")))throw new IOException("请填写 WebDAV 密码");
        if(!same){next.remove("base");next.remove("lastSyncMs");}
        if(!password.isEmpty()){Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());next.put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));next.put("ciphertext",Base64.encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));}
        next.put("url",address);next.put("username",user);next.put("automatic",args.optBoolean("automatic"));next.put("intervalMinutes",intervalMinutes);RhShared.write(file,next);local=next;clearPending();
    }
    public void clear()throws IOException{RhShared.write(file,new JSONObject());local=new JSONObject();clearPending();}
    private void clearPending(){pendingLocal=null;pendingRemote=null;pendingEtag=null;conflicts=new JSONArray();}
    private String password(JSONObject l)throws Exception{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(l.getString("iv"),Base64.NO_WRAP)));return new String(cipher.doFinal(Base64.decode(l.getString("ciphertext"),Base64.NO_WRAP)),StandardCharsets.UTF_8);}
    private static HttpURLConnection open(URL url,String method,String authorization)throws IOException{HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);c.setRequestProperty("Authorization",authorization);return c;}
    private static byte[] read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("远端配置过大");out.write(b,0,n);}return out.toByteArray();}
    public JSONObject run(JSONObject ours,String resolution)throws Exception{
        ours=RhShared.normalize(ours);JSONObject l=local;URL url=endpoint(l.getString("url"));String auth="Basic "+Base64.encodeToString((l.getString("username")+":"+password(l)).getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
        JSONObject remote=null;String etag=null;HttpURLConnection get=open(url,"GET",auth);
        try{int code=get.getResponseCode();if(code==200){etag=get.getHeaderField("ETag");if(etag==null||etag.startsWith("W/"))throw new IOException("WebDAV 服务器未提供强 ETag");try(InputStream in=get.getInputStream()){remote=RhShared.normalize(new JSONObject(new String(read(in),StandardCharsets.UTF_8)));}}else if(code!=404)throw new IOException("WebDAV HTTP "+code);}finally{get.disconnect();}
        if(!resolution.equals("review")){if(!Arrays.asList("local","remote").contains(resolution)||pendingLocal==null||!RhShared.canonical(pendingLocal).equals(RhShared.canonical(ours))||remote==null||!RhShared.canonical(pendingRemote).equals(RhShared.canonical(remote))||!Objects.equals(etag,pendingEtag)){clearPending();throw new IOException("配置已变化，请重新同步并检查冲突");}}
        JSONArray found=new JSONArray();JSONObject merged=remote==null?ours:merge(ours,remote,l.optJSONObject("base"),resolution,found);
        if(found.length()>0){pendingLocal=new JSONObject(ours.toString());pendingRemote=new JSONObject(remote.toString());pendingEtag=etag;conflicts=found;return RhActions.obj("conflicts",found);}
        RhShared.validate(merged);
        if(remote!=null){File dir=new File(context.getFilesDir(),"sync-backups");if(!dir.exists()&&!dir.mkdirs())throw new IOException("backup directory unavailable");RhShared.write(new File(dir,"remote-"+System.currentTimeMillis()+".json"),remote);File[] files=dir.listFiles((d,n)->n.startsWith("remote-"));if(files!=null){Arrays.sort(files,Comparator.comparing(File::getName));for(int i=0;i<files.length-20;i++)files[i].delete();}}
        byte[] bytes=merged.toString().getBytes(StandardCharsets.UTF_8);HttpURLConnection put=open(url,"PUT",auth);try{put.setRequestProperty("Content-Type","application/json");put.setRequestProperty(etag==null?"If-None-Match":"If-Match",etag==null?"*":etag);put.setDoOutput(true);put.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=put.getOutputStream()){out.write(bytes);}int code=put.getResponseCode();if(code<200||code>=300)throw new IOException("WebDAV HTTP "+code);}finally{put.disconnect();}
        JSONObject next=new JSONObject(l.toString());next.put("base",merged);next.put("lastSyncMs",System.currentTimeMillis());RhShared.write(file,next);local=next;clearPending();return RhActions.obj("shared",merged,"conflicts",new JSONArray());
    }
    private static Map<String,JSONObject> devices(JSONObject config)throws JSONException{Map<String,JSONObject> map=new TreeMap<>();if(config!=null){JSONArray list=config.getJSONArray("devices");for(int i=0;i<list.length();i++){JSONObject d=list.getJSONObject(i);map.put(d.getString("uuid"),d);}}return map;}
    private static Object choose(Object left,Object right,Object base,String key,String resolution,JSONArray conflicts)throws JSONException{
        if(left==null)return right;if(right==null)return left;String l=RhShared.canonical(left),r=RhShared.canonical(right),b=base==null?null:RhShared.canonical(base);if(l.equals(r))return left;if(l.equals(b))return right;if(r.equals(b))return left;if(resolution.equals("review"))conflicts.put(key);return resolution.equals("remote")?right:left;
    }
    private static JSONObject mergeMap(JSONObject left,JSONObject right,JSONObject base,String prefix,String resolution,JSONArray conflicts)throws JSONException{
        JSONObject out=new JSONObject();Set<String> ids=new TreeSet<>();left.keys().forEachRemaining(ids::add);right.keys().forEachRemaining(ids::add);for(String id:ids)out.put(id,choose(left.opt(id),right.opt(id),base==null?null:base.opt(id),prefix+"."+id,resolution,conflicts));return out;
    }
    public static JSONObject merge(JSONObject left,JSONObject right,JSONObject base,String resolution,JSONArray conflicts)throws Exception{
        left=RhShared.normalize(left);right=RhShared.normalize(right);if(base!=null)base=RhShared.normalize(base);
        JSONObject lp=left.getJSONObject("profiles"),rp=right.getJSONObject("profiles"),bp=base==null?new JSONObject():base.getJSONObject("profiles"),profiles=new JSONObject();Set<String> ids=new TreeSet<>();lp.keys().forEachRemaining(ids::add);rp.keys().forEachRemaining(ids::add);
        for(String id:ids)profiles.put(id,mergeMap(lp.has(id)?lp.getJSONObject(id):new JSONObject(),rp.has(id)?rp.getJSONObject(id):new JSONObject(),bp.optJSONObject(id),"profiles."+id,resolution,conflicts));
        Map<String,JSONObject> ld=devices(left),rd=devices(right),bd=devices(base);ids=new TreeSet<>();ids.addAll(ld.keySet());ids.addAll(rd.keySet());Map<String,JSONObject> hosts=new TreeMap<>();for(String id:ids)hosts.put(id,(JSONObject)choose(ld.get(id),rd.get(id),bd.get(id),"devices."+id,resolution,conflicts));
        JSONArray lo=order(left),ro=order(right),bo=base==null?null:order(base);
        JSONArray sequence=lo.length()==0?ro:ro.length()==0?lo:(JSONArray)choose(lo,ro,bo,"deviceOrder",resolution,conflicts);
        JSONArray ds=new JSONArray();for(int i=0;i<sequence.length();i++){JSONObject host=hosts.remove(sequence.getString(i));if(host!=null)ds.put(host);}for(JSONObject host:hosts.values())ds.put(host);
        JSONObject clients=mergeMap(left.getJSONObject("clients"),right.getJSONObject("clients"),base==null?null:base.getJSONObject("clients"),"clients",resolution,conflicts);
        Object dark=choose(left.get("dark"),right.get("dark"),base==null?null:base.get("dark"),"appearance.dark",resolution,conflicts);return RhActions.obj("schemaVersion",2,"devices",ds,"clients",clients,"profiles",profiles,"dark",dark);
    }
    private static JSONArray order(JSONObject config)throws JSONException {JSONArray out=new JSONArray(),rows=config.getJSONArray("devices");for(int i=0;i<rows.length();i++)out.put(rows.getJSONObject(i).getString("uuid"));return out;}
}
