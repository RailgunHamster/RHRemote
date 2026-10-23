package com.limelight.rhremote;

/** Optional paired launch extension. Acceptance does not prove that a host file was written. */
public final class RhHostFrameTrace {
    public static boolean validTag(String tag){return tag!=null&&!tag.isEmpty()&&tag.length()<=96&&!tag.equals(".")&&!tag.equals("..")&&tag.matches("[A-Za-z0-9._-]+");}
    public static String query(boolean recording,String tag){return recording&&validTag(tag)?"&rhremoteFrameTrace=1&rhremoteTraceClient="+tag:"";}
    public static String acknowledge(boolean requested,String value){
        if(!requested)return "disabled";
        if("1".equals(value))return "accepted";
        if("0".equals(value))return "not_accepted";
        if(value==null||value.isEmpty())return "unsupported";
        return "invalid_ack";
    }
}
