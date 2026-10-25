package com.limelight.rhremote;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.*;

/** Per-call transport timing. Never records URLs, addresses, headers or error messages. */
public final class RhHttpStatistics extends EventListener {
    private static final AtomicLong IDS=new AtomicLong();
    private final long id=IDS.incrementAndGet();
    private final String command;
    private final boolean secure;
    private RhHttpStatistics(Call call){
        String path=call.request().url().encodedPath();
        switch(path){
            case "/serverinfo":case "/applist":case "/launch":case "/resume":
            case "/pair":case "/unpair":case "/cancel":case "/appasset":
            case "/rhremote/launch/cancel":command=path.substring(1);break;
            default:command="other";
        }
        secure=call.request().url().isHttps();
    }
    public static EventListener create(Call call){return RhStatistics.now()==0?EventListener.NONE:new RhHttpStatistics(call);}
    private void phase(String phase){RhStatistics.event("http_transport",-1,RhActions.obj("request",id,"command",command,"secure",secure,"phase",phase));}
    @Override public void callStart(Call call){phase("call_start");}
    @Override public void connectStart(Call call,InetSocketAddress address,Proxy proxy){phase("connect_start");}
    @Override public void secureConnectStart(Call call){phase("tls_start");}
    @Override public void secureConnectEnd(Call call,Handshake handshake){phase("tls_end");}
    @Override public void connectEnd(Call call,InetSocketAddress address,Proxy proxy,Protocol protocol){phase("connect_end");}
    @Override public void connectFailed(Call call,InetSocketAddress address,Proxy proxy,Protocol protocol,IOException failure){phase("connect_failed");}
    @Override public void connectionAcquired(Call call,Connection connection){phase("connection_acquired");}
    @Override public void requestHeadersStart(Call call){phase("request_headers_start");}
    @Override public void requestHeadersEnd(Call call,Request request){phase("request_headers_end");}
    @Override public void responseHeadersStart(Call call){phase("response_headers_start");}
    @Override public void responseHeadersEnd(Call call,Response response){phase("response_headers_end");}
    @Override public void responseBodyStart(Call call){phase("response_body_start");}
    @Override public void responseBodyEnd(Call call,long bytes){phase("response_body_end");}
    @Override public void callEnd(Call call){phase("call_end");}
    @Override public void callFailed(Call call,IOException failure){phase("call_failed");}
}
