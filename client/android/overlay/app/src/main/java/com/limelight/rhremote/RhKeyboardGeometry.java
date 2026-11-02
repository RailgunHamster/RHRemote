package com.limelight.rhremote;

import java.util.*;
import org.json.*;

/** Physical groups keep their layout when possible; narrow rows wrap without hiding keys. */
final class RhKeyboardGeometry {
    static final class Cell {
        final float x,y,width,height;
        Cell(float x,float y,float width,float height){this.x=x;this.y=y;this.width=width;this.height=height;}
    }
    final Map<String,Cell> keys=new LinkedHashMap<>();
    float columns,rows;String presentation;
    private void put(JSONObject key,float x,float y,float width){
        float height=(float)key.optDouble("height",1);keys.put(key.optString("id"),new Cell(x,y,width,height));columns=Math.max(columns,x+width);rows=Math.max(rows,y+height);
    }
    static RhKeyboardGeometry fit(JSONArray catalog,float columns){
        RhKeyboardGeometry result=new RhKeyboardGeometry();columns=Math.max(1,columns);
        if(columns>=23){result.presentation="physical";for(int i=0;i<catalog.length();i++){JSONObject k=catalog.optJSONObject(i);result.put(k,(float)k.optDouble("x"),(float)k.optDouble("row"),(float)k.optDouble("width"));}return result;}
        result.presentation=columns>=15?"stacked":"wrapped";
        float mainBottom=0;
        for(int row=0;row<6;row++){
            List<JSONObject> line=new ArrayList<>();for(int i=0;i<catalog.length();i++){JSONObject k=catalog.optJSONObject(i);if(k.optDouble("x")<15.5&&k.optInt("row")==row)line.add(k);}line.sort(Comparator.comparingDouble(k->k.optDouble("x")));
            float x=0,y=mainBottom;
            for(JSONObject key:line){float width=Math.min(columns,(float)key.optDouble("width"));if(columns>=15)x=(float)key.optDouble("x");else if(x>0&&x+width>columns){x=0;y+=1;}result.put(key,x,y,width);x+=width;}
            mainBottom=y+1;
        }
        boolean beside=columns>=7.5;float otherTop=mainBottom+.5f;
        for(int i=0;i<catalog.length();i++){JSONObject key=catalog.optJSONObject(i);float x=(float)key.optDouble("x");if(x<15.5)continue;boolean number=x>=19;float origin=number?19:15.5f;result.put(key,x-origin+(number&&beside?3.5f:0),(float)key.optDouble("row")+otherTop+(number&&!beside?6.5f:0),(float)key.optDouble("width"));}
        return result;
    }
}
