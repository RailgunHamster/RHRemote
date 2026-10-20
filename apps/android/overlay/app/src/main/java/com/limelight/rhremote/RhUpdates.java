package com.limelight.rhremote;
import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Public release metadata; publishing tokens are never bundled in the client. */
public final class RhUpdates {
    private static final String GITEE="https://gitee.com/railgunhamster/RHRemote/releases/download/",GITHUB="https://github.com/RailgunHamster/RHRemote/releases/";
    private final Context context;private final File directory;private volatile JSONObject available;private volatile String message="";private volatile boolean ready;
    public RhUpdates(Context c){context=c.getApplicationContext();directory=new File(c.getFilesDir(),"updates");}
    private long currentCode()throws Exception{PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),0);return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    public JSONObject state(){return RhActions.obj("managed",true,"currentVersion",currentName(),"availableVersion",available==null?JSONObject.NULL:available.optString("version"),"pendingRestart",ready,"status",message);}
    private String currentName(){try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception e){return "";}}
    private static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("update metadata too large");out.write(b,0,n);}return out.toByteArray();}
    private static HttpURLConnection open(String url)throws IOException{if(!url.startsWith("https://"))throw new IOException("HTTPS required");HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(20000);return c;}
    private static void name(String value)throws IOException{if(value.isEmpty()||value.equals(".")||value.equals("..")||!value.matches("[A-Za-z0-9._-]+"))throw new IOException("invalid update filename");}
    public void check()throws Exception{
        JSONObject manifest=null;for(String url:new String[]{GITEE+"latest/releases.android.json",GITHUB+"latest/download/releases.android.json"}){HttpURLConnection c=open(url);try{if(c.getResponseCode()!=200)continue;try(InputStream in=c.getInputStream()){JSONObject feed=new JSONObject(new String(read(in,2*1024*1024),StandardCharsets.UTF_8));if(feed.getInt("schemaVersion")!=1)continue;JSONObject candidate=feed.getJSONObject("android");name(candidate.getString("fileName"));name(candidate.getString("tag"));if(!candidate.getString("packageName").equals(context.getPackageName())||!candidate.getString("sha256").matches("(?i)[0-9a-f]{64}")||candidate.getLong("size")<1||candidate.getLong("size")>256L*1024*1024)continue;manifest=candidate;break;}}catch(Exception ignored){}finally{c.disconnect();}}
        if(manifest==null)throw new IOException("GitHub 与 Gitee 暂无可用更新信息");available=manifest.getLong("versionCode")>currentCode()?manifest:null;ready=false;message=available==null?"已是最新版本":"发现新版本";
    }
    private Signature[] signatures(PackageInfo p){return Build.VERSION.SDK_INT>=28?p.signingInfo.getApkContentsSigners():p.signatures;}
    private void verify(File apk,JSONObject expected)throws Exception{
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;PackageManager manager=context.getPackageManager();PackageInfo incoming=manager.getPackageArchiveInfo(apk.getAbsolutePath(),flags),installed=manager.getPackageInfo(context.getPackageName(),flags);
        if(incoming==null||!context.getPackageName().equals(incoming.packageName)||(Build.VERSION.SDK_INT>=28?incoming.getLongVersionCode():incoming.versionCode)!=expected.getLong("versionCode"))throw new IOException("APK 包名或版本不符");
        Set<String> ours=new HashSet<>(),theirs=new HashSet<>();for(Signature s:signatures(installed))ours.add(s.toCharsString());for(Signature s:signatures(incoming))theirs.add(s.toCharsString());if(!ours.equals(theirs))throw new IOException("APK 签名与当前应用不一致");
    }
    public void download()throws Exception{
        JSONObject selected=available;if(selected==null)throw new IOException("请先检查更新");if(!directory.exists()&&!directory.mkdirs())throw new IOException("更新目录不可用");File pending=new File(directory,"pending.apk"),apk=new File(directory,"RHRemote-update.apk");boolean downloaded=false;
        for(String url:new String[]{GITEE+selected.getString("tag")+"/"+selected.getString("fileName"),GITHUB+"download/"+selected.getString("tag")+"/"+selected.getString("fileName")}){
            HttpURLConnection c=open(url);try{if(c.getResponseCode()!=200)continue;long count=0,expected=selected.getLong("size");MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(pending)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){count+=n;if(count>expected)throw new IOException("APK 长度不符");hash.update(b,0,n);out.write(b,0,n);}}StringBuilder digest=new StringBuilder();for(byte b:hash.digest())digest.append(String.format(Locale.ROOT,"%02x",b&255));if(count!=expected||!digest.toString().equalsIgnoreCase(selected.getString("sha256")))continue;verify(pending,selected);java.nio.file.Files.move(pending.toPath(),apk.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);downloaded=true;break;
            }catch(Exception ignored){}finally{c.disconnect();pending.delete();}}
        if(!downloaded)throw new IOException("APK 下载或校验失败");ready=true;message="APK 已下载，点击安装并在系统界面确认";
    }
    public void install(Activity activity)throws Exception{if(!ready||available==null)throw new IOException("尚未下载更新");File apk=new File(directory,"RHRemote-update.apk");verify(apk,available);Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context,context.getPackageName()+".updates",apk),"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);activity.startActivity(intent);}
}
