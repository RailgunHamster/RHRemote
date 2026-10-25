package com.limelight.rhremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Same-UID installer input diagnostic. Never confirms installation or clears app data. */
public final class RhInstallerProbeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try{
            Context c=getTargetContext();File directory=new File(c.getFilesDir(),"updates");
            if(arguments!=null&&"true".equals(arguments.getString("cleanupOnly"))){
                String hash=arguments.getString("sha256","");long version=Long.parseLong(arguments.getString("versionCode","0"));
                if(!hash.matches("[0-9a-f]{64}")||version<1||version>c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode())throw new IllegalArgumentException("Cleanup identity differs");
                File owned=new File(directory,"RHRemote-"+version+"-"+hash.substring(0,16)+".apk");
                if(owned.isFile()){
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(owned)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
                    StringBuilder actual=new StringBuilder();for(byte b:digest.digest())actual.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                    if(!hash.equals(actual.toString()))throw new IllegalStateException("Cleanup content differs");Files.delete(owned.toPath());
                }
                result.putString("stream","INSTALLER_DIAGNOSTIC_SNAPSHOT_REMOVED\n");finish(code,result);return;
            }
            File original=new File(directory,"RHRemote-update.apk");
            JSONObject expected=new JSONObject(new String(Files.readAllBytes(new File(directory,"ready.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(FileInputStream in=new FileInputStream(original)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if(!hex.toString().equalsIgnoreCase(expected.getString("sha256")))throw new IllegalStateException("Cached APK hash differs");
            PackageInfo incoming=c.getPackageManager().getPackageArchiveInfo(original.getAbsolutePath(),0);
            PackageInfo installed=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
            if(incoming==null||incoming.getLongVersionCode()!=expected.getLong("versionCode")||incoming.getLongVersionCode()<=installed.getLongVersionCode())throw new IllegalStateException("Incoming APK is not a newer version");
            File unique=new File(directory,"RHRemote-"+incoming.getLongVersionCode()+"-"+hex.substring(0,16)+".apk");
            Files.copy(original.toPath(),unique.toPath(),StandardCopyOption.REPLACE_EXISTING);
            Uri uri=Uri.parse("content://"+c.getPackageName()+".updates/updates/"+unique.getName());
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setClipData(ClipData.newRawUri("RHRemote update",uri));
            c.startActivity(intent);Thread.sleep(2500);
            result.putString("stream","INSTALLER_INPUT_PROOF "+new JSONObject().put("installedCode",installed.getLongVersionCode()).put("incomingCode",incoming.getLongVersionCode()).put("incomingName",incoming.versionName).put("sha256",hex.toString()).put("uri",uri.toString())+"\n");
        }catch(Exception error){code=Activity.RESULT_CANCELED;result.putString("stream","INSTALLER_INPUT_ERROR "+error+"\n");}
        finish(code,result);
    }
}
