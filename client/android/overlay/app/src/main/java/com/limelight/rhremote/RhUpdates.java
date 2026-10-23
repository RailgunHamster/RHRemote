package com.limelight.rhremote;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Public release metadata; user initiated installation opens Android's installer. */
public final class RhUpdates {
    private static final String GITEE="https://gitee.com/railgunhamster/RHRemote/releases/download/",GITHUB="https://github.com/RailgunHamster/RHRemote/releases/";
    private final Context context;
    private final File directory,apk,receipt;
    private final SharedPreferences preferences;
    private volatile JSONObject available;
    private volatile String message="尚未检查更新",phase="idle",provider="";
    private volatile boolean ready,prepared;
    private volatile long downloadedBytes,totalBytes;
    public RhUpdates(Context c){
        context=c.getApplicationContext();directory=new File(c.getFilesDir(),"updates");
        apk=new File(directory,"RHRemote-update.apk");receipt=new File(directory,"ready.json");
        preferences=context.getSharedPreferences("rhremote.updates",0);restore();
    }
    private long currentCode()throws Exception{PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),0);return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    private String currentName(){try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception e){return "";}}
    private boolean allowed(){return Build.VERSION.SDK_INT<26||context.getPackageManager().canRequestPackageInstalls();}
    public JSONObject state(){JSONObject candidate=available;return RhActions.obj("managed",true,"currentVersion",currentName(),"availableVersion",candidate==null?JSONObject.NULL:candidate.optString("version"),
        "pendingRestart",ready,"downloaded",ready,"canInstall",candidate!=null,"status",message,"phase",phase,"provider",provider,
        "downloadedBytes",downloadedBytes,"totalBytes",totalBytes,"installPermissionGranted",allowed(),"installPermissionPending",preferences.getBoolean("permission",false));}
    private void status(String phase,String text){this.phase=phase;message=text;}
    public void failure(String text){status("error",text==null||text.isEmpty()?"更新操作失败，请重试":text);}
    private static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("更新信息过大");out.write(b,0,n);}return out.toByteArray();}
    private static HttpURLConnection open(String url)throws IOException{if(!url.startsWith("https://"))throw new IOException("HTTPS required");HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(20000);return c;}
    private static void name(String value)throws IOException{if(value.isEmpty()||value.equals(".")||value.equals("..")||!value.matches("[A-Za-z0-9._-]+"))throw new IOException("更新文件名无效");}
    private void validate(JSONObject candidate)throws Exception{
        for(String key:new String[]{"version","fileName","tag"})name(candidate.getString(key));
        long code=candidate.getLong("versionCode"),size=candidate.getLong("size");
        if(!candidate.getString("packageName").equals(context.getPackageName())||!candidate.getString("tag").equals("v"+candidate.getString("version"))||
            !candidate.getString("sha256").matches("(?i)[0-9a-f]{64}")||code<1||code>2100000000||size<1||size>256L*1024*1024)throw new IOException("更新信息无效");
    }
    private void restore(){
        if(!receipt.isFile())return;
        try(InputStream in=new FileInputStream(receipt)){
            JSONObject saved=new JSONObject(new String(read(in,32768),StandardCharsets.UTF_8));validate(saved);
            if(saved.getLong("versionCode")<=currentCode()){receipt.delete();apk.delete();preferences.edit().clear().apply();return;}
            if(apk.isFile()&&apk.length()==saved.getLong("size")){available=saved;ready=true;downloadedBytes=totalBytes=apk.length();status("downloaded","APK 已下载，安装前将重新校验");}
        }catch(Exception invalid){receipt.delete();}
    }
    private void remember()throws Exception{
        if(!directory.exists()&&!directory.mkdirs())throw new IOException("更新目录不可用");
        File temporary=new File(directory,"ready.json.tmp");
        try(OutputStream out=new FileOutputStream(temporary)){out.write(available.toString().getBytes(StandardCharsets.UTF_8));}
        java.nio.file.Files.move(temporary.toPath(),receipt.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    public void check()throws Exception{
        status("checking","正在检查更新");JSONObject manifest=null;
        for(String url:new String[]{GITEE+"latest/releases.android.json",GITHUB+"latest/download/releases.android.json"}){
            HttpURLConnection c=open(url);try{
                if(c.getResponseCode()!=200)continue;
                try(InputStream in=c.getInputStream()){
                    JSONObject feed=new JSONObject(new String(read(in,2*1024*1024),StandardCharsets.UTF_8));
                    if(feed.getInt("schemaVersion")!=1)continue;JSONObject candidate=feed.getJSONObject("android");validate(candidate);manifest=candidate;provider=url.startsWith(GITEE)?"Gitee":"GitHub";break;
                }
            }catch(Exception ignored){}finally{c.disconnect();}
        }
        if(manifest==null){failure("GitHub 与 Gitee 暂无可用更新信息");throw new IOException(message);}
        available=manifest.getLong("versionCode")>currentCode()?manifest:null;prepared=false;ready=false;
        if(available==null){receipt.delete();apk.delete();preferences.edit().clear().apply();status("current","已是最新版本");return;}
        totalBytes=available.getLong("size");
        if(apk.isFile()&&apk.length()==totalBytes)try{verifyFile(apk,available);remember();ready=true;downloadedBytes=totalBytes;}catch(Exception invalid){receipt.delete();}
        status(ready?"downloaded":"available",ready?"APK 已下载，可安装更新":"发现新版本");
    }
    private Signature[] signatures(PackageInfo p)throws IOException{
        Signature[] values=Build.VERSION.SDK_INT>=28?(p.signingInfo==null?null:p.signingInfo.getApkContentsSigners()):p.signatures;
        if(values==null||values.length==0)throw new IOException("无法读取 APK 签名");return values;
    }
    private void verifyPackage(File file,JSONObject expected)throws Exception{
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageManager manager=context.getPackageManager();PackageInfo incoming=manager.getPackageArchiveInfo(file.getAbsolutePath(),flags),installed=manager.getPackageInfo(context.getPackageName(),flags);
        if(incoming==null||!context.getPackageName().equals(incoming.packageName)||(Build.VERSION.SDK_INT>=28?incoming.getLongVersionCode():incoming.versionCode)!=expected.getLong("versionCode"))throw new IOException("APK 包名或版本不符");
        Set<String> ours=new HashSet<>(),theirs=new HashSet<>();for(Signature s:signatures(installed))ours.add(s.toCharsString());for(Signature s:signatures(incoming))theirs.add(s.toCharsString());if(!ours.equals(theirs))throw new IOException("APK 签名与当前应用不一致");
    }
    private static String digest(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    private void verifyFile(File file,JSONObject expected)throws Exception{
        if(!file.isFile()||file.length()!=expected.getLong("size"))throw new IOException("APK 文件不完整，请重新下载");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}
        if(!digest(hash.digest()).equalsIgnoreCase(expected.getString("sha256")))throw new IOException("APK 校验失败，请重新下载");verifyPackage(file,expected);
    }
    public void download()throws Exception{
        JSONObject selected=available;if(selected==null)throw new IOException("请先检查更新");
        if(!directory.exists()&&!directory.mkdirs())throw new IOException("更新目录不可用");
        ready=false;prepared=false;downloadedBytes=0;totalBytes=selected.getLong("size");status("downloading","正在下载 APK（0%）");
        File pending=new File(directory,"pending.apk");boolean downloaded=false;String lastError="APK 下载失败，请检查网络后重试";
        for(String url:new String[]{GITEE+selected.getString("tag")+"/"+selected.getString("fileName"),GITHUB+"download/"+selected.getString("tag")+"/"+selected.getString("fileName")}){
            HttpURLConnection c=open(url);try{
                provider=url.startsWith(GITEE)?"Gitee":"GitHub";downloadedBytes=0;
                if(c.getResponseCode()!=200){lastError="更新源返回 HTTP "+c.getResponseCode();continue;}
                long count=0,expected=selected.getLong("size");
                try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(pending)){
                    byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){count+=n;if(count>expected)throw new IOException("APK 长度不符");out.write(b,0,n);downloadedBytes=count;status("downloading","正在下载 APK（"+(count*100/expected)+"%）");}
                }
                status("verifying","正在校验 APK");verifyFile(pending,selected);
                java.nio.file.Files.move(pending.toPath(),apk.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);remember();downloaded=true;break;
            }catch(Exception e){lastError=e.getMessage()==null?"APK 下载或校验失败":e.getMessage();}finally{c.disconnect();pending.delete();}
        }
        if(!downloaded){failure(lastError);throw new IOException(message);}ready=true;status("downloaded","APK 已下载，可安装更新");
    }
    /** Worker thread only: the primary install action downloads when necessary. */
    public boolean prepareInstall()throws Exception{
        prepared=false;if(available==null)check();if(available==null)return false;
        if(!ready)download();status("verifying","正在校验安装包");
        try{verifyFile(apk,available);}catch(Exception invalid){ready=false;receipt.delete();failure(invalid.getMessage());throw invalid;}
        prepared=true;status("ready","安装包校验完成");return true;
    }
    /** UI thread: no APK parsing or hashing. Android still requires manual confirmation. */
    public void install(Activity activity)throws Exception{
        if(!prepared||!ready||available==null)throw new IOException("请先下载并校验更新");prepared=false;
        if(RhActions.hasStream())throw new IOException("请先断开当前连接再安装更新");
        if(!allowed()){
            preferences.edit().putBoolean("permission",true).apply();status("permission","请允许 RHRemote 安装应用，返回后继续安装");
            try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.getPackageName())));}
            catch(ActivityNotFoundException|SecurityException blocked){preferences.edit().remove("permission").apply();throw new IOException("无法打开安装权限设置，请在系统设置中允许 RHRemote 安装应用",blocked);}return;
        }
        Uri uri=FileProvider.getUriForFile(context,context.getPackageName()+".updates",apk);
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("RHRemote update",uri));
        try{activity.startActivity(intent);preferences.edit().putBoolean("installer",true).apply();status("installer","已打开系统安装界面，请手动确认");}
        catch(ActivityNotFoundException|SecurityException blocked){throw new IOException("系统安装界面未能打开，请在浏览器下载 APK 后安装",blocked);}
    }
    /** Called after returning from Android settings or the package installer. */
    public boolean resumeInstallRequest(){
        if(preferences.getBoolean("permission",false)){
            preferences.edit().remove("permission").apply();
            if(allowed()){status("verifying","正在继续安装更新");return true;}
            status("downloaded","尚未允许 RHRemote 安装应用，可点击安装更新重试");
        }else if(preferences.getBoolean("installer",false)){
            preferences.edit().remove("installer").apply();status("downloaded","安装尚未完成，可点击安装更新重试");
        }
        return false;
    }
}
