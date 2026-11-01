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
    private static final class PendingReview {
        final JSONObject local,remote;final String etag,id;final JSONArray conflicts;
        PendingReview(JSONObject local,JSONObject remote,String etag,String id,JSONArray conflicts)throws JSONException{this.local=new JSONObject(local.toString());this.remote=new JSONObject(remote.toString());this.etag=etag;this.id=id;this.conflicts=new JSONArray(conflicts.toString());}
    }
    private volatile PendingReview pending;
    private volatile JSONObject awaitingApply,appliedAccount;
    private Boolean locking;
    public RhSync(Context c){context=c.getApplicationContext();file=new File(c.getFilesDir(),"webdav-local.json");try{local=new JSONObject(new String(new AtomicFile(file).readFully(),StandardCharsets.UTF_8));}catch(Exception e){local=new JSONObject();}}
    boolean outcomeUncertain(){return local.optBoolean("pendingTransfer");}
    private static JSONArray conflictSnapshot(PendingReview review){try{return review==null?new JSONArray():new JSONArray(review.conflicts.toString());}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    public JSONObject state(){JSONObject l=local;PendingReview p=pending;JSONArray found=conflictSnapshot(p);return RhActions.obj("url",l.optString("url"),"username",l.optString("username"),"automatic",l.optBoolean("automatic"),"intervalMinutes",l.optInt("intervalMinutes",5),"hasPassword",l.has("ciphertext"),"lastSyncMs",l.optLong("lastSyncMs"),"conflicts",found,"conflictItems",review(context,p==null?null:p.local,p==null?null:p.remote,found),"reviewId",p==null?"":p.id,"awaitingLocalApply",awaitingApply!=null,"outcomeUncertain",l.optBoolean("pendingTransfer"));}
    private static String deviceName(JSONObject config,String uuid){if(config!=null){JSONArray rows=config.optJSONArray("devices");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject value=rows.optJSONObject(i);if(value!=null&&uuid.equals(value.optString("uuid")))return value.optString("displayName").isEmpty()?value.optString("name","设备"):value.optString("displayName");}}return "设备";}
    private static JSONObject device(JSONObject config,String uuid){JSONArray rows=config.optJSONArray("devices");if(rows!=null)for(int i=0;i<rows.length();i++)if(uuid.equals(rows.optJSONObject(i).optString("uuid")))return rows.optJSONObject(i);return new JSONObject();}
    private static String summary(JSONObject config,String key){
        if(key.equals("appearance.dark"))return config.optBoolean("dark")?"深色":"浅色";
        if(key.equals("deviceOrder")){List<String> names=new ArrayList<>();JSONArray rows=config.optJSONArray("devices");if(rows!=null)for(int i=0;i<rows.length();i++)names.add(deviceName(config,rows.optJSONObject(i).optString("uuid")));return android.text.TextUtils.join(" → ",names);}
        if(key.startsWith("devices.")){JSONObject value=device(config,key.substring(8));return deviceName(config,key.substring(8))+" · "+value.optString("address")+(value.optBoolean("swapButtons")?" · 左右键交换":"");}
        if(key.startsWith("clients.")){JSONObject clients=config.optJSONObject("clients"),value=clients==null?null:clients.optJSONObject(key.substring(8));return value==null?"未设置":value.optString("name","客户端")+" · "+value.optString("platform");}
        JSONObject profiles=config.optJSONObject("profiles");if(profiles!=null)for(Iterator<String> clients=profiles.keys();clients.hasNext();){String client=clients.next();JSONObject group=profiles.optJSONObject(client);if(group!=null)for(Iterator<String> hosts=group.keys();hosts.hasNext();){String host=hosts.next();if(key.equals("profiles."+client+"."+host)){JSONObject p=group.optJSONObject(host);if(p==null)return "未设置";return p.optInt("width")+" × "+p.optInt("height")+" · "+p.optInt("fps")+" FPS · "+p.optInt("bitrateMbps")+" Mbps";}}}
        return "配置已修改";
    }
    private static JSONArray deviceFields(JSONObject config,String uuid){
        JSONObject row=device(config,uuid);if(!row.has("uuid"))return null;
        JSONArray addresses=row.optJSONArray("addresses");List<String> extras=new ArrayList<>();if(addresses!=null)for(int i=0;i<addresses.length();i++)extras.add("“"+addresses.optString(i)+"”");
        return new JSONArray().put(RhActions.obj("key","name","label","设备名称","value",row.optString("name"),"raw",row.optString("name")))
            .put(RhActions.obj("key","displayName","label","显示名称","value",row.optString("displayName").isEmpty()?"未设置":"“"+row.optString("displayName")+"”","raw",row.optString("displayName")))
            .put(RhActions.obj("key","address","label","首选地址","value",row.optString("address"),"raw",row.optString("address")))
            .put(RhActions.obj("key","addresses","label","备用地址","value",extras.isEmpty()?"未设置":android.text.TextUtils.join("、",extras),"raw",addresses==null?new JSONArray():addresses))
            .put(RhActions.obj("key","swapButtons","label","左右键交换","value",row.optBoolean("swapButtons")?"开启":"关闭","raw",row.optBoolean("swapButtons")));
    }
    private static JSONArray profileFields(Context context,JSONObject config,String key)throws JSONException{
        JSONObject profiles=config.optJSONObject("profiles");if(profiles==null)return null;
        for(Iterator<String> clients=profiles.keys();clients.hasNext();){String client=clients.next();JSONObject group=profiles.optJSONObject(client);if(group==null)continue;
            for(Iterator<String> hosts=group.keys();hosts.hasNext();){String host=hosts.next();if(key.equals("profiles."+client+"."+host)){RhProfile profile=RhProfile.fromClientJson(host,group.getJSONObject(host));JSONObject raw=profile.json();JSONArray fields=RhProfileCopy.fields(context,profile,null);for(int i=0;i<fields.length();i++){JSONObject row=fields.getJSONObject(i);row.put("raw",raw.get(row.getString("key")));}return fields;}}
        }
        return null;
    }
    private static LinkedHashMap<String,JSONObject> values(JSONArray fields){LinkedHashMap<String,JSONObject> result=new LinkedHashMap<>();if(fields!=null)for(int i=0;i<fields.length();i++){JSONObject row=fields.optJSONObject(i);result.put(row.optString("key"),row);}return result;}
    private static String changed(JSONArray fields,Map<String,JSONObject> values,Map<String,JSONObject> other,Set<String> keys){
        if(fields==null)return "未设置";if(keys.isEmpty())return "无差异";List<String> lines=new ArrayList<>();for(String key:keys){JSONObject row=values.get(key),label=row==null?other.get(key):row;lines.add(label.optString("label")+"："+(row==null?"未设置":row.optString("value")));}return android.text.TextUtils.join("\n",lines);
    }
    static JSONArray review(Context context,JSONObject left,JSONObject right,JSONArray keys){
        JSONArray out=new JSONArray();if(left==null||right==null)return out;
        for(int i=0;i<keys.length();i++){String key=keys.optString(i),label="其他配置";
            if(key.equals("deviceOrder"))label="设备顺序";else if(key.equals("appearance.dark"))label="主题";
            else if(key.startsWith("devices."))label="远程设备："+deviceName(left,key.substring(8));
            else if(key.startsWith("clients."))label="客户端资料";
            else if(key.startsWith("profiles.")){JSONObject profiles=left.optJSONObject("profiles"),clients=left.optJSONObject("clients");if(profiles!=null)for(Iterator<String> ids=profiles.keys();ids.hasNext();){String id=ids.next();JSONObject group=profiles.optJSONObject(id);if(group!=null)for(Iterator<String> hosts=group.keys();hosts.hasNext();){String host=hosts.next();if(key.equals("profiles."+id+"."+host)){JSONObject client=clients==null?null:clients.optJSONObject(id);label="连接配置："+(client==null?"客户端":client.optString("name","客户端"))+" → "+deviceName(left,host);}}}}
            String local,remote;JSONArray details=new JSONArray();
            if(key.startsWith("devices.")||key.startsWith("profiles.")){
                JSONArray a,b;try{a=key.startsWith("devices.")?deviceFields(left,key.substring(8)):profileFields(context,left,key);b=key.startsWith("devices.")?deviceFields(right,key.substring(8)):profileFields(context,right,key);}catch(JSONException invalid){throw new IllegalStateException("同步配置差异无法读取",invalid);}
                Map<String,JSONObject> av=values(a),bv=values(b);Set<String> changed=new LinkedHashSet<>(av.keySet());changed.addAll(bv.keySet());try{Iterator<String> fields=changed.iterator();while(fields.hasNext()){String field=fields.next();if(RhShared.canonical(av.containsKey(field)?av.get(field).opt("raw"):null).equals(RhShared.canonical(bv.containsKey(field)?bv.get(field).opt("raw"):null)))fields.remove();}}catch(JSONException invalid){throw new IllegalStateException("同步配置差异无法比较",invalid);}local=changed(a,av,bv,changed);remote=changed(b,bv,av,changed);
                for(String field:changed){JSONObject ar=av.get(field),br=bv.get(field),metadata=ar==null?br:ar;details.put(RhActions.obj("key",field,"label",metadata.optString("label"),"local",ar==null?"未设置":ar.optString("value"),"remote",br==null?"未设置":br.optString("value")));}
            }else{local=summary(left,key);remote=summary(right,key);}
            out.put(RhActions.obj("key",key,"label",label,"local",local,"remote",remote,"fields",details));
        }return out;
    }
    static URL endpoint(String text)throws Exception{URI uri=new URI(text.trim());if(!"https".equalsIgnoreCase(uri.getScheme())&&!"http".equalsIgnoreCase(uri.getScheme()))throw new IOException("WebDAV 请使用 HTTP 或 HTTPS 地址");if(uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IOException("WebDAV 地址无效");return new URL(text.trim().replaceAll("/+$","")+"/rhremote-config-v1.json");}
    static int interval(JSONObject args,int current)throws Exception{if(!args.has("intervalMinutes"))return Arrays.asList(5,15,30,60).contains(current)?current:5;Object v=args.get("intervalMinutes");if(!(v instanceof Number)||((Number)v).doubleValue()!=((Number)v).intValue()||!Arrays.asList(5,15,30,60).contains(((Number)v).intValue()))throw new IOException("自动同步间隔只能为 5、15、30 或 60 分钟");return ((Number)v).intValue();}
    private static javax.crypto.SecretKey key()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(!store.containsAlias("RHRemote.WebDAV")){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder("RHRemote.WebDAV",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();}return (javax.crypto.SecretKey)store.getKey("RHRemote.WebDAV",null);}
    public void configure(JSONObject args)throws Exception{
        String address=args.getString("url").trim(),user=args.getString("username").trim(),password=args.optString("password");endpoint(address);int intervalMinutes=interval(args,local.optInt("intervalMinutes",5));if(user.isEmpty()||user.contains(":")||user.contains("\n")||user.contains("\r"))throw new IOException("WebDAV 用户名无效");
        JSONObject next=new JSONObject(local.toString());boolean same=address.equals(next.optString("url"))&&user.equals(next.optString("username"));if(password.isEmpty()&&(!same||!next.has("ciphertext")))throw new IOException("请填写 WebDAV 密码");
        if(!same){next.remove("base");next.remove("lastSyncMs");next.remove("pendingTransfer");}
        if(!password.isEmpty()){Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());next.put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));next.put("ciphertext",Base64.encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));}
        next.put("url",address);next.put("username",user);next.put("automatic",args.optBoolean("automatic"));next.put("intervalMinutes",intervalMinutes);RhShared.write(file,next);local=next;clearPending();clearApplied();locking=null;
    }
    public void clear()throws IOException{
        if(file.exists()&&!file.isFile())throw new IOException("本机同步配置文件不可写");
        RhShared.write(file,new JSONObject());
        // AtomicFile.finishWrite can log a rename failure without throwing. Do not report
        // removal or discard the in-memory account until its persisted state is verified.
        try{if(new JSONObject(new String(new AtomicFile(file).readFully(),StandardCharsets.UTF_8)).length()!=0)throw new IOException("同步设置未能移除，请重试");}
        catch(JSONException invalid){throw new IOException("本机同步配置写入失败",invalid);}
        local=new JSONObject();clearPending();clearApplied();locking=null;
    }
    private void clearPending(){pending=null;}
    private String password(JSONObject l)throws Exception{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(l.getString("iv"),Base64.NO_WRAP)));return new String(cipher.doFinal(Base64.decode(l.getString("ciphertext"),Base64.NO_WRAP)),StandardCharsets.UTF_8);}
    private HttpURLConnection open(URL url,String method,String authorization)throws IOException{HttpURLConnection c=RhProxy.open(context,url,"sync");c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);c.setRequestProperty("Authorization",authorization);return c;}
    static String failureMessage(Exception failure){
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof javax.net.ssl.SSLException)return "WebDAV 证书或 TLS 连接验证失败，请检查证书和地址";
            if(cause instanceof SocketTimeoutException)return "WebDAV 连接超时，请检查网络后重试";
            if(cause instanceof UnknownHostException)return "无法解析 WebDAV 地址，请检查地址和网络";
        }
        String message=failure.getMessage();
        if(message!=null&&message.startsWith("WebDAV HTTP ")){
            String code=message.substring("WebDAV HTTP ".length());
            if(code.equals("401")||code.equals("403"))return "WebDAV 登录失败，请检查账户和权限（HTTP "+code+"）";
            if(code.equals("412"))return "远端配置已更新，请重新同步后再处理冲突（HTTP 412）";
            if(code.equals("423"))return "WebDAV 文件被占用，请稍后重试（HTTP 423）";
            if(code.startsWith("3"))return "WebDAV 地址发生重定向，请使用最终目录地址（HTTP "+code+"）";
            return "WebDAV 服务器返回错误，请稍后重试（HTTP "+code+"）";
        }
        // Preserve application-level DAV explanations, including a failed lease
        // release, before translating the underlying generic socket failure.
        if(failure instanceof IOException&&message!=null&&message.startsWith("WebDAV "))return message;
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof ConnectException)return "WebDAV 连接失败，请检查地址和网络后重试";
            if(cause instanceof NoRouteToHostException)return "WebDAV 地址不可达，请检查网络和路由";
            if(cause instanceof EOFException)return "WebDAV 连接提前结束，请检查网络后重试";
            if(cause instanceof SocketException)return "WebDAV 连接已中断，请检查网络后重试";
        }
        if(failure instanceof IOException&&message!=null&&!message.isEmpty()&&!message.startsWith("failed to connect")&&!message.startsWith("Unable to resolve"))return message;
        return "同步失败，请检查 WebDAV 设置和网络后重试";
    }
    private static byte[] read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("远端配置过大");out.write(b,0,n);}return out.toByteArray();}
    void validateReviewId(String expected){if(expected!=null&&(expected.isEmpty()||!expected.equals(state().optString("reviewId"))))throw new IllegalStateException("同步冲突内容已更新，请重新查看后再选择");}
    public synchronized JSONObject runReviewed(JSONObject ours,String resolution,String expected)throws Exception{validateReviewId(expected);return run(ours,resolution);}
    public synchronized JSONObject run(JSONObject ours,String resolution)throws Exception{
        clearApplied();
        ours=RhShared.normalize(ours);JSONObject l=local;URL url=endpoint(l.getString("url"));String auth="Basic "+Base64.encodeToString((l.getString("username")+":"+password(l)).getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
        if(locking==null)locking=RhDavLease.supported(url,auth,this::open);
        try(RhDavLease lease=locking?RhDavLease.acquire(url,auth,RhProxy.route(context,"sync")):null){
        JSONObject remote=null;String etag=null;HttpURLConnection get=open(url,"GET",auth);
        try{int code=get.getResponseCode();if(code==200){etag=get.getHeaderField("ETag");if(etag==null||etag.startsWith("W/"))throw new IOException("WebDAV 服务器未提供强 ETag");try(InputStream in=get.getInputStream()){byte[] bytes=read(in);boolean emptyCreation=bytes.length==0&&lease!=null&&(lease.createdResource()||!l.has("base")&&l.optLong("lastSyncMs")==0);if(!emptyCreation)remote=RhShared.normalize(new JSONObject(new String(bytes,StandardCharsets.UTF_8)));}}else if(code!=404)throw new IOException("WebDAV HTTP "+code);}finally{get.disconnect();}
        PendingReview previous=pending;
        if(!resolution.equals("review")){if(!Arrays.asList("local","remote").contains(resolution)||previous==null||!RhShared.canonical(previous.local).equals(RhShared.canonical(ours))||remote==null||!RhShared.canonical(previous.remote).equals(RhShared.canonical(remote))||!Objects.equals(etag,previous.etag)){clearPending();throw new IOException("配置已变化，请重新同步并检查冲突");}}
        JSONArray found=new JSONArray();JSONObject merged=remote==null?ours:merge(ours,remote,l.optJSONObject("base"),resolution,found);
        if(found.length()>0){String id=previous!=null&&Objects.equals(etag,previous.etag)&&RhShared.canonical(previous.local).equals(RhShared.canonical(ours))&&RhShared.canonical(previous.remote).equals(RhShared.canonical(remote))?previous.id:UUID.randomUUID().toString();pending=new PendingReview(ours,remote,etag,id,found);return RhActions.obj("conflicts",found);}
        RhShared.validate(merged);
        if(remote!=null){File dir=new File(context.getFilesDir(),"sync-backups");if(!dir.exists()&&!dir.mkdirs())throw new IOException("backup directory unavailable");RhShared.write(new File(dir,"remote-"+System.currentTimeMillis()+".json"),remote);File[] files=dir.listFiles((d,n)->n.startsWith("remote-"));if(files!=null){Arrays.sort(files,Comparator.comparing(File::getName));for(int i=0;i<files.length-20;i++)files[i].delete();}}
        boolean previouslyPending=outcomeUncertain();recordTransferStarted();
        byte[] bytes=merged.toString().getBytes(StandardCharsets.UTF_8);HttpURLConnection put=open(url,"PUT",auth);try{put.setRequestProperty("Content-Type","application/json");put.setRequestProperty(etag==null?"If-None-Match":"If-Match",etag==null?"*":etag);if(lease!=null)put.setRequestProperty("If",lease.condition());put.setDoOutput(true);put.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=put.getOutputStream()){out.write(bytes);}int code=put.getResponseCode();if(code<200||code>=300){recordRejectedTransfer(code,previouslyPending);throw new IOException("WebDAV HTTP "+code);}}finally{put.disconnect();}
        appliedAccount=new JSONObject(local.toString());awaitingApply=new JSONObject(merged.toString());clearPending();return RhActions.obj("shared",merged,"conflicts",new JSONArray());
        }
    }
    private void clearApplied(){awaitingApply=null;appliedAccount=null;}
    /** A PUT may be committed even when its response never arrives. Record before output. */
    private void recordTransferStarted()throws Exception{
        JSONObject next=new JSONObject(local.toString());next.put("pendingTransfer",true);recordTransferState(next);
    }
    private void recordTransferState(JSONObject next)throws Exception{
        RhShared.write(file,next);
        JSONObject persisted=new JSONObject(new String(new AtomicFile(file).readFully(),StandardCharsets.UTF_8));
        if(!RhShared.canonical(next).equals(RhShared.canonical(persisted)))throw new IOException("同步任务状态未能保存，请检查存储后重试");
        local=next;
    }
    private void recordRejectedTransfer(int code,boolean previouslyPending)throws Exception{
        if(!previouslyPending&&Arrays.asList(400,401,403,404,405,409,412,413,415,423).contains(code)){
            JSONObject next=new JSONObject(local.toString());next.remove("pendingTransfer");recordTransferState(next);
        }
    }
    /** A confirmed PUT is not a local merge base until its exact configuration is durable. */
    void applied(JSONObject shared)throws Exception{
        JSONObject expected=awaitingApply,account=appliedAccount;
        if(expected==null||account==null)throw new IOException("没有等待应用的同步结果，请重新同步");
        if(!RhShared.canonical(expected).equals(RhShared.canonical(RhShared.normalize(shared)))||!RhShared.canonical(account).equals(RhShared.canonical(local)))throw new IOException("同步结果或账户已变化，请重新同步");
        if(!RhShared.canonical(expected).equals(RhShared.canonical(RhShared.read(context))))throw new IOException("本机配置尚未完整应用，请重新同步");
        if(!context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",expected.getBoolean("dark")).commit())throw new IOException("同步主题未能保存，请重试");
        JSONObject next=new JSONObject(account.toString());next.put("base",expected);next.put("lastSyncMs",System.currentTimeMillis());next.remove("pendingTransfer");RhShared.write(file,next);
        JSONObject persisted=new JSONObject(new String(new AtomicFile(file).readFully(),StandardCharsets.UTF_8));
        if(!RhShared.canonical(next).equals(RhShared.canonical(persisted)))throw new IOException("同步完成状态未能保存，请重试");
        local=next;clearApplied();
    }
    private static Map<String,JSONObject> devices(JSONObject config)throws JSONException{Map<String,JSONObject> map=new TreeMap<>();if(config!=null){JSONArray list=config.getJSONArray("devices");for(int i=0;i<list.length();i++){JSONObject d=list.getJSONObject(i);map.put(d.getString("uuid"),d);}}return map;}
    private static Object choose(Object left,Object right,Object base,String key,String resolution,JSONArray conflicts)throws JSONException{
        if(left==null)return right;if(right==null)return left;String l=RhShared.canonical(left),r=RhShared.canonical(right),b=base==null?null:RhShared.canonical(base);if(l.equals(r))return left;if(l.equals(b))return right;if(r.equals(b))return left;
        // Preserve edits relative to the base before treating a URL-only official upgrade as compatible.
        if(key.startsWith("profiles.")&&left instanceof JSONObject&&right instanceof JSONObject){JSONObject upgraded=RhProfile.compatibleRelayUpgrade((JSONObject)left,(JSONObject)right);if(upgraded!=null)return upgraded;}
        if(resolution.equals("review"))conflicts.put(key);return resolution.equals("remote")?right:left;
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
