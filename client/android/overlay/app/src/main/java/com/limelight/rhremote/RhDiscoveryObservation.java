package com.limelight.rhremote;

import android.content.Context;
import com.limelight.nvstream.http.ComputerDetails;
import java.security.cert.X509Certificate;
import java.util.Objects;
import org.json.JSONObject;

/** Immutable runtime-only discovery provenance, scoped to the request's saved configuration and pin. */
public final class RhDiscoveryObservation {
    private final String report,intent,signature;
    private final long networkGeneration;
    private final boolean authenticates;
    private final X509Certificate pin;
    private RhDiscoveryObservation(JSONObject report,X509Certificate pin,String intent){this.report=report.toString();this.pin=pin;this.intent=intent;networkGeneration=RhNetworkObservation.capturedGeneration(intent);authenticates=pin!=null&&report.optString("source").equals("https")&&report.optString("authenticationSource").equals("https")&&report.optBoolean("httpsPinVerified")&&report.optBoolean("hasCertificate")&&(report.optString("outcome").equals("paired")||report.optString("outcome").equals("unpaired"));signature=report.optString("outcome")+"|"+report.optString("source")+"|"+intent+"|"+Objects.hashCode(pin)+"|"+report.optString("failureSignature");}
    static RhDiscoveryObservation failure(JSONObject report,X509Certificate pin,String intent){return new RhDiscoveryObservation(report,pin,intent);}
    public static RhDiscoveryObservation result(ComputerDetails details,X509Certificate pin,String source,String outcome,String fallback,boolean verified,long began){
        JSONObject report=RhDeviceCheck.report(details.uuid,details.uuid,source,pin!=null,outcome,began);
        try{report.put("phase","background_discovery");report.put("identitySource",source);report.put("httpsPinVerified",verified);report.put("authenticationSource",verified?"https":"none");report.put("fallbackReason",fallback);if(!fallback.isEmpty())report.put("failureSource","https");report.put("httpsPort",details.httpsPort);}
        catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
        return new RhDiscoveryObservation(report,pin,null);
    }
    public RhDiscoveryObservation bind(String captured){try{return new RhDiscoveryObservation(new JSONObject(report),pin,captured);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}}
    static String intent(Context context,JSONObject shared,ComputerDetails target)throws Exception{
        JSONObject captured=new JSONObject(RhNetwork.metadata(shared,target).toString());if(!captured.has("addresses"))captured.put("addresses",new org.json.JSONArray());return RhNetworkObservation.scope(RhDiagnosticWork.intent(target.uuid,RhProfile.load(context,shared,target.uuid),captured));
    }
    public static String begin(Context context,ComputerDetails target){try{return intent(context,RhShared.read(context),target);}catch(Exception invalid){return null;}}
    static JSONObject snapshot(Context context,JSONObject shared,ComputerDetails target){
        RhDiscoveryObservation value=target.rhDiscoveryObservation;if(value==null)return new JSONObject();
        try{
            JSONObject report=new JSONObject(value.report);boolean stateMatches=report.optBoolean("backgroundFailure")?target.state==ComputerDetails.State.OFFLINE:target.state==ComputerDetails.State.ONLINE;
            // Preserve the ordinary same-network state shortcut. Only a changed
            // network needs to reconsider an otherwise hidden old observation.
            if(!stateMatches&&value.networkGeneration==RhNetworkObservation.generation())return new JSONObject();
            String current=intent(context,shared,target);
            if(!Objects.equals(value.pin,target.serverCert))return RhNetworkObservation.expired(report,null,current);
            if(value.intent==null||!value.intent.equals(current))return RhNetworkObservation.expired(report,value.intent,current);
            if(!stateMatches)return new JSONObject();return report;
        }
        catch(Exception invalid){return new JSONObject();}
    }
    static String signature(ComputerDetails target){return RhNetworkObservation.generation()+"|"+(target.rhDiscoveryObservation==null?"":target.rhDiscoveryObservation.signature);}
    /** Only a current, pinned response for this identity can retire older check evidence. */
    static boolean authenticates(Context context,JSONObject shared,ComputerDetails target){
        RhDiscoveryObservation value=target.rhDiscoveryObservation;if(value==null||!value.authenticates)return false;
        JSONObject report=snapshot(context,shared,target);
        return !report.optBoolean("stale")&&!report.optBoolean("networkStale")&&!report.optBoolean("backgroundFailure")&&report.optBoolean("httpsPinVerified")&&target.uuid.equals(report.optString("targetUuid"))&&target.uuid.equals(report.optString("actualUuid"));
    }
    static long sequence(ComputerDetails target){try{return target.rhDiscoveryObservation==null?0:new JSONObject(target.rhDiscoveryObservation.report).optLong("observationId");}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}}
}
