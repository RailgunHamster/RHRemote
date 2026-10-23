package com.limelight.rhremote;

import android.content.Context;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

/** Local pairing credentials are committed before publishing success to the UI. */
public final class RhPairingStore {
    private static ComputerDetails.AddressTuple persistent(ComputerDetails.AddressTuple address) {
        if(address==null||address.address.equals("127.77.0.2")||RhNetwork.kind(address.address).equals("transport"))return null;
        return new ComputerDetails.AddressTuple(address.address,address.port);
    }
    public static ComputerDetails save(Context context,ComputerDatabaseManager database,ComputerDetails verified,X509Certificate pairedCertificate)throws IOException {
        if(verified==null||verified.uuid==null||verified.uuid.isEmpty()||pairedCertificate==null)throw new IOException("缺少已验证的配对信息");
        // Canonicalize before writing. A malformed encoding must never clear an old pin.
        X509Certificate pin;
        try{pin=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pairedCertificate.getEncoded()));}
        catch(CertificateException invalid){throw new IOException("配对证书无效，旧记录已保留",invalid);}
        ComputerDetails previous=database.getComputerByUUID(verified.uuid);
        ComputerDetails saved=previous==null?new ComputerDetails(verified):new ComputerDetails(previous);
        if(previous!=null)saved.update(verified);
        saved.name=saved.name==null?saved.uuid:saved.name;
        saved.serverCert=pin;saved.pairState=PairingManager.PairState.PAIRED;
        saved.localAddress=persistent(saved.localAddress);saved.remoteAddress=persistent(saved.remoteAddress);saved.ipv6Address=persistent(saved.ipv6Address);
        saved.manualAddress=persistent(saved.manualAddress);
        if(saved.manualAddress==null&&previous!=null)saved.manualAddress=persistent(previous.manualAddress);
        if(saved.manualAddress==null) {
            List<ComputerDetails.AddressTuple> candidates=RhNetwork.pollingAddresses(context,saved);
            if(!candidates.isEmpty())saved.manualAddress=candidates.get(0);
        }
        // A transport-only host may have no persistent IP tuple. Its rhr identity
        // remains in the shared host facts, while its certificate stays in SQLite.
        if(!database.updateComputer(saved))throw new IOException("本机配对记录保存失败");
        return saved;
    }
    /** The local database, not a discovery snapshot, owns the pairing pin. Caller
     * serializes this with save() until the polled details have been persisted. */
    public static void refreshLocalPin(ComputerDatabaseManager database,ComputerDetails details) {
        ComputerDetails current=database.getComputerByUUID(details.uuid);
        assignPin(details,current==null?null:current.serverCert);
    }
    private static void assignPin(ComputerDetails details,X509Certificate pin) {
        if(!java.util.Objects.equals(pin,details.serverCert)) {
            details.serverCert=pin;details.pairState=null;details.state=ComputerDetails.State.UNKNOWN;
        }
    }
    public static boolean savePoll(ComputerDatabaseManager database,ComputerDetails details,boolean newPc) {
        ComputerDetails current=database.getComputerByUUID(details.uuid);
        if(!newPc&&current==null)return false;
        assignPin(details,current==null?null:current.serverCert);
        if(current!=null){current.update(details);return database.updateComputer(current);}
        return database.updateComputer(details);
    }
}
