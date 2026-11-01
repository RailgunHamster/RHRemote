    // Background metadata keeps the legacy HTTP re-pair discovery fallback,
    // but never treats its PairStatus as authenticated pairing evidence.
    public ComputerDetails getRhDiscoveryDetails(boolean likelyOnline) throws IOException, XmlPullParserException {
        if(serverCert!=null&&!rhStrictPin)throw new IllegalStateException("Discovery requires a strict saved pin");
        long began=System.nanoTime();String response,source="http",fallback="";boolean verified=false;
        OkHttpClient client=likelyOnline?httpClientLongConnectTimeout:httpClientShortConnectTimeout;
        if(serverCert!=null){
            HttpUrl secure=getHttpsUrl(likelyOnline);
            try{
                response=openHttpConnectionToString(client,secure,"serverinfo");
                getServerVersion(response);source="https";verified=true;
            }catch(javax.net.ssl.SSLHandshakeException failure){
                boolean mismatch=false;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof com.limelight.rhremote.RhDeviceCheck.PinnedCertificateMismatch)mismatch=true;
                if(!mismatch)throw failure;fallback="certificate_mismatch";
                response=rhDiscoveryFallback(client,failure);
            }catch(HostHttpResponseException failure){
                if(failure.getErrorCode()!=401)throw failure;fallback="auth_rejected";verified=true;
                response=rhDiscoveryFallback(client,failure);
            }
        }else response=openHttpConnectionToString(client,baseUrlHttp,"serverinfo");
        getServerVersion(response);ComputerDetails details=getComputerDetails(response);
        String outcome=!fallback.isEmpty()?fallback:verified&&details.pairState==PairingManager.PairState.PAIRED?"paired":"unpaired";
        if(!verified||!fallback.isEmpty())details.pairState=PairingManager.PairState.NOT_PAIRED;
        details.rhDiscoveryObservation=com.limelight.rhremote.RhDiscoveryObservation.result(details,serverCert,source,outcome,fallback,verified,began);
        return details;
    }
    private String rhDiscoveryFallback(OkHttpClient client,Throwable secureFailure) throws IOException {
        try{return openHttpConnectionToString(client,baseUrlHttp,"serverinfo");}
        catch(IOException failure){failure.addSuppressed(secureFailure);throw failure;}
    }
