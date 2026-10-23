package com.limelight.rhremote;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;

/** One NvHTTP instance owns its calls until their response bodies are closed. */
public final class RhHttpRequests {
    private final HashSet<Request> active=new HashSet<>();
    private boolean cancelled;
    public final class Request implements AutoCloseable {
        private final Runnable abort;
        private Request(Runnable abort){this.abort=abort;}
        @Override public void close(){synchronized(active){active.remove(this);}}
    }
    public Request track(Runnable abort)throws IOException {
        Request request=new Request(abort);
        synchronized(active){if(!cancelled){active.add(request);return request;}}
        abort.run();throw new IOException("Connection cancelled");
    }
    public void cancel(){
        ArrayList<Request> pending;
        synchronized(active){if(cancelled)return;cancelled=true;pending=new ArrayList<>(active);active.clear();}
        for(Request request:pending)request.abort.run();
    }
}
