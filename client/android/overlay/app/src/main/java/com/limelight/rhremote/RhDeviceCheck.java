package com.limelight.rhremote;

import android.content.Context;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.binding.PlatformBinding;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import org.json.JSONObject;

/** Attempt-scoped, read-only device evidence; never saves or removes pairing identities. */
public final class RhDeviceCheck {
    private static final java.util.concurrent.atomic.AtomicLong observations=new java.util.concurrent.atomic.AtomicLong();
    public static final class PinnedCertificateMismatch extends CertificateException {
        public PinnedCertificateMismatch(){super("Paired certificate mismatch");}
    }
    private RhDeviceCheck(){}
    static final class Resolved extends ComputerDetails {
        final String serverInfo;
        Resolved(ComputerDetails source,String serverInfo){super(source);this.serverInfo=serverInfo;}
    }
    public static JSONObject report(String target,String actual,String source,boolean certificate,String outcome,long began){
        long observed=System.nanoTime();return RhActions.obj("schemaVersion",1,"targetUuid",target,"actualUuid",actual==null?"":actual,
                "source",source,"hasCertificate",certificate,"outcome",outcome,"observationId",observations.incrementAndGet(),"observedAtNs",observed,"elapsedUs",(observed-began)/1000);
    }
    static String intent(ComputerDetails target,RhProfile profile,JSONObject captured)throws Exception{
        String pin="";if(target.serverCert!=null){byte[] digest=MessageDigest.getInstance("SHA-256").digest(target.serverCert.getEncoded());StringBuilder value=new StringBuilder();for(byte b:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));pin=value.toString();}
        return RhNetworkObservation.scope(new org.json.JSONArray().put(RhDiagnosticWork.intent(target.uuid,profile,captured)).put(pin).toString());
    }
    static String intent(Context context,JSONObject shared,ComputerDetails target)throws Exception{
        JSONObject captured=new JSONObject(RhNetwork.metadata(shared,target).toString());if(!captured.has("addresses"))captured.put("addresses",new org.json.JSONArray());
        return intent(target,RhProfile.load(context,shared,target.uuid),captured);
    }
    static JSONObject snapshot(Context context,JSONObject shared,ComputerDetails target){
        try{RhDiagnosticWork work=RhDiagnosticWork.shared(context);JSONObject checked=work.hasCheck(target.uuid)?work.checked(target.uuid,intent(context,shared,target)):new JSONObject();
            if(blocks(checked))return checked;
            JSONObject observed=RhDiscoveryObservation.snapshot(context,shared,target);
            if(observed.optBoolean("backgroundFailure")&&observed.optString("outcome").equals("unavailable")&&target.serverCert!=null){
                String known=checked.optString("outcome");
                boolean rejected=known.equals("auth_rejected")||known.equals("certificate_mismatch")||known.equals("unpaired")&&checked.optBoolean("hasCertificate");
                if(rejected||target.pairState==com.limelight.nvstream.http.PairingManager.PairState.NOT_PAIRED){
                    // Network failures provide no evidence that rejected credentials
                    // became valid. Keep that fact until a later verified response.
                    String outcome=rejected?known:"unpaired";observed.put("outcome",outcome);observed.put("knownAuthenticationOutcome",outcome);
                    observed.put("authenticationObservationId",rejected?checked.optLong("observationId"):0);
                }
            }
            if(checked.has("outcome")&&(observed.optBoolean("stale")||checked.optLong("observationId")>=observed.optLong("observationId")))return checked;
            return observed.length()>0?observed:checked;
        }catch(Exception invalid){return new JSONObject();}
    }
    static JSONObject inspect(Context context,ComputerDetails target,RhCancellation operation)throws Exception{
        operation.checkpoint();if(target.activeAddress==null)throw new java.io.IOException("No selected check address");
        NvHTTP http=new NvHTTP(target.activeAddress,target.httpsPort,"0123456789ABCDEF",target.serverCert,PlatformBinding.getCryptoProvider(context),true);
        try(RhCancellation.Watch request=operation.watch(http::cancelRhRequests)){
            operation.checkpoint();JSONObject result=http.getRhDeviceCheck(target.uuid,8000,target instanceof Resolved?((Resolved)target).serverInfo:null);operation.checkpoint();return result;
        }
    }
    static boolean blocks(JSONObject check){return check.optString("outcome").equals("identity_mismatch")||check.optString("outcome").equals("invalid_response");}
    static boolean online(ComputerDetails target,JSONObject check){if(check.optBoolean("stale")||check.optBoolean("networkStale")||blocks(check))return false;return !check.has("outcome")||!check.optBoolean("reachabilityCurrent",true)?target.state==ComputerDetails.State.ONLINE:!check.optString("phase").equals("route_selection");}
    static String reachability(ComputerDetails target,JSONObject check){if(check.optBoolean("stale"))return "unknown";if(online(target,check))return "online";if(!check.has("outcome"))return target.state==ComputerDetails.State.UNKNOWN?"unknown":"offline";return check.optBoolean("reachabilityCurrent",true)&&check.optString("outcome").equals("unreachable")?"offline":"unknown";}
    static boolean paired(ComputerDetails target,JSONObject check){if(check.optBoolean("stale")||check.optBoolean("networkStale")||check.optBoolean("backgroundFailure"))return false;switch(check.optString("outcome")){case "paired":return true;case "unpaired":case "auth_rejected":case "certificate_mismatch":case "identity_mismatch":case "invalid_response":return false;default:return RhNetwork.pairingStatus(target).equals("verified");}}
    static boolean pairingFailed(JSONObject check){return !(check.optBoolean("backgroundFailure")&&check.optString("outcome").equals("unavailable"))&&failed(check);}
    static boolean failed(JSONObject check){if(check.optBoolean("stale"))return false;switch(check.optString("outcome")){case "":case "paired":return false;case "unpaired":return check.optBoolean("hasCertificate");default:return true;}}
    static ComputerDetails admission(ComputerDetails target,JSONObject check,boolean connect){
        ComputerDetails result=new ComputerDetails(target);String outcome=check.optString("outcome");
        if(connect&&check.optBoolean("stale")&&target.serverCert!=null){result.pairState=null;return result;}
        if(outcome.equals("paired")){result.pairState=com.limelight.nvstream.http.PairingManager.PairState.PAIRED;int port=check.optInt("httpsPort");if(port>0&&port<=65535)result.httpsPort=port;}
        else if(!connect&&outcome.equals("certificate_mismatch")||connect&&check.has("outcome")&&target.serverCert!=null&&!outcome.equals("unpaired")&&!outcome.equals("auth_rejected"))result.pairState=null;
        return result;
    }
    static boolean repairNeedsCheck(ComputerDetails target,JSONObject check){
        if(target.serverCert==null||check.optBoolean("stale"))return false;
        String outcome=check.optString("outcome");boolean rejected=outcome.equals("auth_rejected")||outcome.equals("certificate_mismatch")||outcome.equals("unpaired")&&check.optBoolean("hasCertificate")||outcome.isEmpty()&&target.pairState==com.limelight.nvstream.http.PairingManager.PairState.NOT_PAIRED;
        return rejected&&!online(target,check);
    }
    static String primaryAction(ComputerDetails target,JSONObject check){
        if(check.optBoolean("networkStale"))return "device.check";
        if(check.optBoolean("stale"))return target.serverCert!=null?"device.connect":"device.check";
        if(repairNeedsCheck(target,check))return "device.check";
        switch(check.optString("outcome")){
            case "paired":return "device.connect";
            case "unpaired":case "auth_rejected":case "certificate_mismatch":return "device.pair";
            case "identity_mismatch":case "invalid_response":return "device.check";
            case "tls_failure":case "timeout":case "unavailable":case "unreachable":return target.serverCert!=null?"device.connect":"device.check";
            default:return RhNetwork.primaryAction(target);
        }
    }
    static String primaryLabel(ComputerDetails target,JSONObject check){
        String action=primaryAction(target,check);if(action.equals("device.connect"))return "连接";if(action.equals("device.check"))return "检查连接";
        return check.optBoolean("hasCertificate")?"重新配对":"配对";
    }
    static boolean canConnect(ComputerDetails target,JSONObject check){return primaryAction(target,check).equals("device.connect");}
    static String status(ComputerDetails target,JSONObject check){if(check.optBoolean("stale")||check.optBoolean("backgroundFailure")&&check.optString("outcome").equals("unavailable"))return target.serverCert!=null?"stored":"unknown";return check.has("outcome")?check.optString("outcome"):RhNetwork.pairingStatus(target);}
    static String label(ComputerDetails target,JSONObject check){
        if(check.optBoolean("stale"))return "需重新检查";
        if(check.optBoolean("backgroundFailure")&&check.optString("outcome").equals("unavailable"))return target.serverCert!=null?"已保存配对":"未确认";
        switch(check.optString("outcome")){
            case "paired":return "已配对";
            case "unpaired":return check.optBoolean("hasCertificate")?"需要重新配对":"未配对";
            case "auth_rejected":return "认证失败";
            case "certificate_mismatch":return "证书不符";
            case "tls_failure":return "安全连接失败";
            case "identity_mismatch":return "设备标识不符";
            case "invalid_response":return "设备信息无效";
            case "timeout":return "检查超时";
            case "unreachable":return "暂时无法连接";
            case "unavailable":return "状态未确认";
            default:return RhNetwork.pairingLabel(target);
        }
    }
    static String hint(ComputerDetails target,JSONObject check){
        if(repairNeedsCheck(target,check)){
            String reason=check.optBoolean("networkStale")?"网络已变化。":check.optBoolean("backgroundFailure")?check.optString("failureSummary","连接状态尚未确认。"):"连接状态尚未确认。";
            return (reason.endsWith("。")?reason:reason+"。")+"请先检查连接。";
        }
        return hint(check);
    }
    static String hint(JSONObject check){
        if(check.optBoolean("networkStale")){
            try{JSONObject previous=new JSONObject(check.toString());previous.remove("networkStale");return "网络已变化。上次检查："+hint(previous);}
            catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
        }
        if(check.optBoolean("stale"))return check.optString("staleReason").equals("network_changed")?"网络已变化，连接状态需要重新确认。":"连接配置已更改，请重新检查。";
        if(check.optBoolean("backgroundFailure"))return check.optString("failureSummary","地址探测失败，可检查连接。");
        switch(check.optString("outcome")){
            case "auth_rejected":return "主机未接受本机保存的配对，请重新配对。";
            case "certificate_mismatch":return "主机证书与本机保存的证书不符，请确认目标设备后重新配对。";
            case "tls_failure":return "安全连接未完成，已保存的配对仍保留。";
            case "identity_mismatch":return "返回的设备标识与已保存记录不符，请检查连接地址。";
            case "invalid_response":return "主机返回的设备信息无效，请检查连接地址。";
            case "timeout":case "unavailable":return "设备检查未完成，可重试或查看连接设置。";
            case "unreachable":return "启用的线路暂时无法到达这台设备，请检查网络。";
            default:return "";
        }
    }
}
