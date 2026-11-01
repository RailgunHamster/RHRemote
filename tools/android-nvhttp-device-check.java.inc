    // Active read-only inspection: strict saved pin, bounded body and no HTTP
    // downgrade after HTTPS failure. Background metadata fallback stays separate.
    public org.json.JSONObject getRhDeviceCheck(String expectedUuid,int timeoutMs) {
        return getRhDeviceCheck(expectedUuid,timeoutMs,null);
    }
    public org.json.JSONObject getRhDeviceCheck(String expectedUuid,int timeoutMs,String preflight) {
        final long began=System.nanoTime();String source="http",actual="";
        final boolean pinned=rhStrictPin&&serverCert!=null;
        OkHttpClient bounded=httpClientLongConnectTimeout.newBuilder()
                .connectTimeout(timeoutMs,TimeUnit.MILLISECONDS).readTimeout(timeoutMs,TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs,TimeUnit.MILLISECONDS).build();
        try {
            if(serverCert!=null&&!rhStrictPin)throw new IllegalStateException("Device inspection requires strict pinning");
            if(preflight!=null){
                if(preflight.length()>65536)throw new java.io.EOFException("Oversized serverinfo body");
                getServerVersion(preflight);actual=getXmlString(preflight,"uniqueid",true);
                if(actual.length()>128)return com.limelight.rhremote.RhDeviceCheck.report(expectedUuid,"",source,pinned,"invalid_response",began);
                if(!expectedUuid.equalsIgnoreCase(actual))return com.limelight.rhremote.RhDeviceCheck.report(expectedUuid,actual,source,pinned,"identity_mismatch",began);
                if(!pinned)return com.limelight.rhremote.RhDeviceCheck.report(expectedUuid,actual,source,false,"unpaired",began);
                if(httpsPort==0)httpsPort=getHttpsPort(preflight);
            }
            if(pinned&&httpsPort==0){
                String discovery=readRhDeviceCheck(bounded,baseUrlHttp);
                actual=getXmlString(discovery,"uniqueid",true);
                if(!expectedUuid.equalsIgnoreCase(actual))return com.limelight.rhremote.RhDeviceCheck.report(expectedUuid,actual,source,pinned,"identity_mismatch",began);
                httpsPort=getHttpsPort(discovery);
            }
            HttpUrl url=baseUrlHttp;
            if(pinned){source="https";actual="";url=new HttpUrl.Builder().scheme("https").host(baseUrlHttp.host()).port(com.limelight.rhremote.RhTunnel.port(baseUrlHttp.host(),httpsPort)).build();}
            String response=readRhDeviceCheck(bounded,url);
            getServerVersion(response);
            actual=getXmlString(response,"uniqueid",true);
            if(actual.length()>128)return com.limelight.rhremote.RhDeviceCheck.report(expectedUuid,"",source,pinned,"invalid_response",began);
            String outcome=!expectedUuid.equalsIgnoreCase(actual)?"identity_mismatch":pinned&&getPairState(response)==PairingManager.PairState.PAIRED?"paired":"unpaired";
            org.json.JSONObject report=com.limelight.rhremote.RhDeviceCheck.report(expectedUuid,actual,source,pinned,outcome,began);
            if(pinned)report.put("httpsPort",httpsPort);return report;
        }catch(Exception error){
            String outcome="unavailable";
            for(Throwable cause=error;cause!=null;cause=cause.getCause()){
                if(cause instanceof com.limelight.rhremote.RhDeviceCheck.PinnedCertificateMismatch){outcome="certificate_mismatch";break;}
                if(cause instanceof javax.net.ssl.SSLException)outcome="tls_failure";
            }
            if(outcome.equals("unavailable")){
                if(error instanceof HostHttpResponseException&&((HostHttpResponseException)error).getErrorCode()==401&&pinned&&source.equals("https"))outcome="auth_rejected";
                else if(error instanceof java.net.SocketTimeoutException||error instanceof java.io.InterruptedIOException)outcome="timeout";
                else if(error instanceof org.xmlpull.v1.XmlPullParserException||error instanceof java.io.EOFException||error instanceof NumberFormatException)outcome="invalid_response";
            }
            return com.limelight.rhremote.RhDeviceCheck.report(expectedUuid,actual,source,pinned,outcome,began);
        }
    }
    private String readRhDeviceCheck(OkHttpClient client,HttpUrl url)throws IOException {
        try(ResponseBody body=openHttpConnection(client,url,"serverinfo",null)){
            if(body==null)throw new java.io.EOFException("Missing serverinfo body");
            try(java.io.InputStream input=body.byteStream();java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){
                byte[] buffer=new byte[2048];int count;
                while((count=input.read(buffer))!=-1){if(bytes.size()+count>65536)throw new java.io.EOFException("Oversized serverinfo body");bytes.write(buffer,0,count);}
                return bytes.toString("UTF-8");
            }
        }
    }

