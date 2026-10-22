package com.limelight.rhremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.util.AtomicFile;
import com.limelight.nvstream.http.ComputerDetails;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Portable schema v2: shared host facts and independently owned client × host profiles. */
public final class RhShared {
    private static File file(Context c) { return new File(c.getFilesDir(), "rhremote-shared.json"); }
    public static boolean dark(Context c) { return c.getSharedPreferences("rhremote.global",0).getBoolean("dark",(c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES); }
    public static void theme(Context c,boolean value) { c.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",value).apply(); }
    public static synchronized String clientId(Context c) {
        SharedPreferences prefs=c.getSharedPreferences("rhremote.global",0);
        String id=prefs.getString("clientId","");
        if(id.isEmpty()) { id="client-"+UUID.randomUUID().toString().replace("-",""); if(!prefs.edit().putString("clientId",id).commit())throw new IllegalStateException("client identity write failed"); }
        return id;
    }
    public static JSONObject clientInfo() { return RhActions.obj("name",Build.MANUFACTURER+" "+Build.MODEL,"platform","android"); }
    private static JSONObject empty(Context c) { return RhActions.obj("schemaVersion",2,"devices",new JSONArray(),"clients",RhActions.obj(clientId(c),clientInfo()),"profiles",new JSONObject(),"dark",dark(c)); }
    private static JSONObject rawRead(Context c)throws Exception {
        AtomicFile atomic=new AtomicFile(file(c));
        if(!atomic.getBaseFile().exists()&&!new File(file(c)+".bak").exists())return empty(c);
        return new JSONObject(new String(atomic.readFully(),StandardCharsets.UTF_8));
    }
    public static synchronized void initialize(Context c)throws Exception {
        SharedPreferences prefs=c.getSharedPreferences("rhremote.global",0);
        String client=clientId(c);
        if(prefs.getInt("profileSchemaVersion",0)>=2)return;
        JSONObject old=rawRead(c), shared=normalize(old);
        File before=new File(c.getFilesDir(),"rhremote-shared.pre-client-profiles.json");
        if(file(c).exists()&&!before.exists())write(before,old);
        JSONObject group=shared.getJSONObject("profiles").optJSONObject(client);boolean existingGroup=group!=null;
        if(group==null)group=new JSONObject();Set<String> ids=new TreeSet<>();
        SharedPreferences local=c.getSharedPreferences("rhremote.devices",0);
        if(!existingGroup)for(String key:local.getAll().keySet())if(key.endsWith(".width"))ids.add(key.substring(0,key.length()-6));
        if(old.optInt("schemaVersion")==1)old.getJSONObject("profiles").keys().forEachRemaining(ids::add);
        for(String id:ids) {
            RhProfile p=local.contains(id+".width")?RhProfile.loadLocal(c,id):RhProfile.fromJson(id,old.getJSONObject("profiles").getJSONObject(id));
            group.put(id,p.clientJson());
            JSONArray devices=shared.getJSONArray("devices");
            for(int i=0;i<devices.length();i++)if(id.equals(devices.getJSONObject(i).getString("uuid")))devices.getJSONObject(i).put("swapButtons",p.swapButtons);
        }
        shared.getJSONObject("clients").put(client,clientInfo());
        shared.getJSONObject("profiles").put(client,group);
        validate(shared);write(file(c),shared);
        if(!prefs.edit().putInt("profileSchemaVersion",2).commit())throw new IOException("profile migration write failed");
    }
    public static synchronized JSONObject read(Context c) {
        try { initialize(c);initializeOrder(c); return normalize(rawRead(c)); }
        catch(Exception e) { throw new IllegalStateException("连接配置读取失败",e); }
    }
    private static void initializeOrder(Context c)throws Exception {
        SharedPreferences prefs=c.getSharedPreferences("rhremote.global",0);if(prefs.getBoolean("deviceOrderInitialized",false))return;
        JSONObject shared=normalize(rawRead(c));JSONArray saved=shared.getJSONArray("devices");List<JSONObject> rows=new ArrayList<>();for(int i=0;i<saved.length();i++)rows.add(saved.getJSONObject(i));
        rows.sort(Comparator.comparing((JSONObject d)->!d.optString("name").equalsIgnoreCase("winserver")).thenComparing(d->d.optString("name").toLowerCase(Locale.ROOT)));
        JSONArray ordered=new JSONArray();for(JSONObject row:rows)ordered.put(row);shared.put("devices",ordered);write(file(c),shared);
        if(!prefs.edit().putBoolean("deviceOrderInitialized",true).commit())throw new IOException("device order migration write failed");
    }
    public static void write(File file,JSONObject value)throws IOException {
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=atomic.startWrite();
        try { out.write(value.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out); }
        catch(Exception e) { atomic.failWrite(out);throw new IOException("configuration write failed",e); }
    }
    /** Old remote tuning has no reliable client owner. Preserve it as an explicit copy source. */
    public static JSONObject normalize(JSONObject value)throws Exception {
        JSONObject next=new JSONObject(value.toString());
        if(next.getInt("schemaVersion")==1) {
            only(next,"schemaVersion","devices","profiles","dark");
            JSONObject old=next.getJSONObject("profiles"),group=new JSONObject();
            if(old.length()>1000)throw new IOException("configuration too large");
            for(Iterator<String> it=old.keys();it.hasNext();) { String id=it.next();only(old.getJSONObject(id),RhProfile.KEYS);group.put(id,RhProfile.fromJson(id,old.getJSONObject(id)).clientJson()); }
            JSONArray devices=next.getJSONArray("devices");
            for(int i=0;i<devices.length();i++) { JSONObject d=devices.getJSONObject(i);only(d,"uuid","name","address","swapButtons","displayName","addresses");JSONObject p=old.optJSONObject(d.getString("uuid"));d.put("swapButtons",p!=null?p.getBoolean("swapButtons"):d.optBoolean("swapButtons")); }
            next.put("schemaVersion",2);next.put("clients",RhActions.obj("legacy-v1",RhActions.obj("name","旧版配置","platform","legacy")));next.put("profiles",RhActions.obj("legacy-v1",group));
        }
        JSONArray hosts=next.getJSONArray("devices");
        for(int i=0;i<hosts.length();i++){JSONObject d=hosts.getJSONObject(i);if(!d.has("displayName"))d.put("displayName","");if(!d.has("swapButtons"))d.put("swapButtons",false);}
        validate(next);JSONObject groups=next.getJSONObject("profiles");for(Iterator<String> it=groups.keys();it.hasNext();){JSONObject group=groups.getJSONObject(it.next());for(Iterator<String> hostsIt=group.keys();hostsIt.hasNext();){String id=hostsIt.next();group.put(id,RhProfile.fromClientJson(id,group.getJSONObject(id)).clientJson());}}return next;
    }
    private static boolean validId(String id) { return !id.isEmpty()&&id.getBytes(StandardCharsets.UTF_8).length<=128&&id.codePoints().noneMatch(Character::isISOControl); }
    public static void validate(JSONObject value)throws Exception {
        only(value,"schemaVersion","devices","clients","profiles","dark");
        if(value.getInt("schemaVersion")!=2)throw new IOException("unsupported sync schema");
        JSONArray devices=value.getJSONArray("devices");JSONObject clients=value.getJSONObject("clients"),profiles=value.getJSONObject("profiles");
        if(devices.length()>1000||clients.length()>100||profiles.length()>100)throw new IOException("configuration too large");Set<String> ids=new HashSet<>();
        for(int i=0;i<devices.length();i++) {
            JSONObject d=devices.getJSONObject(i);only(d,"uuid","name","address","swapButtons","displayName","addresses");String id=d.getString("uuid"),address=d.getString("address");
            validateDisplayName(d.optString("displayName"));RhNetwork.addresses(d);
            if(!validId(id)||!ids.add(id.toLowerCase(Locale.ROOT))||d.getString("name").getBytes(StandardCharsets.UTF_8).length>256||address.isEmpty()||address.length()>253||address.matches(".*[/\\\\@\\r\\n?#].*"))throw new IOException("invalid device metadata");
            if(d.has("swapButtons"))d.getBoolean("swapButtons");
        }
        for(Iterator<String> it=clients.keys();it.hasNext();) {
            String id=it.next();JSONObject client=clients.getJSONObject(id);only(client,"name","platform");String name=client.getString("name");
            if(!validId(id)||name.isEmpty()||name.getBytes(StandardCharsets.UTF_8).length>256||!Arrays.asList("windows","android","legacy").contains(client.getString("platform")))throw new IOException("invalid client metadata");
        }
        int count=0;
        for(Iterator<String> it=profiles.keys();it.hasNext();) {
            String client=it.next();if(!clients.has(client))throw new IOException("profile client is missing");JSONObject group=profiles.getJSONObject(client);count+=group.length();if(count>10000)throw new IOException("configuration too large");
            for(Iterator<String> hosts=group.keys();hosts.hasNext();) { String id=hosts.next();if(!validId(id))throw new IOException("invalid profile UUID");JSONObject p=group.getJSONObject(id);if(p.has("swapButtons"))throw new IOException("host facts do not belong in client profiles");only(p,RhProfile.KEYS);RhProfile.fromClientJson(id,p); }
        }
        value.getBoolean("dark");
    }
    private static void only(JSONObject value,String... allowed)throws IOException { Set<String> keys=new HashSet<>(Arrays.asList(allowed));Iterator<String> it=value.keys();while(it.hasNext())if(!keys.contains(it.next()))throw new IOException("unsupported configuration field"); }
    public static boolean swap(JSONObject shared,String uuid) {
        JSONArray list=shared.optJSONArray("devices");if(list==null)return false;
        for(int i=0;i<list.length();i++){JSONObject d=list.optJSONObject(i);if(d!=null&&uuid.equals(d.optString("uuid")))return d.optBoolean("swapButtons");}return false;
    }
    public static String validateDisplayName(String value)throws IOException {String name=value.trim();if(name.getBytes(StandardCharsets.UTF_8).length>256||name.codePoints().anyMatch(Character::isISOControl))throw new IOException("别名不能超过 256 字节，且不能包含控制字符");return name;}
    public static String alias(Context c,String uuid) {JSONArray list=read(c).optJSONArray("devices");for(int i=0;i<list.length();i++){JSONObject d=list.optJSONObject(i);if(d!=null&&uuid.equals(d.optString("uuid")))return d.optString("displayName");}return "";}
    public static String displayName(Context c,String uuid,String hostname) {String alias=alias(c,uuid);return alias.isEmpty()?(hostname==null?"电脑":hostname):alias;}
    private static JSONObject metadata(ComputerDetails d,boolean swap,String displayName) {
        ComputerDetails.AddressTuple a=d.manualAddress!=null?d.manualAddress:d.localAddress!=null?d.localAddress:d.activeAddress;
        if(d.uuid==null||a==null||a.port!=47989)return null;
        return RhActions.obj("uuid",d.uuid,"name",d.name==null?d.uuid:d.name,"address",a.address.contains(":")&&!a.address.startsWith("[")?"["+a.address+"]":a.address,"swapButtons",swap,"displayName",displayName);
    }
    public static synchronized JSONObject capture(Context c,Collection<ComputerDetails> computers)throws Exception {
        JSONObject shared=read(c);LinkedHashMap<String,JSONObject> devices=new LinkedHashMap<>();JSONArray old=shared.getJSONArray("devices");
        for(int i=0;i<old.length();i++){JSONObject d=old.getJSONObject(i);devices.put(d.getString("uuid"),d);}
        for(ComputerDetails d:computers){JSONObject previous=devices.get(d.uuid);JSONObject item=metadata(d,swap(shared,d.uuid),previous==null?"":previous.optString("displayName"));if(item!=null){if(previous!=null){item.put("address",previous.getString("address"));if(previous.has("addresses"))item.put("addresses",previous.getJSONArray("addresses"));}devices.put(d.uuid,item);}}
        JSONArray list=new JSONArray();JSONObject group=new JSONObject();
        JSONObject existing=shared.getJSONObject("profiles").optJSONObject(clientId(c));if(existing!=null)group=new JSONObject(existing.toString());
        for(Map.Entry<String,JSONObject> d:devices.entrySet()){list.put(d.getValue());group.put(d.getKey(),RhProfile.load(c,d.getKey()).clientJson());}
        shared.put("devices",list);shared.getJSONObject("clients").put(clientId(c),clientInfo());shared.getJSONObject("profiles").put(clientId(c),group);shared.put("dark",dark(c));validate(shared);write(file(c),shared);return shared;
    }
    public static synchronized void saveAddresses(Context c,String uuid,String primary,JSONArray extra)throws Exception {
        JSONObject shared=read(c);JSONArray devices=shared.getJSONArray("devices");boolean found=false;
        JSONObject metadata=RhActions.obj("address",primary,"addresses",extra);List<String> all=RhNetwork.addresses(metadata);JSONArray normalized=new JSONArray();for(String address:all)if(!address.equalsIgnoreCase(primary))normalized.put(address);
        for(int i=0;i<devices.length();i++)if(uuid.equals(devices.getJSONObject(i).getString("uuid"))){devices.getJSONObject(i).put("address",primary).put("addresses",normalized);found=true;}
        if(!found)throw new IOException("未找到设备");validate(shared);write(file(c),shared);
    }
    public static synchronized void saveProfile(Context c,ComputerDetails d,RhProfile p,String displayName)throws Exception {
        displayName=validateDisplayName(displayName);
        JSONObject shared=capture(c,Collections.singletonList(d));
        JSONArray devices=shared.getJSONArray("devices");boolean found=false;
        for(int i=0;i<devices.length();i++)if(d.uuid.equals(devices.getJSONObject(i).getString("uuid"))){devices.getJSONObject(i).put("swapButtons",p.swapButtons).put("displayName",displayName);found=true;}
        if(!found)throw new IOException("host address unavailable");
        shared.getJSONObject("profiles").getJSONObject(clientId(c)).put(d.uuid,p.clientJson());validate(shared);write(file(c),shared);
    }
    public static List<ComputerDetails> ordered(Context c,Collection<ComputerDetails> computers) {
        JSONArray saved=read(c).optJSONArray("devices");Map<String,Integer> rank=new HashMap<>();
        for(int i=0;i<saved.length();i++)rank.put(saved.optJSONObject(i).optString("uuid"),i);
        List<ComputerDetails> rows=new ArrayList<>(computers);rows.sort(Comparator.comparingInt(d->rank.getOrDefault(d.uuid,Integer.MAX_VALUE)));return rows;
    }
    public static synchronized void move(Context c,Collection<ComputerDetails> computers,String uuid,String direction)throws Exception {
        if(!Arrays.asList("up","down").contains(direction))throw new IllegalArgumentException("direction must be up or down");
        List<ComputerDetails> rows=ordered(c,computers);int index=-1;for(int i=0;i<rows.size();i++)if(uuid.equals(rows.get(i).uuid))index=i;
        if(index<0)throw new IllegalArgumentException("unknown device");
        int target=direction.equals("up")?Math.max(0,index-1):Math.min(rows.size()-1,index+1);
        JSONObject shared=capture(c,rows);JSONArray list=shared.getJSONArray("devices");int a=-1,b=-1;
        for(int i=0;i<list.length();i++){String id=list.getJSONObject(i).getString("uuid");if(id.equals(uuid))a=i;if(id.equals(rows.get(target).uuid))b=i;}
        if(a<0||b<0)throw new IOException("host address unavailable");Object before=list.get(a);list.put(a,list.get(b));list.put(b,before);validate(shared);write(file(c),shared);
    }
    public static synchronized void apply(Context c,JSONObject shared)throws Exception {
        shared=normalize(shared);File backups=new File(c.getFilesDir(),"sync-backups");if(!backups.exists()&&!backups.mkdirs())throw new IOException("backup directory unavailable");
        write(new File(backups,"local-"+System.currentTimeMillis()+".json"),read(c));File[] files=backups.listFiles((d,n)->n.startsWith("local-"));
        if(files!=null){Arrays.sort(files,Comparator.comparing(File::getName));for(int i=0;i<files.length-20;i++)files[i].delete();}
        // No foreign group is applied to this client's active preferences.
        write(file(c),shared);theme(c,shared.getBoolean("dark"));
    }
    public static String canonical(Object value)throws JSONException {
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;TreeMap<String,String> keys=new TreeMap<>();for(Iterator<String> it=o.keys();it.hasNext();){String k=it.next();keys.put(k,canonical(o.get(k)));}StringBuilder out=new StringBuilder("{");for(Map.Entry<String,String> e:keys.entrySet())out.append(JSONObject.quote(e.getKey())).append(':').append(e.getValue()).append(',');return out.append('}').toString();}
        if(value instanceof JSONArray){StringBuilder out=new StringBuilder("[");JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)out.append(canonical(a.get(i))).append(',');return out.append(']').toString();}
        if(value==null||value==JSONObject.NULL)return "null";if(value instanceof String)return JSONObject.quote((String)value);if(value instanceof Boolean||value instanceof Number)return String.valueOf(value);throw new JSONException("unsupported value");
    }
}
