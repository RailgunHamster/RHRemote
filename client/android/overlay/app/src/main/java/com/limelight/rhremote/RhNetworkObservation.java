package com.limelight.rhremote;

import android.net.Network;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Process-local default-network identity for device evidence, not a routing policy. */
public final class RhNetworkObservation {
    private static final AtomicLong generation=new AtomicLong();
    private static boolean initialized;
    private static Network current;
    private RhNetworkObservation(){}
    public static long generation(){return generation.get();}
    public static synchronized boolean available(Network network){
        if(network==null||initialized&&Objects.equals(current,network))return false;
        initialized=true;current=network;generation.incrementAndGet();return true;
    }
    public static synchronized boolean lost(Network network){
        if(network==null||initialized&&!Objects.equals(current,network))return false;
        initialized=true;current=null;generation.incrementAndGet();return true;
    }
    static String scope(String intent){return new JSONArray().put(generation()).put(intent).toString();}
    static long capturedGeneration(String intent){
        if(intent==null)return -1;
        try{return new JSONArray(intent).getLong(0);}catch(org.json.JSONException invalid){return -1;}
    }
    private static String staleReason(String captured,String current){
        if(captured==null||current==null)return "configuration_changed";
        try{
            JSONArray before=new JSONArray(captured),after=new JSONArray(current);
            return before.getString(1).equals(after.getString(1))&&before.getLong(0)!=after.getLong(0)?"network_changed":"configuration_changed";
        }catch(org.json.JSONException invalid){return "configuration_changed";}
    }
    static JSONObject expired(JSONObject report,String captured,String current){
        String reason=staleReason(captured,current),outcome=report.optString("outcome");
        boolean negative=outcome.equals("auth_rejected")||outcome.equals("certificate_mismatch")||outcome.equals("unpaired")&&report.optBoolean("hasCertificate")||outcome.equals("identity_mismatch")||outcome.equals("invalid_response");
        if(reason.equals("network_changed")&&negative){
            try{JSONObject retained=new JSONObject(report.toString());retained.put("networkStale",true);retained.put("staleReason",reason);retained.put("reachabilityCurrent",false);return retained;}
            catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
        }
        return RhActions.obj("stale",true,"staleReason",reason,"previousOutcome",outcome,"previousObservationId",report.optLong("observationId"),"phase",report.optString("phase"),"operationId",report.optLong("operationId"));
    }
}
