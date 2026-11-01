package com.limelight.rhremote;

import android.content.Context;
import com.limelight.nvstream.http.ComputerDetails;
import java.net.*;
import java.util.*;
import javax.net.ssl.SSLException;
import org.json.*;

/** One discovery cycle, with immutable configuration and no persisted pairing changes. */
public final class RhDiscoveryFailures {
    private static final class Attempt {
        final ComputerDetails.AddressTuple address;final long began=System.nanoTime();JSONObject report;
        Attempt(ComputerDetails.AddressTuple address){this.address=address;}
    }
    private final ComputerDetails source;
    private final String intent;
    private final long began=System.nanoTime();
    private final LinkedHashMap<String,Attempt> attempts=new LinkedHashMap<>();
    private RhDiscoveryFailures(Context context,ComputerDetails target){source=new ComputerDetails(target);intent=RhDiscoveryObservation.begin(context,source);}
    public static RhDiscoveryFailures begin(Context context,ComputerDetails source){return source.uuid==null||source.uuid.isEmpty()?null:new RhDiscoveryFailures(context,source);}
    public ComputerDetails source(){return new ComputerDetails(source);}
    public String intent(){return intent;}
    private static String key(ComputerDetails.AddressTuple address){return address.address+"\u0000"+address.port;}
    public synchronized void register(ComputerDetails.AddressTuple address){attempts.putIfAbsent(key(address),new Attempt(address));}
    public void failed(ComputerDetails.AddressTuple address,Throwable failure){
        LinkedHashSet<String> reasons=new LinkedHashSet<>();reasons.add(reason(failure));
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());collectSuppressed(failure,reasons,seen);
        failed(address,reasons,"");
    }
    public void failed(ComputerDetails.AddressTuple address,String reason,String actualUuid){failed(address,new LinkedHashSet<>(Collections.singleton(reason)),actualUuid);}
    private synchronized void failed(ComputerDetails.AddressTuple address,LinkedHashSet<String> reasons,String actualUuid){
        if(Thread.currentThread().isInterrupted())return;
        Attempt attempt=attempts.get(key(address));if(attempt==null||attempt.report!=null)return;
        JSONObject report=RhDeviceCheck.report(source.uuid,actualUuid,"discovery",source.serverCert!=null,"unavailable",attempt.began);
        try{report.put("address",address.address);report.put("port",address.port);report.put("reason",reasons.iterator().next());report.put("reasons",new JSONArray(reasons));}
        catch(JSONException impossible){throw new IllegalStateException(impossible);}attempt.report=report;
    }
    private static void collectSuppressed(Throwable failure,Set<String> reasons,Set<Throwable> seen){
        if(failure==null||seen.size()>=16||!seen.add(failure))return;
        for(Throwable suppressed:failure.getSuppressed()){reasons.add(reason(suppressed));collectSuppressed(suppressed,reasons,seen);}
        collectSuppressed(failure.getCause(),reasons,seen);
    }
    private static String reason(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable cause=failure;cause!=null&&seen.add(cause);cause=cause.getCause())if(cause instanceof RhDeviceCheck.PinnedCertificateMismatch)return "certificate_mismatch";
        if(failure instanceof SocketTimeoutException)return "timeout";
        if(failure instanceof UnknownHostException)return "dns_failure";
        if(failure instanceof ConnectException)return "connection_failed";
        if(failure instanceof SSLException)return "tls_failure";
        if(failure instanceof org.xmlpull.v1.XmlPullParserException)return "invalid_response";
        return "network_error";
    }
    private static String label(String reason){
        switch(reason){case "timeout":return "连接超时";case "dns_failure":return "域名解析失败";case "connection_failed":return "连接失败";case "tls_failure":return "安全连接失败";case "certificate_mismatch":return "证书不符";case "identity_mismatch":return "设备标识不符";case "invalid_response":return "设备信息无效";default:return "网络错误";}
    }
    public synchronized RhDiscoveryObservation finish(){
        if(Thread.currentThread().isInterrupted()||attempts.isEmpty())return null;
        JSONObject latest=null;JSONArray rows=new JSONArray(),signature=new JSONArray();LinkedHashSet<String> reasons=new LinkedHashSet<>();
        try{
            for(Attempt attempt:attempts.values()){
                if(attempt.report==null)return null;
                JSONObject row=new JSONObject(attempt.report.toString());rows.put(row);JSONArray codes=row.getJSONArray("reasons");for(int i=0;i<codes.length();i++)reasons.add(codes.getString(i));
                signature.put(RhActions.obj("address",row.getString("address"),"port",row.getInt("port"),"reasons",codes,"actualUuid",row.optString("actualUuid")));
                if(latest==null||row.getLong("observationId")>latest.getLong("observationId"))latest=row;
            }
            // Preserve the final request's observation order. Aggregating later must
            // not make old failures newer than an intervening successful check.
            JSONObject report=new JSONObject(latest.toString());report.remove("address");report.remove("port");report.remove("reason");report.remove("reasons");
            String outcome=reasons.contains("identity_mismatch")?"identity_mismatch":reasons.contains("invalid_response")?"invalid_response":"unavailable";
            report.put("outcome",outcome);report.put("actualUuid","");report.put("phase","background_discovery");report.put("backgroundFailure",true);report.put("reachabilityCurrent",false);report.put("authenticationSource","none");report.put("identitySource","none");
            report.put("attempts",rows);report.put("failureSignature",signature.toString());report.put("elapsedUs",Math.max(0,(report.getLong("observedAtNs")-began)/1000));
            List<String> summary=new ArrayList<>();for(String code:reasons){if(summary.size()==3)break;summary.add(label(code));}
            report.put("failureSummary","地址探测失败："+android.text.TextUtils.join("、",summary)+(reasons.size()>3?"等":""));
            return RhDiscoveryObservation.failure(report,source.serverCert,intent);
        }catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
}
