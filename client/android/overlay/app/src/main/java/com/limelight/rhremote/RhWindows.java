package com.limelight.rhremote;

import android.util.Xml;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.StringReader;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/** Session-only opaque window selectors. Native handles and desktop fallbacks are never accepted. */
public final class RhWindows {
    public static final String EXTRA="rhremote.window";
    private RhWindows(){}
    public interface Request { String get()throws Exception; }
    /** Older engines return HTTP 404 before XML parsing; keep that failure actionable. */
    public static JSONObject read(Request request)throws Exception{
        try{return parse(request.get());}
        catch(FileNotFoundException missing){throw new IOException("主机尚未支持窗口串流，请先更新这台电脑的 RHRemote-Sunshine",missing);}
    }
    public static boolean valid(String source){return source!=null&&source.matches("rhrwindow:[0-9a-f]{32}");}
    public static JSONObject parse(String xml)throws Exception{
        if(xml==null||xml.length()>1024*1024)throw new IOException("窗口列表过大");
        XmlPullParser parser=Xml.newPullParser();parser.setInput(new StringReader(xml));
        JSONArray rows=new JSONArray();JSONObject row=null;boolean supported=false,root=false;
        java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(int event=parser.getEventType();event!=XmlPullParser.END_DOCUMENT;event=parser.next()){
            if(event==XmlPullParser.START_TAG){String name=parser.getName();
                if(parser.getDepth()==1){if(root||!name.equals("root"))throw new IOException("无效窗口列表");root=true;String code=parser.getAttributeValue(null,"status_code");if(!"200".equals(code))throw new IOException("主机不支持窗口串流或配对已失效（"+code+"）");}
                else if(parser.getDepth()==2&&name.equals("supported"))supported=parser.nextText().equals("1");
                else if(parser.getDepth()==2&&name.equals("Window")){if(rows.length()>=128)throw new IOException("窗口列表过大");row=new JSONObject();}
                else if(parser.getDepth()==3&&row!=null){String text=parser.nextText();if(text.length()>2048)throw new IOException("窗口信息过长");row.put(name,text);}
            }else if(event==XmlPullParser.END_TAG&&parser.getDepth()==2&&parser.getName().equals("Window")&&row!=null){
                String id=row.optString("id");boolean ready=row.optString("state").equals("ready");
                if(!id.isEmpty()&&(!valid(id)||!ids.add(id)))throw new IOException("无效或重复的窗口标识");
                int width=row.optInt("width"),height=row.optInt("height");
                if(ready&&(!valid(id)||width<1||height<1||width>16384||height>16384))throw new IOException("无效窗口尺寸");
                row.put("width",width);row.put("height",height);row.put("available",ready);rows.put(row);row=null;
            }
        }
        if(!root)throw new IOException("无效窗口列表");
        if(!supported)throw new IOException("主机当前无法启用窗口捕获，请检查主机服务的运行方式");
        return RhActions.obj("windows",rows,"supported",true,"secondaryWindows",false);
    }
    /** Fit to the configured quality ceiling, preserve aspect ratio, and never enlarge a small window. */
    public static int[] resolution(JSONObject window,int limitWidth,int limitHeight)throws IOException{
        if(window==null||!valid(window.optString("id")))throw new IOException("请重新选择窗口");
        int width=window.optInt("width"),height=window.optInt("height");
        if(width<1||height<1||width>16384||height>16384||limitWidth<2||limitHeight<2)throw new IOException("无效窗口尺寸");
        double scale=Math.min(1.,Math.min((double)limitWidth/width,(double)limitHeight/height));
        return new int[]{Math.max(2,((int)(width*scale))&~1),Math.max(2,((int)(height*scale))&~1)};
    }
}
