package com.limelight.rhremote;

/** Known protocol replies only. Raw server text, URLs and credentials are never retained. */
enum RhLaunchConflict {
    UNKNOWN("",""),
    INITIAL_DISPLAY_BUSY("initial_display_busy","主机报告已有串流或启动请求，暂时不能选择初始屏幕（409）。结束相关会话后重试。"),
    PENDING_CHANGED("pending_launch_changed","主机未接受本次启动请求（409）。清理结束后重试。"),
    DUPLICATE("launch_duplicate","主机已处理过本次连接请求（409）。清理结束后重试。");
    final String id,message;
    RhLaunchConflict(String id,String message){this.id=id;this.message=message;}
    static RhLaunchConflict from(int code,String reason){
        if(code!=409||reason==null)return UNKNOWN;
        switch(reason){
            case "Another launch or stream is using this host; disconnect it before choosing an initial display":return INITIAL_DISPLAY_BUSY;
            case "Launch request no longer owns the pending slot":return PENDING_CHANGED;
            case "Launch ownership admission rejected":return DUPLICATE;
            default:return UNKNOWN;
        }
    }
}
