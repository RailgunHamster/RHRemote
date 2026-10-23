package com.limelight.rhremote;

import org.json.JSONArray;
import org.json.JSONObject;

/** Per-connection state. Never saved to profiles or WebDAV. */
public final class RhRecoveryPolicy {
    private final JSONArray failed;
    private long burstStart, healthySince=-1;
    private int recoveries;
    public final int attempt;
    public long originUs;
    private volatile boolean rendered;
    public RhRecoveryPolicy(JSONObject saved,long nowUs) {
        failed=saved.optJSONArray("failedRoutes")==null?new JSONArray():saved.optJSONArray("failedRoutes");
        burstStart=saved.optLong("burstStartMs",-1);
        recoveries=saved.optInt("recoveries",0);
        attempt=Math.max(1,saved.optInt("attempt",1));
        originUs=saved.optLong("originUs",nowUs);
    }
    public synchronized void frame(long nowMs) {
        rendered=true;
        if(healthySince<0)healthySince=nowMs;
        if(nowMs-healthySince>=10_000){while(failed.length()>0)failed.remove(failed.length()-1);recoveries=0;burstStart=-1;}
    }
    public boolean rendered(){return rendered;}
    public synchronized JSONArray excluded(){try{return new JSONArray(failed.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    /** Returns the delay before retry, or -1 when the burst must stop. */
    public synchronized long failure(long nowMs,long nowUs,String route,String address,boolean eligible) {
        if(!eligible)return -1;
        healthySince=-1;
        if(burstStart<0)burstStart=nowMs;
        if(nowMs-burstStart>=60_000)return -1;
        if(rendered){
            if(recoveries>=3)return -1;
            if(recoveries==0)originUs=nowUs;
            return new long[]{500,1500,3000}[recoveries++];
        }
        if(route.isEmpty()||address.isEmpty()||failed.length()>=8||excluded(failed,route,address))return -1;
        failed.put(RhActions.obj("route",route,"address",address));
        return 0;
    }
    public synchronized JSONObject next(){return RhActions.obj("attempt",attempt+1,"originUs",originUs,"burstStartMs",burstStart,"recoveries",recoveries,"failedRoutes",excluded());}
    public synchronized JSONObject state(){return RhActions.obj("attempt",attempt,"originUs",originUs,"recoveries",recoveries,"firstFrame",rendered,"failedRoutes",excluded());}
    public static boolean excluded(JSONArray failed,String route,String address){
        if(failed!=null)for(int i=0;i<failed.length();i++){JSONObject entry=failed.optJSONObject(i);if(entry!=null&&route.equals(entry.optString("route"))&&address.equals(entry.optString("address")))return true;}
        return false;
    }
    public static boolean networkError(Throwable failure){
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof javax.net.ssl.SSLException||cause instanceof java.security.cert.CertificateException)return false;
        return failure instanceof java.io.IOException;
    }
    public static boolean networkStage(String stage,int portFlags,int code){
        // Decoder/audio setup returns negative values at establishment. Those are
        // local failures; positive socket errno values can try another route.
        return portFlags!=0||stage.equals("name resolution")||stage.equals("input stream establishment")
            ||code>0&&(stage.equals("video stream establishment")||stage.equals("audio stream establishment"));
    }
}
