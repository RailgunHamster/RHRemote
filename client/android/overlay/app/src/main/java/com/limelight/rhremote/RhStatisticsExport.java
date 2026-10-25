package com.limelight.rhremote;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;

/** Closed, app-owned telemetry only. Called on the authenticated HTTP worker, never the UI thread. */
final class RhStatisticsExport {
    static final class Export implements AutoCloseable {
        final FileInputStream input;final long length;final String sha256;
        Export(File file)throws Exception {
            input=new FileInputStream(file);length=input.getChannel().size();
            try{
                MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int count;
                while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);
                if(input.getChannel().size()!=length)throw new IOException("trace changed during export");
                StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
                sha256=hex.toString();input.getChannel().position(0);
            }catch(Exception e){input.close();throw e;}
        }
        public void close()throws IOException {input.close();}
    }
    private static File folder(File root,String run)throws IOException {
        if(run==null||!run.matches("[0-9]+-[a-f0-9-]{12}"))throw new IOException("invalid statistics run");
        File canonical=root.getCanonicalFile(),directory=new File(canonical,run).getCanonicalFile();
        if(!canonical.equals(directory.getParentFile())||!directory.isDirectory())throw new IOException("statistics run unavailable");
        return directory;
    }
    private static File owned(File directory,String name)throws IOException {
        File file=new File(directory,name).getCanonicalFile();
        if(!directory.equals(file.getParentFile())||!file.isFile())throw new IOException("statistics file unavailable");
        return file;
    }
    static Export open(File root,String run,String name)throws Exception {
        if(!"manifest.json".equals(name)&&!"native.jsonl".equals(name))throw new IOException("unsupported statistics file");
        File directory=folder(root,run),nativeFile=owned(directory,"native.jsonl");
        try(RandomAccessFile input=new RandomAccessFile(nativeFile,"r")){
            long length=input.length();if(length<1||length>513L*1024*1024)throw new IOException("invalid trace length");
            int size=(int)Math.min(length,2048);byte[] tail=new byte[size];input.seek(length-size);input.readFully(tail);
            String text=new String(tail,StandardCharsets.UTF_8);
            if(!text.endsWith("\n"))throw new IOException("statistics writer has not finished");
            String[] lines=text.trim().split("\n");JSONObject footer=new JSONObject(lines[lines.length-1]);
            if(!"trace_end".equals(footer.optString("event")))throw new IOException("statistics writer has not finished");
        }
        return new Export(owned(directory,name));
    }
    static void complete(File root,String run,String expectedSha256)throws Exception {
        if(expectedSha256==null||!expectedSha256.matches("[a-f0-9]{64}"))throw new IOException("invalid exported trace digest");
        try(Export exported=open(root,run,"native.jsonl")){
            if(!MessageDigest.isEqual(exported.sha256.getBytes(StandardCharsets.US_ASCII),expectedSha256.getBytes(StandardCharsets.US_ASCII)))throw new IOException("exported trace digest differs");
        }
        File directory=folder(root,run),marker=new File(directory,"analysis-complete.json");
        try(FileOutputStream output=new FileOutputStream(marker)){
            output.write(new JSONObject().put("schema","rhremote.statistics.cleanup/v1").put("nativeSha256",expectedSha256).put("analysisComplete",true).toString().getBytes(StandardCharsets.UTF_8));
        }
        // beginAt prunes acknowledged runs before the next recording. Unacknowledged evidence stays available.
    }
}
